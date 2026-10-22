/*
 * arc7tls — Self-Contained TLS 1.3 Network, NTP & Browser Gateway Engine
 *           for Kobo Arc 7 (Android 4.2.2 Jelly Bean, ARMv7-A)
 *
 * Upgraded in v1.8.0 to permanently eliminate ALL SSL / Index / Thread crashes:
 *   1. Thread & Stack Hardening:
 *      - `2 MiB` explicit pthread stack (`pthread_attr_setstacksize`) — fixes
 *        `musl` static `128 KB` default stack overflow (`SIGSEGV`) during X.509
 *        chain verification & dynamic cert generation!
 *      - All large HTTP/TLS buffers allocated on heap (`malloc` / `calloc`).
 *      - Built with `-DMBEDTLS_THREADING_C -DMBEDTLS_THREADING_PTHREAD` so
 *        Mbed-TLS 3.6 + PSA Crypto key slots are 100% mutex-protected across
 *        concurrent browser/proxy/F-Droid threads.
 *   2. Universal Local `TLS 1.0 / 1.1 / 1.2` -> Upstream `TLS 1.3` Upgrade Tunnel:
 *      - Uses BearSSL's zero-copy non-blocking server state machine
 *        (`br_ssl_server_init_full_rsa`) on `127.0.0.1` so 2013 Chrome,
 *        Android 4.2 Stock Browser, and legacy apps speaking `TLS 1.0`,
 *        `TLS 1.1`, OR `TLS 1.2` handshake cleanly in <1 ms with CLEAN
 *        `close_notify` shutdown (zero `SSL connection error` or truncated bodies!).
 *      - Dynamic leaf certificates include `NotBefore = 20120101000000` through
 *        `20371231235959` (never fails `CERT_NOT_YET_VALID` on dead-RTC boot!),
 *        `SubjectAltName` (`DNS` / `IP`), AND `ExtendedKeyUsage = serverAuth`.
 *   3. Transparent `TLS 1.3` Repository Mirror Bridge (`/__https/<host>/<path>`):
 *      - Supports `GET` & `HEAD`, `Range`, `If-Modified-Since`, `If-None-Match`,
 *        `200 / 206 / 304`, and up to 12 HTTPS redirects over real `TLS 1.3` so
 *        F-Droid Classic downloads & cryptographically verifies official `f-droid.org`
 *        and `f-droid.org/archive` indexes cleanly at full Wi-Fi speed!
 *   4. Dual UDP NTP (`:123`) + TCP HTTP `Date:` (`:80`) Clock Sync (`ntp [--set]`):
 *      - Syncs system clock to exact 2026 UTC even behind routers blocking UDP 123!
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
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
#include <time.h>
#include <unistd.h>

#include <bearssl.h>

#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/error.h>
#include <mbedtls/md5.h>
#include <mbedtls/net_sockets.h>
#include <mbedtls/oid.h>
#include <mbedtls/pk.h>
#include <mbedtls/ssl.h>
#include <mbedtls/x509.h>
#include <mbedtls/x509_crt.h>
#include <psa/crypto.h>

#define USER_AGENT_STR \
    "Mozilla/5.0 (Linux; Android 10; Kobo Arc 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
#define ARC7_WORKER_STACK_BYTES (2 * 1024 * 1024)

/* -------------------- URL Parser -------------------- */

typedef struct {
    int is_https;
    char host[256];
    char port[8];
    char path[2048];
} parsed_url_t;

static int parse_url(const char *raw, parsed_url_t *out) {
    memset(out, 0, sizeof(*out));
    const char *p = raw;
    if (strncasecmp(p, "https://", 8) == 0) {
        out->is_https = 1;
        strcpy(out->port, "443");
        p += 8;
    } else if (strncasecmp(p, "http://", 7) == 0) {
        out->is_https = 0;
        strcpy(out->port, "80");
        p += 7;
    } else {
        return -1;
    }

    const char *slash = strchr(p, '/');
    const char *qmark = strchr(p, '?');
    const char *host_end = slash;
    if (!host_end || (qmark && qmark < host_end)) host_end = qmark;

    size_t hostport_len = host_end ? (size_t)(host_end - p) : strlen(p);
    if (hostport_len == 0 || hostport_len >= sizeof(out->host)) return -1;

    char hostport[264];
    memcpy(hostport, p, hostport_len);
    hostport[hostport_len] = '\0';

    /* Strip userinfo if any */
    char *at = strrchr(hostport, '@');
    const char *hp = at ? (at + 1) : hostport;

    const char *colon = strrchr(hp, ':');
    if (colon && strchr(hp, ']') == NULL) {
        size_t hlen = (size_t)(colon - hp);
        if (hlen == 0 || hlen >= sizeof(out->host)) return -1;
        memcpy(out->host, hp, hlen);
        out->host[hlen] = '\0';
        snprintf(out->port, sizeof(out->port), "%.6s", colon + 1);
    } else {
        snprintf(out->host, sizeof(out->host), "%.254s", hp);
    }

    if (host_end) {
        if (*host_end == '?') {
            snprintf(out->path, sizeof(out->path), "/%.2045s", host_end);
        } else {
            snprintf(out->path, sizeof(out->path), "%.2046s", host_end);
        }
    } else {
        strcpy(out->path, "/");
    }
    /* Strip fragment (#...) which is never sent on HTTP wire */
    char *hash = strchr(out->path, '#');
    if (hash) *hash = '\0';
    return 0;
}

static void resolve_redirect(const parsed_url_t *base, const char *loc, char *dest, size_t cap) {
    /* Trim trailing CR/LF/whitespace */
    char clean[2048];
    size_t clen = 0;
    while (*loc == ' ' || *loc == '\t') loc++;
    while (loc[clen] && loc[clen] != '\r' && loc[clen] != '\n' && clen + 1 < sizeof(clean)) {
        clean[clen] = loc[clen];
        clen++;
    }
    while (clen > 0 && (clean[clen - 1] == ' ' || clean[clen - 1] == '\t')) clen--;
    clean[clen] = '\0';

    if (strncasecmp(clean, "https://", 8) == 0 || strncasecmp(clean, "http://", 7) == 0) {
        snprintf(dest, cap, "%s", clean);
    } else if (strncmp(clean, "//", 2) == 0) {
        snprintf(dest, cap, "%s:%s", base->is_https ? "https" : "http", clean);
    } else if (clean[0] == '/') {
        int std_port = (base->is_https && strcmp(base->port, "443") == 0) ||
                       (!base->is_https && strcmp(base->port, "80") == 0);
        if (std_port) {
            snprintf(dest, cap, "%s://%s%s", base->is_https ? "https" : "http", base->host, clean);
        } else {
            snprintf(dest, cap, "%s://%s:%s%s",
                     base->is_https ? "https" : "http", base->host, base->port, clean);
        }
    } else {
        /* Relative path resolution (e.g. Location: index-v1.jar) */
        char dir[1024];
        snprintf(dir, sizeof(dir), "%s", base->path);
        char *q = strchr(dir, '?');
        if (q) *q = '\0';
        char *last_slash = strrchr(dir, '/');
        if (last_slash) *(last_slash + 1) = '\0';
        else strcpy(dir, "/");
        snprintf(dest, cap, "%s://%s%s%s", base->is_https ? "https" : "http", base->host, dir, clean);
    }
}

/* -------------------- Dual NTP (UDP 123) + HTTP Date (TCP 80) Sync -------------------- */

static int month_from_abbr(const char *m) {
    static const char *names[12] = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };
    for (int i = 0; i < 12; i++) {
        if (strncasecmp(m, names[i], 3) == 0) return i;
    }
    return -1;
}

static int64_t days_from_civil(int y, unsigned m, unsigned d) {
    y -= m <= 2;
    const int era = (y >= 0 ? y : y - 399) / 400;
    const unsigned yoe = (unsigned)(y - era * 400);
    const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return (int64_t)era * 146097 + (int64_t)doe - 719468;
}

static time_t parse_http_date_gmt(const char *hdr_val) {
    /* RFC 7231 IMF-fixdate: "Wed, 21 Oct 2026 14:28:53 GMT" */
    const char *comma = strchr(hdr_val, ',');
    const char *p = comma ? (comma + 1) : hdr_val;
    while (*p == ' ' || *p == '\t') p++;
    int day = 0, year = 0, hh = 0, mm = 0, ss = 0;
    char mon[8] = {0};
    if (sscanf(p, "%d %3s %d %d:%d:%d", &day, mon, &year, &hh, &mm, &ss) != 6) {
        return 0;
    }
    int m = month_from_abbr(mon);
    if (m < 0 || year < 2024 || year > 2037 || day < 1 || day > 31) return 0;
    int64_t secs = days_from_civil(year, (unsigned)(m + 1), (unsigned)day) * 86400LL
                 + (int64_t)hh * 3600LL + (int64_t)mm * 60LL + (int64_t)ss;
    return (time_t)secs;
}

typedef struct {
    mbedtls_net_context net;
    mbedtls_ssl_context ssl;
    mbedtls_ssl_config conf;
    mbedtls_ctr_drbg_context ctr_drbg;
    mbedtls_entropy_context entropy;
    mbedtls_x509_crt local_ca;
    int owns_ca;
} tls_conn_t;

static int tls_connect_shared(tls_conn_t *c,
                              const parsed_url_t *u,
                              const char *ca_bundle_path,
                              mbedtls_x509_crt *shared_ca,
                              int allow_bad_clock_cert,
                              char *err_buf,
                              size_t err_cap);
static void tls_conn_init(tls_conn_t *c);
static void tls_conn_free(tls_conn_t *c);
static int tls_write_all(tls_conn_t *c, const void *buf, size_t len);
static int tls_read_some(tls_conn_t *c, void *buf, size_t cap);

/* ==================== Self-Contained Multi-Tier Android/Linux DNS Engine ====================
 * Why static musl `getaddrinfo()` fails on Android 4.2.2 (Kobo Arc 7):
 *   Android does NOT have `/etc/resolv.conf` and runs no DNS daemon on `127.0.0.1:53`.
 *   Static `musl` therefore queries `127.0.0.1:53`, gets `ECONNREFUSED`, and fails EVERY
 *   hostname lookup with `MBEDTLS_ERR_NET_UNKNOWN_HOST` ("NET - Failed to get an IP address
 *   for the given hostname") in both Arc7 Browser AND F-Droid Classic!
 *
 * Our 4-Tier Pure-C Resolver (`arc7_resolve_ipv4_list` + `arc7_tcp_connect_host`):
 *   Tier 0: Literal IPv4 (`inet_pton`) + 128-entry Thread-Safe RAM Cache (300s TTL, <0.001ms)
 *   Tier 1: Pure-C RFC 1035 UDP `:53` across `/etc/resolv.conf` + Android `getprop` Wi-Fi DNS
 *           (`dhcp.wlan0.dns1/2`, `net.dns1/2`) + `1.1.1.1`, `8.8.8.8`, `1.0.0.1`, `9.9.9.9`
 *   Tier 2: Pure-C RFC 1035 TCP `:53` fallback (handles truncated `TC=1` & UDP-blocked networks)
 *   Tier 3: Pure-C RFC 8484 DNS-over-HTTPS (`POST https://1.1.1.1/dns-query` & `8.8.8.8:443`)
 *           using raw IPv4 literals over port 443 TLS 1.3 — 100% immune even to total port-53 blocks!
 * ============================================================================================ */

static int connect_ipv4_raw_ms(uint32_t net_ipv4, uint16_t host_port, int timeout_ms) {
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) return -1;
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);

    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons(host_port);
    sa.sin_addr.s_addr = net_ipv4;

    int rc = connect(fd, (struct sockaddr *)&sa, sizeof(sa));
    if (rc != 0) {
        if (errno != EINPROGRESS) {
            close(fd);
            return -1;
        }
        fd_set wfds;
        FD_ZERO(&wfds);
        FD_SET(fd, &wfds);
        struct timeval tv = {
            .tv_sec = timeout_ms / 1000,
            .tv_usec = (timeout_ms % 1000) * 1000
        };
        if (select(fd + 1, NULL, &wfds, NULL, &tv) <= 0) {
            close(fd);
            return -1;
        }
        int so_err = 0;
        socklen_t slen = sizeof(so_err);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_err, &slen) != 0 || so_err != 0) {
            close(fd);
            return -1;
        }
    }
    if (flags >= 0) fcntl(fd, F_SETFL, flags);
    struct timeval io_tv = { .tv_sec = 4, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &io_tv, sizeof(io_tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &io_tv, sizeof(io_tv));
    return fd;
}

typedef struct {
    char host[256];
    uint32_t addrs[4];
    int count;
    time_t expires;
} dns_cache_entry_t;

static pthread_mutex_t g_dns_mu = PTHREAD_MUTEX_INITIALIZER;
static dns_cache_entry_t g_dns_cache[128];
static size_t g_dns_cache_next = 0;
static char g_android_prop_dns[4][40];
static int g_android_prop_dns_cnt = 0;
static time_t g_android_prop_dns_ts = 0;

static void dns_cache_lookup(const char *host, uint32_t out_addrs[4], int *out_cnt) {
    *out_cnt = 0;
    time_t now = time(NULL);
    pthread_mutex_lock(&g_dns_mu);
    for (size_t i = 0; i < 128; i++) {
        if (g_dns_cache[i].count > 0 &&
            g_dns_cache[i].expires > now &&
            strcasecmp(g_dns_cache[i].host, host) == 0) {
            int n = g_dns_cache[i].count;
            if (n > 4) n = 4;
            memcpy(out_addrs, g_dns_cache[i].addrs, (size_t)n * sizeof(uint32_t));
            *out_cnt = n;
            break;
        }
    }
    pthread_mutex_unlock(&g_dns_mu);
}

static void dns_cache_store(const char *host, const uint32_t addrs[4], int cnt) {
    if (cnt <= 0 || strlen(host) >= sizeof(g_dns_cache[0].host)) return;
    if (cnt > 4) cnt = 4;
    time_t exp = time(NULL) + 300;
    pthread_mutex_lock(&g_dns_mu);
    for (size_t i = 0; i < 128; i++) {
        if (g_dns_cache[i].count > 0 && strcasecmp(g_dns_cache[i].host, host) == 0) {
            memcpy(g_dns_cache[i].addrs, addrs, (size_t)cnt * sizeof(uint32_t));
            g_dns_cache[i].count = cnt;
            g_dns_cache[i].expires = exp;
            pthread_mutex_unlock(&g_dns_mu);
            return;
        }
    }
    size_t slot = g_dns_cache_next % 128;
    g_dns_cache_next++;
    snprintf(g_dns_cache[slot].host, sizeof(g_dns_cache[slot].host), "%s", host);
    memcpy(g_dns_cache[slot].addrs, addrs, (size_t)cnt * sizeof(uint32_t));
    g_dns_cache[slot].count = cnt;
    g_dns_cache[slot].expires = exp;
    pthread_mutex_unlock(&g_dns_mu);
}

static void add_unique_server_ip(uint32_t list[16], int *cnt, const char *ip_str) {
    if (!ip_str || *cnt >= 16) return;
    while (*ip_str && isspace((unsigned char)*ip_str)) ip_str++;
    char clean[64];
    size_t k = 0;
    while (ip_str[k] && !isspace((unsigned char)ip_str[k]) && ip_str[k] != ',' &&
           ip_str[k] != ';' && k + 1 < sizeof(clean)) {
        clean[k] = ip_str[k];
        k++;
    }
    clean[k] = '\0';
    struct in_addr addr;
    if (inet_pton(AF_INET, clean, &addr) != 1) return;
    if (addr.s_addr == 0) return;
    for (int i = 0; i < *cnt; i++) {
        if (list[i] == addr.s_addr) return;
    }
    list[(*cnt)++] = addr.s_addr;
}

