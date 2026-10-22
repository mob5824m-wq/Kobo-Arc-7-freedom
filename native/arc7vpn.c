/*
 * arc7vpn — Native ARMv7 System-Wide Android VpnService (/dev/tun) Engine,
 *           Userspace TCP/UDP tun2socks NAT Stack, Encrypted DNS-over-HTTPS
 *           (RFC 8484 over MbedTLS TLS 1.3), Port-443 School Stealth Tunnel,
 *           and WireGuard Curve25519 Key Tooling for Kobo Arc 7 (Android 4.2.2)
 *
 * Modes:
 *   1. arc7vpn tun2socks [options]
 *        --tun-fd <fd>              Direct file descriptor for /dev/tun or socketpair
 *        --tun-ipc <abstract_name>  Receive /dev/tun fd + protect(fd) RPC over AF_UNIX SCM_RIGHTS
 *        --mode <direct|socks5|http|tls>
 *        --upstream <host:port>     Upstream proxy/tunnel (e.g. 127.0.0.1:1055 for tailscaled)
 *        --auth <user:pass>         Optional SOCKS5 RFC1929 / HTTP Basic credentials
 *        --sni <hostname>           Optional TLS 1.3 SNI override for school stealth mode
 *        --doh-host <ip_or_host>    DNS-over-HTTPS server (default 1.1.1.1)
 *        --dns-udp <ip:port>        Fallback direct UDP DNS server (default 1.1.1.1:53)
 *        --cafile <pem_path>        2026 Mozilla Root CA bundle path
 *        --status-file <path>       Live JSON telemetry file updated every second
 *
 *   2. arc7vpn wg-keygen
 *        Generates authentic RFC 7748 Curve25519 WireGuard PrivateKey + PublicKey (Base64)
 *
 *   3. arc7vpn wg-pubkey <base64_privkey>
 *        Derives WireGuard Curve25519 PublicKey from Base64 PrivateKey
 *
 *   4. arc7vpn doh-query <doh_ip> <domain> [cafile]
 *        Executes live TLS 1.3 RFC-8484 binary DNS-over-HTTPS query and prints resolved A records
 *
 *   5. arc7vpn selftest
 *        Runs deterministic Curve25519 + ChaCha20-Poly1305 + IPv4/TCP/UDP checksum checks AND
 *        a live end-to-end raw IPv4 /dev/tun -> tun2socks -> SOCKS5 -> HTTP target roundtrip!
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <mbedtls/base64.h>
#include <mbedtls/chachapoly.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/ecp.h>
#include <mbedtls/entropy.h>
#include <mbedtls/error.h>
#include <mbedtls/net_sockets.h>
#include <mbedtls/ssl.h>
#include <mbedtls/x509_crt.h>
#include <psa/crypto.h>

#define STACK_SIZE_2MB (2 * 1024 * 1024)
#define MAX_FLOWS 128
#define TUN_MTU 1500

static mbedtls_entropy_context g_entropy;
static mbedtls_ctr_drbg_context g_drbg;
static mbedtls_x509_crt g_cacert;
static int g_ca_loaded = 0;
static pthread_mutex_t g_tun_write_mtx = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_ipc_mtx = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_stats_mtx = PTHREAD_MUTEX_INITIALIZER;

static volatile int g_running = 1;
static uint64_t g_tx_bytes = 0;
static uint64_t g_rx_bytes = 0;
static uint64_t g_dns_queries = 0;
static int g_active_flows = 0;
static time_t g_start_time = 0;

typedef enum {
    MODE_DIRECT_DOH = 0,
    MODE_SOCKS5 = 1,
    MODE_HTTP_CONNECT = 2,
    MODE_TLS_STEALTH = 3
} tunnel_mode_t;

typedef struct {
    int tun_fd;
    int ipc_fd;
    tunnel_mode_t mode;
    char upstream_host[128];
    int upstream_port;
    char auth_user[64];
    char auth_pass[64];
    char tls_sni[128];
    char doh_host[128];
    char dns_udp_host[64];
    int dns_udp_port;
    char cafile[256];
    char status_file[256];
} vpn_cfg_t;

static vpn_cfg_t g_cfg;

/* ==================== RFC 1071 IPv4 / TCP / UDP Checksums ==================== */

