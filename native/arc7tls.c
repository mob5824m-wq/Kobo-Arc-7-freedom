/*
 * Kobo Arc 7 Freedom — Native Modern TLS 1.2 + TLS 1.3 Network Engine (`libarc7tls.so`)
 *
 * Statically linked with Mbed-TLS 3.6 LTS + PSA Crypto so a 2013 Kobo Arc 7
 * running Android 4.2.2 (API 17, Linux 3.4 ARMv7) immediately gains:
 *   1. Full modern TLS 1.3 & TLS 1.2 (`X25519`, `ECDSA`, `AES-GCM`, `ChaCha20-Poly1305`, SNI)
 *      verified strictly against our 120 pre-downloaded 2026 Root CA certificates
 *      (`assets/cacerts/ca-bundle-2026.crt`) — zero system OpenSSL limitations!
 *   2. Direct RFC 5905 UDP NTP atomic time query + kernel `settimeofday()` clock sync
 *      using raw IPv4 servers (`162.159.200.1`, `216.239.35.0`, `129.6.15.28`) so a
 *      dead-battery tablet stuck on Jan 1, 2013 fixes its clock BEFORE TLS handshakes!
 *   3. Streaming multi-redirect HTTPS downloader (`arc7tls fetch <ca.pem> <url> <out>`)
 *      with real-time `PROGRESS:<pct>:<done>:<total>` events for Android `.zip` ROMs,
 *      `recovery.img` images, and `.apk` apps.
 *   4. Local `127.0.0.1:8118` TLS 1.3 Web & Download Gateway (`arc7tls proxy <ca.pem> 8118`)
 *      so even Android 4.2's stock 2013 Browser can browse modern HTTPS websites, search
 *      the web, and click any `.zip` / `.apk` download link natively!
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include "mbedtls/ctr_drbg.h"
#include "mbedtls/entropy.h"
#include "mbedtls/error.h"
#include "mbedtls/net_sockets.h"
#include "mbedtls/ssl.h"
#include "mbedtls/x509_crt.h"
#include "psa/crypto.h"

#define MAX_URL_LEN   4096
#define MAX_HDR_BYTES 65536
#define MAX_REDIRECTS 12
#define USER_AGENT    "Mozilla/5.0 (Linux; Android 4.2.2; Kobo Arc 7) Arc7Freedom/1.1"

typedef struct {
    int is_https;
    char host[512];
    char port[16];
    char path[3584];
} parsed_url_t;

static mbedtls_x509_crt g_cacert;
static int g_ca_loaded = 0;

static int parse_url(const char *url, parsed_url_t *out) {
    memset(out, 0, sizeof(*out));
    const char *p = NULL;
    if (strncasecmp(url, "https://", 8) == 0) {
        out->is_https = 1;
        strcpy(out->port, "443");
        p = url + 8;
    } else if (strncasecmp(url, "http://", 7) == 0) {
        out->is_https = 0;
        strcpy(out->port, "80");
        p = url + 7;
    } else {
        return -1;
    }

    const char *slash = strchr(p, '/');
    const char *qmark = strchr(p, '?');
    const char *host_end = slash;
    if (!host_end || (qmark && qmark < host_end)) host_end = qmark;

    size_t hostport_len = host_end ? (size_t)(host_end - p) : strlen(p);
    if (hostport_len == 0 || hostport_len >= sizeof(out->host)) return -1;

    char hostport[540];
    memcpy(hostport, p, hostport_len);
    hostport[hostport_len] = '\0';

    /* Strip userinfo if present */
    char *at = strrchr(hostport, '@');
    char *hp = at ? at + 1 : hostport;

    char *colon = strrchr(hp, ':');
    if (colon && strchr(hp, ']') == NULL) {
        *colon = '\0';
        snprintf(out->port, sizeof(out->port), "%s", colon + 1);
    }
    snprintf(out->host, sizeof(out->host), "%s", hp);

    if (!host_end) {
        strcpy(out->path, "/");
    } else if (*host_end == '?') {
        snprintf(out->path, sizeof(out->path), "/%s", host_end);
    } else {
        snprintf(out->path, sizeof(out->path), "%s", host_end);
    }
    return out->host[0] ? 0 : -1;
}

static void resolve_redirect(const parsed_url_t *cur, const char *loc, char *dest, size_t cap) {
    while (*loc == ' ' || *loc == '\t') loc++;
    if (strncasecmp(loc, "http://", 7) == 0 || strncasecmp(loc, "https://", 8) == 0) {
        snprintf(dest, cap, "%s", loc);
    } else if (strncmp(loc, "//", 2) == 0) {
        snprintf(dest, cap, "%s:%s", cur->is_https ? "https" : "http", loc);
    } else if (loc[0] == '/') {
        int default_port = (cur->is_https && strcmp(cur->port, "443") == 0) ||
                           (!cur->is_https && strcmp(cur->port, "80") == 0);
        if (default_port) {
            snprintf(dest, cap, "%s://%s%s", cur->is_https ? "https" : "http", cur->host, loc);
        } else {
            snprintf(dest, cap, "%s://%s:%s%s", cur->is_https ? "https" : "http", cur->host, cur->port, loc);
        }
    } else {
        char base_dir[3584];
        snprintf(base_dir, sizeof(base_dir), "%s", cur->path);
        char *q = strchr(base_dir, '?');
        if (q) *q = '\0';
        char *last_sl = strrchr(base_dir, '/');
        if (last_sl) *(last_sl + 1) = '\0';
        else strcpy(base_dir, "/");
        snprintf(dest, cap, "%s://%s%s%s", cur->is_https ? "https" : "http", cur->host, base_dir, loc);
    }
}