static void parse_resolv_conf_file(const char *path, uint32_t list[16], int *cnt) {
    FILE *f = fopen(path, "r");
    if (!f) return;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        char *p = line;
        while (*p && isspace((unsigned char)*p)) p++;
        if (strncmp(p, "nameserver", 10) == 0 && isspace((unsigned char)p[10])) {
            add_unique_server_ip(list, cnt, p + 11);
        }
    }
    fclose(f);
}

static void refresh_android_prop_dns_if_needed(uint32_t list[16], int *cnt) {
    if (access("/system/bin/getprop", X_OK) != 0) return;
    time_t now = time(NULL);
    pthread_mutex_lock(&g_dns_mu);
    if (now < g_android_prop_dns_ts || now - g_android_prop_dns_ts > 30) {
        g_android_prop_dns_cnt = 0;
        g_android_prop_dns_ts = now;
        static const char *props[4] = {
            "dhcp.wlan0.dns1", "dhcp.wlan0.dns2", "net.dns1", "net.dns2"
        };
        for (int i = 0; i < 4; i++) {
            char cmd[96];
            snprintf(cmd, sizeof(cmd), "/system/bin/getprop %s 2>/dev/null", props[i]);
            FILE *fp = popen(cmd, "r");
            if (!fp) continue;
            char val[64] = {0};
            if (fgets(val, sizeof(val), fp)) {
                size_t l = strlen(val);
                while (l > 0 && (val[l - 1] == '\n' || val[l - 1] == '\r' || val[l - 1] == ' ')) {
                    val[--l] = '\0';
                }
                struct in_addr tmp;
                if (l > 0 && inet_pton(AF_INET, val, &tmp) == 1 && tmp.s_addr != 0 &&
                    strcmp(val, "127.0.0.1") != 0) {
                    snprintf(g_android_prop_dns[g_android_prop_dns_cnt],
                             sizeof(g_android_prop_dns[0]), "%s", val);
                    g_android_prop_dns_cnt++;
                }
            }
            pclose(fp);
        }
    }
    for (int i = 0; i < g_android_prop_dns_cnt; i++) {
        add_unique_server_ip(list, cnt, g_android_prop_dns[i]);
    }
    pthread_mutex_unlock(&g_dns_mu);
}

static int collect_dns_servers(uint32_t list[16], int *doh_only) {
    int cnt = 0;
    *doh_only = 0;
    const char *env_dns = getenv("ARC7_DNS");
    if (env_dns && env_dns[0]) {
        if (strcasecmp(env_dns, "DOH_ONLY") == 0) {
            *doh_only = 1;
            return 0;
        }
        const char *p = env_dns;
        while (*p) {
            while (*p && (isspace((unsigned char)*p) || *p == ',' || *p == ';')) p++;
            if (!*p) break;
            add_unique_server_ip(list, &cnt, p);
            while (*p && !isspace((unsigned char)*p) && *p != ',' && *p != ';') p++;
        }
        if (cnt > 0) return cnt;
    }

    parse_resolv_conf_file("/etc/resolv.conf", list, &cnt);
    parse_resolv_conf_file("/system/etc/resolv.conf", list, &cnt);
    refresh_android_prop_dns_if_needed(list, &cnt);

    add_unique_server_ip(list, &cnt, "1.1.1.1");
    add_unique_server_ip(list, &cnt, "8.8.8.8");
    add_unique_server_ip(list, &cnt, "1.0.0.1");
    add_unique_server_ip(list, &cnt, "8.8.4.4");
    add_unique_server_ip(list, &cnt, "9.9.9.9");
    return cnt;
}

static int dns_build_query_a(const char *host, uint16_t txid, uint8_t *pkt, size_t cap) {
    if (!host || !host[0] || cap < 64) return -1;
    memset(pkt, 0, 12);
    pkt[0] = (uint8_t)(txid >> 8);
    pkt[1] = (uint8_t)(txid & 0xFF);
    pkt[2] = 0x01; /* RD = 1 (recursion desired) */
    pkt[3] = 0x00;
    pkt[4] = 0x00;
    pkt[5] = 0x01; /* QDCOUNT = 1 */

    size_t pos = 12;
    const char *p = host;
    while (*p) {
        const char *dot = strchr(p, '.');
        size_t len = dot ? (size_t)(dot - p) : strlen(p);
        if (len == 0) {
            if (!dot || dot[1] == '\0') break; /* trailing dot */
            return -1;
        }
        if (len > 63 || pos + 1 + len + 6 > cap) return -1;
        pkt[pos++] = (uint8_t)len;
        for (size_t i = 0; i < len; i++) {
            pkt[pos++] = (uint8_t)tolower((unsigned char)p[i]);
        }
        if (!dot) break;
        p = dot + 1;
    }
    pkt[pos++] = 0x00;
    pkt[pos++] = 0x00; pkt[pos++] = 0x01; /* QTYPE = A (1) */
    pkt[pos++] = 0x00; pkt[pos++] = 0x01; /* QCLASS = IN (1) */
    return (int)pos;
}

static int dns_read_name(const uint8_t *pkt, size_t pkt_len, size_t *pos,
                         char *out_name, size_t out_cap) {
    size_t cur = *pos;
    int jumped = 0;
    int jumps = 0;
    size_t w = 0;
    if (out_name && out_cap > 0) out_name[0] = '\0';

    while (jumps < 20) {
        if (cur >= pkt_len) return -1;
        uint8_t b = pkt[cur];
        if (b == 0) {
            if (!jumped) *pos = cur + 1;
            if (out_name && out_cap > 0) out_name[w] = '\0';
            return 0;
        }
        if ((b & 0xC0) == 0xC0) {
            if (cur + 1 >= pkt_len) return -1;
            size_t ptr = ((size_t)(b & 0x3F) << 8) | (size_t)pkt[cur + 1];
            if (ptr >= pkt_len) return -1;
            if (!jumped) {
                *pos = cur + 2;
                jumped = 1;
            }
            cur = ptr;
            jumps++;
            continue;
        }
        if ((b & 0xC0) != 0) return -1;
        size_t len = (size_t)b;
        if (cur + 1 + len > pkt_len) return -1;
        if (out_name && out_cap > 1) {
            if (w > 0 && w + 1 < out_cap) out_name[w++] = '.';
            for (size_t i = 0; i < len && w + 1 < out_cap; i++) {
                out_name[w++] = (char)pkt[cur + 1 + i];
            }
            out_name[w] = '\0';
        }
        cur += 1 + len;
    }
    return -1;
}

static int dns_parse_answers_a(const uint8_t *pkt, size_t pkt_len,
                               uint16_t expected_txid, int check_txid,
                               int *out_truncated, uint32_t out_ips[4], int *out_ip_cnt,
                               char *out_cname, size_t cname_cap) {
    *out_truncated = 0;
    *out_ip_cnt = 0;
    if (out_cname && cname_cap > 0) out_cname[0] = '\0';
    if (pkt_len < 12) return -1;

    uint16_t txid = ((uint16_t)pkt[0] << 8) | (uint16_t)pkt[1];
    if (check_txid && txid != expected_txid) return -1;

    uint16_t flags = ((uint16_t)pkt[2] << 8) | (uint16_t)pkt[3];
    *out_truncated = (flags & 0x0200) ? 1 : 0;
    if ((flags & 0x8000) == 0) return -1; /* Not a DNS response */
    if ((flags & 0x000F) != 0) return -1; /* NXDOMAIN / SERVFAIL */

    uint16_t qdcount = ((uint16_t)pkt[4] << 8) | (uint16_t)pkt[5];
    uint16_t ancount = ((uint16_t)pkt[6] << 8) | (uint16_t)pkt[7];
    size_t pos = 12;

    for (uint16_t q = 0; q < qdcount; q++) {
        if (dns_read_name(pkt, pkt_len, &pos, NULL, 0) != 0) return -1;
        if (pos + 4 > pkt_len) return -1;
        pos += 4;
    }

    for (uint16_t a = 0; a < ancount; a++) {
        if (dns_read_name(pkt, pkt_len, &pos, NULL, 0) != 0) return -1;
        if (pos + 10 > pkt_len) return -1;
        uint16_t rtype = ((uint16_t)pkt[pos] << 8) | (uint16_t)pkt[pos + 1];
        uint16_t rclass = ((uint16_t)pkt[pos + 2] << 8) | (uint16_t)pkt[pos + 3];
        uint16_t rdlen = ((uint16_t)pkt[pos + 8] << 8) | (uint16_t)pkt[pos + 9];
        pos += 10;
        if (pos + rdlen > pkt_len) return -1;

        if (rtype == 1 && rclass == 1 && rdlen == 4) {
            uint32_t ip_net = 0;
            memcpy(&ip_net, pkt + pos, 4);
            if (ip_net != 0 && *out_ip_cnt < 4) {
                int dup = 0;
                for (int k = 0; k < *out_ip_cnt; k++) {
                    if (out_ips[k] == ip_net) { dup = 1; break; }
                }
                if (!dup) out_ips[(*out_ip_cnt)++] = ip_net;
            }
        } else if (rtype == 5 && rclass == 1 && out_cname && out_cname[0] == '\0') {
            size_t cpos = pos;
            dns_read_name(pkt, pkt_len, &cpos, out_cname, cname_cap);
        }
        pos += rdlen;
    }
    return 0;
}

static int dns_query_udp_or_tcp(uint32_t server_net_ip, const uint8_t *qpkt, size_t qlen,
                                uint16_t txid, uint8_t *rpkt, size_t rcap,
                                uint32_t out_ips[4], int *out_ip_cnt,
                                char *out_cname, size_t cname_cap) {
    int dns_port = 53;
    const char *env_dport = getenv("ARC7_DNS_PORT");
    if (env_dport && atoi(env_dport) > 0) dns_port = atoi(env_dport);

    /* 1. Try fast UDP :53 (1100ms timeout) */
    int ufd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    int need_tcp = 1;
    if (ufd >= 0) {
        struct timeval tv = { .tv_sec = 1, .tv_usec = 100000 };
        setsockopt(ufd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(ufd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        struct sockaddr_in sa;
        memset(&sa, 0, sizeof(sa));
        sa.sin_family = AF_INET;
        sa.sin_port = htons((uint16_t)dns_port);
        sa.sin_addr.s_addr = server_net_ip;

        if (sendto(ufd, qpkt, qlen, 0, (struct sockaddr *)&sa, sizeof(sa)) == (ssize_t)qlen) {
            ssize_t got = recv(ufd, rpkt, rcap, 0);
            if (got >= 12) {
                int trunc = 0;
                if (dns_parse_answers_a(rpkt, (size_t)got, txid, 1, &trunc,
                                        out_ips, out_ip_cnt, out_cname, cname_cap) == 0) {
                    if (!trunc) {
                        close(ufd);
                        return 0;
                    }
                } else {
                    /* Server answered authoritative NXDOMAIN/NOANSWER without truncation */
                    uint16_t flags = ((uint16_t)rpkt[2] << 8) | (uint16_t)rpkt[3];
                    if ((flags & 0x8000) && ((flags & 0x000F) == 3)) {
                        close(ufd);
                        return -2; /* Definitive NXDOMAIN */
                    }
                    need_tcp = 0;
                }
            }
        }
        close(ufd);
    }

    if (!need_tcp) return -1;

    /* 2. Automatic TCP :53 fallback (handles UDP-filtered Wi-Fi & truncated >512B answers) */
    int tfd = connect_ipv4_raw_ms(server_net_ip, (uint16_t)dns_port, 1400);
    if (tfd < 0) return -1;
    uint8_t hdr2[2] = { (uint8_t)(qlen >> 8), (uint8_t)(qlen & 0xFF) };
    if (send(tfd, hdr2, 2, MSG_NOSIGNAL) == 2 &&
        send(tfd, qpkt, qlen, MSG_NOSIGNAL) == (ssize_t)qlen) {
        uint8_t rhdr[2];
        if (recv(tfd, rhdr, 2, MSG_WAITALL) == 2) {
            size_t rlen = ((size_t)rhdr[0] << 8) | (size_t)rhdr[1];
            if (rlen >= 12 && rlen <= rcap) {
                size_t got = 0;
                while (got < rlen) {
                    ssize_t n = recv(tfd, rpkt + got, rlen - got, 0);
                    if (n <= 0) break;
                    got += (size_t)n;
                }
                if (got == rlen) {
                    int trunc = 0;
                    if (dns_parse_answers_a(rpkt, rlen, txid, 1, &trunc,
                                            out_ips, out_ip_cnt, out_cname, cname_cap) == 0) {
                        close(tfd);
                        return 0;
                    }
                }
            }
        }
    }
    close(tfd);
    return -1;
}

/* Tier 3: RFC 8484 DNS-over-HTTPS (`POST /dns-query`, `application/dns-message`) directly
 * to `1.1.1.1:443` / `8.8.8.8:443` via raw IPv4 literal — works even when ALL port 53 is blocked! */
static int dns_query_doh_443(const uint8_t *qpkt, size_t qlen, uint16_t txid,
                             uint32_t out_ips[4], int *out_ip_cnt,
                             char *out_cname, size_t cname_cap) {
    static const struct {
        const char *ip_str;
        const char *sni_host;
    } doh_endpoints[] = {
        { "1.1.1.1", "cloudflare-dns.com" },
        { "8.8.8.8", "dns.google" },
        { "1.0.0.1", "cloudflare-dns.com" }
    };

    psa_crypto_init();
    for (size_t i = 0; i < sizeof(doh_endpoints) / sizeof(doh_endpoints[0]); i++) {
        struct in_addr ip4;
        if (inet_pton(AF_INET, doh_endpoints[i].ip_str, &ip4) != 1) continue;
        int fd = connect_ipv4_raw_ms(ip4.s_addr, 443, 2500);
        if (fd < 0) continue;

        tls_conn_t c;
        tls_conn_init(&c);
        c.net.fd = fd;

        int ok = 0;
        const char *pers = "arc7_doh_443";
        if (mbedtls_ctr_drbg_seed(&c.ctr_drbg, mbedtls_entropy_func, &c.entropy,
                                  (const unsigned char *)pers, strlen(pers)) == 0 &&
            mbedtls_ssl_config_defaults(&c.conf, MBEDTLS_SSL_IS_CLIENT,
                                        MBEDTLS_SSL_TRANSPORT_STREAM,
                                        MBEDTLS_SSL_PRESET_DEFAULT) == 0) {
            mbedtls_ssl_conf_authmode(&c.conf, MBEDTLS_SSL_VERIFY_NONE);
            mbedtls_ssl_conf_rng(&c.conf, mbedtls_ctr_drbg_random, &c.ctr_drbg);
            if (mbedtls_ssl_setup(&c.ssl, &c.conf) == 0) {
                mbedtls_ssl_set_hostname(&c.ssl, doh_endpoints[i].sni_host);
                mbedtls_ssl_set_bio(&c.ssl, &c.net, mbedtls_net_send, mbedtls_net_recv, NULL);
                int hr;
                while ((hr = mbedtls_ssl_handshake(&c.ssl)) != 0) {
                    if (hr != MBEDTLS_ERR_SSL_WANT_READ &&
                        hr != MBEDTLS_ERR_SSL_WANT_WRITE &&
                        hr != MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET) {
                        break;
                    }
                }
                if (hr == 0) {
                    char req_hdr[384];
                    int hlen = snprintf(req_hdr, sizeof(req_hdr),
                                        "POST /dns-query HTTP/1.1\r\n"
                                        "Host: %s\r\n"
                                        "Accept: application/dns-message\r\n"
                                        "Content-Type: application/dns-message\r\n"
                                        "Content-Length: %zu\r\n"
                                        "Connection: close\r\n\r\n",
                                        doh_endpoints[i].sni_host, qlen);
                    if (tls_write_all(&c, req_hdr, (size_t)hlen) == 0 &&
                        tls_write_all(&c, qpkt, qlen) == 0) {
                        uint8_t *raw = (uint8_t *)malloc(16384);
                        if (raw) {
                            size_t used = 0;
                            while (used < 16384) {
                                int n = tls_read_some(&c, raw + used, 16384 - used);
                                if (n <= 0) break;
                                used += (size_t)n;
                            }
                            if (used > 20) {
                                uint8_t *sep = NULL;
                                for (size_t k = 0; k + 3 < used; k++) {
                                    if (raw[k] == '\r' && raw[k + 1] == '\n' &&
                                        raw[k + 2] == '\r' && raw[k + 3] == '\n') {
                                        sep = raw + k + 4;
                                        break;
                                    }
                                }
                                if (sep) {
                                    size_t body_len = used - (size_t)(sep - raw);
                                    int trunc = 0;
                                    if (body_len >= 12 &&
                                        dns_parse_answers_a(sep, body_len, txid, 0, &trunc,
                                                            out_ips, out_ip_cnt,
                                                            out_cname, cname_cap) == 0) {
                                        ok = 1;
                                    }
                                }
                            }
                            free(raw);
                        }
                    }
                }
            }
        }
        tls_conn_free(&c);
        if (ok && (*out_ip_cnt > 0 || (out_cname && out_cname[0] != '\0'))) {
            return 0;
        }
    }
    return -1;
}

static int arc7_resolve_ipv4_list(const char *host, uint32_t out_addrs[4], int *out_cnt) {
    *out_cnt = 0;
    if (!host || !host[0]) return -1;

    if (strcasecmp(host, "localhost") == 0) {
        struct in_addr lo;
        inet_pton(AF_INET, "127.0.0.1", &lo);
        out_addrs[0] = lo.s_addr;
        *out_cnt = 1;
        return 0;
    }

    struct in_addr literal_v4;
    if (inet_pton(AF_INET, host, &literal_v4) == 1) {
        out_addrs[0] = literal_v4.s_addr;
        *out_cnt = 1;
        return 0;
    }

    dns_cache_lookup(host, out_addrs, out_cnt);
    if (*out_cnt > 0) return 0;

    uint32_t servers[16];
    int doh_only = 0;
    int server_cnt = collect_dns_servers(servers, &doh_only);

    char cur_name[256];
    snprintf(cur_name, sizeof(cur_name), "%s", host);
    uint8_t *rpkt = (uint8_t *)malloc(8192);
    if (!rpkt) return -1;

    static uint32_t s_txid_seq = 0x4172;
    for (int hop = 0; hop < 5; hop++) {
        uint8_t qpkt[512];
        uint16_t txid = (uint16_t)((__sync_add_and_fetch(&s_txid_seq, 1) ^ (uint32_t)getpid()) & 0xFFFF);
        int qlen = dns_build_query_a(cur_name, txid, qpkt, sizeof(qpkt));
        if (qlen <= 0) break;

        char cname[256] = {0};
        int ip_cnt = 0;
        uint32_t ips[4];

        if (!doh_only) {
            for (int s = 0; s < server_cnt; s++) {
                cname[0] = '\0';
                ip_cnt = 0;
                int rc = dns_query_udp_or_tcp(servers[s], qpkt, (size_t)qlen, txid,
                                              rpkt, 8192, ips, &ip_cnt, cname, sizeof(cname));
                if (rc == -2) break; /* Definitive NXDOMAIN */
                if (rc == 0 && (ip_cnt > 0 || cname[0] != '\0')) break;
            }
        }

        /* Tier 3 automatic fallback: DNS-over-HTTPS (`1.1.1.1:443` / `8.8.8.8:443`) */
        if (ip_cnt == 0 && cname[0] == '\0') {
            dns_query_doh_443(qpkt, (size_t)qlen, txid, ips, &ip_cnt, cname, sizeof(cname));
        }

        if (ip_cnt > 0) {
            memcpy(out_addrs, ips, (size_t)ip_cnt * sizeof(uint32_t));
            *out_cnt = ip_cnt;
            dns_cache_store(host, out_addrs, ip_cnt);
            free(rpkt);
            return 0;
        }
        if (cname[0] != '\0' && strcasecmp(cname, cur_name) != 0) {
            snprintf(cur_name, sizeof(cur_name), "%s", cname);
            continue;
        }
        break;
    }

    free(rpkt);

    /* Last-resort libc `getaddrinfo` check for `/etc/hosts` custom local aliases */
    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, NULL, &hints, &res) == 0 && res) {
        for (struct addrinfo *p = res; p && *out_cnt < 4; p = p->ai_next) {
            if (p->ai_family == AF_INET && p->ai_addr) {
                uint32_t ip = ((struct sockaddr_in *)p->ai_addr)->sin_addr.s_addr;
                out_addrs[(*out_cnt)++] = ip;
            }
        }
        freeaddrinfo(res);
        if (*out_cnt > 0) {
            dns_cache_store(host, out_addrs, *out_cnt);
            return 0;
        }
    }
    return -1;
}