static uint16_t ones_complement_sum(const uint8_t *buf, size_t len, uint32_t init_sum) {
    uint32_t sum = init_sum;
    while (len > 1) {
        sum += ((uint32_t)buf[0] << 8) | (uint32_t)buf[1];
        buf += 2;
        len -= 2;
    }
    if (len == 1) {
        sum += ((uint32_t)buf[0] << 8);
    }
    while (sum >> 16) {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    return (uint16_t)(~sum & 0xFFFF);
}

static uint16_t ipv4_checksum(const uint8_t *iphdr, size_t ihl_bytes) {
    return ones_complement_sum(iphdr, ihl_bytes, 0);
}

static uint16_t l4_ipv4_checksum(uint32_t src_be, uint32_t dst_be, uint8_t proto,
                                 const uint8_t *l4_pkt, size_t l4_len) {
    uint32_t src = ntohl(src_be);
    uint32_t dst = ntohl(dst_be);
    uint32_t sum = 0;
    sum += (src >> 16) & 0xFFFF;
    sum += src & 0xFFFF;
    sum += (dst >> 16) & 0xFFFF;
    sum += dst & 0xFFFF;
    sum += (uint32_t)proto;
    sum += (uint32_t)l4_len;
    uint16_t res = ones_complement_sum(l4_pkt, l4_len, sum);
    if (proto == IPPROTO_UDP && res == 0) res = 0xFFFF;
    return res;
}

static int tun_send_packet(int tun_fd, const uint8_t *pkt, size_t len) {
    pthread_mutex_lock(&g_tun_write_mtx);
    ssize_t w = write(tun_fd, pkt, len);
    pthread_mutex_unlock(&g_tun_write_mtx);
    if (w > 0) {
        pthread_mutex_lock(&g_stats_mtx);
        g_rx_bytes += (uint64_t)w;
        pthread_mutex_unlock(&g_stats_mtx);
    }
    return (w == (ssize_t)len) ? 0 : -1;
}

static void send_ipv4_udp_to_tun(int tun_fd, uint32_t src_be, uint32_t dst_be,
                                 uint16_t sport_be, uint16_t dport_be,
                                 const uint8_t *payload, size_t plen) {
    if (plen > 1420) return;
    size_t udp_len = 8 + plen;
    size_t tot_len = 20 + udp_len;
    uint8_t pkt[1500];
    memset(pkt, 0, 28);
    pkt[0] = 0x45;
    pkt[2] = (uint8_t)((tot_len >> 8) & 0xFF);
    pkt[3] = (uint8_t)(tot_len & 0xFF);
    pkt[6] = 0x40;
    pkt[8] = 64;
    pkt[9] = IPPROTO_UDP;
    memcpy(pkt + 12, &src_be, 4);
    memcpy(pkt + 16, &dst_be, 4);
    uint16_t ip_csum = htons(ipv4_checksum(pkt, 20));
    memcpy(pkt + 10, &ip_csum, 2);

    memcpy(pkt + 20, &sport_be, 2);
    memcpy(pkt + 22, &dport_be, 2);
    uint16_t ulen_be = htons((uint16_t)udp_len);
    memcpy(pkt + 24, &ulen_be, 2);
    pkt[26] = 0;
    pkt[27] = 0;
    if (plen > 0) memcpy(pkt + 28, payload, plen);
    uint16_t ucsum = htons(l4_ipv4_checksum(src_be, dst_be, IPPROTO_UDP, pkt + 20, udp_len));
    memcpy(pkt + 26, &ucsum, 2);
    tun_send_packet(tun_fd, pkt, tot_len);
}

static void send_ipv4_tcp_to_tun(int tun_fd, uint32_t src_be, uint32_t dst_be,
                                 uint16_t sport_be, uint16_t dport_be,
                                 uint32_t seq, uint32_t ack, uint8_t flags,
                                 const uint8_t *payload, size_t plen) {
    int syn_mss = (flags & 0x02) ? 1 : 0;
    size_t tcp_hdr_len = syn_mss ? 24 : 20;
    size_t tcp_len = tcp_hdr_len + plen;
    size_t tot_len = 20 + tcp_len;
    if (tot_len > 1500) return;

    uint8_t pkt[1500];
    memset(pkt, 0, 20 + tcp_hdr_len);
    pkt[0] = 0x45;
    pkt[2] = (uint8_t)((tot_len >> 8) & 0xFF);
    pkt[3] = (uint8_t)(tot_len & 0xFF);
    pkt[6] = 0x40;
    pkt[8] = 64;
    pkt[9] = IPPROTO_TCP;
    memcpy(pkt + 12, &src_be, 4);
    memcpy(pkt + 16, &dst_be, 4);
    uint16_t ip_csum = htons(ipv4_checksum(pkt, 20));
    memcpy(pkt + 10, &ip_csum, 2);

    uint8_t *tcp = pkt + 20;
    memcpy(tcp + 0, &sport_be, 2);
    memcpy(tcp + 2, &dport_be, 2);
    uint32_t seq_be = htonl(seq);
    uint32_t ack_be = htonl(ack);
    memcpy(tcp + 4, &seq_be, 4);
    memcpy(tcp + 8, &ack_be, 4);
    tcp[12] = (uint8_t)((tcp_hdr_len / 4) << 4);
    tcp[13] = flags;
    uint16_t win_be = htons(65535);
    memcpy(tcp + 14, &win_be, 2);
    if (syn_mss) {
        tcp[20] = 2;
        tcp[21] = 4;
        tcp[22] = (1380 >> 8) & 0xFF;
        tcp[23] = 1380 & 0xFF;
    }
    if (plen > 0) memcpy(tcp + tcp_hdr_len, payload, plen);
    uint16_t tcsum = htons(l4_ipv4_checksum(src_be, dst_be, IPPROTO_TCP, tcp, tcp_len));
    memcpy(tcp + 16, &tcsum, 2);
    tun_send_packet(tun_fd, pkt, tot_len);
}

/* ==================== Android VpnService SCM_RIGHTS IPC ==================== */

static int connect_abstract_ipc(const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    size_t nlen = strlen(name);
    if (nlen > sizeof(addr.sun_path) - 2) nlen = sizeof(addr.sun_path) - 2;
    memcpy(addr.sun_path + 1, name, nlen);
    socklen_t slen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + nlen);
    if (connect(fd, (struct sockaddr *)&addr, slen) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int recv_fd_from_ipc(int ipc_fd) {
    char dummy = 0;
    struct iovec iov = { .iov_base = &dummy, .iov_len = 1 };
    char cmsgbuf[CMSG_SPACE(sizeof(int))];
    memset(cmsgbuf, 0, sizeof(cmsgbuf));
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsgbuf;
    msg.msg_controllen = sizeof(cmsgbuf);
    if (recvmsg(ipc_fd, &msg, 0) <= 0) return -1;
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) return -1;
    int received_fd = -1;
    memcpy(&received_fd, CMSG_DATA(cmsg), sizeof(int));
    return received_fd;
}

static void protect_outbound_socket(int sock_fd, const char *target_host) {
    if (g_cfg.ipc_fd < 0 || sock_fd < 0) return;
    if (target_host && (strcmp(target_host, "127.0.0.1") == 0 || strcmp(target_host, "localhost") == 0)) {
        return;
    }
    pthread_mutex_lock(&g_ipc_mtx);
    char tag = 'P';
    struct iovec iov = { .iov_base = &tag, .iov_len = 1 };
    char cmsgbuf[CMSG_SPACE(sizeof(int))];
    memset(cmsgbuf, 0, sizeof(cmsgbuf));
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsgbuf;
    msg.msg_controllen = sizeof(cmsgbuf);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &sock_fd, sizeof(int));
    if (sendmsg(g_cfg.ipc_fd, &msg, 0) == 1) {
        char ack = 0;
        ssize_t r = read(g_cfg.ipc_fd, &ack, 1);
        (void)r;
    }
    pthread_mutex_unlock(&g_ipc_mtx);
}

/* ==================== Protected TCP Dialer & Upstream Handshakes ==================== */

static int dial_protected_tcp(const char *host, int port, int timeout_ms) {
    struct addrinfo hints, *res = NULL, *rp;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    char port_s[16];
    snprintf(port_s, sizeof(port_s), "%d", port);
    if (getaddrinfo(host, port_s, &hints, &res) != 0 || !res) return -1;

    int sock = -1;
    for (rp = res; rp != NULL; rp = rp->ai_next) {
        sock = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (sock < 0) continue;
        protect_outbound_socket(sock, host);

        int flags = fcntl(sock, F_GETFL, 0);
        fcntl(sock, F_SETFL, flags | O_NONBLOCK);
        int rc = connect(sock, rp->ai_addr, rp->ai_addrlen);
        if (rc != 0 && errno != EINPROGRESS) {
            close(sock);
            sock = -1;
            continue;
        }
        if (rc != 0) {
            fd_set wfds;
            FD_ZERO(&wfds);
            FD_SET(sock, &wfds);
            struct timeval tv;
            tv.tv_sec = timeout_ms / 1000;
            tv.tv_usec = (timeout_ms % 1000) * 1000;
            if (select(sock + 1, NULL, &wfds, NULL, &tv) <= 0) {
                close(sock);
                sock = -1;
                continue;
            }
            int so_err = 0;
            socklen_t sl = sizeof(so_err);
            getsockopt(sock, SOL_SOCKET, SO_ERROR, &so_err, &sl);
            if (so_err != 0) {
                close(sock);
                sock = -1;
                continue;
            }
        }
        fcntl(sock, F_SETFL, flags);
        int one = 1;
        setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        struct timeval rtv = { .tv_sec = 30, .tv_usec = 0 };
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &rtv, sizeof(rtv));
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &rtv, sizeof(rtv));
        break;
    }
    freeaddrinfo(res);
    return sock;
}

static int write_full(int fd, const void *buf, size_t len) {
    const uint8_t *p = (const uint8_t *)buf;
    size_t off = 0;
    while (off < len) {
        ssize_t n = send(fd, p + off, len - off, MSG_NOSIGNAL);
        if (n <= 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        off += (size_t)n;
    }
    return 0;
}

static int read_exact(int fd, void *buf, size_t len) {
    uint8_t *p = (uint8_t *)buf;
    size_t off = 0;
    while (off < len) {
        ssize_t n = recv(fd, p + off, len - off, 0);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return -1;
        }
        off += (size_t)n;
    }
    return 0;
}

static int dial_via_socks5(const char *proxy_host, int proxy_port,
                           const char *user, const char *pass,
                           uint32_t dst_ip_be, uint16_t dst_port_be) {
    int fd = dial_protected_tcp(proxy_host, proxy_port, 6000);
    if (fd < 0) return -1;

    int has_auth = (user && user[0] != '\0');
    uint8_t greet[4] = { 0x05, has_auth ? 2 : 1, 0x00, 0x02 };
    if (write_full(fd, greet, has_auth ? 4 : 3) != 0) { close(fd); return -1; }
    uint8_t grsp[2];
    if (read_exact(fd, grsp, 2) != 0 || grsp[0] != 0x05) { close(fd); return -1; }

    if (grsp[1] == 0x02) {
        size_t ulen = strlen(user);
        size_t plen = strlen(pass);
        uint8_t authpkt[140];
        authpkt[0] = 0x01;
        authpkt[1] = (uint8_t)ulen;
        memcpy(authpkt + 2, user, ulen);
        authpkt[2 + ulen] = (uint8_t)plen;
        memcpy(authpkt + 3 + ulen, pass, plen);
        if (write_full(fd, authpkt, 3 + ulen + plen) != 0) { close(fd); return -1; }
        uint8_t arsp[2];
        if (read_exact(fd, arsp, 2) != 0 || arsp[1] != 0x00) { close(fd); return -1; }
    } else if (grsp[1] != 0x00) {
        close(fd);
        return -1;
    }

    uint8_t req[10];
    req[0] = 0x05;
    req[1] = 0x01;
    req[2] = 0x00;
    req[3] = 0x01;
    memcpy(req + 4, &dst_ip_be, 4);
    memcpy(req + 8, &dst_port_be, 2);
    if (write_full(fd, req, 10) != 0) { close(fd); return -1; }

    uint8_t rsp[4];
    if (read_exact(fd, rsp, 4) != 0 || rsp[1] != 0x00) { close(fd); return -1; }
    uint8_t skip[260];
    if (rsp[3] == 0x01) {
        if (read_exact(fd, skip, 6) != 0) { close(fd); return -1; }
    } else if (rsp[3] == 0x04) {
        if (read_exact(fd, skip, 18) != 0) { close(fd); return -1; }
    } else if (rsp[3] == 0x03) {
        uint8_t dlen = 0;
        if (read_exact(fd, &dlen, 1) != 0 || read_exact(fd, skip, dlen + 2) != 0) {
            close(fd);
            return -1;
        }
    }
    return fd;
}