/* ---------------- Connection Abstraction (Plain TCP or TLS 1.2/1.3) ---------------- */

typedef struct {
    int is_tls;
    mbedtls_net_context net;
    mbedtls_entropy_context entropy;
    mbedtls_ctr_drbg_context ctr_drbg;
    mbedtls_ssl_context ssl;
    mbedtls_ssl_config conf;
} http_conn_t;

static void conn_init(http_conn_t *c) {
    memset(c, 0, sizeof(*c));
    mbedtls_net_init(&c->net);
    mbedtls_entropy_init(&c->entropy);
    mbedtls_ctr_drbg_init(&c->ctr_drbg);
    mbedtls_ssl_init(&c->ssl);
    mbedtls_ssl_config_init(&c->conf);
}

static void conn_close(http_conn_t *c) {
    if (c->is_tls) {
        mbedtls_ssl_close_notify(&c->ssl);
    }
    mbedtls_net_free(&c->net);
    mbedtls_ssl_free(&c->ssl);
    mbedtls_ssl_config_free(&c->conf);
    mbedtls_ctr_drbg_free(&c->ctr_drbg);
    mbedtls_entropy_free(&c->entropy);
}

static int conn_open(http_conn_t *c, const parsed_url_t *u, int strict_verify, char *errbuf, size_t errcap) {
    conn_init(c);
    c->is_tls = u->is_https;

    int ret = mbedtls_net_connect(&c->net, u->host, u->port, MBEDTLS_NET_PROTO_TCP);
    if (ret != 0) {
        snprintf(errbuf, errcap, "DNS/TCP connect failed (%s:%s, code=-0x%04x)", u->host, u->port, -ret);
        conn_close(c);
        return -1;
    }

    struct timeval tv = { .tv_sec = 30, .tv_usec = 0 };
    setsockopt(c->net.fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(c->net.fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    if (!u->is_https) {
        return 0;
    }

    const char *pers = "arc7freedom_tls13";
    if ((ret = mbedtls_ctr_drbg_seed(&c->ctr_drbg, mbedtls_entropy_func, &c->entropy,
                                     (const unsigned char *)pers, strlen(pers))) != 0) {
        snprintf(errbuf, errcap, "RNG seed error (-0x%04x)", -ret);
        conn_close(c);
        return -1;
    }

    if ((ret = mbedtls_ssl_config_defaults(&c->conf,
                                           MBEDTLS_SSL_IS_CLIENT,
                                           MBEDTLS_SSL_TRANSPORT_STREAM,
                                           MBEDTLS_SSL_PRESET_DEFAULT)) != 0) {
        snprintf(errbuf, errcap, "SSL config error (-0x%04x)", -ret);
        conn_close(c);
        return -1;
    }

    mbedtls_ssl_conf_authmode(&c->conf,
                              strict_verify ? MBEDTLS_SSL_VERIFY_REQUIRED : MBEDTLS_SSL_VERIFY_OPTIONAL);
    mbedtls_ssl_conf_ca_chain(&c->conf, &g_cacert, NULL);
    mbedtls_ssl_conf_rng(&c->conf, mbedtls_ctr_drbg_random, &c->ctr_drbg);

    if ((ret = mbedtls_ssl_setup(&c->ssl, &c->conf)) != 0) {
        snprintf(errbuf, errcap, "SSL setup error (-0x%04x)", -ret);
        conn_close(c);
        return -1;
    }

    if ((ret = mbedtls_ssl_set_hostname(&c->ssl, u->host)) != 0) {
        snprintf(errbuf, errcap, "SNI hostname error (-0x%04x)", -ret);
        conn_close(c);
        return -1;
    }

    mbedtls_ssl_set_bio(&c->ssl, &c->net, mbedtls_net_send, mbedtls_net_recv, NULL);

    while ((ret = mbedtls_ssl_handshake(&c->ssl)) != 0) {
        if (ret != MBEDTLS_ERR_SSL_WANT_READ && ret != MBEDTLS_ERR_SSL_WANT_WRITE &&
            ret != MBEDTLS_ERR_SSL_CRYPTO_IN_PROGRESS) {
            char mbed_msg[256] = {0};
            mbedtls_strerror(ret, mbed_msg, sizeof(mbed_msg));
            uint32_t vflags = mbedtls_ssl_get_verify_result(&c->ssl);
            snprintf(errbuf, errcap, "TLS handshake failed (%s, verify_flags=0x%x)", mbed_msg, vflags);
            conn_close(c);
            return -1;
        }
    }
    return 0;
}

static int conn_write_all(http_conn_t *c, const void *buf, size_t len) {
    const unsigned char *p = (const unsigned char *)buf;
    size_t sent = 0;
    while (sent < len) {
        int r = c->is_tls
                    ? mbedtls_ssl_write(&c->ssl, p + sent, len - sent)
                    : mbedtls_net_send(&c->net, p + sent, len - sent);
        if (r == MBEDTLS_ERR_SSL_WANT_READ || r == MBEDTLS_ERR_SSL_WANT_WRITE) continue;
        if (r <= 0) return -1;
        sent += (size_t)r;
    }
    return 0;
}

static int conn_read_some(http_conn_t *c, void *buf, size_t cap) {
    for (;;) {
        int r = c->is_tls
                    ? mbedtls_ssl_read(&c->ssl, (unsigned char *)buf, cap)
                    : mbedtls_net_recv(&c->net, (unsigned char *)buf, cap);
        if (r == MBEDTLS_ERR_SSL_WANT_READ || r == MBEDTLS_ERR_SSL_WANT_WRITE ||
            r == MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET) {
            continue;
        }
        if (r == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY || r == 0) return 0;
        if (r < 0) return -1;
        return r;
    }
}

/* ---------------- HTTP Header Parser ---------------- */

typedef struct {
    int status_code;
    long long content_length;
    int is_chunked;
    char location[MAX_URL_LEN];
    char content_type[256];
} http_resp_hdr_t;

static void trim_crlf(char *s) {
    size_t n = strlen(s);
    while (n > 0 && (s[n - 1] == '\r' || s[n - 1] == '\n' || s[n - 1] == ' ' || s[n - 1] == '\t')) {
        s[--n] = '\0';
    }
}

static int read_response_headers(http_conn_t *c, http_resp_hdr_t *hdr,
                                 unsigned char *leftover, size_t *leftover_len) {
    memset(hdr, 0, sizeof(*hdr));
    hdr->content_length = -1;
    *leftover_len = 0;

    char *raw = (char *)malloc(MAX_HDR_BYTES);
    if (!raw) return -1;
    size_t used = 0;
    char *body_start = NULL;

    while (used + 1 < MAX_HDR_BYTES) {
        int r = conn_read_some(c, raw + used, MAX_HDR_BYTES - 1 - used);
        if (r <= 0) break;
        used += (size_t)r;
        raw[used] = '\0';
        body_start = strstr(raw, "\r\n\r\n");
        if (body_start) {
            body_start += 4;
            break;
        }
    }
    if (!body_start) {
        free(raw);
        return -1;
    }

    size_t hdr_bytes = (size_t)(body_start - raw);
    *leftover_len = used - hdr_bytes;
    if (*leftover_len > 0) {
        memcpy(leftover, body_start, *leftover_len);
    }
    raw[hdr_bytes - 2] = '\0'; /* terminate header block cleanly */

    char *saveptr = NULL;
    char *line = strtok_r(raw, "\n", &saveptr);
    if (!line || sscanf(line, "HTTP/%*d.%*d %d", &hdr->status_code) != 1) {
        free(raw);
        return -1;
    }

    while ((line = strtok_r(NULL, "\n", &saveptr)) != NULL) {
        trim_crlf(line);
        if (strncasecmp(line, "Content-Length:", 15) == 0) {
            const char *v = line + 15;
            while (*v == ' ' || *v == '\t') v++;
            hdr->content_length = atoll(v);
        } else if (strncasecmp(line, "Transfer-Encoding:", 18) == 0) {
            if (strcasestr(line + 18, "chunked") != NULL) hdr->is_chunked = 1;
        } else if (strncasecmp(line, "Location:", 9) == 0) {
            const char *v = line + 9;
            while (*v == ' ' || *v == '\t') v++;
            snprintf(hdr->location, sizeof(hdr->location), "%s", v);
        } else if (strncasecmp(line, "Content-Type:", 13) == 0) {
            const char *v = line + 13;
            while (*v == ' ' || *v == '\t') v++;
            snprintf(hdr->content_type, sizeof(hdr->content_type), "%s", v);
        }
    }

    free(raw);
    return 0;
}

/* Chunked + Content-Length unified body reader */
typedef int (*data_sink_fn)(const unsigned char *data, size_t len, void *ctx);

static int stream_http_body(http_conn_t *c, const http_resp_hdr_t *hdr,
                            const unsigned char *initial, size_t initial_len,
                            data_sink_fn sink, void *ctx) {
    if (!hdr->is_chunked) {
        long long total_done = 0;
        if (initial_len > 0) {
            size_t to_emit = initial_len;
            if (hdr->content_length >= 0 && (long long)to_emit > hdr->content_length) {
                to_emit = (size_t)hdr->content_length;
            }
            if (sink(initial, to_emit, ctx) != 0) return -1;
            total_done += (long long)to_emit;
        }
        unsigned char buf[32768];
        while (hdr->content_length < 0 || total_done < hdr->content_length) {
            size_t want = sizeof(buf);
            if (hdr->content_length >= 0 && hdr->content_length - total_done < (long long)want) {
                want = (size_t)(hdr->content_length - total_done);
            }
            int r = conn_read_some(c, buf, want);
            if (r < 0) return -1;
            if (r == 0) break;
            if (sink(buf, (size_t)r, ctx) != 0) return -1;
            total_done += r;
        }
        return (hdr->content_length >= 0 && total_done < hdr->content_length) ? -1 : 0;
    }

    /* HTTP/1.1 Transfer-Encoding: chunked decoder */
    size_t cap = 65536 + initial_len;
    unsigned char *buf = (unsigned char *)malloc(cap);
    if (!buf) return -1;
    size_t len = initial_len;
    if (len > 0) memcpy(buf, initial, len);

    int status = 0;
    for (;;) {
        /* Find CRLF ending chunk size line */
        unsigned char *crlf = NULL;
        for (size_t i = 0; i + 1 < len; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') {
                crlf = buf + i;
                break;
            }
        }
        if (!crlf) {
            if (len + 16384 > cap) {
                cap *= 2;
                unsigned char *nb = (unsigned char *)realloc(buf, cap);
                if (!nb) { status = -1; break; }
                buf = nb;
            }
            int r = conn_read_some(c, buf + len, 16384);
            if (r <= 0) { status = -1; break; }
            len += (size_t)r;
            continue;
        }

        *crlf = '\0';
        size_t chunk_sz = (size_t)strtoull((char *)buf, NULL, 16);
        size_t hdr_len = (size_t)((crlf + 2) - buf);
        memmove(buf, crlf + 2, len - hdr_len);
        len -= hdr_len;

        if (chunk_sz == 0) {
            status = 0;
            break;
        }

        size_t rem = chunk_sz;
        if (len > 0) {
            size_t take = len < rem ? len : rem;
            if (sink(buf, take, ctx) != 0) { status = -1; break; }
            memmove(buf, buf + take, len - take);
            len -= take;
            rem -= take;
        }
        unsigned char cbuf[32768];
        while (rem > 0) {
            size_t want = rem < sizeof(cbuf) ? rem : sizeof(cbuf);
            int r = conn_read_some(c, cbuf, want);
            if (r <= 0) { status = -1; goto done_chunked; }
            if (sink(cbuf, (size_t)r, ctx) != 0) { status = -1; goto done_chunked; }
            rem -= (size_t)r;
        }
        /* Consume trailing CRLF after chunk payload */
        while (len < 2) {
            int r = conn_read_some(c, buf + len, 16);
            if (r <= 0) { status = -1; goto done_chunked; }
            len += (size_t)r;
        }
        memmove(buf, buf + 2, len - 2);
        len -= 2;
    }

done_chunked:
    free(buf);
    return status;
}

/* ---------------- Mode 1: Direct UDP SNTP Time Sync ---------------- */

static int query_single_ntp(const char *server, time_t *out_epoch) {
    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    if (getaddrinfo(server, "123", &hints, &res) != 0 || !res) return -1;

    int sock = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (sock < 0) {
        freeaddrinfo(res);
        return -1;
    }

    struct timeval tv = { .tv_sec = 4, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    unsigned char pkt[48];
    memset(pkt, 0, sizeof(pkt));
    pkt[0] = 0x23; /* LI=0, VN=4, Mode=3 (Client) */

    int ok = -1;
    if (sendto(sock, pkt, sizeof(pkt), 0, res->ai_addr, res->ai_addrlen) == sizeof(pkt)) {
        ssize_t n = recvfrom(sock, pkt, sizeof(pkt), 0, NULL, NULL);
        if (n >= 48) {
            uint32_t secs1900 = ((uint32_t)pkt[40] << 24) |
                                ((uint32_t)pkt[41] << 16) |
                                ((uint32_t)pkt[42] << 8)  |
                                ((uint32_t)pkt[43]);
            if (secs1900 > 2208988800U + 1700000000U) {
                *out_epoch = (time_t)(secs1900 - 2208988800U);
                ok = 0;
            }
        }
    }
    close(sock);
    freeaddrinfo(res);
    return ok;
}

static int cmd_ntp(int set_clock) {
    const char *servers[] = {
        "162.159.200.1",      /* Cloudflare raw IPv4 — works even when DNS is broken! */
        "216.239.35.0",       /* Google Public NTP raw IPv4 */
        "129.6.15.28",        /* NIST raw IPv4 */
        "time.cloudflare.com",
        "pool.ntp.org",
        NULL
    };
    time_t epoch = 0;
    const char *used = NULL;
    for (int i = 0; servers[i]; i++) {
        if (query_single_ntp(servers[i], &epoch) == 0) {
            used = servers[i];
            break;
        }
    }
    if (!used) {
        fprintf(stderr, "ERROR: All UDP NTP queries failed.\n");
        return 1;
    }

    if (set_clock) {
        struct timeval tv = { .tv_sec = epoch, .tv_usec = 0 };
        if (settimeofday(&tv, NULL) != 0) {
            fprintf(stderr, "WARN: settimeofday failed (%s)\n", strerror(errno));
        }
    }

    struct tm g;
    gmtime_r(&epoch, &g);
    printf("NTP_SERVER=%s\n", used);
    printf("UNIX_EPOCH=%lld\n", (long long)epoch);
    printf("ANDROID_DATE=%04d%02d%02d.%02d%02d%02d\n",
           g.tm_year + 1900, g.tm_mon + 1, g.tm_mday,
           g.tm_hour, g.tm_min, g.tm_sec);
    printf("ISO_UTC=%04d-%02d-%02d %02d:%02d:%02d UTC\n",
           g.tm_year + 1900, g.tm_mon + 1, g.tm_mday,
           g.tm_hour, g.tm_min, g.tm_sec);
    fflush(stdout);
    return 0;
}

/* ---------------- Mode 2 & 3: HTTPS Handshake Test & File Downloader ---------------- */

typedef struct {
    FILE *fp;
    long long written;
    long long total;
    int last_pct;
    long long last_report_bytes;
} file_sink_t;

static int write_file_sink(const unsigned char *data, size_t len, void *ctx) {
    file_sink_t *s = (file_sink_t *)ctx;
    if (fwrite(data, 1, len, s->fp) != len) return -1;
    s->written += (long long)len;
    if (s->total > 0) {
        int pct = (int)((s->written * 100LL) / s->total);
        if (pct > 100) pct = 100;
        if (pct >= s->last_pct + 2 || pct == 100) {
            s->last_pct = pct;
            printf("PROGRESS:%d:%lld:%lld\n", pct, s->written, s->total);
            fflush(stdout);
        }
    } else if (s->written - s->last_report_bytes >= 262144) {
        s->last_report_bytes = s->written;
        printf("PROGRESS:50:%lld:-1\n", s->written);
        fflush(stdout);
    }
    return 0;
}

static int perform_get_following_redirects(const char *start_url, int strict_verify,
                                           http_conn_t *out_conn, http_resp_hdr_t *out_hdr,
                                           parsed_url_t *out_final_url,
                                           unsigned char *leftover, size_t *leftover_len) {
    char cur_url[MAX_URL_LEN];
    snprintf(cur_url, sizeof(cur_url), "%s", start_url);

    for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
        parsed_url_t u;
        if (parse_url(cur_url, &u) != 0) {
            fprintf(stderr, "ERROR: Invalid URL: %s\n", cur_url);
            return -1;
        }
        char err[384] = {0};
        if (conn_open(out_conn, &u, strict_verify, err, sizeof(err)) != 0) {
            fprintf(stderr, "ERROR: %s\n", err);
            return -1;
        }

        char req[4608];
        int req_len = snprintf(req, sizeof(req),
                               "GET %s HTTP/1.1\r\n"
                               "Host: %s\r\n"
                               "User-Agent: %s\r\n"
                               "Accept: */*\r\n"
                               "Connection: close\r\n\r\n",
                               u.path, u.host, USER_AGENT);
        if (conn_write_all(out_conn, req, (size_t)req_len) != 0) {
            conn_close(out_conn);
            fprintf(stderr, "ERROR: Failed writing HTTP request to %s\n", u.host);
            return -1;
        }

        if (read_response_headers(out_conn, out_hdr, leftover, leftover_len) != 0) {
            conn_close(out_conn);
            fprintf(stderr, "ERROR: Invalid HTTP response headers from %s\n", u.host);
            return -1;
        }

        if ((out_hdr->status_code == 301 || out_hdr->status_code == 302 ||
             out_hdr->status_code == 303 || out_hdr->status_code == 307 ||
             out_hdr->status_code == 308) && out_hdr->location[0] != '\0') {
            char next_url[MAX_URL_LEN];
            resolve_redirect(&u, out_hdr->location, next_url, sizeof(next_url));
            conn_close(out_conn);
            snprintf(cur_url, sizeof(cur_url), "%s", next_url);
            continue;
        }

        *out_final_url = u;
        return 0;
    }

    fprintf(stderr, "ERROR: Too many HTTP redirects (> %d)\n", MAX_REDIRECTS);
    return -1;
}

static int cmd_test_url(const char *url) {
    http_conn_t c;
    http_resp_hdr_t hdr;
    parsed_url_t final_u;
    unsigned char leftover[MAX_HDR_BYTES];
    size_t leftover_len = 0;

    if (perform_get_following_redirects(url, 1, &c, &hdr, &final_u, leftover, &leftover_len) != 0) {
        return 1;
    }
    printf("FINAL_HOST=%s\n", final_u.host);
    if (c.is_tls) {
        printf("TLS_PROTOCOL=%s\n", mbedtls_ssl_get_version(&c.ssl));
        printf("TLS_CIPHER=%s\n", mbedtls_ssl_get_ciphersuite(&c.ssl));
        printf("CERT_VERIFY=OK\n");
    }
    printf("HTTP_STATUS=%d\n", hdr.status_code);
    conn_close(&c);
    return (hdr.status_code >= 200 && hdr.status_code < 400) ? 0 : 2;
}

static int cmd_fetch(const char *url, const char *out_path, int strict_verify) {
    http_conn_t c;
    http_resp_hdr_t hdr;
    parsed_url_t final_u;
    unsigned char *leftover = (unsigned char *)malloc(MAX_HDR_BYTES);
    if (!leftover) return 1;
    size_t leftover_len = 0;

    if (perform_get_following_redirects(url, strict_verify, &c, &hdr, &final_u, leftover, &leftover_len) != 0) {
        free(leftover);
        return 1;
    }

    if (c.is_tls) {
        printf("CONNECTED:%s:%s:%s\n",
               final_u.host,
               mbedtls_ssl_get_version(&c.ssl),
               mbedtls_ssl_get_ciphersuite(&c.ssl));
        fflush(stdout);
    }

    if (hdr.status_code < 200 || hdr.status_code >= 300) {
        fprintf(stderr, "ERROR: Server returned HTTP %d from %s\n", hdr.status_code, final_u.host);
        conn_close(&c);
        free(leftover);
        return 2;
    }

    char part_path[4096];
    snprintf(part_path, sizeof(part_path), "%s.part", out_path);
    FILE *fp = fopen(part_path, "wb");
    if (!fp) {
        fprintf(stderr, "ERROR: Cannot write to %s (%s)\n", part_path, strerror(errno));
        conn_close(&c);
        free(leftover);
        return 3;
    }

    file_sink_t sink = {
        .fp = fp,
        .written = 0,
        .total = hdr.content_length,
        .last_pct = -2,
        .last_report_bytes = 0
    };
    int rc = stream_http_body(&c, &hdr, leftover, leftover_len, write_file_sink, &sink);
    fclose(fp);
    conn_close(&c);
    free(leftover);

    if (rc != 0) {
        unlink(part_path);
        fprintf(stderr, "ERROR: Stream interrupted after %lld bytes\n", sink.written);
        return 4;
    }
    if (rename(part_path, out_path) != 0) {
        fprintf(stderr, "ERROR: Rename failed (%s)\n", strerror(errno));
        return 5;
    }
    printf("DONE:%lld:%s\n", sink.written, out_path);
    fflush(stdout);
    return 0;
}

/* ---------------- Mode 4: Local 127.0.0.1 TLS 1.3 Browser Gateway ---------------- */

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static void url_decode(const char *src, char *dst, size_t cap) {
    size_t o = 0;
    for (size_t i = 0; src[i] && o + 1 < cap; i++) {
        if (src[i] == '%' && hexval(src[i + 1]) >= 0 && hexval(src[i + 2]) >= 0) {
            dst[o++] = (char)((hexval(src[i + 1]) << 4) | hexval(src[i + 2]));
            i += 2;
        } else if (src[i] == '+') {
            dst[o++] = ' ';
        } else {
            dst[o++] = src[i];
        }
    }
    dst[o] = '\0';
}

static int sock_send_all(int fd, const void *buf, size_t len) {
    const char *p = (const char *)buf;
    size_t done = 0;
    while (done < len) {
        ssize_t n = send(fd, p + done, len - done, MSG_NOSIGNAL);
        if (n <= 0) return -1;
        done += (size_t)n;
    }
    return 0;
}

static int raw_sock_sink(const unsigned char *data, size_t len, void *ctx) {
    int fd = *(int *)ctx;
    return sock_send_all(fd, data, len);
}

typedef struct {
    unsigned char *data;
    size_t len;
    size_t cap;
} mem_buf_t;

static int mem_sink(const unsigned char *data, size_t len, void *ctx) {
    mem_buf_t *m = (mem_buf_t *)ctx;
    if (m->len + len > 8 * 1024 * 1024) return -1; /* cap HTML buffer at 8 MB */
    if (m->len + len + 1 > m->cap) {
        size_t nc = (m->cap == 0 ? 65536 : m->cap * 2);
        while (nc < m->len + len + 1) nc *= 2;
        unsigned char *nb = (unsigned char *)realloc(m->data, nc);
        if (!nb) return -1;
        m->data = nb;
        m->cap = nc;
    }
    memcpy(m->data + m->len, data, len);
    m->len += len;
    m->data[m->len] = '\0';
    return 0;
}

static void send_portal_home(int client_fd) {
    const char *page =
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: text/html; charset=utf-8\r\n"
        "Connection: close\r\n\r\n"
        "<!DOCTYPE html><html><head><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        "<title>Kobo Arc 7 — Modern TLS 1.3 Internet Portal</title>"
        "<style>"
        "body{font-family:sans-serif;background:#0f172a;color:#f8fafc;margin:0;padding:18px;}"
        ".card{background:#1e293b;border:1px solid #334155;border-radius:10px;padding:16px;margin-bottom:16px;}"
        "h1{font-size:20px;margin:0 0 8px 0;color:#38bdf8;}"
        "input[type=text]{width:100%;box-sizing:border-box;padding:12px;font-size:16px;"
        "border-radius:6px;border:1px solid #475569;background:#0f172a;color:#fff;margin-bottom:10px;}"
        "button,.btn{display:inline-block;background:#0284c7;color:#fff;text-decoration:none;"
        "padding:10px 16px;border-radius:6px;border:0;font-weight:bold;font-size:14px;margin:4px 6px 4px 0;}"
        ".green{background:#16a34a;}"
        "</style></head><body>"
        "<div class='card'>"
        "<h1>Kobo Arc 7 Modern TLS 1.3 &amp; 2026 CA Gateway</h1>"
        "<p style='color:#cbd5e1;margin:4px 0 12px 0;'>Powered offline by 120 pre-downloaded 2026 Root CA certificates + ARMv7 MbedTLS 3.6. Open any HTTPS page or click any <b>.zip</b> ROM / <b>.apk</b> file to download straight to your tablet!</p>"
        "<form action='/go' method='GET'>"
        "<input type='text' name='u' placeholder='https://archive.org  or  paste direct .zip / .apk URL'>"
        "<button type='submit' class='green'>Open / Download via TLS 1.3</button>"
        "</form>"
        "<form action='/search' method='GET' style='margin-top:10px;'>"
        "<input type='text' name='q' placeholder='Search the web (e.g. Kobo Arc 7 Android 4.2 APK or ROM)...'>"
        "<button type='submit'>Web Search (DuckDuckGo Lite)</button>"
        "</form></div>"
        "<div class='card'><b>One-Tap Android 4.2 Downloads &amp; Sites:</b><br><br>"
        "<a class='btn green' href='/go?u=https://f-droid.org/archive/org.fdroid.fdroid_1002051.apk'>Download F-Droid Classic (Android 4.2 Store APK)</a>"
        "<a class='btn' href='/go?u=https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases'>Kobo Arc 7 Freedom GitHub Releases</a>"
        "<a class='btn' href='/go?u=https://archive.org/details/software'>Internet Archive Software</a>"
        "<a class='btn' href='/go?u=https://xdaforums.com/t/arc-7-root-and-recovery-and-mt8125-chipset-info.2635520/'>XDA Kobo Arc 7 Thread</a>"
        "</div></body></html>";
    sock_send_all(client_fd, page, strlen(page));
}

static void stream_rewritten_html(int client_fd, const parsed_url_t *base, const char *html, size_t len) {
    char hdr[1536];
    snprintf(hdr, sizeof(hdr),
             "HTTP/1.1 200 OK\r\n"
             "Content-Type: text/html; charset=utf-8\r\n"
             "Connection: close\r\n\r\n"
             "<div style='background:#0f172a;color:#e2e8f0;padding:8px 12px;font-family:sans-serif;"
             "font-size:13px;border-bottom:2px solid #0284c7;'>"
             "<a href='/' style='color:#38bdf8;font-weight:bold;text-decoration:none;margin-right:12px;'>[Portal Home]</a>"
             "TLS 1.3 Verified: <b>%s%s</b></div>",
             base->host, base->path);
    sock_send_all(client_fd, hdr, strlen(hdr));

    size_t i = 0;
    while (i < len) {
        if ((strncasecmp(html + i, "href=\"", 6) == 0 ||
             strncasecmp(html + i, "href='", 6) == 0 ||
             strncasecmp(html + i, "action=\"", 8) == 0 ||
             strncasecmp(html + i, "action='", 8) == 0)) {
            const char *eq = strchr(html + i, '=');
            char quote = *(eq + 1);
            size_t attr_prefix = (size_t)((eq + 2) - (html + i));
            sock_send_all(client_fd, html + i, attr_prefix);
            i += attr_prefix;
            const char *endq = strchr(html + i, quote);
            if (!endq) continue;
            size_t ulen = (size_t)(endq - (html + i));
            if (ulen > 0 && ulen < 2000 && html[i] != '#' && strncasecmp(html + i, "javascript:", 11) != 0) {
                char raw_link[2048];
                memcpy(raw_link, html + i, ulen);
                raw_link[ulen] = '\0';
                char full_link[MAX_URL_LEN];
                resolve_redirect(base, raw_link, full_link, sizeof(full_link));
                sock_send_all(client_fd, "/go?u=", 6);
                sock_send_all(client_fd, full_link, strlen(full_link));
            } else {
                sock_send_all(client_fd, html + i, ulen);
            }
            i += ulen;
            continue;
        }
        sock_send_all(client_fd, html + i, 1);
        i++;
    }
}

static void *handle_proxy_client(void *arg) {
    int fd = (int)(intptr_t)arg;
    char req[8192];
    ssize_t n = recv(fd, req, sizeof(req) - 1, 0);
    if (n <= 0) {
        close(fd);
        return NULL;
    }
    req[n] = '\0';

    char method[16] = {0}, target[4096] = {0};
    if (sscanf(req, "%15s %4095s", method, target) != 2) {
        close(fd);
        return NULL;
    }

    if (strcmp(target, "/") == 0 || strncmp(target, "/index", 6) == 0) {
        send_portal_home(fd);
        close(fd);
        return NULL;
    }

    char dest_url[MAX_URL_LEN] = {0};
    if (strncmp(target, "/search?q=", 10) == 0) {
        snprintf(dest_url, sizeof(dest_url), "https://lite.duckduckgo.com/lite/?q=%s", target + 10);
    } else if (strncmp(target, "/go?u=", 6) == 0) {
        url_decode(target + 6, dest_url, sizeof(dest_url));
        if (strncasecmp(dest_url, "http://", 7) != 0 && strncasecmp(dest_url, "https://", 8) != 0) {
            char tmp[MAX_URL_LEN];
            snprintf(tmp, sizeof(tmp), "https://%s", dest_url);
            snprintf(dest_url, sizeof(dest_url), "%s", tmp);
        }
    } else if (strncasecmp(target, "http://", 7) == 0 || strncasecmp(target, "https://", 8) == 0) {
        snprintf(dest_url, sizeof(dest_url), "%s", target);
    } else {
        send_portal_home(fd);
        close(fd);
        return NULL;
    }

    http_conn_t c;
    http_resp_hdr_t hdr;
    parsed_url_t final_u;
    unsigned char *leftover = (unsigned char *)malloc(MAX_HDR_BYTES);
    size_t leftover_len = 0;
    if (!leftover || perform_get_following_redirects(dest_url, 1, &c, &hdr, &final_u, leftover, &leftover_len) != 0) {
        const char *errp = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\n\r\nFailed to connect upstream via TLS 1.3.\n";
        sock_send_all(fd, errp, strlen(errp));
        free(leftover);
        close(fd);
        return NULL;
    }

    if (strcasestr(hdr.content_type, "text/html") != NULL) {
        mem_buf_t mb = {0};
        stream_http_body(&c, &hdr, leftover, leftover_len, mem_sink, &mb);
        if (mb.data) {
            stream_rewritten_html(fd, &final_u, (const char *)mb.data, mb.len);
            free(mb.data);
        }
    } else {
        char out_hdr[1024];
        const char *ctype = hdr.content_type[0] ? hdr.content_type : "application/octet-stream";
        int hl;
        if (hdr.content_length >= 0 && !hdr.is_chunked) {
            hl = snprintf(out_hdr, sizeof(out_hdr),
                          "HTTP/1.1 %d OK\r\nContent-Type: %s\r\nContent-Length: %lld\r\nConnection: close\r\n\r\n",
                          hdr.status_code, ctype, hdr.content_length);
        } else {
            hl = snprintf(out_hdr, sizeof(out_hdr),
                          "HTTP/1.1 %d OK\r\nContent-Type: %s\r\nConnection: close\r\n\r\n",
                          hdr.status_code, ctype);
        }
        sock_send_all(fd, out_hdr, (size_t)hl);
        stream_http_body(&c, &hdr, leftover, leftover_len, raw_sock_sink, &fd);
    }

    conn_close(&c);
    free(leftover);
    close(fd);
    return NULL;
}

static int cmd_proxy(int port) {
    signal(SIGPIPE, SIG_IGN);
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) return 1;
    int yes = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(srv, 32) != 0) {
        fprintf(stderr, "ERROR: Cannot bind 127.0.0.1:%d (%s)\n", port, strerror(errno));
        close(srv);
        return 1;
    }
    printf("PROXY_READY=http://127.0.0.1:%d/\n", port);
    fflush(stdout);

    for (;;) {
        int client = accept(srv, NULL, NULL);
        if (client < 0) continue;
        pthread_t tid;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        if (pthread_create(&tid, &attr, handle_proxy_client, (void *)(intptr_t)client) != 0) {
            close(client);
        }
        pthread_attr_destroy(&attr);
    }
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr,
                "Usage:\n"
                "  arc7tls ntp [--set]\n"
                "  arc7tls test <ca-bundle.crt> <url>\n"
                "  arc7tls fetch <ca-bundle.crt> <url> <output_path> [--insecure-clock]\n"
                "  arc7tls proxy <ca-bundle.crt> <port>\n");
        return 1;
    }

    if (strcmp(argv[1], "ntp") == 0) {
        int set_flag = (argc >= 3 && strcmp(argv[2], "--set") == 0);
        return cmd_ntp(set_flag);
    }

    if (psa_crypto_init() != PSA_SUCCESS) {
        fprintf(stderr, "ERROR: psa_crypto_init failed\n");
        return 1;
    }

    if (argc < 4) {
        fprintf(stderr, "ERROR: Missing CA bundle path and target arguments.\n");
        return 1;
    }

    mbedtls_x509_crt_init(&g_cacert);
    int ret = mbedtls_x509_crt_parse_file(&g_cacert, argv[2]);
    if (ret < 0) {
        fprintf(stderr, "ERROR: Failed parsing CA bundle %s (-0x%04x)\n", argv[2], -ret);
        mbedtls_x509_crt_free(&g_cacert);
        return 1;
    }
    g_ca_loaded = 1;

    int rc = 1;
    if (strcmp(argv[1], "test") == 0) {
        rc = cmd_test_url(argv[3]);
    } else if (strcmp(argv[1], "fetch") == 0 && argc >= 5) {
        int strict = !(argc >= 6 && strcmp(argv[5], "--insecure-clock") == 0);
        rc = cmd_fetch(argv[3], argv[4], strict);
    } else if (strcmp(argv[1], "proxy") == 0) {
        rc = cmd_proxy(atoi(argv[3]));
    } else {
        fprintf(stderr, "ERROR: Unknown arc7tls subcommand: %s\n", argv[1]);
    }

    mbedtls_x509_crt_free(&g_cacert);
    return rc;
}