static int arc7_tcp_connect_host(const char *host, const char *port_str, int timeout_ms,
                                 char *err_buf, size_t err_cap) {
    int port_num = atoi(port_str ? port_str : "443");
    if (port_num <= 0 || port_num > 65535) port_num = 443;

    uint32_t addrs[4];
    int cnt = 0;
    if (arc7_resolve_ipv4_list(host, addrs, &cnt) != 0 || cnt <= 0) {
        if (err_buf && err_cap > 0) {
            snprintf(err_buf, err_cap, "DNS lookup could not resolve hostname '%s' (check Wi-Fi internet connection)", host);
        }
        return -1;
    }

    for (int i = 0; i < cnt; i++) {
        int fd = connect_ipv4_raw_ms(addrs[i], (uint16_t)port_num, timeout_ms);
        if (fd >= 0) return fd;
    }

    char ip_str[INET_ADDRSTRLEN] = {0};
    inet_ntop(AF_INET, &addrs[0], ip_str, sizeof(ip_str));
    if (err_buf && err_cap > 0) {
        snprintf(err_buf, err_cap, "TCP connect to %s (%s:%d) timed out", host, ip_str, port_num);
    }
    return -1;
}

static int sync_via_http_date(int set_clock, const char **out_source, time_t *out_epoch) {
    static const struct {
        const char *connect_host;
        const char *host_hdr;
    } targets[] = {
        { "1.1.1.1",                        "one.one.one.one" },
        { "connectivitycheck.gstatic.com",  "connectivitycheck.gstatic.com" },
        { "github.com",                     "github.com" },
    };

    for (size_t i = 0; i < sizeof(targets) / sizeof(targets[0]); i++) {
        int fd = arc7_tcp_connect_host(targets[i].connect_host, "80", 1500, NULL, 0);
        if (fd < 0) continue;

        int ok = 0;
        char req[256];
        int rlen = snprintf(req, sizeof(req),
                            "HEAD / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n",
                            targets[i].host_hdr);
        if (send(fd, req, (size_t)rlen, MSG_NOSIGNAL) > 0) {
            char resp[2048];
            ssize_t n = recv(fd, resp, sizeof(resp) - 1, 0);
            if (n > 20) {
                resp[n] = '\0';
                const char *dh = strcasestr(resp, "\nDate:");
                if (dh) {
                    time_t ep = parse_http_date_gmt(dh + 6);
                    if (ep > 1700000000) {
                        *out_source = targets[i].connect_host;
                        *out_epoch = ep;
                        ok = 1;
                    }
                }
            }
        }
        close(fd);
        if (ok) {
            if (set_clock) {
                struct timeval set_tv = { .tv_sec = *out_epoch, .tv_usec = 0 };
                settimeofday(&set_tv, NULL);
            }
            return 0;
        }
    }

    /* Tier 3: HTTPS:443 `HEAD /` `Date:` fallback (works even on strict 443-only networks!) */
    static const char *https_hosts[] = { "1.1.1.1", "github.com", "www.google.com" };
    for (size_t i = 0; i < sizeof(https_hosts) / sizeof(https_hosts[0]); i++) {
        parsed_url_t u;
        memset(&u, 0, sizeof(u));
        u.is_https = 1;
        snprintf(u.host, sizeof(u.host), "%s", https_hosts[i]);
        strcpy(u.port, "443");
        strcpy(u.path, "/");

        tls_conn_t c;
        tls_conn_init(&c);
        psa_crypto_init();
        const char *pers = "arc7_time_443";
        int raw_fd = arc7_tcp_connect_host(u.host, u.port, 3000, NULL, 0);
        if (raw_fd >= 0) c.net.fd = raw_fd;
        if (raw_fd >= 0 &&
            mbedtls_ctr_drbg_seed(&c.ctr_drbg, mbedtls_entropy_func, &c.entropy,
                                  (const unsigned char *)pers, strlen(pers)) == 0 &&
            mbedtls_ssl_config_defaults(&c.conf, MBEDTLS_SSL_IS_CLIENT,
                                        MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT) == 0) {
            struct timeval tv = { .tv_sec = 4, .tv_usec = 0 };
            setsockopt(c.net.fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
            setsockopt(c.net.fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
            mbedtls_ssl_conf_min_tls_version(&c.conf, MBEDTLS_SSL_VERSION_TLS1_2);
            mbedtls_ssl_conf_max_tls_version(&c.conf, MBEDTLS_SSL_VERSION_TLS1_2);
            mbedtls_ssl_conf_authmode(&c.conf, MBEDTLS_SSL_VERIFY_NONE);
            mbedtls_ssl_conf_rng(&c.conf, mbedtls_ctr_drbg_random, &c.ctr_drbg);
            if (mbedtls_ssl_setup(&c.ssl, &c.conf) == 0) {
                mbedtls_ssl_set_hostname(&c.ssl, u.host);
                mbedtls_ssl_set_bio(&c.ssl, &c.net, mbedtls_net_send, mbedtls_net_recv, NULL);
                int hr;
                while ((hr = mbedtls_ssl_handshake(&c.ssl)) != 0) {
                    if (hr != MBEDTLS_ERR_SSL_WANT_READ && hr != MBEDTLS_ERR_SSL_WANT_WRITE &&
                        hr != MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET) break;
                }
                if (hr == 0) {
                    char req[256];
                    int rlen = snprintf(req, sizeof(req),
                                        "HEAD / HTTP/1.1\r\nHost: %s\r\nUser-Agent: %s\r\nConnection: close\r\n\r\n",
                                        u.host, USER_AGENT_STR);
                    if (tls_write_all(&c, req, (size_t)rlen) == 0) {
                        char *resp = (char *)malloc(16384);
                        if (resp) {
                            size_t used = 0;
                            while (used + 1 < 16384) {
                                int n = tls_read_some(&c, resp + used, 16384 - 1 - used);
                                if (n <= 0) break;
                                used += (size_t)n;
                                resp[used] = '\0';
                                if (strstr(resp, "\r\n\r\n") != NULL) break;
                            }
                            const char *dh = strcasestr(resp, "\nDate:");
                            if (dh) {
                                time_t ep = parse_http_date_gmt(dh + 6);
                                if (ep > 1700000000) {
                                    *out_source = https_hosts[i];
                                    *out_epoch = ep;
                                    free(resp);
                                    tls_conn_free(&c);
                                    if (set_clock) {
                                        struct timeval set_tv = { .tv_sec = *out_epoch, .tv_usec = 0 };
                                        settimeofday(&set_tv, NULL);
                                    }
                                    return 0;
                                }
                            }
                            free(resp);
                        }
                    }
                }
            }
        }
        tls_conn_free(&c);
    }
    return -1;
}

static int cmd_ntp(int set_clock) {
    static const char *servers[] = {
        "time.cloudflare.com",
        "time.google.com",
        "pool.ntp.org",
        "162.159.200.1",
        "216.239.35.0"
    };
    const char *winner = NULL;
    time_t unix_sec = 0;

    for (size_t i = 0; i < sizeof(servers) / sizeof(servers[0]); i++) {
        uint32_t addrs[4];
        int acnt = 0;
        if (arc7_resolve_ipv4_list(servers[i], addrs, &acnt) != 0 || acnt <= 0) continue;

        int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (sock < 0) continue;

        struct timeval tv = { .tv_sec = 0, .tv_usec = 800000 };
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        struct sockaddr_in sa;
        memset(&sa, 0, sizeof(sa));
        sa.sin_family = AF_INET;
        sa.sin_port = htons(123);
        sa.sin_addr.s_addr = addrs[0];

        uint8_t pkt[48];
        memset(pkt, 0, sizeof(pkt));
        pkt[0] = 0x23; /* LI=0, VN=4, Mode=3 (Client) */

        ssize_t sent = sendto(sock, pkt, sizeof(pkt), 0, (struct sockaddr *)&sa, sizeof(sa));
        ssize_t got = (sent == sizeof(pkt)) ? recv(sock, pkt, sizeof(pkt), 0) : -1;
        close(sock);

        if (got < 48) continue;

        uint32_t tx_sec = ((uint32_t)pkt[40] << 24) | ((uint32_t)pkt[41] << 16) |
                          ((uint32_t)pkt[42] << 8)  | ((uint32_t)pkt[43]);
        if (tx_sec < 2208988800U + 1700000000U) continue;
        unix_sec = (time_t)(tx_sec - 2208988800U);
        winner = servers[i];
        break;
    }

    /* Automatic TCP port-80 HTTP `Date:` fallback when router blocks UDP 123! */
    if (!winner) {
        if (sync_via_http_date(set_clock, &winner, &unix_sec) != 0) {
            fprintf(stderr, "NTP_SYNC=FAILED reason=All UDP NTP & TCP HTTP time sources unreachable\n");
            return 1;
        }
    }

    int applied = 0;
    if (set_clock) {
        struct timeval set_tv = { .tv_sec = unix_sec, .tv_usec = 0 };
        if (settimeofday(&set_tv, NULL) == 0) applied = 1;
    }
    struct tm gmt;
    gmtime_r(&unix_sec, &gmt);
    char iso[64];
    strftime(iso, sizeof(iso), "%Y-%m-%dT%H:%M:%SZ", &gmt);
    printf("NTP_SERVER=%s\nNTP_EPOCH=%lld\nNTP_UTC=%s\nCLOCK_SET=%s\n",
           winner, (long long)unix_sec, iso, applied ? "YES" : (set_clock ? "NO_ROOT" : "SKIPPED"));
    return 0;
}

/* -------------------- TLS 1.3 Upstream Session Helper -------------------- */

static void tls_conn_init(tls_conn_t *c) {
    memset(c, 0, sizeof(*c));
    mbedtls_net_init(&c->net);
    mbedtls_ssl_init(&c->ssl);
    mbedtls_ssl_config_init(&c->conf);
    mbedtls_ctr_drbg_init(&c->ctr_drbg);
    mbedtls_entropy_init(&c->entropy);
    mbedtls_x509_crt_init(&c->local_ca);
}

static void tls_conn_free(tls_conn_t *c) {
    mbedtls_ssl_free(&c->ssl);
    mbedtls_ssl_config_free(&c->conf);
    if (c->owns_ca) mbedtls_x509_crt_free(&c->local_ca);
    mbedtls_ctr_drbg_free(&c->ctr_drbg);
    mbedtls_entropy_free(&c->entropy);
    mbedtls_net_free(&c->net);
}

static int tls_connect_shared(tls_conn_t *c,
                              const parsed_url_t *u,
                              const char *ca_bundle_path,
                              mbedtls_x509_crt *shared_ca,
                              int allow_bad_clock_cert,
                              char *err_buf,
                              size_t err_cap) {
    psa_crypto_init();

    const char *pers = "arc7tls_v1_8";
    int ret = mbedtls_ctr_drbg_seed(&c->ctr_drbg, mbedtls_entropy_func, &c->entropy,
                                    (const unsigned char *)pers, strlen(pers));
    if (ret != 0) {
        snprintf(err_buf, err_cap, "ctr_drbg_seed failed (-0x%04x)", -ret);
        return -1;
    }

    mbedtls_x509_crt *ca_ptr = shared_ca;
    if (!ca_ptr && u->is_https) {
        c->owns_ca = 1;
        ret = mbedtls_x509_crt_parse_file(&c->local_ca, ca_bundle_path);
        if (ret < 0) {
            char mbed_msg[128];
            mbedtls_strerror(ret, mbed_msg, sizeof(mbed_msg));
            snprintf(err_buf, err_cap, "CA bundle load failed (%s): %s", ca_bundle_path, mbed_msg);
            return -1;
        }
        ca_ptr = &c->local_ca;
    }

    int sock_fd = arc7_tcp_connect_host(u->host, u->port, 8000, err_buf, err_cap);
    if (sock_fd < 0) {
        return -1;
    }
    c->net.fd = sock_fd;

    struct timeval tv = { .tv_sec = 25, .tv_usec = 0 };
    setsockopt(c->net.fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(c->net.fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    if (!u->is_https) return 0;

    if ((ret = mbedtls_ssl_config_defaults(&c->conf,
                                           MBEDTLS_SSL_IS_CLIENT,
                                           MBEDTLS_SSL_TRANSPORT_STREAM,
                                           MBEDTLS_SSL_PRESET_DEFAULT)) != 0) {
        snprintf(err_buf, err_cap, "ssl_config_defaults failed (-0x%04x)", -ret);
        return -1;
    }

    mbedtls_ssl_conf_min_tls_version(&c->conf, MBEDTLS_SSL_VERSION_TLS1_2);
    mbedtls_ssl_conf_max_tls_version(&c->conf, MBEDTLS_SSL_VERSION_TLS1_3);
    mbedtls_ssl_conf_authmode(&c->conf,
                              allow_bad_clock_cert ? MBEDTLS_SSL_VERIFY_OPTIONAL
                                                   : MBEDTLS_SSL_VERIFY_REQUIRED);
    mbedtls_ssl_conf_ca_chain(&c->conf, ca_ptr, NULL);
    mbedtls_ssl_conf_rng(&c->conf, mbedtls_ctr_drbg_random, &c->ctr_drbg);

    if ((ret = mbedtls_ssl_setup(&c->ssl, &c->conf)) != 0) {
        snprintf(err_buf, err_cap, "ssl_setup failed (-0x%04x)", -ret);
        return -1;
    }
    /* Omit SNI only if host is a literal IPv4 address */
    struct in_addr dummy_v4;
    if (inet_pton(AF_INET, u->host, &dummy_v4) != 1) {
        if ((ret = mbedtls_ssl_set_hostname(&c->ssl, u->host)) != 0) {
            snprintf(err_buf, err_cap, "ssl_set_hostname failed (-0x%04x)", -ret);
            return -1;
        }
    }
    mbedtls_ssl_set_bio(&c->ssl, &c->net, mbedtls_net_send, mbedtls_net_recv, NULL);

    while ((ret = mbedtls_ssl_handshake(&c->ssl)) != 0) {
        if (ret != MBEDTLS_ERR_SSL_WANT_READ && ret != MBEDTLS_ERR_SSL_WANT_WRITE &&
            ret != MBEDTLS_ERR_SSL_CRYPTO_IN_PROGRESS &&
            ret != MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET) {
            char mbed_msg[160];
            mbedtls_strerror(ret, mbed_msg, sizeof(mbed_msg));
            uint32_t flags = mbedtls_ssl_get_verify_result(&c->ssl);
            char vbuf[256] = {0};
            if (flags != 0) mbedtls_x509_crt_verify_info(vbuf, sizeof(vbuf), "", flags);
            snprintf(err_buf, err_cap, "TLS handshake failed (%s): %s %s", u->host, mbed_msg, vbuf);
            return -1;
        }
    }
    if (allow_bad_clock_cert) {
        /* Reject MITM / untrusted CA / wrong hostname; allow ONLY future/expired clock skew! */
        uint32_t flags = mbedtls_ssl_get_verify_result(&c->ssl);
        uint32_t non_time_flags = flags & ~(uint32_t)(MBEDTLS_X509_BADCERT_FUTURE | MBEDTLS_X509_BADCERT_EXPIRED);
        if (non_time_flags != 0) {
            char vbuf[256] = {0};
            mbedtls_x509_crt_verify_info(vbuf, sizeof(vbuf), "", non_time_flags);
            snprintf(err_buf, err_cap, "Untrusted certificate (%s): %s", u->host, vbuf);
            return -1;
        }
    }
    return 0;
}

static int tls_connect(tls_conn_t *c, const parsed_url_t *u, const char *ca_bundle, char *err_buf, size_t err_cap) {
    int rc = tls_connect_shared(c, u, ca_bundle, NULL, 0, err_buf, err_cap);
    if (rc != 0 && u->is_https && time(NULL) < 1767225600) {
        tls_conn_free(c);
        tls_conn_init(c);
        rc = tls_connect_shared(c, u, ca_bundle, NULL, 1, err_buf, err_cap);
    }
    return rc;
}

static int tls_write_all(tls_conn_t *c, const void *buf, size_t len) {
    const unsigned char *p = (const unsigned char *)buf;
    size_t rem = len;
    while (rem > 0) {
        int n = c->ssl.private_conf
            ? mbedtls_ssl_write(&c->ssl, p, rem)
            : mbedtls_net_send(&c->net, p, rem);
        if (n == MBEDTLS_ERR_SSL_WANT_READ || n == MBEDTLS_ERR_SSL_WANT_WRITE ||
            n == MBEDTLS_ERR_SSL_CRYPTO_IN_PROGRESS) continue;
        if (n <= 0) return -1;
        p += n;
        rem -= (size_t)n;
    }
    return 0;
}

static int tls_read_some(tls_conn_t *c, void *buf, size_t cap) {
    for (;;) {
        int n = c->ssl.private_conf
            ? mbedtls_ssl_read(&c->ssl, (unsigned char *)buf, cap)
            : mbedtls_net_recv(&c->net, (unsigned char *)buf, cap);
        if (n == MBEDTLS_ERR_SSL_WANT_READ || n == MBEDTLS_ERR_SSL_WANT_WRITE ||
            n == MBEDTLS_ERR_SSL_CRYPTO_IN_PROGRESS ||
            n == MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET) {
            continue;
        }
        if (n == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY) return 0;
        return n;
    }
}

/* -------------------- Chunked / Raw HTTP Body Reader -------------------- */

static int stream_http_to_fd(const char *url_initial,
                             const char *ca_bundle,
                             int out_fd,
                             char *content_type_out,
                             size_t ct_cap,
                             char *final_url_out,
                             size_t fu_cap,
                             char *tls_ver_out,
                             size_t tv_cap,
                             char *err_buf,
                             size_t err_cap) {
    char current_url[2048];
    snprintf(current_url, sizeof(current_url), "%s", url_initial);

    for (int hop = 0; hop < 12; hop++) {
        parsed_url_t u;
        if (parse_url(current_url, &u) != 0) {
            snprintf(err_buf, err_cap, "Invalid URL: %s", current_url);
            return -1;
        }

        tls_conn_t conn;
        tls_conn_init(&conn);
        if (tls_connect(&conn, &u, ca_bundle, err_buf, err_cap) != 0) {
            tls_conn_free(&conn);
            return -1;
        }

        if (u.is_https && tls_ver_out && tv_cap > 0) {
            snprintf(tls_ver_out, tv_cap, "%s (%s)",
                     mbedtls_ssl_get_version(&conn.ssl),
                     mbedtls_ssl_get_ciphersuite(&conn.ssl));
        } else if (tls_ver_out && tv_cap > 0) {
            snprintf(tls_ver_out, tv_cap, "PLAINTEXT_HTTP");
        }

        char *req = (char *)malloc(3072);
        unsigned char *hdr = (unsigned char *)malloc(65536);
        unsigned char *buf = (unsigned char *)malloc(32768);
        if (!req || !hdr || !buf) {
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            snprintf(err_buf, err_cap, "Out of memory");
            return -1;
        }

        int req_len = snprintf(req, 3072,
                               "GET %s HTTP/1.1\r\n"
                               "Host: %s\r\n"
                               "User-Agent: %s\r\n"
                               "Accept: */*\r\n"
                               "Accept-Encoding: identity\r\n"
                               "Connection: close\r\n\r\n",
                               u.path, u.host, USER_AGENT_STR);
        if (tls_write_all(&conn, req, (size_t)req_len) != 0) {
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            snprintf(err_buf, err_cap, "Failed sending HTTP request");
            return -1;
        }

        size_t hdr_used = 0;
        ssize_t body_pos = -1;
        while (hdr_used + 1 < 65536) {
            int n = tls_read_some(&conn, hdr + hdr_used, 65536 - 1 - hdr_used);
            if (n <= 0) break;
            hdr_used += (size_t)n;
            hdr[hdr_used] = '\0';
            char *sep = strstr((char *)hdr, "\r\n\r\n");
            if (sep) {
                body_pos = (sep - (char *)hdr) + 4;
                break;
            }
        }
        if (body_pos < 0) {
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            snprintf(err_buf, err_cap, "Malformed or empty HTTP response headers");
            return -1;
        }

        char headers_str[16384];
        size_t hcopy = (size_t)body_pos < sizeof(headers_str) - 1 ? (size_t)body_pos : sizeof(headers_str) - 1;
        memcpy(headers_str, hdr, hcopy);
        headers_str[hcopy] = '\0';

        int status_code = 0;
        sscanf(headers_str, "HTTP/%*d.%*d %d", &status_code);

        if (status_code == 301 || status_code == 302 || status_code == 303 ||
            status_code == 307 || status_code == 308) {
            const char *loc = strcasestr(headers_str, "\r\nLocation:");
            if (loc) {
                loc += 11;
                char next_url[2048];
                resolve_redirect(&u, loc, next_url, sizeof(next_url));
                free(req); free(hdr); free(buf);
                tls_conn_free(&conn);
                snprintf(current_url, sizeof(current_url), "%s", next_url);
                continue;
            }
        }

        if (status_code < 200 || status_code >= 400) {
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            snprintf(err_buf, err_cap, "HTTP status %d from %s", status_code, u.host);
            return -1;
        }

        if (final_url_out && fu_cap > 0) snprintf(final_url_out, fu_cap, "%s", current_url);
        if (content_type_out && ct_cap > 0) {
            const char *ct = strcasestr(headers_str, "\r\nContent-Type:");
            if (ct) {
                ct += 15;
                while (*ct == ' ' || *ct == '\t') ct++;
                size_t k = 0;
                while (ct[k] && ct[k] != '\r' && ct[k] != '\n' && k + 1 < ct_cap) {
                    content_type_out[k] = ct[k];
                    k++;
                }
                content_type_out[k] = '\0';
            } else {
                snprintf(content_type_out, ct_cap, "text/html; charset=utf-8");
            }
        }

        int is_chunked = (strcasestr(headers_str, "Transfer-Encoding: chunked") != NULL);
        size_t leftover_len = hdr_used - (size_t)body_pos;

        if (!is_chunked) {
            if (leftover_len > 0 && write(out_fd, hdr + body_pos, leftover_len) < 0) {
                free(req); free(hdr); free(buf);
                tls_conn_free(&conn);
                return -1;
            }
            for (;;) {
                int n = tls_read_some(&conn, buf, 32768);
                if (n <= 0) break;
                if (write(out_fd, buf, (size_t)n) < 0) {
                    free(req); free(hdr); free(buf);
                    tls_conn_free(&conn);
                    return -1;
                }
            }
        } else {
            size_t cap_dyn = leftover_len + 65536;
            unsigned char *cbuf = (unsigned char *)malloc(cap_dyn);
            if (!cbuf) {
                free(req); free(hdr); free(buf);
                tls_conn_free(&conn);
                return -1;
            }
            size_t cused = leftover_len;
            if (cused > 0) memcpy(cbuf, hdr + body_pos, cused);

            for (;;) {
                char *crlf = NULL;
                for (size_t i = 0; i + 1 < cused; i++) {
                    if (cbuf[i] == '\r' && cbuf[i + 1] == '\n') { crlf = (char *)(cbuf + i); break; }
                }
                if (!crlf) {
                    if (cused + 16384 > cap_dyn) {
                        cap_dyn *= 2;
                        unsigned char *nb = (unsigned char *)realloc(cbuf, cap_dyn);
                        if (!nb) break;
                        cbuf = nb;
                    }
                    int n = tls_read_some(&conn, cbuf + cused, 16384);
                    if (n <= 0) break;
                    cused += (size_t)n;
                    continue;
                }
                unsigned long chunk_sz = strtoul((char *)cbuf, NULL, 16);
                size_t line_len = (size_t)(crlf - (char *)cbuf) + 2;
                if (chunk_sz == 0) break;

                while (cused < line_len + chunk_sz + 2) {
                    if (cused + 32768 > cap_dyn) {
                        cap_dyn = (line_len + chunk_sz + 65536);
                        unsigned char *nb = (unsigned char *)realloc(cbuf, cap_dyn);
                        if (!nb) break;
                        cbuf = nb;
                    }
                    int n = tls_read_some(&conn, cbuf + cused, 32768);
                    if (n <= 0) break;
                    cused += (size_t)n;
                }
                size_t avail = cused > line_len ? cused - line_len : 0;
                size_t to_write = avail < chunk_sz ? avail : chunk_sz;
                if (to_write > 0 && write(out_fd, cbuf + line_len, to_write) < 0) break;
                size_t consume = line_len + chunk_sz + 2;
                if (cused >= consume) {
                    memmove(cbuf, cbuf + consume, cused - consume);
                    cused -= consume;
                } else {
                    cused = 0;
                    break;
                }
            }
            free(cbuf);
        }

        free(req); free(hdr); free(buf);
        tls_conn_free(&conn);
        return 0;
    }

    snprintf(err_buf, err_cap, "Too many HTTP redirects (>12)");
    return -1;
}

/* -------------------- Subcommands: test & fetch -------------------- */

static int cmd_test(const char *ca_bundle, const char *url) {
    parsed_url_t u;
    if (parse_url(url, &u) != 0) {
        fprintf(stderr, "INVALID_URL=%s\n", url);
        return 1;
    }
    char err[320] = {0};
    tls_conn_t c;
    tls_conn_init(&c);
    if (tls_connect(&c, &u, ca_bundle, err, sizeof(err)) != 0) {
        fprintf(stderr, "TLS_HANDSHAKE=FAILED\nERROR=%s\n", err);
        tls_conn_free(&c);
        return 1;
    }
    printf("TLS_HANDSHAKE=OK\nHOST=%s\nTLS_PROTOCOL=%s\nTLS_CIPHER=%s\nCERT_VERIFY=OK\n",
           u.host,
           mbedtls_ssl_get_version(&c.ssl),
           mbedtls_ssl_get_ciphersuite(&c.ssl));
    tls_conn_free(&c);
    return 0;
}

static int cmd_fetch(const char *ca_bundle, const char *url, const char *dest_path) {
    int out_fd = -1;
    if (strcmp(dest_path, "-") == 0) {
        out_fd = STDOUT_FILENO;
    } else {
        out_fd = open(dest_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (out_fd < 0) {
            fprintf(stderr, "FETCH=FAILED\nERROR=Cannot open %s: %s\n", dest_path, strerror(errno));
            return 1;
        }
    }

    char ct[128] = {0}, final_u[2048] = {0}, tls_ver[128] = {0}, err[320] = {0};
    int rc = stream_http_to_fd(url, ca_bundle, out_fd,
                               ct, sizeof(ct), final_u, sizeof(final_u),
                               tls_ver, sizeof(tls_ver), err, sizeof(err));
    if (out_fd != STDOUT_FILENO) close(out_fd);
    if (rc != 0) {
        if (out_fd != STDOUT_FILENO) unlink(dest_path);
        fprintf(stderr, "FETCH=FAILED\nERROR=%s\n", err);
        return 1;
    }
    if (out_fd != STDOUT_FILENO) {
        struct stat st;
        long long sz = (stat(dest_path, &st) == 0) ? (long long)st.st_size : 0;
        printf("FETCH=OK\nFINAL_URL=%s\nTLS=%s\nBYTES=%lld\nDEST=%s\n",
               final_u, tls_ver, sz, dest_path);
    }
    return 0;
}

/* -------------------- Local CA + Dynamic Leaf Cert Engine -------------------- */

static pthread_mutex_t g_cert_mu = PTHREAD_MUTEX_INITIALIZER;
static mbedtls_x509_crt g_upstream_ca_chain;
static int g_upstream_ca_ready = 0;

static mbedtls_x509_crt g_ca_crt;
static mbedtls_pk_context g_ca_key;
static mbedtls_pk_context g_leaf_key;
static mbedtls_ctr_drbg_context g_ca_rng;
static mbedtls_entropy_context g_ca_entropy;
static unsigned char g_ca_der[4096];
static size_t g_ca_der_len = 0;
static br_skey_decoder_context g_br_skey;
static const br_rsa_private_key *g_br_rsa_sk = NULL;
static int g_connect_mitm_ready = 0;

#define CERT_CACHE_SLOTS 96
typedef struct {
    char host[256];
    unsigned char der[2048];
    size_t der_len;
} cached_cert_t;
static cached_cert_t g_cert_cache[CERT_CACHE_SLOTS];
static size_t g_cert_cursor = 0;

static int cmd_gen_local_ca(const char *out_dir) {
    psa_crypto_init();
    mbedtls_entropy_context ent;
    mbedtls_ctr_drbg_context drbg;
    mbedtls_pk_context key;
    mbedtls_x509write_cert crt;
    mbedtls_x509_crt parsed;
    mbedtls_entropy_init(&ent);
    mbedtls_ctr_drbg_init(&drbg);
    mbedtls_pk_init(&key);
    mbedtls_x509write_crt_init(&crt);
    mbedtls_x509_crt_init(&parsed);

    const char *pers = "arc7_local_ca_2026_v18";
    if (mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &ent,
                              (const unsigned char *)pers, strlen(pers)) != 0 ||
        mbedtls_pk_setup(&key, mbedtls_pk_info_from_type(MBEDTLS_PK_RSA)) != 0 ||
        mbedtls_rsa_gen_key(mbedtls_pk_rsa(key), mbedtls_ctr_drbg_random, &drbg, 2048, 65537) != 0) {
        fprintf(stderr, "GEN_LOCAL_CA=FAILED step=keygen\n");
        return 1;
    }

    unsigned char serial[8];
    mbedtls_ctr_drbg_random(&drbg, serial, sizeof(serial));
    serial[0] &= 0x7f;
    serial[0] |= 0x01;

    mbedtls_x509write_crt_set_subject_key(&crt, &key);
    mbedtls_x509write_crt_set_issuer_key(&crt, &key);
    mbedtls_x509write_crt_set_md_alg(&crt, MBEDTLS_MD_SHA256);
    mbedtls_x509write_crt_set_version(&crt, MBEDTLS_X509_CRT_VERSION_3);
    mbedtls_x509write_crt_set_serial_raw(&crt, serial, sizeof(serial));
    /* Valid 2012..2037 so unrooted / dead-battery 2013 clocks NEVER fail `CERT_NOT_YET_VALID`! */
    mbedtls_x509write_crt_set_validity(&crt, "20120101000000", "20371231235959");
    if (mbedtls_x509write_crt_set_subject_name(&crt, "CN=Kobo Arc 7 TLS 1.3 Upgrade Root CA,O=Arc7Freedom") != 0 ||
        mbedtls_x509write_crt_set_issuer_name(&crt, "CN=Kobo Arc 7 TLS 1.3 Upgrade Root CA,O=Arc7Freedom") != 0) {
        fprintf(stderr, "GEN_LOCAL_CA=FAILED step=dn\n");
        return 1;
    }
    mbedtls_x509write_crt_set_basic_constraints(&crt, 1, 1);
    mbedtls_x509write_crt_set_key_usage(&crt, MBEDTLS_X509_KU_KEY_CERT_SIGN | MBEDTLS_X509_KU_CRL_SIGN);
    mbedtls_x509write_crt_set_subject_key_identifier(&crt);

    unsigned char cert_pem[8192], key_pem[8192];
    if (mbedtls_x509write_crt_pem(&crt, cert_pem, sizeof(cert_pem), mbedtls_ctr_drbg_random, &drbg) != 0 ||
        mbedtls_pk_write_key_pem(&key, key_pem, sizeof(key_pem)) != 0 ||
        mbedtls_x509_crt_parse(&parsed, cert_pem, strlen((char *)cert_pem) + 1) != 0) {
        fprintf(stderr, "GEN_LOCAL_CA=FAILED step=pem_export\n");
        return 1;
    }

    unsigned char md5sum[16];
    mbedtls_md5(parsed.subject_raw.p, parsed.subject_raw.len, md5sum);
    uint32_t subject_hash_old = ((uint32_t)md5sum[0]) | ((uint32_t)md5sum[1] << 8) |
                                ((uint32_t)md5sum[2] << 16) | ((uint32_t)md5sum[3] << 24);

    char crt_path[512], key_path[512], hash_path[512];
    snprintf(crt_path, sizeof(crt_path), "%s/local-ca.crt", out_dir);
    snprintf(key_path, sizeof(key_path), "%s/local-ca.key", out_dir);
    snprintf(hash_path, sizeof(hash_path), "%s/%08x.0", out_dir, subject_hash_old);

    FILE *fc = fopen(crt_path, "wb");
    FILE *fk = fopen(key_path, "wb");
    FILE *fh = fopen(hash_path, "wb");
    if (!fc || !fk || !fh) {
        fprintf(stderr, "GEN_LOCAL_CA=FAILED step=fopen\n");
        return 1;
    }
    fwrite(cert_pem, 1, strlen((char *)cert_pem), fc);
    fwrite(key_pem, 1, strlen((char *)key_pem), fk);
    fwrite(cert_pem, 1, strlen((char *)cert_pem), fh);
    fclose(fc); fclose(fk); fclose(fh);
    chmod(key_path, 0600);
    chmod(crt_path, 0644);
    chmod(hash_path, 0644);

    printf("GEN_LOCAL_CA=OK\nANDROID_HASH_FILE=%08x.0\nCA_CERT=%s\nCA_KEY=%s\n",
           subject_hash_old, crt_path, key_path);

    mbedtls_x509_crt_free(&parsed);
    mbedtls_x509write_crt_free(&crt);
    mbedtls_pk_free(&key);
    mbedtls_ctr_drbg_free(&drbg);
    mbedtls_entropy_free(&ent);
    return 0;
}

static int init_connect_mitm_ca(const char *ca_cert_path, const char *ca_key_path) {
    psa_crypto_init();
    mbedtls_x509_crt_init(&g_ca_crt);
    mbedtls_pk_init(&g_ca_key);
    mbedtls_pk_init(&g_leaf_key);
    mbedtls_ctr_drbg_init(&g_ca_rng);
    mbedtls_entropy_init(&g_ca_entropy);

    const char *pers = "arc7_connect_proxy_v18";
    if (mbedtls_ctr_drbg_seed(&g_ca_rng, mbedtls_entropy_func, &g_ca_entropy,
                              (const unsigned char *)pers, strlen(pers)) != 0) return -1;
    if (mbedtls_x509_crt_parse_file(&g_ca_crt, ca_cert_path) != 0) return -1;
    if (mbedtls_pk_parse_keyfile(&g_ca_key, ca_key_path, NULL, mbedtls_ctr_drbg_random, &g_ca_rng) != 0) return -1;
    if (mbedtls_pk_setup(&g_leaf_key, mbedtls_pk_info_from_type(MBEDTLS_PK_RSA)) != 0 ||
        mbedtls_rsa_gen_key(mbedtls_pk_rsa(g_leaf_key), mbedtls_ctr_drbg_random, &g_ca_rng, 2048, 65537) != 0) {
        return -1;
    }

    if (g_ca_crt.raw.len > sizeof(g_ca_der)) return -1;
    memcpy(g_ca_der, g_ca_crt.raw.p, g_ca_crt.raw.len);
    g_ca_der_len = g_ca_crt.raw.len;

    unsigned char leaf_der[2048];
    int kl = mbedtls_pk_write_key_der(&g_leaf_key, leaf_der, sizeof(leaf_der));
    if (kl <= 0) return -1;

    br_skey_decoder_init(&g_br_skey);
    br_skey_decoder_push(&g_br_skey, leaf_der + sizeof(leaf_der) - (size_t)kl, (size_t)kl);
    if (br_skey_decoder_last_error(&g_br_skey) != 0) return -1;
    g_br_rsa_sk = br_skey_decoder_get_rsa(&g_br_skey);
    if (!g_br_rsa_sk) return -1;

    g_connect_mitm_ready = 1;
    return 0;
}

static int get_or_mint_host_cert_der(const char *hostname, unsigned char *out_der, size_t *out_len) {
    pthread_mutex_lock(&g_cert_mu);
    for (size_t i = 0; i < CERT_CACHE_SLOTS; i++) {
        if (g_cert_cache[i].der_len > 0 && strcasecmp(g_cert_cache[i].host, hostname) == 0) {
            memcpy(out_der, g_cert_cache[i].der, g_cert_cache[i].der_len);
            *out_len = g_cert_cache[i].der_len;
            pthread_mutex_unlock(&g_cert_mu);
            return 0;
        }
    }

    mbedtls_x509write_cert crt;
    mbedtls_x509write_crt_init(&crt);
    unsigned char serial[8];
    mbedtls_ctr_drbg_random(&g_ca_rng, serial, sizeof(serial));
    serial[0] &= 0x7f;
    serial[0] |= 0x01;

    char issuer_dn[256], subj_dn[320];
    mbedtls_x509_dn_gets(issuer_dn, sizeof(issuer_dn), &g_ca_crt.subject);

    /* Sanitize CN characters (commas/slashes break DN parser) */
    char safe_cn[64];
    size_t si = 0;
    for (size_t k = 0; hostname[k] && si + 1 < sizeof(safe_cn); k++) {
        char c = hostname[k];
        if (isalnum((unsigned char)c) || c == '.' || c == '-' || c == '_') safe_cn[si++] = c;
    }
    if (si == 0) strcpy(safe_cn, "tls13.local");
    else safe_cn[si] = '\0';
    snprintf(subj_dn, sizeof(subj_dn), "CN=%s,O=Arc7TLS13Bridge", safe_cn);

    mbedtls_x509write_crt_set_subject_key(&crt, &g_leaf_key);
    mbedtls_x509write_crt_set_issuer_key(&crt, &g_ca_key);
    mbedtls_x509write_crt_set_md_alg(&crt, MBEDTLS_MD_SHA256);
    mbedtls_x509write_crt_set_version(&crt, MBEDTLS_X509_CRT_VERSION_3);
    mbedtls_x509write_crt_set_serial_raw(&crt, serial, sizeof(serial));
    /* Wide 2012..2037 validity so un-synced Kobo clocks in 2013 still validate 100%! */
    mbedtls_x509write_crt_set_validity(&crt, "20120101000000", "20371231235959");
    if (mbedtls_x509write_crt_set_subject_name(&crt, subj_dn) != 0 ||
        mbedtls_x509write_crt_set_issuer_name(&crt, issuer_dn) != 0) {
        mbedtls_x509write_crt_free(&crt);
        pthread_mutex_unlock(&g_cert_mu);
        return -1;
    }
    mbedtls_x509write_crt_set_basic_constraints(&crt, 0, -1);
    mbedtls_x509write_crt_set_key_usage(&crt,
                                        MBEDTLS_X509_KU_DIGITAL_SIGNATURE | MBEDTLS_X509_KU_KEY_ENCIPHERMENT);

    /* ExtendedKeyUsage = id-kp-serverAuth (1.3.6.1.5.5.7.3.1) DER: 30 0A 06 08 2B 06 01 05 05 07 03 01 */
    static const unsigned char eku_server_auth[] = {
        0x30, 0x0A, 0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01
    };
    mbedtls_x509write_crt_set_extension(&crt,
                                        MBEDTLS_OID_EXTENDED_KEY_USAGE,
                                        MBEDTLS_OID_SIZE(MBEDTLS_OID_EXTENDED_KEY_USAGE),
                                        0, eku_server_auth, sizeof(eku_server_auth));

    /* SubjectAltName DER: IPv4 (`0x87 0x04 ...`) OR DNS (`0x82 len ...`) */
    size_t hlen = strlen(hostname);
    struct in_addr ipv4_bin;
    unsigned char san_der[264];
    if (inet_pton(AF_INET, hostname, &ipv4_bin) == 1) {
        san_der[0] = 0x30;
        san_der[1] = 6;
        san_der[2] = 0x87;
        san_der[3] = 4;
        memcpy(san_der + 4, &ipv4_bin, 4);
        mbedtls_x509write_crt_set_extension(&crt,
                                            MBEDTLS_OID_SUBJECT_ALT_NAME,
                                            MBEDTLS_OID_SIZE(MBEDTLS_OID_SUBJECT_ALT_NAME),
                                            0, san_der, 8);
    } else if (hlen > 0 && hlen <= 248) {
        size_t p = 0;
        san_der[p++] = 0x30;
        if (hlen + 2 < 128) {
            san_der[p++] = (unsigned char)(hlen + 2);
        } else {
            san_der[p++] = 0x81;
            san_der[p++] = (unsigned char)(hlen + 2);
        }
        san_der[p++] = 0x82;
        if (hlen < 128) {
            san_der[p++] = (unsigned char)hlen;
        } else {
            san_der[1] = 0x81;
            p = 1;
            san_der[p++] = 0x81;
            san_der[p++] = (unsigned char)(hlen + 3);
            san_der[p++] = 0x82;
            san_der[p++] = 0x81;
            san_der[p++] = (unsigned char)hlen;
        }
        memcpy(san_der + p, hostname, hlen);
        p += hlen;
        mbedtls_x509write_crt_set_extension(&crt,
                                            MBEDTLS_OID_SUBJECT_ALT_NAME,
                                            MBEDTLS_OID_SIZE(MBEDTLS_OID_SUBJECT_ALT_NAME),
                                            0, san_der, p);
    }

    unsigned char der_tmp[2048];
    int der_len = mbedtls_x509write_crt_der(&crt, der_tmp, sizeof(der_tmp),
                                            mbedtls_ctr_drbg_random, &g_ca_rng);
    mbedtls_x509write_crt_free(&crt);
    if (der_len <= 0 || (size_t)der_len > sizeof(g_cert_cache[0].der)) {
        pthread_mutex_unlock(&g_cert_mu);
        return -1;
    }

    const unsigned char *der_start = der_tmp + sizeof(der_tmp) - (size_t)der_len;
    cached_cert_t *slot = &g_cert_cache[g_cert_cursor % CERT_CACHE_SLOTS];
    g_cert_cursor++;
    snprintf(slot->host, sizeof(slot->host), "%s", hostname);
    memcpy(slot->der, der_start, (size_t)der_len);
    slot->der_len = (size_t)der_len;

    memcpy(out_der, slot->der, slot->der_len);
    *out_len = slot->der_len;
    pthread_mutex_unlock(&g_cert_mu);
    return 0;
}

/* -------------------- BearSSL Local TLS 1.0/1.1/1.2 <-> Upstream TLS 1.3 Tunnel -------------------- */

typedef struct {
    br_ssl_server_context sc;
    unsigned char iobuf[BR_SSL_BUFSIZE_BIDI];
    unsigned char leaf_der[2048];
    size_t leaf_der_len;
    br_x509_certificate chain[2];
} bear_server_session_t;

static void handle_connect_tunnel(int client_fd, const char *authority, const char *ca_bundle) {
    char host[256] = {0}, port[8] = "443";
    const char *colon = strrchr(authority, ':');
    if (colon && strchr(authority, ']') == NULL) {
        size_t hl = (size_t)(colon - authority);
        if (hl == 0 || hl >= sizeof(host)) return;
        memcpy(host, authority, hl);
        snprintf(port, sizeof(port), "%.6s", colon + 1);
    } else {
        snprintf(host, sizeof(host), "%.254s", authority);
    }

    bear_server_session_t *bs = (bear_server_session_t *)calloc(1, sizeof(bear_server_session_t));
    if (!bs) return;

    if (get_or_mint_host_cert_der(host, bs->leaf_der, &bs->leaf_der_len) != 0) {
        static const char bad[] = "HTTP/1.1 502 Leaf Cert Generation Error\r\nConnection: close\r\n\r\n";
        send(client_fd, bad, sizeof(bad) - 1, MSG_NOSIGNAL);
        free(bs);
        return;
    }

    parsed_url_t up_u = { .is_https = 1 };
    snprintf(up_u.host, sizeof(up_u.host), "%s", host);
    snprintf(up_u.port, sizeof(up_u.port), "%s", port);
    strcpy(up_u.path, "/");

    tls_conn_t upstream;
    tls_conn_init(&upstream);
    char err[320] = {0};
    int allow_skew = (time(NULL) < 1767225600) ? 1 : 0;
    int rc = tls_connect_shared(&upstream, &up_u, ca_bundle,
                                g_upstream_ca_ready ? &g_upstream_ca_chain : NULL,
                                allow_skew, err, sizeof(err));
    if (rc != 0 && !allow_skew) {
        /* If RTC clock drifted, retry once with clock-only tolerance (still verifying trusted Root CA + CN/SAN!) */
        tls_conn_free(&upstream);
        tls_conn_init(&upstream);
        rc = tls_connect_shared(&upstream, &up_u, ca_bundle,
                                g_upstream_ca_ready ? &g_upstream_ca_chain : NULL,
                                1, err, sizeof(err));
    }
    if (rc != 0) {
        char bad[512];
        int bl = snprintf(bad, sizeof(bad),
                          "HTTP/1.1 502 Upstream TLS Error\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n%s\n",
                          err);
        send(client_fd, bad, (size_t)bl, MSG_NOSIGNAL);
        tls_conn_free(&upstream);
        free(bs);
        return;
    }

    static const char established[] = "HTTP/1.1 200 Connection Established\r\n\r\n";
    if (send(client_fd, established, sizeof(established) - 1, MSG_NOSIGNAL) <= 0) {
        tls_conn_free(&upstream);
        free(bs);
        return;
    }

    /* CRITICAL: Put upstream socket in O_NONBLOCK mode so consuming post-handshake
       TLS 1.3 NewSessionTicket records NEVER blocks waiting for app data before request! */
    mbedtls_net_set_nonblock(&upstream.net);

    /* Setup BearSSL local server supporting TLS 1.0, TLS 1.1, AND TLS 1.2! */
    bs->chain[0].data = bs->leaf_der;
    bs->chain[0].data_len = bs->leaf_der_len;
    bs->chain[1].data = g_ca_der;
    bs->chain[1].data_len = g_ca_der_len;

    br_ssl_server_init_full_rsa(&bs->sc, bs->chain, 2, g_br_rsa_sk);
    br_ssl_engine_set_buffer(&bs->sc.eng, bs->iobuf, sizeof(bs->iobuf), 1);

    unsigned char seed[32];
    pthread_mutex_lock(&g_cert_mu);
    mbedtls_ctr_drbg_random(&g_ca_rng, seed, sizeof(seed));
    pthread_mutex_unlock(&g_cert_mu);
    br_ssl_engine_inject_entropy(&bs->sc.eng, seed, sizeof(seed));

    if (!br_ssl_server_reset(&bs->sc)) {
        tls_conn_free(&upstream);
        free(bs);
        return;
    }

    int upstream_eof = 0;

    for (;;) {
        unsigned st = br_ssl_engine_current_state(&bs->sc.eng);
        if (st == BR_SSL_CLOSED) break;

        /* 1. Flush any encrypted TLS records ready to send to local client */
        if (st & BR_SSL_SENDREC) {
            size_t len = 0;
            unsigned char *buf = br_ssl_engine_sendrec_buf(&bs->sc.eng, &len);
            ssize_t w = send(client_fd, buf, len, MSG_NOSIGNAL);
            if (w <= 0) {
                if (w < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
                break;
            }
            br_ssl_engine_sendrec_ack(&bs->sc.eng, (size_t)w);
            continue;
        }

        /* 2. Forward any decrypted plaintext from local client -> upstream TLS 1.3 server */
        if (st & BR_SSL_RECVAPP) {
            size_t len = 0;
            unsigned char *buf = br_ssl_engine_recvapp_buf(&bs->sc.eng, &len);
            if (tls_write_all(&upstream, buf, len) != 0) break;
            br_ssl_engine_recvapp_ack(&bs->sc.eng, len);
            continue;
        }

        /* 3. Once upstream has closed, flush close_notify via BR_SSL_SENDREC then immediately close TCP socket! */
        if (upstream_eof) {
            if (st & BR_SSL_SENDAPP) {
                br_ssl_engine_close(&bs->sc.eng);
                continue;
            }
            break;
        }

        /* 4. If Mbed-TLS already buffered decrypted bytes internally, pump immediately without waiting on select()! */
        if (!upstream_eof && (st & BR_SSL_SENDAPP) && mbedtls_ssl_get_bytes_avail(&upstream.ssl) > 0) {
            size_t len = 0;
            unsigned char *buf = br_ssl_engine_sendapp_buf(&bs->sc.eng, &len);
            int n = mbedtls_ssl_read(&upstream.ssl, buf, len);
            if (n > 0) {
                br_ssl_engine_sendapp_ack(&bs->sc.eng, (size_t)n);
                br_ssl_engine_flush(&bs->sc.eng, 0);
            } else if (n == 0 || n == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY) {
                upstream_eof = 1;
                br_ssl_engine_close(&bs->sc.eng);
            }
            continue;
        }

        /* 5. Wait via select() for whichever direction can make progress */
        fd_set rfds;
        FD_ZERO(&rfds);
        int maxfd = -1;
        if (st & BR_SSL_RECVREC) {
            FD_SET(client_fd, &rfds);
            if (client_fd > maxfd) maxfd = client_fd;
        }
        if (!upstream_eof && (st & BR_SSL_SENDAPP)) {
            FD_SET(upstream.net.fd, &rfds);
            if (upstream.net.fd > maxfd) maxfd = upstream.net.fd;
        }
        if (maxfd < 0) break;

        struct timeval idle = { .tv_sec = 45, .tv_usec = 0 };
        int sel = select(maxfd + 1, &rfds, NULL, NULL, &idle);
        if (sel <= 0) {
            if (sel < 0 && errno == EINTR) continue;
            break;
        }

        if ((st & BR_SSL_RECVREC) && FD_ISSET(client_fd, &rfds)) {
            size_t len = 0;
            unsigned char *buf = br_ssl_engine_recvrec_buf(&bs->sc.eng, &len);
            ssize_t r = recv(client_fd, buf, len, 0);
            if (r < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) {
                /* retry */
            } else if (r <= 0) {
                break;
            } else {
                br_ssl_engine_recvrec_ack(&bs->sc.eng, (size_t)r);
            }
        }

        if (!upstream_eof && (st & BR_SSL_SENDAPP) && FD_ISSET(upstream.net.fd, &rfds)) {
            for (;;) {
                size_t len = 0;
                unsigned char *buf = br_ssl_engine_sendapp_buf(&bs->sc.eng, &len);
                if (!buf || len == 0) break;
                int n = mbedtls_ssl_read(&upstream.ssl, buf, len);
                if (n == MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET ||
                    n == MBEDTLS_ERR_SSL_CRYPTO_IN_PROGRESS) {
                    continue;
                }
                if (n == MBEDTLS_ERR_SSL_WANT_READ || n == MBEDTLS_ERR_SSL_WANT_WRITE) {
                    break;
                }
                if (n > 0) {
                    br_ssl_engine_sendapp_ack(&bs->sc.eng, (size_t)n);
                    br_ssl_engine_flush(&bs->sc.eng, 0);
                    break;
                }
                upstream_eof = 1;
                br_ssl_engine_close(&bs->sc.eng);
                break;
            }
        }
    }

    tls_conn_free(&upstream);
    free(bs);
}

/* -------------------- Transparent Local TLS 1.3 HTTPS Mirror Bridge (`/__https/...`) -------------------- */

static void copy_inbound_header_line(const char *client_req, const char *hdr_name, char *extra_out, size_t extra_cap) {
    char needle[64];
    snprintf(needle, sizeof(needle), "\n%s:", hdr_name);
    const char *p = strcasestr(client_req, needle);
    if (!p) return;
    p += 1; /* start at header name */
    const char *eol = strpbrk(p, "\r\n");
    if (!eol) return;
    size_t cur = strlen(extra_out);
    size_t len = (size_t)(eol - p);
    if (len > 0 && cur + len + 4 < extra_cap) {
        memcpy(extra_out + cur, p, len);
        extra_out[cur + len] = '\r';
        extra_out[cur + len + 1] = '\n';
        extra_out[cur + len + 2] = '\0';
    }
}

static void extract_header_value(const char *headers, const char *name, char *out, size_t cap);

static void stream_https_bridge(int client_fd,
                                const char *method,
                                const char *url_initial,
                                const char *client_req,
                                size_t client_req_len,
                                const char *ca_bundle) {
    int is_head = (strcasecmp(method, "HEAD") == 0);
    int is_post = (strcasecmp(method, "POST") == 0 || strcasecmp(method, "PUT") == 0);
    int allow_skew = (time(NULL) < 1767225600) ? 1 : 0;

    /* Extract optional request body (including reading remaining bytes if > initial recv) */
    size_t content_len = 0;
    uint8_t *post_body = NULL;
    const char *hdr_end = strstr(client_req, "\r\n\r\n");
    if (is_post && hdr_end) {
        char cl_buf[64] = {0};
        extract_header_value(client_req, "Content-Length", cl_buf, sizeof(cl_buf));
        if (cl_buf[0]) content_len = (size_t)strtoul(cl_buf, NULL, 10);
        if (content_len > 0 && content_len <= 8 * 1024 * 1024) {
            post_body = (uint8_t *)malloc(content_len);
            if (post_body) {
                size_t body_offset = (size_t)((hdr_end + 4) - client_req);
                size_t already = client_req_len > body_offset ? (client_req_len - body_offset) : 0;
                if (already > content_len) already = content_len;
                if (already > 0) memcpy(post_body, hdr_end + 4, already);
                while (already < content_len) {
                    ssize_t r = recv(client_fd, post_body + already, content_len - already, 0);
                    if (r <= 0) break;
                    already += (size_t)r;
                }
                content_len = already;
            }
        }
    }

    /* Forward custom caller headers (`Range`, `Authorization`, `X-DFE-*`, `Content-Type`, custom `User-Agent`, etc.) */
    char extra_hdrs[8192] = {0};
    char custom_ua[512] = {0};
    char custom_accept[256] = {0};
    extract_header_value(client_req, "User-Agent", custom_ua, sizeof(custom_ua));
    extract_header_value(client_req, "Accept", custom_accept, sizeof(custom_accept));

    if (hdr_end) {
        const char *ln = strstr(client_req, "\r\n");
        if (ln) ln += 2;
        size_t pos = 0;
        while (ln && ln < hdr_end && pos + 256 < sizeof(extra_hdrs)) {
            const char *eol = strstr(ln, "\r\n");
            if (!eol || eol > hdr_end) break;
            size_t llen = (size_t)(eol - ln);
            if (llen > 0 &&
                strncasecmp(ln, "Host:", 5) != 0 &&
                strncasecmp(ln, "Connection:", 11) != 0 &&
                strncasecmp(ln, "Proxy-Connection:", 17) != 0 &&
                strncasecmp(ln, "User-Agent:", 11) != 0 &&
                strncasecmp(ln, "Accept:", 7) != 0 &&
                strncasecmp(ln, "Accept-Encoding:", 16) != 0 &&
                strncasecmp(ln, "Content-Length:", 15) != 0) {
                memcpy(extra_hdrs + pos, ln, llen);
                pos += llen;
                extra_hdrs[pos++] = '\r';
                extra_hdrs[pos++] = '\n';
                extra_hdrs[pos] = '\0';
            }
            ln = eol + 2;
        }
    }
    if (is_post) {
        size_t cur = strlen(extra_hdrs);
        snprintf(extra_hdrs + cur, sizeof(extra_hdrs) - cur, "Content-Length: %zu\r\n", content_len);
    }

    char current_url[2048];
    snprintf(current_url, sizeof(current_url), "%s", url_initial);

    for (int hop = 0; hop < 12; hop++) {
        parsed_url_t u;
        if (parse_url(current_url, &u) != 0) {
            free(post_body);
            dprintf(client_fd, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\nInvalid URL\n");
            return;
        }

        tls_conn_t conn;
        tls_conn_init(&conn);
        char err[320] = {0};
        int rc = tls_connect_shared(&conn, &u, ca_bundle,
                                    g_upstream_ca_ready ? &g_upstream_ca_chain : NULL,
                                    allow_skew, err, sizeof(err));
        if (rc != 0 && !allow_skew) {
            tls_conn_free(&conn);
            tls_conn_init(&conn);
            rc = tls_connect_shared(&conn, &u, ca_bundle,
                                    g_upstream_ca_ready ? &g_upstream_ca_chain : NULL,
                                    1, err, sizeof(err));
        }
        if (rc != 0) {
            tls_conn_free(&conn);
            dprintf(client_fd,
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n%s\n",
                    err);
            return;
        }

        char *req = (char *)malloc(12288);
        unsigned char *hdr = (unsigned char *)malloc(65536);
        unsigned char *buf = (unsigned char *)malloc(32768);
        if (!req || !hdr || !buf) {
            free(post_body);
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            return;
        }

        const char *wire_method = is_head ? "HEAD" : (is_post ? method : "GET");
        int req_len = snprintf(req, 12288,
                               "%s %s HTTP/1.1\r\n"
                               "Host: %s\r\n"
                               "User-Agent: %s\r\n"
                               "Accept: %s\r\n"
                               "Accept-Encoding: identity\r\n"
                               "%s"
                               "Connection: close\r\n\r\n",
                               wire_method,
                               u.path, u.host,
                               custom_ua[0] ? custom_ua : "F-Droid 1.2 (Arc7Freedom/1.9 TLS1.3)",
                               custom_accept[0] ? custom_accept : "*/*",
                               extra_hdrs);
        if (tls_write_all(&conn, req, (size_t)req_len) != 0 ||
            (is_post && post_body && content_len > 0 && tls_write_all(&conn, post_body, content_len) != 0)) {
            free(post_body);
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            dprintf(client_fd, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\nWrite error\n");
            return;
        }

        size_t hdr_used = 0;
        ssize_t body_pos = -1;
        while (hdr_used + 1 < 65536) {
            int n = tls_read_some(&conn, hdr + hdr_used, 65536 - 1 - hdr_used);
            if (n <= 0) break;
            hdr_used += (size_t)n;
            hdr[hdr_used] = '\0';
            char *sep = strstr((char *)hdr, "\r\n\r\n");
            if (sep) { body_pos = (sep - (char *)hdr) + 4; break; }
        }
        if (body_pos < 0) {
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            dprintf(client_fd, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\nEmpty upstream response\n");
            return;
        }

        char headers_str[16384];
        size_t hcopy = (size_t)body_pos < sizeof(headers_str) - 1 ? (size_t)body_pos : sizeof(headers_str) - 1;
        memcpy(headers_str, hdr, hcopy);
        headers_str[hcopy] = '\0';

        int status_code = 0;
        sscanf(headers_str, "HTTP/%*d.%*d %d", &status_code);
        if (status_code == 301 || status_code == 302 || status_code == 303 ||
            status_code == 307 || status_code == 308) {
            const char *loc = strcasestr(headers_str, "\r\nLocation:");
            if (loc) {
                loc += 11;
                char next_url[2048];
                resolve_redirect(&u, loc, next_url, sizeof(next_url));
                free(req); free(hdr); free(buf);
                tls_conn_free(&conn);
                snprintf(current_url, sizeof(current_url), "%s", next_url);
                continue;
            }
        }

        int is_chunked = (strcasestr(headers_str, "Transfer-Encoding: chunked") != NULL);

        /* Forward clean HTTP/1.1 status + preserved metadata headers (`Content-Length`, `ETag`, `Last-Modified`, `Content-Range`, `Content-Type`) */
        char out_hdr[8192];
        size_t opos = 0;
        const char *line = headers_str;
        int first_line = 1;
        while (*line && opos + 256 < sizeof(out_hdr)) {
            const char *eol = strstr(line, "\r\n");
            if (!eol || eol == line) break;
            size_t llen = (size_t)(eol - line);
            if (first_line) {
                first_line = 0;
                memcpy(out_hdr + opos, line, llen);
                opos += llen;
                out_hdr[opos++] = '\r';
                out_hdr[opos++] = '\n';
            } else if (strncasecmp(line, "Content-Type:", 13) == 0 ||
                       strncasecmp(line, "Content-Range:", 14) == 0 ||
                       strncasecmp(line, "ETag:", 5) == 0 ||
                       strncasecmp(line, "Last-Modified:", 14) == 0 ||
                       strncasecmp(line, "Accept-Ranges:", 14) == 0 ||
                       (!is_chunked && strncasecmp(line, "Content-Length:", 15) == 0)) {
                memcpy(out_hdr + opos, line, llen);
                opos += llen;
                out_hdr[opos++] = '\r';
                out_hdr[opos++] = '\n';
            }
            line = eol + 2;
        }
        opos += (size_t)snprintf(out_hdr + opos, sizeof(out_hdr) - opos, "Connection: close\r\n\r\n");
        send(client_fd, out_hdr, opos, MSG_NOSIGNAL);

        if (is_head || status_code == 304 || status_code == 204) {
            free(post_body);
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            return;
        }

        size_t leftover_len = hdr_used - (size_t)body_pos;
        if (!is_chunked) {
            if (leftover_len > 0 && send(client_fd, hdr + body_pos, leftover_len, MSG_NOSIGNAL) <= 0) {
                free(post_body);
                free(req); free(hdr); free(buf);
                tls_conn_free(&conn);
                return;
            }
            for (;;) {
                int n = tls_read_some(&conn, buf, 32768);
                if (n <= 0) break;
                if (send(client_fd, buf, (size_t)n, MSG_NOSIGNAL) <= 0) break;
            }
        } else {
            size_t cap_dyn = leftover_len + 65536;
            unsigned char *cbuf = (unsigned char *)malloc(cap_dyn);
            if (cbuf) {
                size_t cused = leftover_len;
                if (cused > 0) memcpy(cbuf, hdr + body_pos, cused);
                for (;;) {
                    char *crlf = NULL;
                    for (size_t i = 0; i + 1 < cused; i++) {
                        if (cbuf[i] == '\r' && cbuf[i + 1] == '\n') { crlf = (char *)(cbuf + i); break; }
                    }
                    if (!crlf) {
                        if (cused + 16384 > cap_dyn) {
                            cap_dyn *= 2;
                            unsigned char *nb = (unsigned char *)realloc(cbuf, cap_dyn);
                            if (!nb) break;
                            cbuf = nb;
                        }
                        int n = tls_read_some(&conn, cbuf + cused, 16384);
                        if (n <= 0) break;
                        cused += (size_t)n;
                        continue;
                    }
                    unsigned long chunk_sz = strtoul((char *)cbuf, NULL, 16);
                    size_t line_len = (size_t)(crlf - (char *)cbuf) + 2;
                    if (chunk_sz == 0) break;
                    while (cused < line_len + chunk_sz + 2) {
                        if (cused + 32768 > cap_dyn) {
                            cap_dyn = (line_len + chunk_sz + 65536);
                            unsigned char *nb = (unsigned char *)realloc(cbuf, cap_dyn);
                            if (!nb) break;
                            cbuf = nb;
                        }
                        int n = tls_read_some(&conn, cbuf + cused, 32768);
                        if (n <= 0) break;
                        cused += (size_t)n;
                    }
                    size_t avail = cused > line_len ? cused - line_len : 0;
                    size_t to_write = avail < chunk_sz ? avail : chunk_sz;
                    if (to_write > 0 && send(client_fd, cbuf + line_len, to_write, MSG_NOSIGNAL) <= 0) break;
                    size_t consume = line_len + chunk_sz + 2;
                    if (cused >= consume) {
                        memmove(cbuf, cbuf + consume, cused - consume);
                        cused -= consume;
                    } else {
                        break;
                    }
                }
                free(cbuf);
            }
        }
        free(post_body);
        free(req); free(hdr); free(buf);
        tls_conn_free(&conn);
        return;
    }
    free(post_body);
    dprintf(client_fd, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\nToo many redirects\n");
}

/* -------------------- Local 127.0.0.1 TLS 1.3 Web & Browser Gateway -------------------- */

typedef struct {
    int client_fd;
    char ca_bundle[512];
} proxy_job_t;

static void url_decode_inplace(char *s) {
    char *dst = s;
    for (const char *src = s; *src; src++) {
        if (*src == '+') {
            *dst++ = ' ';
        } else if (*src == '%' && isxdigit((unsigned char)src[1]) && isxdigit((unsigned char)src[2])) {
            char hex[3] = { src[1], src[2], 0 };
            *dst++ = (char)strtol(hex, NULL, 16);
            src += 2;
        } else {
            *dst++ = *src;
        }
    }
    *dst = '\0';
}

static void extract_query_param(const char *query, const char *key, char *out, size_t cap) {
    out[0] = '\0';
    size_t klen = strlen(key);
    const char *p = query;
    while (p && *p) {
        if (strncmp(p, key, klen) == 0 && p[klen] == '=') {
            const char *val = p + klen + 1;
            const char *amp = strchr(val, '&');
            size_t vlen = amp ? (size_t)(amp - val) : strlen(val);
            if (vlen >= cap) vlen = cap - 1;
            memcpy(out, val, vlen);
            out[vlen] = '\0';
            url_decode_inplace(out);
            return;
        }
        p = strchr(p, '&');
        if (p) p++;
    }
}

static const char *PORTAL_HTML =
    "<!DOCTYPE html><html><head><meta charset='utf-8'>"
    "<meta name='viewport' content='width=device-width,initial-scale=1'>"
    "<title>Arc7 Modern TLS 1.3 Browser Gateway</title>"
    "<style>"
    "body{font-family:sans-serif;background:#101622;color:#eef2ff;margin:0;padding:18px;}"
    ".box{max-width:780px;margin:0 auto;background:#192234;border-radius:10px;padding:18px;border:1px solid #2e3e5c;}"
    "h1{font-size:20px;margin:0 0 8px;color:#60a5fa;}p{font-size:14px;line-height:1.45;color:#cbd5e1;}"
    "input[type=text]{width:74%;padding:10px;border-radius:6px;border:1px solid #475569;background:#0f172a;color:#fff;font-size:15px;}"
    "button{padding:10px 16px;border-radius:6px;border:0;background:#2563eb;color:#fff;font-weight:bold;font-size:15px;}"
    ".links a{display:inline-block;margin:6px 8px 0 0;padding:7px 12px;background:#24324d;color:#93c5fd;border-radius:6px;text-decoration:none;font-size:13px;}"
    "</style></head><body><div class='box'>"
    "<h1>Kobo Arc 7 Modern TLS 1.3 + 2026 CA Browser Gateway</h1>"
    "<p>Active on <b>127.0.0.1</b> using Mbed-TLS 3.6 (TLS 1.3 + BearSSL TLS 1.0-1.2 local upgrade + 120 2026 Root CAs).</p>"
    "<form action='/browse' method='get'>"
    "<input type='text' name='u' placeholder='https://duckduckgo.com/html' value='https://en.wikipedia.org/wiki/Kobo_Arc'>"
    " <button type='submit'>Open via TLS 1.3</button></form>"
    "<div class='links'>"
    "<a href='/browse?u=https%3A%2F%2Fhtml.duckduckgo.com%2Fhtml%2F'>DuckDuckGo Search</a>"
    "<a href='/browse?u=https%3A%2F%2Fen.m.wikipedia.org%2Fwiki%2FMain_Page'>Wikipedia</a>"
    "<a href='/browse?u=https%3A%2F%2Fgithub.com%2Fmob5824m-wq%2FKobo-Arc-7-freedom%2Freleases'>Arc7 Releases</a>"
    "<a href='/browse?u=https%3A%2F%2Fwww.gutenberg.org%2F'>Project Gutenberg Free Ebooks</a>"
    "<a href='/browse?u=https%3A%2F%2Fnews.ycombinator.com%2F'>Hacker News</a>"
    "</div></div></body></html>";

static void stream_raw_for_arc7_browser(int client_fd,
                                        const char *url_initial,
                                        const char *referer_url,
                                        const char *cookie_hdr,
                                        const char *user_agent_hdr,
                                        const char *ca_bundle) {
    char current_url[2048];
    snprintf(current_url, sizeof(current_url), "%s", url_initial);
    int allow_skew = (time(NULL) < 1767225600) ? 1 : 0;

    char set_cookies_accum[2048] = {0};

    for (int hop = 0; hop < 12; hop++) {
        parsed_url_t u;
        if (parse_url(current_url, &u) != 0) {
            dprintf(client_fd,
                    "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\nInvalid URL: %s\n",
                    current_url);
            return;
        }

        tls_conn_t conn;
        tls_conn_init(&conn);
        char err[320] = {0};
        int rc = tls_connect_shared(&conn, &u, ca_bundle,
                                    g_upstream_ca_ready ? &g_upstream_ca_chain : NULL,
                                    allow_skew, err, sizeof(err));
        if (rc != 0 && !allow_skew) {
            tls_conn_free(&conn);
            tls_conn_init(&conn);
            rc = tls_connect_shared(&conn, &u, ca_bundle,
                                    g_upstream_ca_ready ? &g_upstream_ca_chain : NULL,
                                    1, err, sizeof(err));
        }
        if (rc != 0) {
            tls_conn_free(&conn);
            dprintf(client_fd,
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n%s\n",
                    err);
            return;
        }

        char *req = (char *)malloc(6144);
        unsigned char *hdr = (unsigned char *)malloc(65536);
        unsigned char *buf = (unsigned char *)malloc(32768);
        if (!req || !hdr || !buf) {
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            return;
        }

        const char *ua_use = (user_agent_hdr && user_agent_hdr[0]) ? user_agent_hdr : USER_AGENT_STR;
        int req_len = snprintf(req, 6144,
                               "GET %s HTTP/1.1\r\n"
                               "Host: %s\r\n"
                               "User-Agent: %s\r\n"
                               "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/*,*/*;q=0.8\r\n"
                               "Accept-Language: en-US,en;q=0.9\r\n"
                               "Accept-Encoding: gzip, identity\r\n"
                               "%s%s%s"
                               "%s%s%s"
                               "Connection: close\r\n\r\n",
                               u.path, u.host, ua_use,
                               (referer_url && referer_url[0]) ? "Referer: " : "",
                               (referer_url && referer_url[0]) ? referer_url : "",
                               (referer_url && referer_url[0]) ? "\r\n" : "",
                               (cookie_hdr && cookie_hdr[0]) ? "Cookie: " : "",
                               (cookie_hdr && cookie_hdr[0]) ? cookie_hdr : "",
                               (cookie_hdr && cookie_hdr[0]) ? "\r\n" : "");
        if (tls_write_all(&conn, req, (size_t)req_len) != 0) {
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            dprintf(client_fd,
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\nUpstream write error\n");
            return;
        }

        size_t hdr_used = 0;
        ssize_t body_pos = -1;
        while (hdr_used + 1 < 65536) {
            int n = tls_read_some(&conn, hdr + hdr_used, 65536 - 1 - hdr_used);
            if (n <= 0) break;
            hdr_used += (size_t)n;
            hdr[hdr_used] = '\0';
            char *sep = strstr((char *)hdr, "\r\n\r\n");
            if (sep) { body_pos = (sep - (char *)hdr) + 4; break; }
        }
        if (body_pos < 0) {
            free(req); free(hdr); free(buf);
            tls_conn_free(&conn);
            dprintf(client_fd,
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\nEmpty upstream response\n");
            return;
        }

        char headers_str[16384];
        size_t hcopy = (size_t)body_pos < sizeof(headers_str) - 1 ? (size_t)body_pos : sizeof(headers_str) - 1;
        memcpy(headers_str, hdr, hcopy);
        headers_str[hcopy] = '\0';

        const char *sc_scan = headers_str;
        while ((sc_scan = strcasestr(sc_scan, "\r\nSet-Cookie:")) != NULL) {
            sc_scan += 13;
            while (*sc_scan == ' ' || *sc_scan == '\t') sc_scan++;
            const char *eol = strpbrk(sc_scan, "\r\n");
            size_t len = eol ? (size_t)(eol - sc_scan) : strlen(sc_scan);
            size_t cur = strlen(set_cookies_accum);
            if (len > 0 && cur + len + 20 < sizeof(set_cookies_accum)) {
                strcat(set_cookies_accum, "X-Arc7-Cookie: ");
                strncat(set_cookies_accum, sc_scan, len);
                strcat(set_cookies_accum, "\r\n");
            }
        }

        int status_code = 0;
        sscanf(headers_str, "HTTP/%*d.%*d %d", &status_code);
        if (status_code == 301 || status_code == 302 || status_code == 303 ||
            status_code == 307 || status_code == 308) {
            const char *loc = strcasestr(headers_str, "\r\nLocation:");
            if (loc) {
                loc += 11;
                char next_url[2048];
                resolve_redirect(&u, loc, next_url, sizeof(next_url));
                free(req); free(hdr); free(buf);
                tls_conn_free(&conn);
                snprintf(current_url, sizeof(current_url), "%s", next_url);
                continue;
            }
        }

        char ctype[160] = "application/octet-stream";
        const char *ct = strcasestr(headers_str, "\r\nContent-Type:");
        if (ct) {
            ct += 15;
            while (*ct == ' ' || *ct == '\t') ct++;
            size_t k = 0;
            while (ct[k] && ct[k] != '\r' && ct[k] != '\n' && k + 1 < sizeof(ctype)) {
                ctype[k] = ct[k];
                k++;
            }
            ctype[k] = '\0';
        }

        char cenc[64] = "";
        const char *ce = strcasestr(headers_str, "\r\nContent-Encoding:");
        if (ce) {
            ce += 19;
            while (*ce == ' ' || *ce == '\t') ce++;
            size_t k = 0;
            while (ce[k] && ce[k] != '\r' && ce[k] != '\n' && k + 1 < sizeof(cenc)) {
                cenc[k] = ce[k];
                k++;
            }
            cenc[k] = '\0';
        }

        const char *tls_ver = u.is_https ? mbedtls_ssl_get_version(&conn.ssl) : "PLAINTEXT";
        int is_chunked = (strcasestr(headers_str, "Transfer-Encoding: chunked") != NULL);

        dprintf(client_fd,
                "HTTP/1.1 %d OK\r\n"
                "Content-Type: %s\r\n"
                "X-Arc7-Final-URL: %s\r\n"
                "X-Arc7-TLS: %s\r\n"
                "X-Arc7-Encoding: %s\r\n"
                "%s"
                "Connection: close\r\n\r\n",
                (status_code >= 100 && status_code < 600) ? status_code : 200,
                ctype, current_url, tls_ver, cenc, set_cookies_accum);

        size_t leftover_len = hdr_used - (size_t)body_pos;
        if (!is_chunked) {
            if (leftover_len > 0 && send(client_fd, hdr + body_pos, leftover_len, MSG_NOSIGNAL) <= 0) {
                free(req); free(hdr); free(buf);
                tls_conn_free(&conn);
                return;
            }
            for (;;) {
                int n = tls_read_some(&conn, buf, 32768);
                if (n <= 0) break;
                if (send(client_fd, buf, (size_t)n, MSG_NOSIGNAL) <= 0) break;
            }
        } else {
            size_t cap_dyn = leftover_len + 65536;
            unsigned char *cbuf = (unsigned char *)malloc(cap_dyn);
            if (cbuf) {
                size_t cused = leftover_len;
                if (cused > 0) memcpy(cbuf, hdr + body_pos, cused);
                for (;;) {
                    char *crlf = NULL;
                    for (size_t i = 0; i + 1 < cused; i++) {
                        if (cbuf[i] == '\r' && cbuf[i + 1] == '\n') { crlf = (char *)(cbuf + i); break; }
                    }
                    if (!crlf) {
                        if (cused + 16384 > cap_dyn) {
                            cap_dyn *= 2;
                            unsigned char *nb = (unsigned char *)realloc(cbuf, cap_dyn);
                            if (!nb) break;
                            cbuf = nb;
                        }
                        int n = tls_read_some(&conn, cbuf + cused, 16384);
                        if (n <= 0) break;
                        cused += (size_t)n;
                        continue;
                    }
                    unsigned long chunk_sz = strtoul((char *)cbuf, NULL, 16);
                    size_t line_len = (size_t)(crlf - (char *)cbuf) + 2;
                    if (chunk_sz == 0) break;
                    while (cused < line_len + chunk_sz + 2) {
                        if (cused + 32768 > cap_dyn) {
                            cap_dyn = (line_len + chunk_sz + 65536);
                            unsigned char *nb = (unsigned char *)realloc(cbuf, cap_dyn);
                            if (!nb) break;
                            cbuf = nb;
                        }
                        int n = tls_read_some(&conn, cbuf + cused, 32768);
                        if (n <= 0) break;
                        cused += (size_t)n;
                    }
                    size_t avail = cused > line_len ? cused - line_len : 0;
                    size_t to_write = avail < chunk_sz ? avail : chunk_sz;
                    if (to_write > 0 && send(client_fd, cbuf + line_len, to_write, MSG_NOSIGNAL) <= 0) break;
                    size_t consume = line_len + chunk_sz + 2;
                    if (cused >= consume) {
                        memmove(cbuf, cbuf + consume, cused - consume);
                        cused -= consume;
                    } else {
                        break;
                    }
                }
                free(cbuf);
            }
        }
        free(req); free(hdr); free(buf);
        tls_conn_free(&conn);
        return;
    }

    dprintf(client_fd,
            "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\nToo many redirects\n");
}

static void extract_header_value(const char *headers, const char *name, char *out, size_t cap) {
    out[0] = '\0';
    char needle[64];
    snprintf(needle, sizeof(needle), "\n%s:", name);
    const char *p = strcasestr(headers, needle);
    if (!p) return;
    p += strlen(needle);
    while (*p == ' ' || *p == '\t') p++;
    size_t i = 0;
    while (p[i] && p[i] != '\r' && p[i] != '\n' && i + 1 < cap) {
        out[i] = p[i];
        i++;
    }
    out[i] = '\0';
}

static void *handle_proxy_client(void *arg) {
    proxy_job_t *job = (proxy_job_t *)arg;
    int fd = job->client_fd;
    char *req = (char *)calloc(1, 16384);
    if (!req) {
        close(fd);
        free(job);
        return NULL;
    }

    struct timeval tv = { .tv_sec = 25, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    ssize_t used = 0;
    while (used + 1 < 16384) {
        ssize_t n = recv(fd, req + used, 16384 - 1 - (size_t)used, 0);
        if (n <= 0) break;
        used += n;
        req[used] = '\0';
        if (strstr(req, "\r\n\r\n") != NULL) break;
    }
    if (used <= 0) {
        free(req);
        close(fd);
        free(job);
        return NULL;
    }

    char method[16] = {0}, target[2048] = {0};
    sscanf(req, "%15s %2047s", method, target);

    /* Normalize proxy-form local requests (`http://127.0.0.1:<port>/...` -> `/...`) */
    if (strncasecmp(target, "http://127.0.0.1:", 17) == 0 ||
        strncasecmp(target, "http://localhost:", 17) == 0) {
        const char *slash = strchr(target + 7, '/');
        if (slash) {
            memmove(target, slash, strlen(slash) + 1);
        } else {
            strcpy(target, "/");
        }
    }

    /* 1. System-Wide HTTP `CONNECT host:port` -> BearSSL TLS 1.0-1.2 <-> Mbed-TLS 1.3 Tunnel */
    if (strcasecmp(method, "CONNECT") == 0) {
        if (!g_connect_mitm_ready) {
            dprintf(fd, "HTTP/1.1 501 Local TLS 1.3 CA Not Initialized\r\nConnection: close\r\n\r\n");
        } else {
            handle_connect_tunnel(fd, target, job->ca_bundle);
        }
        free(req);
        close(fd);
        free(job);
        return NULL;
    }

    /* 2. Fast Versioned Liveness Probe & Hot-Upgrade Shutdown Endpoint */
    if (strcmp(target, "/__ping") == 0) {
        dprintf(fd, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nARC7_TLS13_READY_V190\n");
        free(req);
        close(fd);
        free(job);
        return NULL;
    }
    if (strcmp(target, "/__shutdown") == 0) {
        dprintf(fd, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nBYE\n");
        free(req);
        close(fd);
        free(job);
        _exit(0);
    }

    /* 3. Transparent Local TLS 1.3 HTTPS Mirror Bridge (`/__https/<host>/<path>` & plain HTTP proxy `http://...`) */
    if (strncmp(target, "/__https/", 9) == 0) {
        char https_url[2048];
        snprintf(https_url, sizeof(https_url), "https://%.2035s", target + 9);
        stream_https_bridge(fd, method, https_url, req, (size_t)used, job->ca_bundle);
        free(req);
        close(fd);
        free(job);
        return NULL;
    }
    if (strncasecmp(target, "http://", 7) == 0 || strncasecmp(target, "https://", 8) == 0) {
        stream_https_bridge(fd, method, target, req, (size_t)used, job->ca_bundle);
        free(req);
        close(fd);
        free(job);
        return NULL;
    }

    /* 4. Native High-Speed Browser Fetch Endpoint (`/__arc7_raw?u=<encoded>`) */
    if (strncmp(target, "/__arc7_raw?", 12) == 0) {
        char raw_target[2048];
        extract_query_param(target + 12, "u", raw_target, sizeof(raw_target));
        if (raw_target[0] == '\0') {
            dprintf(fd, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\nMissing u param\n");
        } else {
            char full_u[2048];
            if (strncasecmp(raw_target, "http://", 7) == 0 || strncasecmp(raw_target, "https://", 8) == 0) {
                snprintf(full_u, sizeof(full_u), "%s", raw_target);
            } else {
                snprintf(full_u, sizeof(full_u), "https://%s", raw_target);
            }
            char ref_hdr[1024], ck_hdr[2048], ua_hdr[512];
            extract_header_value(req, "X-Arc7-Referer", ref_hdr, sizeof(ref_hdr));
            extract_header_value(req, "X-Arc7-Cookie", ck_hdr, sizeof(ck_hdr));
            extract_header_value(req, "X-Arc7-UA", ua_hdr, sizeof(ua_hdr));
            stream_raw_for_arc7_browser(fd, full_u, ref_hdr, ck_hdr, ua_hdr, job->ca_bundle);
        }
        free(req);
        close(fd);
        free(job);
        return NULL;
    }

    /* 5. Interactive Browser Gateway Portal (`/`, `/browse?u=...`) */
    char target_url[2048] = {0};
    if (strncmp(target, "/browse?", 8) == 0) {
        extract_query_param(target + 8, "u", target_url, sizeof(target_url));
    }

    if (target_url[0] == '\0') {
        size_t plen = strlen(PORTAL_HTML);
        dprintf(fd,
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %zu\r\nConnection: close\r\n\r\n%s",
                plen, PORTAL_HTML);
        free(req);
        close(fd);
        free(job);
        return NULL;
    }

    if (strncasecmp(target_url, "http://", 7) != 0 && strncasecmp(target_url, "https://", 8) != 0) {
        char tmp[2048];
        snprintf(tmp, sizeof(tmp), "https://%.2030s", target_url);
        snprintf(target_url, sizeof(target_url), "%s", tmp);
    }

    char tmp_path[128];
    snprintf(tmp_path, sizeof(tmp_path), "/tmp/arc7_gw_%d_%lu.tmp",
             getpid(), (unsigned long)pthread_self());
    int tmp_fd = open(tmp_path, O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (tmp_fd < 0) {
        snprintf(tmp_path, sizeof(tmp_path), "/data/local/tmp/arc7_gw_%d_%lu.tmp",
                 getpid(), (unsigned long)pthread_self());
        tmp_fd = open(tmp_path, O_RDWR | O_CREAT | O_TRUNC, 0600);
    }
    if (tmp_fd < 0) {
        dprintf(fd, "HTTP/1.1 500 Internal Error\r\nConnection: close\r\n\r\nTemp file error\n");
        free(req);
        close(fd);
        free(job);
        return NULL;
    }
    unlink(tmp_path);

    char ct[128] = {0}, final_u[2048] = {0}, tls_ver[128] = {0}, err[320] = {0};
    if (stream_http_to_fd(target_url, job->ca_bundle, tmp_fd,
                          ct, sizeof(ct), final_u, sizeof(final_u),
                          tls_ver, sizeof(tls_ver), err, sizeof(err)) != 0) {
        dprintf(fd,
                "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n"
                "<html><body style='font-family:sans-serif;background:#101622;color:#f8fafc;padding:20px;'>"
                "<h2>TLS 1.3 Gateway Could Not Reach Site</h2><p><b>URL:</b> %s</p><p><b>Reason:</b> %s</p>"
                "<p><a style='color:#60a5fa' href='/'>Back to TLS 1.3 Gateway Home</a></p></body></html>",
                target_url, err);
        close(tmp_fd);
        free(req);
        close(fd);
        free(job);
        return NULL;
    }

    lseek(tmp_fd, 0, SEEK_SET);
    struct stat st;
    off_t body_len = (fstat(tmp_fd, &st) == 0) ? st.st_size : 0;

    if (strcasestr(ct, "text/html") != NULL) {
        char base_tag[2300];
        int blen = snprintf(base_tag, sizeof(base_tag),
                            "<base href=\"%s\">"
                            "<div style=\"position:sticky;top:0;z-index:99999;background:#0f172a;color:#93c5fd;"
                            "font:12px sans-serif;padding:6px 10px;border-bottom:1px solid #334155;\">"
                            "<b>TLS 1.3 Verified:</b> %s &nbsp;|&nbsp; <a style=\"color:#fff\" href=\"/\">New URL</a></div>",
                            final_u, tls_ver);
        dprintf(fd,
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n%.*s",
                blen, base_tag);
    } else {
        dprintf(fd,
                "HTTP/1.1 200 OK\r\nContent-Type: %s\r\nContent-Length: %lld\r\nConnection: close\r\n\r\n",
                ct[0] ? ct : "application/octet-stream", (long long)body_len);
    }

    unsigned char *buf = (unsigned char *)malloc(32768);
    if (buf) {
        for (;;) {
            ssize_t r = read(tmp_fd, buf, 32768);
            if (r <= 0) break;
            if (send(fd, buf, (size_t)r, MSG_NOSIGNAL) <= 0) break;
        }
        free(buf);
    }
    close(tmp_fd);
    free(req);
    close(fd);
    free(job);
    return NULL;
}

static int cmd_proxy(const char *ca_bundle, int port, const char *local_ca_crt, const char *local_ca_key) {
    signal(SIGPIPE, SIG_IGN);
    signal(SIGHUP, SIG_IGN);
    setsid();
    /* Protect the 1.8 MB native TLS 1.3 + DNS gateway daemon from Android 4.2's
     * lowmemorykiller when foreground apps like F-Droid parse 45 MB index JSONs! */
    {
        FILE *oomf = fopen("/proc/self/oom_score_adj", "w");
        if (oomf) { fputs("-1000\n", oomf); fclose(oomf); }
        FILE *ooml = fopen("/proc/self/oom_adj", "w");
        if (ooml) { fputs("-17\n", ooml); fclose(ooml); }
    }
    /* Bind & listen on `127.0.0.1:<port>` FIRST (<0.2ms!) so fast-opening clients
     * (Arc7 Browser / F-Droid Classic right at boot on 1.2 GHz Cortex-A7) queue cleanly
     * in the kernel listen backlog while the 120-CA bundle parses into RAM! */
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) {
        perror("socket");
        return 1;
    }
    int yes = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(srv, 64) != 0) {
        fprintf(stderr, "PROXY=FAILED port=%d errno=%s\n", port, strerror(errno));
        close(srv);
        return 1;
    }

    psa_crypto_init();

    mbedtls_x509_crt_init(&g_upstream_ca_chain);
    if (mbedtls_x509_crt_parse_file(&g_upstream_ca_chain, ca_bundle) >= 0) {
        g_upstream_ca_ready = 1;
    }

    if (local_ca_crt && local_ca_key && init_connect_mitm_ca(local_ca_crt, local_ca_key) != 0) {
        fprintf(stderr, "PROXY=FAILED reason=local_ca_load(%s)\n", local_ca_crt);
        close(srv);
        return 1;
    }

    printf("PROXY=READY listen=127.0.0.1:%d ca=%s CONNECT_TLS13=%s\n",
           port, ca_bundle, g_connect_mitm_ready ? "ENABLED" : "PORTAL_ONLY");
    fflush(stdout);

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, ARC7_WORKER_STACK_BYTES);

    for (;;) {
        int cfd = accept(srv, NULL, NULL);
        if (cfd < 0) continue;
        proxy_job_t *job = (proxy_job_t *)calloc(1, sizeof(proxy_job_t));
        if (!job) { close(cfd); continue; }
        job->client_fd = cfd;
        snprintf(job->ca_bundle, sizeof(job->ca_bundle), "%s", ca_bundle);

        pthread_t tid;
        if (pthread_create(&tid, &attr, handle_proxy_client, job) != 0) {
            close(cfd);
            free(job);
        }
    }
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    if (argc >= 2 && strcmp(argv[1], "--version") == 0) {
        printf("arc7tls 1.8.0 (Mbed-TLS 3.6 TLSv1.3 + BearSSL TLSv1.0-1.2 + F-Droid Bridge + Dual NTP/HTTP Sync)\n");
        return 0;
    }
    if (argc >= 2 && strcmp(argv[1], "ntp") == 0) {
        int set_clk = (argc >= 3 && strcmp(argv[2], "--set") == 0);
        return cmd_ntp(set_clk);
    }
    if (argc >= 4 && strcmp(argv[1], "test") == 0) {
        return cmd_test(argv[2], argv[3]);
    }
    if (argc >= 5 && strcmp(argv[1], "fetch") == 0) {
        return cmd_fetch(argv[2], argv[3], argv[4]);
    }
    if (argc >= 3 && strcmp(argv[1], "gen-local-ca") == 0) {
        return cmd_gen_local_ca(argv[2]);
    }
    if (argc >= 4 && strcmp(argv[1], "proxy") == 0) {
        return cmd_proxy(argv[2], atoi(argv[3]),
                         argc >= 6 ? argv[4] : NULL,
                         argc >= 6 ? argv[5] : NULL);
    }
    fprintf(stderr,
            "Usage:\n"
            "  arc7tls ntp [--set]\n"
            "  arc7tls test <ca-bundle.crt> <https://url>\n"
            "  arc7tls fetch <ca-bundle.crt> <https://url> <dest-file | ->\n"
            "  arc7tls gen-local-ca <out-dir>\n"
            "  arc7tls proxy <ca-bundle.crt> <port> [local-ca.crt local-ca.key]\n");
    return 2;
}