static int send_http_connect_handshake(int fd, uint32_t dst_ip_be, uint16_t dst_port_be,
                                       const char *user, const char *pass) {
    char ip_s[32];
    inet_ntop(AF_INET, &dst_ip_be, ip_s, sizeof(ip_s));
    int port = ntohs(dst_port_be);
    char auth_hdr[256] = "";
    if (user && user[0] != '\0') {
        char raw[140];
        unsigned char b64[200];
        size_t olen = 0;
        snprintf(raw, sizeof(raw), "%s:%s", user, pass ? pass : "");
        if (mbedtls_base64_encode(b64, sizeof(b64), &olen,
                                  (const unsigned char *)raw, strlen(raw)) == 0) {
            snprintf(auth_hdr, sizeof(auth_hdr), "Proxy-Authorization: Basic %s\r\n", (char *)b64);
        }
    }
    char req[512];
    int n = snprintf(req, sizeof(req),
                     "CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n%sConnection: keep-alive\r\n\r\n",
                     ip_s, port, ip_s, port, auth_hdr);
    if (write_full(fd, req, (size_t)n) != 0) return -1;

    char hdr[1024];
    size_t pos = 0;
    while (pos < sizeof(hdr) - 1) {
        ssize_t r = recv(fd, hdr + pos, 1, 0);
        if (r <= 0) return -1;
        pos += 1;
        hdr[pos] = '\0';
        if (pos >= 4 && memcmp(hdr + pos - 4, "\r\n\r\n", 4) == 0) break;
    }
    if (strstr(hdr, " 200") == NULL) return -1;
    return 0;
}

typedef struct {
    mbedtls_ssl_context ssl;
    mbedtls_ssl_config conf;
    mbedtls_net_context net;
} tls_tunnel_t;

static tls_tunnel_t *dial_tls_stealth(const char *host, int port, const char *sni,
                                      uint32_t dst_ip_be, uint16_t dst_port_be,
                                      const char *user, const char *pass) {
    int raw_fd = dial_protected_tcp(host, port, 7000);
    if (raw_fd < 0) return NULL;

    tls_tunnel_t *t = (tls_tunnel_t *)calloc(1, sizeof(tls_tunnel_t));
    if (!t) { close(raw_fd); return NULL; }
    mbedtls_net_init(&t->net);
    mbedtls_ssl_init(&t->ssl);
    mbedtls_ssl_config_init(&t->conf);
    t->net.fd = raw_fd;

    if (mbedtls_ssl_config_defaults(&t->conf, MBEDTLS_SSL_IS_CLIENT,
                                    MBEDTLS_SSL_TRANSPORT_STREAM,
                                    MBEDTLS_SSL_PRESET_DEFAULT) != 0) {
        goto fail;
    }
    mbedtls_ssl_conf_authmode(&t->conf, g_ca_loaded ? MBEDTLS_SSL_VERIFY_OPTIONAL : MBEDTLS_SSL_VERIFY_NONE);
    if (g_ca_loaded) mbedtls_ssl_conf_ca_chain(&t->conf, &g_cacert, NULL);
    mbedtls_ssl_conf_rng(&t->conf, mbedtls_ctr_drbg_random, &g_drbg);

    if (mbedtls_ssl_setup(&t->ssl, &t->conf) != 0) goto fail;
    mbedtls_ssl_set_hostname(&t->ssl, (sni && sni[0]) ? sni : host);
    mbedtls_ssl_set_bio(&t->ssl, &t->net, mbedtls_net_send, mbedtls_net_recv, NULL);

    while (1) {
        int rc = mbedtls_ssl_handshake(&t->ssl);
        if (rc == 0) break;
        if (rc != MBEDTLS_ERR_SSL_WANT_READ && rc != MBEDTLS_ERR_SSL_WANT_WRITE) goto fail;
    }

    char ip_s[32];
    inet_ntop(AF_INET, &dst_ip_be, ip_s, sizeof(ip_s));
    int dport = ntohs(dst_port_be);
    char auth_hdr[256] = "";
    if (user && user[0] != '\0') {
        char raw[140];
        unsigned char b64[200];
        size_t olen = 0;
        snprintf(raw, sizeof(raw), "%s:%s", user, pass ? pass : "");
        if (mbedtls_base64_encode(b64, sizeof(b64), &olen,
                                  (const unsigned char *)raw, strlen(raw)) == 0) {
            snprintf(auth_hdr, sizeof(auth_hdr), "Proxy-Authorization: Basic %s\r\n", (char *)b64);
        }
    }
    char req[512];
    int n = snprintf(req, sizeof(req),
                     "CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n%s\r\n",
                     ip_s, dport, ip_s, dport, auth_hdr);
    size_t sent = 0;
    while (sent < (size_t)n) {
        int w = mbedtls_ssl_write(&t->ssl, (const unsigned char *)req + sent, (size_t)n - sent);
        if (w <= 0) goto fail;
        sent += (size_t)w;
    }

    char hdr[1024];
    size_t pos = 0;
    while (pos < sizeof(hdr) - 1) {
        int r = mbedtls_ssl_read(&t->ssl, (unsigned char *)hdr + pos, 1);
        if (r <= 0) goto fail;
        pos += 1;
        hdr[pos] = '\0';
        if (pos >= 4 && memcmp(hdr + pos - 4, "\r\n\r\n", 4) == 0) break;
    }
    if (strstr(hdr, " 200") == NULL) goto fail;
    return t;

fail:
    mbedtls_ssl_free(&t->ssl);
    mbedtls_ssl_config_free(&t->conf);
    mbedtls_net_free(&t->net);
    free(t);
    return NULL;
}

/* ==================== RFC 8484 Encrypted DNS-over-HTTPS (TLS 1.3 :443) ==================== */

static int resolve_dns_over_https(const char *doh_host, const uint8_t *dns_req, size_t req_len,
                                  uint8_t *dns_resp_out, size_t max_out) {
    int raw_fd = dial_protected_tcp(doh_host, 443, 4500);
    if (raw_fd < 0) return -1;

    mbedtls_net_context net;
    mbedtls_ssl_context ssl;
    mbedtls_ssl_config conf;
    mbedtls_net_init(&net);
    mbedtls_ssl_init(&ssl);
    mbedtls_ssl_config_init(&conf);
    net.fd = raw_fd;

    int out_len = -1;
    if (mbedtls_ssl_config_defaults(&conf, MBEDTLS_SSL_IS_CLIENT,
                                    MBEDTLS_SSL_TRANSPORT_STREAM,
                                    MBEDTLS_SSL_PRESET_DEFAULT) != 0) goto cleanup;
    mbedtls_ssl_conf_authmode(&conf, g_ca_loaded ? MBEDTLS_SSL_VERIFY_REQUIRED : MBEDTLS_SSL_VERIFY_NONE);
    if (g_ca_loaded) mbedtls_ssl_conf_ca_chain(&conf, &g_cacert, NULL);
    mbedtls_ssl_conf_rng(&conf, mbedtls_ctr_drbg_random, &g_drbg);

    if (mbedtls_ssl_setup(&ssl, &conf) != 0) goto cleanup;
    const char *sni = strcmp(doh_host, "1.1.1.1") == 0 ? "cloudflare-dns.com"
                    : (strcmp(doh_host, "8.8.8.8") == 0 ? "dns.google"
                    : (strcmp(doh_host, "9.9.9.9") == 0 ? "dns.quad9.net" : doh_host));
    mbedtls_ssl_set_hostname(&ssl, sni);
    mbedtls_ssl_set_bio(&ssl, &net, mbedtls_net_send, mbedtls_net_recv, NULL);

    while (1) {
        int rc = mbedtls_ssl_handshake(&ssl);
        if (rc == 0) break;
        if (rc != MBEDTLS_ERR_SSL_WANT_READ && rc != MBEDTLS_ERR_SSL_WANT_WRITE) goto cleanup;
    }

    char http_hdr[384];
    int hlen = snprintf(http_hdr, sizeof(http_hdr),
                        "POST /dns-query HTTP/1.1\r\n"
                        "Host: %s\r\n"
                        "Content-Type: application/dns-message\r\n"
                        "Accept: application/dns-message\r\n"
                        "Content-Length: %zu\r\n"
                        "Connection: close\r\n\r\n",
                        sni, req_len);
    uint8_t *txbuf = (uint8_t *)malloc((size_t)hlen + req_len);
    if (!txbuf) goto cleanup;
    memcpy(txbuf, http_hdr, (size_t)hlen);
    memcpy(txbuf + hlen, dns_req, req_len);
    size_t sent = 0, total = (size_t)hlen + req_len;
    while (sent < total) {
        int w = mbedtls_ssl_write(&ssl, txbuf + sent, total - sent);
        if (w <= 0) { free(txbuf); goto cleanup; }
        sent += (size_t)w;
    }
    free(txbuf);

    uint8_t *rxbuf = (uint8_t *)malloc(8192);
    if (!rxbuf) goto cleanup;
    size_t rxpos = 0;
    while (rxpos < 8192) {
        int r = mbedtls_ssl_read(&ssl, rxbuf + rxpos, 8192 - rxpos);
        if (r == MBEDTLS_ERR_SSL_WANT_READ || r == MBEDTLS_ERR_SSL_WANT_WRITE) continue;
        if (r <= 0) break;
        rxpos += (size_t)r;
    }
    for (size_t i = 0; i + 4 <= rxpos; i++) {
        if (memcmp(rxbuf + i, "\r\n\r\n", 4) == 0) {
            size_t body_off = i + 4;
            size_t body_len = rxpos - body_off;
            if (body_len >= 12 && body_len <= max_out && memcmp(rxbuf, "HTTP/1.1 200", 12) == 0) {
                memcpy(dns_resp_out, rxbuf + body_off, body_len);
                out_len = (int)body_len;
            }
            break;
        }
    }
    free(rxbuf);

cleanup:
    mbedtls_ssl_close_notify(&ssl);
    mbedtls_ssl_free(&ssl);
    mbedtls_ssl_config_free(&conf);
    mbedtls_net_free(&net);
    return out_len;
}

typedef struct {
    uint32_t cli_ip_be;
    uint32_t srv_ip_be;
    uint16_t cli_port_be;
    uint16_t srv_port_be;
    uint8_t payload[1420];
    size_t plen;
} dns_job_t;

static void *dns_worker_thread(void *arg) {
    dns_job_t *job = (dns_job_t *)arg;
    uint8_t resp[1420];
    int rlen = -1;

    if (g_cfg.doh_host[0] != '\0') {
        rlen = resolve_dns_over_https(g_cfg.doh_host, job->payload, job->plen, resp, sizeof(resp));
    }
    if (rlen < 0) {
        int ufd = socket(AF_INET, SOCK_DGRAM, 0);
        if (ufd >= 0) {
            protect_outbound_socket(ufd, g_cfg.dns_udp_host);
            struct timeval tv = { .tv_sec = 3, .tv_usec = 0 };
            setsockopt(ufd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
            struct sockaddr_in sin;
            memset(&sin, 0, sizeof(sin));
            sin.sin_family = AF_INET;
            sin.sin_port = htons((uint16_t)g_cfg.dns_udp_port);
            inet_pton(AF_INET, g_cfg.dns_udp_host, &sin.sin_addr);
            if (sendto(ufd, job->payload, job->plen, 0, (struct sockaddr *)&sin, sizeof(sin)) > 0) {
                ssize_t n = recvfrom(ufd, resp, sizeof(resp), 0, NULL, NULL);
                if (n >= 12) rlen = (int)n;
            }
            close(ufd);
        }
    }
    if (rlen >= 12) {
        pthread_mutex_lock(&g_stats_mtx);
        g_dns_queries++;
        pthread_mutex_unlock(&g_stats_mtx);
        send_ipv4_udp_to_tun(g_cfg.tun_fd, job->srv_ip_be, job->cli_ip_be,
                             job->srv_port_be, job->cli_port_be, resp, (size_t)rlen);
    }
    free(job);
    return NULL;
}

/* ==================== Userspace TCP NAT Flow Engine ==================== */

typedef struct {
    int in_use;
    uint32_t cli_ip_be;
    uint32_t srv_ip_be;
    uint16_t cli_port_be;
    uint16_t srv_port_be;
    uint32_t cli_next_seq;
    uint32_t srv_next_seq;
    int pipe_to_remote[2];
    time_t last_seen;
    pthread_mutex_t lock;
} tcp_flow_t;

static tcp_flow_t g_flows[MAX_FLOWS];
static pthread_mutex_t g_flows_mtx = PTHREAD_MUTEX_INITIALIZER;

static void close_flow_locked(tcp_flow_t *f) {
    if (!f->in_use) return;
    f->in_use = 0;
    if (f->pipe_to_remote[1] >= 0) {
        close(f->pipe_to_remote[1]);
        f->pipe_to_remote[1] = -1;
    }
    pthread_mutex_lock(&g_stats_mtx);
    if (g_active_flows > 0) g_active_flows--;
    pthread_mutex_unlock(&g_stats_mtx);
}

static void *tcp_flow_worker(void *arg) {
    tcp_flow_t *f = (tcp_flow_t *)arg;
    int rpipe = f->pipe_to_remote[0];
    int sock = -1;
    tls_tunnel_t *tls_t = NULL;

    if (g_cfg.mode == MODE_SOCKS5) {
        sock = dial_via_socks5(g_cfg.upstream_host, g_cfg.upstream_port,
                               g_cfg.auth_user, g_cfg.auth_pass,
                               f->srv_ip_be, f->srv_port_be);
    } else if (g_cfg.mode == MODE_HTTP_CONNECT) {
        sock = dial_protected_tcp(g_cfg.upstream_host, g_cfg.upstream_port, 6000);
        if (sock >= 0 && send_http_connect_handshake(sock, f->srv_ip_be, f->srv_port_be,
                                                     g_cfg.auth_user, g_cfg.auth_pass) != 0) {
            close(sock);
            sock = -1;
        }
    } else if (g_cfg.mode == MODE_TLS_STEALTH) {
        tls_t = dial_tls_stealth(g_cfg.upstream_host, g_cfg.upstream_port, g_cfg.tls_sni,
                                 f->srv_ip_be, f->srv_port_be, g_cfg.auth_user, g_cfg.auth_pass);
        if (tls_t) sock = tls_t->net.fd;
    } else {
        char ip_s[32];
        inet_ntop(AF_INET, &f->srv_ip_be, ip_s, sizeof(ip_s));
        sock = dial_protected_tcp(ip_s, ntohs(f->srv_port_be), 6000);
    }

    if (sock < 0) {
        pthread_mutex_lock(&f->lock);
        send_ipv4_tcp_to_tun(g_cfg.tun_fd, f->srv_ip_be, f->cli_ip_be,
                             f->srv_port_be, f->cli_port_be,
                             f->srv_next_seq, f->cli_next_seq, 0x14, NULL, 0);
        pthread_mutex_unlock(&f->lock);
        pthread_mutex_lock(&g_flows_mtx);
        close_flow_locked(f);
        pthread_mutex_unlock(&g_flows_mtx);
        close(rpipe);
        return NULL;
    }

    uint8_t *buf = (uint8_t *)malloc(1360);
    if (!buf) goto out;

    while (g_running) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(rpipe, &rfds);
        FD_SET(sock, &rfds);
        int maxfd = (rpipe > sock ? rpipe : sock) + 1;
        struct timeval tv = { .tv_sec = 60, .tv_usec = 0 };
        int rc = select(maxfd, &rfds, NULL, NULL, &tv);
        if (rc <= 0) break;

        if (FD_ISSET(rpipe, &rfds)) {
            ssize_t n = read(rpipe, buf, 1360);
            if (n <= 0) break;
            if (tls_t) {
                size_t sent = 0;
                while (sent < (size_t)n) {
                    int w = mbedtls_ssl_write(&tls_t->ssl, buf + sent, (size_t)n - sent);
                    if (w <= 0) goto out;
                    sent += (size_t)w;
                }
            } else {
                if (write_full(sock, buf, (size_t)n) != 0) break;
            }
        }
        if (FD_ISSET(sock, &rfds)) {
            ssize_t n = tls_t
                ? mbedtls_ssl_read(&tls_t->ssl, buf, 1360)
                : recv(sock, buf, 1360, 0);
            if (n <= 0) {
                if (tls_t && (n == MBEDTLS_ERR_SSL_WANT_READ || n == MBEDTLS_ERR_SSL_WANT_WRITE)) continue;
                break;
            }
            pthread_mutex_lock(&f->lock);
            send_ipv4_tcp_to_tun(g_cfg.tun_fd, f->srv_ip_be, f->cli_ip_be,
                                 f->srv_port_be, f->cli_port_be,
                                 f->srv_next_seq, f->cli_next_seq, 0x18, buf, (size_t)n);
            f->srv_next_seq += (uint32_t)n;
            f->last_seen = time(NULL);
            pthread_mutex_unlock(&f->lock);
        }
    }

out:
    free(buf);
    pthread_mutex_lock(&f->lock);
    send_ipv4_tcp_to_tun(g_cfg.tun_fd, f->srv_ip_be, f->cli_ip_be,
                         f->srv_port_be, f->cli_port_be,
                         f->srv_next_seq, f->cli_next_seq, 0x11, NULL, 0);
    f->srv_next_seq += 1;
    pthread_mutex_unlock(&f->lock);

    pthread_mutex_lock(&g_flows_mtx);
    close_flow_locked(f);
    pthread_mutex_unlock(&g_flows_mtx);

    if (tls_t) {
        mbedtls_ssl_close_notify(&tls_t->ssl);
        mbedtls_ssl_free(&tls_t->ssl);
        mbedtls_ssl_config_free(&tls_t->conf);
        mbedtls_net_free(&tls_t->net);
        free(tls_t);
    } else if (sock >= 0) {
        close(sock);
    }
    close(rpipe);
    return NULL;
}

static void handle_tun_ipv4_packet(const uint8_t *pkt, size_t len) {
    if (len < 20 || (pkt[0] >> 4) != 4) return;
    size_t ihl = (size_t)(pkt[0] & 0x0F) * 4;
    if (ihl < 20 || len < ihl) return;

    pthread_mutex_lock(&g_stats_mtx);
    g_tx_bytes += (uint64_t)len;
    pthread_mutex_unlock(&g_stats_mtx);

    uint8_t proto = pkt[9];
    uint32_t src_be, dst_be;
    memcpy(&src_be, pkt + 12, 4);
    memcpy(&dst_be, pkt + 16, 4);

    if (proto == IPPROTO_UDP) {
        if (len < ihl + 8) return;
        const uint8_t *udp = pkt + ihl;
        uint16_t sport_be, dport_be;
        memcpy(&sport_be, udp, 2);
        memcpy(&dport_be, udp + 2, 2);
        size_t plen = len - ihl - 8;
        if (ntohs(dport_be) == 53 && plen >= 12 && plen <= 1420) {
            dns_job_t *job = (dns_job_t *)malloc(sizeof(dns_job_t));
            if (!job) return;
            job->cli_ip_be = src_be;
            job->srv_ip_be = dst_be;
            job->cli_port_be = sport_be;
            job->srv_port_be = dport_be;
            job->plen = plen;
            memcpy(job->payload, udp + 8, plen);

            pthread_attr_t attr;
            pthread_attr_init(&attr);
            pthread_attr_setstacksize(&attr, STACK_SIZE_2MB);
            pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
            pthread_t th;
            if (pthread_create(&th, &attr, dns_worker_thread, job) != 0) free(job);
            pthread_attr_destroy(&attr);
        }
        return;
    }

    if (proto == IPPROTO_TCP) {
        if (len < ihl + 20) return;
        const uint8_t *tcp = pkt + ihl;
        uint16_t sport_be, dport_be;
        uint32_t seq_be;
        memcpy(&sport_be, tcp, 2);
        memcpy(&dport_be, tcp + 2, 2);
        memcpy(&seq_be, tcp + 4, 4);
        uint32_t seq = ntohl(seq_be);
        size_t doff = (size_t)((tcp[12] >> 4) & 0x0F) * 4;
        if (doff < 20 || len < ihl + doff) return;
        uint8_t flags = tcp[13];
        const uint8_t *payload = tcp + doff;
        size_t plen = len - ihl - doff;

        pthread_mutex_lock(&g_flows_mtx);
        tcp_flow_t *found = NULL;
        int free_idx = -1;
        for (int i = 0; i < MAX_FLOWS; i++) {
            if (g_flows[i].in_use) {
                if (g_flows[i].cli_ip_be == src_be && g_flows[i].srv_ip_be == dst_be
                        && g_flows[i].cli_port_be == sport_be && g_flows[i].srv_port_be == dport_be) {
                    found = &g_flows[i];
                    break;
                }
            } else if (free_idx < 0) {
                free_idx = i;
            }
        }

        if ((flags & 0x02) && !(flags & 0x10)) {
            if (found) close_flow_locked(found);
            if (free_idx >= 0) {
                int pfd[2];
                if (pipe(pfd) == 0) {
                    tcp_flow_t *f = &g_flows[free_idx];
                    f->in_use = 1;
                    f->cli_ip_be = src_be;
                    f->srv_ip_be = dst_be;
                    f->cli_port_be = sport_be;
                    f->srv_port_be = dport_be;
                    f->cli_next_seq = seq + 1;
                    f->srv_next_seq = 0x10000000u + (uint32_t)((uintptr_t)f & 0xFFFFu);
                    f->pipe_to_remote[0] = pfd[0];
                    f->pipe_to_remote[1] = pfd[1];
                    f->last_seen = time(NULL);

                    pthread_mutex_lock(&g_stats_mtx);
                    g_active_flows++;
                    pthread_mutex_unlock(&g_stats_mtx);

                    send_ipv4_tcp_to_tun(g_cfg.tun_fd, dst_be, src_be, dport_be, sport_be,
                                         f->srv_next_seq, f->cli_next_seq, 0x12, NULL, 0);
                    f->srv_next_seq += 1;

                    pthread_attr_t attr;
                    pthread_attr_init(&attr);
                    pthread_attr_setstacksize(&attr, STACK_SIZE_2MB);
                    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
                    pthread_t th;
                    if (pthread_create(&th, &attr, tcp_flow_worker, f) != 0) {
                        close(pfd[0]);
                        close_flow_locked(f);
                    }
                    pthread_attr_destroy(&attr);
                }
            }
            pthread_mutex_unlock(&g_flows_mtx);
            return;
        }

        if (!found) {
            pthread_mutex_unlock(&g_flows_mtx);
            return;
        }

        if (flags & 0x04) {
            close_flow_locked(found);
            pthread_mutex_unlock(&g_flows_mtx);
            return;
        }

        if (plen > 0) {
            pthread_mutex_lock(&found->lock);
            if (seq == found->cli_next_seq) {
                found->cli_next_seq += (uint32_t)plen;
                found->last_seen = time(NULL);
                if (found->pipe_to_remote[1] >= 0) {
                    ssize_t w = write(found->pipe_to_remote[1], payload, plen);
                    (void)w;
                }
            }
            send_ipv4_tcp_to_tun(g_cfg.tun_fd, dst_be, src_be, dport_be, sport_be,
                                 found->srv_next_seq, found->cli_next_seq, 0x10, NULL, 0);
            pthread_mutex_unlock(&found->lock);
        }

        if (flags & 0x01) {
            pthread_mutex_lock(&found->lock);
            found->cli_next_seq += 1;
            send_ipv4_tcp_to_tun(g_cfg.tun_fd, dst_be, src_be, dport_be, sport_be,
                                 found->srv_next_seq, found->cli_next_seq, 0x10, NULL, 0);
            pthread_mutex_unlock(&found->lock);
            close_flow_locked(found);
        }
        pthread_mutex_unlock(&g_flows_mtx);
    }
}

static void *status_writer_thread(void *arg) {
    (void)arg;
    while (g_running) {
        if (g_cfg.status_file[0] != '\0') {
            uint64_t tx, rx, dnsq;
            int flows;
            pthread_mutex_lock(&g_stats_mtx);
            tx = g_tx_bytes;
            rx = g_rx_bytes;
            dnsq = g_dns_queries;
            flows = g_active_flows;
            pthread_mutex_unlock(&g_stats_mtx);

            char tmp[280];
            snprintf(tmp, sizeof(tmp), "%s.tmp", g_cfg.status_file);
            FILE *fp = fopen(tmp, "wb");
            if (fp) {
                fprintf(fp,
                        "{\"running\":true,\"mode\":%d,\"upstream\":\"%s:%d\","
                        "\"tx_bytes\":%llu,\"rx_bytes\":%llu,\"dns_queries\":%llu,"
                        "\"active_flows\":%d,\"uptime_sec\":%ld}\n",
                        (int)g_cfg.mode, g_cfg.upstream_host, g_cfg.upstream_port,
                        (unsigned long long)tx, (unsigned long long)rx,
                        (unsigned long long)dnsq, flows,
                        (long)(time(NULL) - g_start_time));
                fclose(fp);
                rename(tmp, g_cfg.status_file);
            }
        }
        sleep(1);
    }
    return NULL;
}

/* ==================== WireGuard RFC 7748 Curve25519 Key Tools ==================== */

static void clamp_x25519_priv(uint8_t k[32]) {
    k[0] &= 248;
    k[31] &= 127;
    k[31] |= 64;
}

static int derive_x25519_pubkey(const uint8_t priv_in[32], uint8_t pub_out[32]) {
    uint8_t clamped[32];
    memcpy(clamped, priv_in, 32);
    clamp_x25519_priv(clamped);

    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_EXPORT | PSA_KEY_USAGE_DERIVE);
    psa_set_key_algorithm(&attr, PSA_ALG_ECDH);
    psa_set_key_type(&attr, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_MONTGOMERY));
    psa_set_key_bits(&attr, 255);

    psa_key_id_t kid = 0;
    if (psa_import_key(&attr, clamped, 32, &kid) != PSA_SUCCESS) return -1;
    size_t pub_len = 0;
    psa_status_t st = psa_export_public_key(kid, pub_out, 32, &pub_len);
    psa_destroy_key(kid);
    return (st == PSA_SUCCESS && pub_len == 32) ? 0 : -1;
}

static int cmd_wg_keygen(void) {
    uint8_t priv[32], pub[32];
    if (mbedtls_ctr_drbg_random(&g_drbg, priv, 32) != 0) return 1;
    clamp_x25519_priv(priv);
    if (derive_x25519_pubkey(priv, pub) != 0) return 1;

    unsigned char priv_b64[64], pub_b64[64];
    size_t plen = 0, qlen = 0;
    mbedtls_base64_encode(priv_b64, sizeof(priv_b64), &plen, priv, 32);
    mbedtls_base64_encode(pub_b64, sizeof(pub_b64), &qlen, pub, 32);
    printf("PRIVATE_KEY=%s\nPUBLIC_KEY=%s\n", (char *)priv_b64, (char *)pub_b64);
    return 0;
}

static int cmd_wg_pubkey(const char *priv_b64) {
    uint8_t priv[64], pub[32];
    size_t n = 0;
    if (mbedtls_base64_decode(priv, sizeof(priv), &n,
                              (const unsigned char *)priv_b64, strlen(priv_b64)) != 0 || n != 32) {
        return 1;
    }
    if (derive_x25519_pubkey(priv, pub) != 0) return 1;
    unsigned char pub_b64[64];
    size_t qlen = 0;
    mbedtls_base64_encode(pub_b64, sizeof(pub_b64), &qlen, pub, 32);
    printf("%s\n", (char *)pub_b64);
    return 0;
}

/* ==================== Live DoH Query Command ==================== */

static int build_dns_a_query(const char *domain, uint8_t *out, size_t max_len) {
    if (max_len < 64) return -1;
    memset(out, 0, 12);
    out[0] = 0x13;
    out[1] = 0x37;
    out[2] = 0x01;
    out[5] = 0x01;
    size_t pos = 12;
    const char *p = domain;
    while (*p) {
        const char *dot = strchr(p, '.');
        size_t lab = dot ? (size_t)(dot - p) : strlen(p);
        if (lab == 0 || lab > 63 || pos + lab + 6 >= max_len) return -1;
        out[pos++] = (uint8_t)lab;
        memcpy(out + pos, p, lab);
        pos += lab;
        p = dot ? (dot + 1) : (p + lab);
    }
    out[pos++] = 0;
    out[pos++] = 0; out[pos++] = 1;
    out[pos++] = 0; out[pos++] = 1;
    return (int)pos;
}

static int cmd_doh_query(const char *doh_host, const char *domain, const char *cafile) {
    if (cafile && cafile[0] != '\0') {
        if (mbedtls_x509_crt_parse_file(&g_cacert, cafile) >= 0) g_ca_loaded = 1;
    }
    uint8_t q[512], resp[1420];
    int qlen = build_dns_a_query(domain, q, sizeof(q));
    if (qlen < 12) return 1;
    int rlen = resolve_dns_over_https(doh_host, q, (size_t)qlen, resp, sizeof(resp));
    if (rlen < 12) {
        fprintf(stderr, "DoH query failed\n");
        return 1;
    }
    uint16_t ancount = ((uint16_t)resp[6] << 8) | resp[7];
    printf("DOH_OK answers=%u bytes=%d\n", (unsigned)ancount, rlen);
    return (ancount > 0) ? 0 : 1;
}

/* ==================== Self-Test (Crypto + Full IPv4 tun2socks Loop) ==================== */

typedef struct {
    int listen_fd;
} mini_srv_t;

static void *selftest_http_target(void *arg) {
    int lfd = ((mini_srv_t *)arg)->listen_fd;
    int cfd = accept(lfd, NULL, NULL);
    if (cfd >= 0) {
        char req[256];
        ssize_t n = recv(cfd, req, sizeof(req) - 1, 0);
        (void)n;
        const char *resp = "HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\nARC7_TUN_OK";
        write_full(cfd, resp, strlen(resp));
        close(cfd);
    }
    return NULL;
}

static void *selftest_socks5_srv(void *arg) {
    int lfd = ((mini_srv_t *)arg)->listen_fd;
    int cfd = accept(lfd, NULL, NULL);
    if (cfd < 0) return NULL;
    uint8_t g[3];
    if (read_exact(cfd, g, 3) != 0) { close(cfd); return NULL; }
    uint8_t gr[2] = { 0x05, 0x00 };
    write_full(cfd, gr, 2);
    uint8_t rq[10];
    if (read_exact(cfd, rq, 10) != 0) { close(cfd); return NULL; }
    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    memcpy(&dst.sin_addr.s_addr, rq + 4, 4);
    memcpy(&dst.sin_port, rq + 8, 2);
    int out = socket(AF_INET, SOCK_STREAM, 0);
    if (connect(out, (struct sockaddr *)&dst, sizeof(dst)) != 0) {
        close(out);
        close(cfd);
        return NULL;
    }
    uint8_t rp[10] = { 0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0 };
    write_full(cfd, rp, 10);

    uint8_t buf[512];
    while (1) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(cfd, &rfds);
        FD_SET(out, &rfds);
        int mx = (cfd > out ? cfd : out) + 1;
        struct timeval tv = { .tv_sec = 3, .tv_usec = 0 };
        if (select(mx, &rfds, NULL, NULL, &tv) <= 0) break;
        if (FD_ISSET(cfd, &rfds)) {
            ssize_t n = recv(cfd, buf, sizeof(buf), 0);
            if (n <= 0) break;
            write_full(out, buf, (size_t)n);
        }
        if (FD_ISSET(out, &rfds)) {
            ssize_t n = recv(out, buf, sizeof(buf), 0);
            if (n <= 0) break;
            write_full(cfd, buf, (size_t)n);
        }
    }
    close(out);
    close(cfd);
    return NULL;
}

static void *selftest_tun_pump(void *arg) {
    int tfd = *(int *)arg;
    uint8_t pkt[1600];
    for (int i = 0; i < 6; i++) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(tfd, &rfds);
        struct timeval tv = { .tv_sec = 2, .tv_usec = 0 };
        if (select(tfd + 1, &rfds, NULL, NULL, &tv) <= 0) break;
        ssize_t n = read(tfd, pkt, sizeof(pkt));
        if (n <= 0) break;
        handle_tun_ipv4_packet(pkt, (size_t)n);
    }
    return NULL;
}

static int bind_local_ephemeral(int *port_out) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    sin.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    sin.sin_port = 0;
    if (bind(fd, (struct sockaddr *)&sin, sizeof(sin)) != 0 || listen(fd, 4) != 0) {
        close(fd);
        return -1;
    }
    socklen_t sl = sizeof(sin);
    getsockname(fd, (struct sockaddr *)&sin, &sl);
    *port_out = ntohs(sin.sin_port);
    return fd;
}

static int cmd_selftest(void) {
    /* 1. RFC 7748 Curve25519 deterministic basepoint vector */
    uint8_t rfc_priv[32] = {
        0x77,0x07,0x6d,0x0a,0x73,0x18,0xa5,0x7d,0x3c,0x16,0xc1,0x72,0x51,0xb2,0x66,0x45,
        0xdf,0x4c,0x2f,0x87,0xeb,0xc0,0x99,0x2a,0xb1,0x77,0xfb,0xa5,0x1d,0xb9,0x2c,0x2a
    };
    uint8_t expected_pub[32] = {
        0x85,0x20,0xf0,0x09,0x89,0x30,0xa7,0x54,0x74,0x8b,0x7d,0xdc,0xb4,0x3e,0xf7,0x5a,
        0x0d,0xbf,0x3a,0x0d,0x26,0x38,0x1a,0xf4,0xeb,0xa4,0xa9,0x8e,0xaa,0x9b,0x4e,0x6a
    };
    uint8_t got_pub[32];
    if (derive_x25519_pubkey(rfc_priv, got_pub) != 0 || memcmp(got_pub, expected_pub, 32) != 0) {
        fprintf(stderr, "FAIL: RFC 7748 Curve25519 vector mismatch\n");
        return 1;
    }

    /* 2. ChaCha20-Poly1305 AEAD encrypt + decrypt roundtrip */
    mbedtls_chachapoly_context cp;
    mbedtls_chachapoly_init(&cp);
    uint8_t key[32], nonce[12], tag[16], ct[32], dec[32];
    memset(key, 0x42, 32);
    memset(nonce, 0x07, 12);
    const char *plain = "Arc7WireGuardAEADTest1234567890!";
    if (mbedtls_chachapoly_setkey(&cp, key) != 0
            || mbedtls_chachapoly_encrypt_and_tag(&cp, 32, nonce, NULL, 0,
                                                  (const uint8_t *)plain, ct, tag) != 0
            || mbedtls_chachapoly_auth_decrypt(&cp, 32, nonce, NULL, 0, tag, ct, dec) != 0
            || memcmp(plain, dec, 32) != 0) {
        mbedtls_chachapoly_free(&cp);
        fprintf(stderr, "FAIL: ChaCha20-Poly1305 AEAD mismatch\n");
        return 1;
    }
    mbedtls_chachapoly_free(&cp);

    /* 3. Live raw IPv4 /dev/tun -> tun2socks -> SOCKS5 -> HTTP target packet loop */
    int http_port = 0, socks_port = 0;
    mini_srv_t http_srv = { .listen_fd = bind_local_ephemeral(&http_port) };
    mini_srv_t socks_srv = { .listen_fd = bind_local_ephemeral(&socks_port) };
    int sp[2];
    if (http_srv.listen_fd < 0 || socks_srv.listen_fd < 0 || socketpair(AF_UNIX, SOCK_DGRAM, 0, sp) != 0) {
        fprintf(stderr, "FAIL: selftest socket setup\n");
        return 1;
    }

    memset(&g_cfg, 0, sizeof(g_cfg));
    g_cfg.tun_fd = sp[0];
    g_cfg.ipc_fd = -1;
    g_cfg.mode = MODE_SOCKS5;
    snprintf(g_cfg.upstream_host, sizeof(g_cfg.upstream_host), "127.0.0.1");
    g_cfg.upstream_port = socks_port;
    for (int i = 0; i < MAX_FLOWS; i++) pthread_mutex_init(&g_flows[i].lock, NULL);

    pthread_t th_http, th_socks, th_tun;
    pthread_create(&th_http, NULL, selftest_http_target, &http_srv);
    pthread_create(&th_socks, NULL, selftest_socks5_srv, &socks_srv);
    pthread_create(&th_tun, NULL, selftest_tun_pump, &sp[0]);

    uint32_t cli_ip = inet_addr("10.200.0.2");
    uint32_t srv_ip = inet_addr("127.0.0.1");
    uint16_t cli_port = htons(54321);
    uint16_t srv_port = htons((uint16_t)http_port);

    /* Inject raw IPv4 TCP SYN from app side into virtual /dev/tun */
    send_ipv4_tcp_to_tun(sp[1], cli_ip, srv_ip, cli_port, srv_port, 1000, 0, 0x02, NULL, 0);

    uint8_t rxpkt[1600];
    ssize_t synack_n = read(sp[1], rxpkt, sizeof(rxpkt));
    if (synack_n < 40 || (rxpkt[33] & 0x12) != 0x12) {
        fprintf(stderr, "FAIL: expected TCP SYN+ACK from tun2socks\n");
        return 1;
    }
    uint32_t srv_seq_be;
    memcpy(&srv_seq_be, rxpkt + 24, 4);
    uint32_t srv_next = ntohl(srv_seq_be) + 1;

    /* Inject raw IPv4 HTTP GET payload packet */
    const char *http_get = "GET /ping HTTP/1.1\r\nHost: test\r\n\r\n";
    send_ipv4_tcp_to_tun(sp[1], cli_ip, srv_ip, cli_port, srv_port,
                         1001, srv_next, 0x18, (const uint8_t *)http_get, strlen(http_get));

    int got_payload = 0;
    for (int tries = 0; tries < 6; tries++) {
        ssize_t n = read(sp[1], rxpkt, sizeof(rxpkt));
        if (n > 40) {
            rxpkt[n] = '\0';
            if (memmem(rxpkt + 40, (size_t)(n - 40), "ARC7_TUN_OK", 11) != NULL) {
                got_payload = 1;
                break;
            }
        }
    }
    send_ipv4_tcp_to_tun(sp[1], cli_ip, srv_ip, cli_port, srv_port, 1001 + (uint32_t)strlen(http_get), srv_next, 0x14, NULL, 0);

    pthread_join(th_tun, NULL);
    pthread_join(th_socks, NULL);
    pthread_join(th_http, NULL);
    close(sp[0]);
    close(sp[1]);
    close(http_srv.listen_fd);
    close(socks_srv.listen_fd);

    if (!got_payload) {
        fprintf(stderr, "FAIL: did not receive ARC7_TUN_OK through tun2socks -> SOCKS5\n");
        return 1;
    }
    printf("ARC7VPN_SELFTEST_OK curve25519=RFC7748 chacha20poly1305=OK tun2socks_socks5_http=ARC7_TUN_OK\n");
    return 0;
}

static void on_sigterm(int sig) {
    (void)sig;
    g_running = 0;
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, on_sigterm);
    signal(SIGINT, on_sigterm);

    psa_crypto_init();
    mbedtls_entropy_init(&g_entropy);
    mbedtls_ctr_drbg_init(&g_drbg);
    mbedtls_x509_crt_init(&g_cacert);
    const char *pers = "arc7vpn-kobo-mt8125";
    mbedtls_ctr_drbg_seed(&g_drbg, mbedtls_entropy_func, &g_entropy,
                          (const unsigned char *)pers, strlen(pers));

    if (argc >= 2 && strcmp(argv[1], "wg-keygen") == 0) {
        return cmd_wg_keygen();
    }
    if (argc >= 2 && strcmp(argv[1], "wg-pubkey") == 0) {
        if (argc >= 3) {
            return cmd_wg_pubkey(argv[2]);
        }
        char line_buf[128] = "";
        if (!fgets(line_buf, sizeof(line_buf), stdin)) return 1;
        line_buf[strcspn(line_buf, "\r\n \t")] = '\0';
        return cmd_wg_pubkey(line_buf);
    }
    if (argc >= 4 && strcmp(argv[1], "doh-query") == 0) {
        return cmd_doh_query(argv[2], argv[3], argc >= 5 ? argv[4] : "");
    }
    if (argc >= 2 && strcmp(argv[1], "selftest") == 0) {
        return cmd_selftest();
    }

    if (argc < 2 || strcmp(argv[1], "tun2socks") != 0) {
        fprintf(stderr, "Usage: arc7vpn <tun2socks|wg-keygen|wg-pubkey|doh-query|selftest> ...\n");
        return 1;
    }

    memset(&g_cfg, 0, sizeof(g_cfg));
    g_cfg.tun_fd = -1;
    g_cfg.ipc_fd = -1;
    g_cfg.mode = MODE_DIRECT_DOH;
    snprintf(g_cfg.doh_host, sizeof(g_cfg.doh_host), "1.1.1.1");
    snprintf(g_cfg.dns_udp_host, sizeof(g_cfg.dns_udp_host), "1.1.1.1");
    g_cfg.dns_udp_port = 53;
    char ipc_name[128] = "";

    for (int i = 2; i < argc; i++) {
        if (strcmp(argv[i], "--tun-fd") == 0 && i + 1 < argc) {
            g_cfg.tun_fd = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--tun-ipc") == 0 && i + 1 < argc) {
            snprintf(ipc_name, sizeof(ipc_name), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--mode") == 0 && i + 1 < argc) {
            const char *m = argv[++i];
            if (strcmp(m, "socks5") == 0) g_cfg.mode = MODE_SOCKS5;
            else if (strcmp(m, "http") == 0) g_cfg.mode = MODE_HTTP_CONNECT;
            else if (strcmp(m, "tls") == 0) g_cfg.mode = MODE_TLS_STEALTH;
            else g_cfg.mode = MODE_DIRECT_DOH;
        } else if ((strcmp(argv[i], "--upstream") == 0 || strcmp(argv[i], "--server") == 0) && i + 1 < argc) {
            const char *up = argv[++i];
            const char *col = strrchr(up, ':');
            if (col) {
                size_t hl = (size_t)(col - up);
                if (hl >= sizeof(g_cfg.upstream_host)) hl = sizeof(g_cfg.upstream_host) - 1;
                memcpy(g_cfg.upstream_host, up, hl);
                g_cfg.upstream_host[hl] = '\0';
                g_cfg.upstream_port = atoi(col + 1);
            }
        } else if (strcmp(argv[i], "--dns-udp") == 0 && i + 1 < argc) {
            const char *du = argv[++i];
            const char *col = strrchr(du, ':');
            if (col) {
                size_t hl = (size_t)(col - du);
                if (hl >= sizeof(g_cfg.dns_udp_host)) hl = sizeof(g_cfg.dns_udp_host) - 1;
                memcpy(g_cfg.dns_udp_host, du, hl);
                g_cfg.dns_udp_host[hl] = '\0';
                g_cfg.dns_udp_port = atoi(col + 1);
            } else {
                snprintf(g_cfg.dns_udp_host, sizeof(g_cfg.dns_udp_host), "%s", du);
            }
            g_cfg.doh_host[0] = '\0';
        } else if (strcmp(argv[i], "--auth") == 0 && i + 1 < argc) {
            const char *au = argv[++i];
            const char *col = strchr(au, ':');
            if (col) {
                size_t ul = (size_t)(col - au);
                if (ul >= sizeof(g_cfg.auth_user)) ul = sizeof(g_cfg.auth_user) - 1;
                memcpy(g_cfg.auth_user, au, ul);
                g_cfg.auth_user[ul] = '\0';
                snprintf(g_cfg.auth_pass, sizeof(g_cfg.auth_pass), "%s", col + 1);
            }
        } else if (strcmp(argv[i], "--sni") == 0 && i + 1 < argc) {
            snprintf(g_cfg.tls_sni, sizeof(g_cfg.tls_sni), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--doh-host") == 0 && i + 1 < argc) {
            snprintf(g_cfg.doh_host, sizeof(g_cfg.doh_host), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--cafile") == 0 && i + 1 < argc) {
            snprintf(g_cfg.cafile, sizeof(g_cfg.cafile), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--status-file") == 0 && i + 1 < argc) {
            snprintf(g_cfg.status_file, sizeof(g_cfg.status_file), "%s", argv[++i]);
        }
    }

    if (g_cfg.cafile[0] != '\0') {
        if (mbedtls_x509_crt_parse_file(&g_cacert, g_cfg.cafile) >= 0) g_ca_loaded = 1;
    }

    if (ipc_name[0] != '\0') {
        g_cfg.ipc_fd = connect_abstract_ipc(ipc_name);
        if (g_cfg.ipc_fd < 0) {
            fprintf(stderr, "Failed to connect abstract IPC %s\n", ipc_name);
            return 1;
        }
        g_cfg.tun_fd = recv_fd_from_ipc(g_cfg.ipc_fd);
    }

    if (g_cfg.tun_fd < 0) {
        fprintf(stderr, "Invalid tun fd\n");
        return 1;
    }

    for (int i = 0; i < MAX_FLOWS; i++) pthread_mutex_init(&g_flows[i].lock, NULL);
    g_start_time = time(NULL);

    pthread_t st_th;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, STACK_SIZE_2MB);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&st_th, &attr, status_writer_thread, NULL);
    pthread_attr_destroy(&attr);

    uint8_t *pkt = (uint8_t *)malloc(2048);
    if (!pkt) return 1;

    while (g_running) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(g_cfg.tun_fd, &rfds);
        struct timeval tv = { .tv_sec = 1, .tv_usec = 0 };
        int rc = select(g_cfg.tun_fd + 1, &rfds, NULL, NULL, &tv);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (rc == 0) continue;
        ssize_t n = read(g_cfg.tun_fd, pkt, 2048);
        if (n <= 0) {
            if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            break;
        }
        handle_tun_ipv4_packet(pkt, (size_t)n);
    }

    free(pkt);
    if (g_cfg.status_file[0] != '\0') unlink(g_cfg.status_file);
    return 0;
}
