/*
 * native/arc7tool.c
 *
 * High-speed native ARMv7 C partition imaging, streaming gzip compression/decompression,
 * Android boot/recovery image header validator (`ANDROID!`), SP Flash Tool scatter builder,
 * and single-pass FIPS 180-4 SHA-256 checksum engine for Kobo Arc 7 / 7HD.
 */

#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <time.h>
#include <zlib.h>

#ifndef SO_ORIGINAL_DST
#define SO_ORIGINAL_DST 80
#endif

/* ======================== Embedded FIPS 180-4 SHA-256 ======================== */
typedef struct {
    uint32_t state[8];
    uint64_t bitcount;
    uint8_t buffer[64];
} sha256_ctx_t;

static const uint32_t K256[64] = {
    0x428a2f98U,0x71374491U,0xb5c0fbcfU,0xe9b5dba5U,0x3956c25bU,0x59f111f1U,0x923f82a4U,0xab1c5ed5U,
    0xd807aa98U,0x12835b01U,0x243185beU,0x550c7dc3U,0x72be5d74U,0x80deb1feU,0x9bdc06a7U,0xc19bf174U,
    0xe49b69c1U,0xefbe4786U,0x0fc19dc6U,0x240ca1ccU,0x2de92c6fU,0x4a7484aaU,0x5cb0a9dcU,0x76f988daU,
    0x983e5152U,0xa831c66dU,0xb00327c8U,0xbf597fc7U,0xc6e00bf3U,0xd5a79147U,0x06ca6351U,0x14292967U,
    0x27b70a85U,0x2e1b2138U,0x4d2c6dfcU,0x53380d13U,0x650a7354U,0x766a0abbU,0x81c2c92eU,0x92722c85U,
    0xa2bfe8a1U,0xa81a664bU,0xc24b8b70U,0xc76c51a3U,0xd192e819U,0xd6990624U,0xf40e3585U,0x106aa070U,
    0x19a4c116U,0x1e376c08U,0x2748774cU,0x34b0bcb5U,0x391c0cb3U,0x4ed8aa4aU,0x5b9cca4fU,0x682e6ff3U,
    0x748f82eeU,0x78a5636fU,0x84c87814U,0x8cc70208U,0x90befffaU,0xa4506cebU,0xbef9a3f7U,0xc67178f2U
};

#define ROR32(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void sha256_transform(sha256_ctx_t *ctx, const uint8_t data[64]) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++) {
        w[i] = ((uint32_t)data[i * 4] << 24) | ((uint32_t)data[i * 4 + 1] << 16) |
               ((uint32_t)data[i * 4 + 2] << 8) | (uint32_t)data[i * 4 + 3];
    }
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = ROR32(w[i - 15], 7) ^ ROR32(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = ROR32(w[i - 2], 17) ^ ROR32(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = ctx->state[0], b = ctx->state[1], c = ctx->state[2], d = ctx->state[3];
    uint32_t e = ctx->state[4], f = ctx->state[5], g = ctx->state[6], h = ctx->state[7];
    for (int i = 0; i < 64; i++) {
        uint32_t S1 = ROR32(e, 6) ^ ROR32(e, 11) ^ ROR32(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t t1 = h + S1 + ch + K256[i] + w[i];
        uint32_t S0 = ROR32(a, 2) ^ ROR32(a, 13) ^ ROR32(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

static void sha256_init(sha256_ctx_t *ctx) {
    ctx->bitcount = 0;
    ctx->state[0] = 0x6a09e667U; ctx->state[1] = 0xbb67ae85U;
    ctx->state[2] = 0x3c6ef372U; ctx->state[3] = 0xa54ff53aU;
    ctx->state[4] = 0x510e527fU; ctx->state[5] = 0x9b05688cU;
    ctx->state[6] = 0x1f83d9abU; ctx->state[7] = 0x5be0cd19U;
}

static void sha256_update(sha256_ctx_t *ctx, const uint8_t *data, size_t len) {
    size_t idx = (size_t)((ctx->bitcount >> 3) & 63U);
    ctx->bitcount += ((uint64_t)len << 3);
    size_t i = 0;
    if (idx > 0 && idx + len >= 64) {
        memcpy(ctx->buffer + idx, data, 64 - idx);
        sha256_transform(ctx, ctx->buffer);
        i = 64 - idx;
        idx = 0;
    }
    for (; i + 64 <= len; i += 64) {
        sha256_transform(ctx, data + i);
    }
    if (i < len) {
        memcpy(ctx->buffer + idx, data + i, len - i);
    }
}

static void sha256_final_hex(sha256_ctx_t *ctx, char hex_out[65]) {
    uint64_t bits = ctx->bitcount;
    size_t idx = (size_t)((bits >> 3) & 63U);
    ctx->buffer[idx++] = 0x80;
    if (idx > 56) {
        memset(ctx->buffer + idx, 0, 64 - idx);
        sha256_transform(ctx, ctx->buffer);
        idx = 0;
    }
    memset(ctx->buffer + idx, 0, 56 - idx);
    for (int i = 0; i < 8; i++) {
        ctx->buffer[56 + i] = (uint8_t)(bits >> (56 - i * 8));
    }
    sha256_transform(ctx, ctx->buffer);
    for (int i = 0; i < 8; i++) {
        snprintf(hex_out + i * 8, 9, "%08x", ctx->state[i]);
    }
    hex_out[64] = '\0';
}

/* ======================== Block Size Helper ======================== */
static uint64_t get_entity_size(int fd) {
    struct stat st;
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0) {
        return (uint64_t)st.st_size;
    }
    uint64_t blk_bytes = 0;
    if (ioctl(fd, BLKGETSIZE64, &blk_bytes) == 0 && blk_bytes > 0) {
        return blk_bytes;
    }
    off_t end = lseek(fd, 0, SEEK_END);
    if (end > 0) {
        lseek(fd, 0, SEEK_SET);
        return (uint64_t)end;
    }
    return 0;
}

/* ======================== Subcommands ======================== */
static int cmd_sha256(const char *path, uint64_t limit_bytes) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        fprintf(stderr, "ERROR: cannot open %s: %s\n", path, strerror(errno));
        return 1;
    }
    uint8_t *buf = (uint8_t *)malloc(1048576);
    if (!buf) { close(fd); return 1; }

    sha256_ctx_t ctx;
    sha256_init(&ctx);
    uint64_t done = 0;
    while (limit_bytes == 0 || done < limit_bytes) {
        size_t want = 1048576;
        if (limit_bytes > 0 && limit_bytes - done < want) want = (size_t)(limit_bytes - done);
        ssize_t r = read(fd, buf, want);
        if (r < 0) {
            if (errno == EINTR) continue;
            free(buf); close(fd);
            return 1;
        }
        if (r == 0) break;
        sha256_update(&ctx, buf, (size_t)r);
        done += (uint64_t)r;
    }
    free(buf);
    close(fd);

    char hex[65];
    sha256_final_hex(&ctx, hex);
    printf("SHA256 %s %llu\n", hex, (unsigned long long)done);
    return 0;
}

static int cmd_dump_gz(const char *src_path, const char *dst_path, uint64_t offset_bytes, uint64_t max_bytes) {
    int in_fd = open(src_path, O_RDONLY);
    if (in_fd < 0) {
        fprintf(stderr, "ERROR: cannot open source %s: %s\n", src_path, strerror(errno));
        return 1;
    }
    uint64_t total = max_bytes > 0 ? max_bytes : get_entity_size(in_fd);
    if (offset_bytes > 0) {
        if (lseek(in_fd, (off_t)offset_bytes, SEEK_SET) < 0) {
            fprintf(stderr, "ERROR: lseek failed: %s\n", strerror(errno));
            close(in_fd);
            return 1;
        }
    }

    int out_fd = open(dst_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out_fd < 0) {
        fprintf(stderr, "ERROR: cannot create %s: %s\n", dst_path, strerror(errno));
        close(in_fd);
        return 1;
    }

    z_stream zs;
    memset(&zs, 0, sizeof(zs));
    /* windowBits = 15 + 16 writes a standard RFC 1952 .gz header & CRC32 trailer */
    if (deflateInit2(&zs, 1, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY) != Z_OK) {
        close(in_fd); close(out_fd);
        return 1;
    }

    const size_t CHUNK = 524288; /* 512 KB */
    uint8_t *in_buf = (uint8_t *)malloc(CHUNK);
    uint8_t *out_buf = (uint8_t *)malloc(CHUNK + 65536);
    if (!in_buf || !out_buf) {
        deflateEnd(&zs);
        free(in_buf); free(out_buf);
        close(in_fd); close(out_fd);
        return 1;
    }

    sha256_ctx_t gz_sha, raw_sha;
    sha256_init(&gz_sha);
    sha256_init(&raw_sha);

    uint64_t raw_done = 0, gz_done = 0, next_report = 0;
    int eof = 0;
    while (!eof) {
        size_t want = CHUNK;
        if (max_bytes > 0 && raw_done + want > max_bytes) {
            want = (size_t)(max_bytes - raw_done);
        }
        ssize_t n = (want > 0) ? read(in_fd, in_buf, want) : 0;
        if (n < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "ERROR: read failure at %llu: %s\n",
                    (unsigned long long)raw_done, strerror(errno));
            deflateEnd(&zs);
            free(in_buf); free(out_buf);
            close(in_fd); close(out_fd);
            return 1;
        }
        if (n == 0) eof = 1;
        else {
            sha256_update(&raw_sha, in_buf, (size_t)n);
            raw_done += (uint64_t)n;
        }

        zs.next_in = in_buf;
        zs.avail_in = (uInt)n;
        do {
            zs.next_out = out_buf;
            zs.avail_out = (uInt)(CHUNK + 65536);
            int zrc = deflate(&zs, eof ? Z_FINISH : Z_NO_FLUSH);
            if (zrc == Z_STREAM_ERROR) {
                deflateEnd(&zs);
                free(in_buf); free(out_buf);
                close(in_fd); close(out_fd);
                return 1;
            }
            size_t have = (CHUNK + 65536) - zs.avail_out;
            if (have > 0) {
                sha256_update(&gz_sha, out_buf, have);
                size_t off = 0;
                while (off < have) {
                    ssize_t w = write(out_fd, out_buf + off, have - off);
                    if (w <= 0) {
                        fprintf(stderr, "ERROR: write error (disk full?): %s\n", strerror(errno));
                        deflateEnd(&zs);
                        free(in_buf); free(out_buf);
                        close(in_fd); close(out_fd);
                        unlink(dst_path);
                        return 1;
                    }
                    off += (size_t)w;
                }
                gz_done += have;
            }
        } while (zs.avail_out == 0);

        if (raw_done >= next_report || eof) {
            printf("PROGRESS %llu %llu %llu\n",
                   (unsigned long long)raw_done,
                   (unsigned long long)total,
                   (unsigned long long)gz_done);
            fflush(stdout);
            next_report = raw_done + (4ULL * 1048576ULL);
        }
    }

    deflateEnd(&zs);
    fsync(out_fd);
    free(in_buf);
    free(out_buf);
    close(in_fd);
    close(out_fd);

    char gz_hex[65], raw_hex[65];
    sha256_final_hex(&gz_sha, gz_hex);
    sha256_final_hex(&raw_sha, raw_hex);
    printf("DONE %llu %llu %s %s\n",
           (unsigned long long)raw_done,
           (unsigned long long)gz_done,
           gz_hex,
           raw_hex);
    fflush(stdout);
    return 0;
}

static int cmd_flash_image(const char *src_path, const char *dst_block, uint64_t offset_bytes, uint64_t max_bytes) {
    /* Safety guard: NEVER allow raw device writes at offset 0 on internal mmcblk0 whole disk by mistake */
    if (strcmp(dst_block, "/dev/block/mmcblk0") == 0 && offset_bytes == 0 && max_bytes == 0) {
        fprintf(stderr, "ERROR: safety guard blocked unbounded write at offset 0 on /dev/block/mmcblk0\n");
        return 1;
    }

    gzFile gzf = gzopen(src_path, "rb");
    if (!gzf) {
        fprintf(stderr, "ERROR: cannot open image %s: %s\n", src_path, strerror(errno));
        return 1;
    }
    gzbuffer(gzf, 262144);

    struct stat st;
    uint64_t compressed_total = (stat(src_path, &st) == 0) ? (uint64_t)st.st_size : 0;

    int out_fd = open(dst_block, O_WRONLY | O_SYNC);
    if (out_fd < 0) {
        /* Allow creating regular file during test / simulation only outside /dev/ */
        if (strncmp(dst_block, "/dev/", 5) == 0) {
            fprintf(stderr, "ERROR: cannot open block device %s: %s\n", dst_block, strerror(errno));
            gzclose(gzf);
            return 1;
        }
        out_fd = open(dst_block, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (out_fd < 0) {
            fprintf(stderr, "ERROR: cannot open target %s for writing: %s\n", dst_block, strerror(errno));
            gzclose(gzf);
            return 1;
        }
    }
    uint64_t dev_capacity = get_entity_size(out_fd);
    if (max_bytes == 0 && offset_bytes == 0 && dev_capacity > 0 && strncmp(dst_block, "/dev/", 5) == 0) {
        max_bytes = dev_capacity;
    }
    if (offset_bytes > 0 && lseek(out_fd, (off_t)offset_bytes, SEEK_SET) < 0) {
        fprintf(stderr, "ERROR: lseek target failed: %s\n", strerror(errno));
        close(out_fd);
        gzclose(gzf);
        return 1;
    }

    const size_t CHUNK = 524288;
    uint8_t *buf = (uint8_t *)malloc(CHUNK);
    if (!buf) { close(out_fd); gzclose(gzf); return 1; }

    sha256_ctx_t raw_sha;
    sha256_init(&raw_sha);
    uint64_t written = 0, next_report = 0;

    for (;;) {
        int n = gzread(gzf, buf, (unsigned int)CHUNK);
        if (n < 0) {
            int zerr = 0;
            fprintf(stderr, "ERROR: decompression error: %s\n", gzerror(gzf, &zerr));
            free(buf); close(out_fd); gzclose(gzf);
            return 1;
        }
        if (n == 0) break;

        if (max_bytes > 0 && written + (uint64_t)n > max_bytes) {
            fprintf(stderr, "ERROR: partition boundary guard stopped write (%llu + %d > max %llu bytes)!\n",
                    (unsigned long long)written, n, (unsigned long long)max_bytes);
            free(buf); close(out_fd); gzclose(gzf);
            return 1;
        }

        sha256_update(&raw_sha, buf, (size_t)n);
        size_t off = 0;
        while (off < (size_t)n) {
            ssize_t w = write(out_fd, buf + off, (size_t)n - off);
            if (w <= 0) {
                fprintf(stderr, "ERROR: block write error at %llu: %s\n",
                        (unsigned long long)written, strerror(errno));
                free(buf); close(out_fd); gzclose(gzf);
                return 1;
            }
            off += (size_t)w;
        }
        written += (uint64_t)n;
        if (written >= next_report) {
            uint64_t comp_pos = (uint64_t)gzoffset(gzf);
            printf("PROGRESS %llu %llu %llu\n",
                   (unsigned long long)comp_pos,
                   (unsigned long long)compressed_total,
                   (unsigned long long)written);
            fflush(stdout);
            next_report = written + (4ULL * 1048576ULL);
        }
    }

    fsync(out_fd);
    ioctl(out_fd, BLKFLSBUF, 0);
    sync();
    free(buf);
    close(out_fd);
    gzclose(gzf);

    char raw_hex[65];
    sha256_final_hex(&raw_sha, raw_hex);
    printf("DONE %llu %s\n", (unsigned long long)written, raw_hex);
    fflush(stdout);
    return 0;
}

/* Android standard boot_img_hdr (system/core/mkbootimg/bootimg.h) */
typedef struct {
    uint8_t  magic[8];
    uint32_t kernel_size;
    uint32_t kernel_addr;
    uint32_t ramdisk_size;
    uint32_t ramdisk_addr;
    uint32_t second_size;
    uint32_t second_addr;
    uint32_t tags_addr;
    uint32_t page_size;
    uint32_t unused[2];
    uint8_t  name[16];
    uint8_t  cmdline[512];
    uint32_t id[8];
} __attribute__((packed)) android_boot_hdr_t;

static int cmd_inspect_boot(const char *img_path) {
    int fd = open(img_path, O_RDONLY);
    if (fd < 0) {
        fprintf(stderr, "ERROR: cannot open %s\n", img_path);
        return 1;
    }
    struct stat st;
    fstat(fd, &st);

    android_boot_hdr_t hdr;
    if (read(fd, &hdr, sizeof(hdr)) != sizeof(hdr)) {
        close(fd);
        printf("VALID_BOOT_IMG=NO REASON=TOO_SMALL\n");
        return 1;
    }
    close(fd);

    /* Support both ANDROID! at offset 0 AND MediaTek 512-byte outer header + ANDROID! at 512 */
    size_t outer_off = 0;
    if (memcmp(hdr.magic, "ANDROID!", 8) != 0) {
        int fd2 = open(img_path, O_RDONLY);
        if (fd2 >= 0) {
            if (lseek(fd2, 512, SEEK_SET) == 512 &&
                read(fd2, &hdr, sizeof(hdr)) == sizeof(hdr) &&
                memcmp(hdr.magic, "ANDROID!", 8) == 0) {
                outer_off = 512;
            }
            close(fd2);
        }
    }
    if (memcmp(hdr.magic, "ANDROID!", 8) != 0) {
        printf("VALID_BOOT_IMG=NO REASON=BAD_MAGIC\n");
        return 1;
    }
    uint32_t ps = hdr.page_size ? hdr.page_size : 2048;
    if (ps != 2048 && ps != 4096 && ps != 8192 && ps != 16384) {
        printf("VALID_BOOT_IMG=NO REASON=BAD_PAGESIZE\n");
        return 1;
    }
    uint64_t k_pages = (hdr.kernel_size + ps - 1) / ps;
    uint64_t r_pages = (hdr.ramdisk_size + ps - 1) / ps;
    uint64_t s_pages = (hdr.second_size + ps - 1) / ps;
    uint64_t expected_bytes = (1ULL + k_pages + r_pages + s_pages) * (uint64_t)ps;

    char board[17] = {0};
    memcpy(board, hdr.name, 16);
    char cmdline[513] = {0};
    memcpy(cmdline, hdr.cmdline, 512);

    printf("VALID_BOOT_IMG=YES\n");
    printf("OUTER_MTK_OFFSET=%zu\n", outer_off);
    printf("FILE_SIZE=%lld\n", (long long)st.st_size);
    printf("PAYLOAD_BYTES=%llu\n", (unsigned long long)(expected_bytes + outer_off));
    printf("PAGE_SIZE=%u\n", ps);
    printf("KERNEL_SIZE=%u\n", hdr.kernel_size);
    printf("RAMDISK_SIZE=%u\n", hdr.ramdisk_size);
    printf("BOARD_NAME=%s\n", board);
    printf("CMDLINE=%s\n", cmdline);
    return 0;
}

/* ============================================================================
 * Anti-Brick On-Device Boot / Recovery Root-ADB Ramdisk Patcher (`patch-boot-adb`)
 *
 * Unpacks an Android `boot.img` or `recovery.img` (preserving MediaTek MT8125
 * 512-byte `0x58881688` `ROOTFS`/`RECOVERY` inner AND outer headers + exact
 * `dsize` lengths, page alignment, board name, kernel cmdline, and SHA-1 ID!),
 * patches `default.prop` inside the gzip `newc` (`070701`) cpio ramdisk so:
 *   ro.secure=0
 *   ro.adb.secure=0
 *   ro.debuggable=1
 *   persist.sys.usb.config=mtp,adb
 * giving early-init root `adb shell` unbrick insurance on any boot or recovery!
 * ============================================================================ */

/* Compact FIPS 180-4 SHA-1 used by Android `mkbootimg` for `boot_img_hdr.id[]` */
typedef struct {
    uint32_t state[5];
    uint64_t count;
    uint8_t  buffer[64];
} sha1_ctx_t;

static uint32_t rol32(uint32_t v, int b) { return (v << b) | (v >> (32 - b)); }

static void sha1_transform(uint32_t state[5], const uint8_t block[64]) {
    uint32_t w[80];
    for (int i = 0; i < 16; i++) {
        w[i] = ((uint32_t)block[i * 4] << 24) | ((uint32_t)block[i * 4 + 1] << 16) |
               ((uint32_t)block[i * 4 + 2] << 8) | ((uint32_t)block[i * 4 + 3]);
    }
    for (int i = 16; i < 80; i++) w[i] = rol32(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    uint32_t a = state[0], b = state[1], c = state[2], d = state[3], e = state[4];
    for (int i = 0; i < 80; i++) {
        uint32_t f, k;
        if (i < 20)      { f = (b & c) | ((~b) & d);          k = 0x5A827999U; }
        else if (i < 40) { f = b ^ c ^ d;                     k = 0x6ED9EBA1U; }
        else if (i < 60) { f = (b & c) | (b & d) | (c & d);   k = 0x8F1BBCDCU; }
        else             { f = b ^ c ^ d;                     k = 0xCA62C1D6U; }
        uint32_t t = rol32(a, 5) + f + e + k + w[i];
        e = d; d = c; c = rol32(b, 30); b = a; a = t;
    }
    state[0] += a; state[1] += b; state[2] += c; state[3] += d; state[4] += e;
}

static void sha1_init(sha1_ctx_t *ctx) {
    ctx->state[0] = 0x67452301U; ctx->state[1] = 0xEFCDAB89U;
    ctx->state[2] = 0x98BADCFEU; ctx->state[3] = 0x10325476U;
    ctx->state[4] = 0xC3D2E1F0U; ctx->count = 0;
}

static void sha1_update(sha1_ctx_t *ctx, const uint8_t *data, size_t len) {
    size_t idx = (size_t)((ctx->count >> 3) & 63U);
    ctx->count += (uint64_t)len << 3;
    size_t i = 0;
    if (idx > 0 && idx + len >= 64) {
        memcpy(ctx->buffer + idx, data, 64 - idx);
        sha1_transform(ctx->state, ctx->buffer);
        i = 64 - idx;
        idx = 0;
    }
    for (; i + 64 <= len; i += 64) sha1_transform(ctx->state, data + i);
    if (i < len) memcpy(ctx->buffer + idx, data + i, len - i);
}

static void sha1_final(sha1_ctx_t *ctx, uint8_t digest[20]) {
    uint8_t bits[8];
    for (int i = 0; i < 8; i++) bits[i] = (uint8_t)(ctx->count >> ((7 - i) * 8));
    uint8_t pad = 0x80;
    sha1_update(ctx, &pad, 1);
    uint8_t zero = 0x00;
    while (((ctx->count >> 3) & 63U) != 56U) sha1_update(ctx, &zero, 1);
    sha1_update(ctx, bits, 8);
    for (int i = 0; i < 5; i++) {
        digest[i * 4]     = (uint8_t)(ctx->state[i] >> 24);
        digest[i * 4 + 1] = (uint8_t)(ctx->state[i] >> 16);
        digest[i * 4 + 2] = (uint8_t)(ctx->state[i] >> 8);
        digest[i * 4 + 3] = (uint8_t)(ctx->state[i]);
    }
}

typedef struct {
    uint8_t *data;
    size_t   len;
    size_t   cap;
} dynbuf_t;

static int dynbuf_append(dynbuf_t *b, const void *src, size_t n) {
    if (n == 0) return 0;
    if (b->len + n > b->cap) {
        size_t nc = b->cap ? b->cap * 2 : 65536;
        while (nc < b->len + n) nc *= 2;
        uint8_t *np = (uint8_t *)realloc(b->data, nc);
        if (!np) return -1;
        b->data = np;
        b->cap = nc;
    }
    memcpy(b->data + b->len, src, n);
    b->len += n;
    return 0;
}

static int gzip_decompress_mem(const uint8_t *in, size_t in_len, dynbuf_t *out) {
    z_stream zs;
    memset(&zs, 0, sizeof(zs));
    if (inflateInit2(&zs, 16 + MAX_WBITS) != Z_OK) return -1;
    zs.next_in = (Bytef *)in;
    zs.avail_in = (uInt)in_len;
    uint8_t chunk[32768];
    int ret;
    do {
        zs.next_out = chunk;
        zs.avail_out = sizeof(chunk);
        ret = inflate(&zs, Z_NO_FLUSH);
        if (ret != Z_OK && ret != Z_STREAM_END) {
            inflateEnd(&zs);
            return -1;
        }
        size_t have = sizeof(chunk) - zs.avail_out;
        if (dynbuf_append(out, chunk, have) != 0) {
            inflateEnd(&zs);
            return -1;
        }
    } while (ret != Z_STREAM_END);
    inflateEnd(&zs);
    return 0;
}

static int gzip_compress_mem(const uint8_t *in, size_t in_len, dynbuf_t *out) {
    z_stream zs;
    memset(&zs, 0, sizeof(zs));
    if (deflateInit2(&zs, 9, Z_DEFLATED, 16 + MAX_WBITS, 8, Z_DEFAULT_STRATEGY) != Z_OK) return -1;
    zs.next_in = (Bytef *)in;
    zs.avail_in = (uInt)in_len;
    uint8_t chunk[32768];
    int ret;
    do {
        zs.next_out = chunk;
        zs.avail_out = sizeof(chunk);
        ret = deflate(&zs, Z_FINISH);
        if (ret == Z_STREAM_ERROR) {
            deflateEnd(&zs);
            return -1;
        }
        size_t have = sizeof(chunk) - zs.avail_out;
        if (dynbuf_append(out, chunk, have) != 0) {
            deflateEnd(&zs);
            return -1;
        }
    } while (zs.avail_out == 0);
    deflateEnd(&zs);
    return 0;
}

static uint32_t parse_hex8(const char *p) {
    char tmp[9];
    memcpy(tmp, p, 8);
    tmp[8] = '\0';
    return (uint32_t)strtoul(tmp, NULL, 16);
}

static void patch_default_prop_content(const uint8_t *orig, size_t orig_len, dynbuf_t *out) {
    char *copy = (char *)malloc(orig_len + 1);
    if (!copy) return;
    memcpy(copy, orig, orig_len);
    copy[orig_len] = '\0';

    /* Keep non-conflicting lines from factory default.prop, then append unlocked root ADB keys */
    char *save = NULL;
    for (char *ln = strtok_r(copy, "\n", &save); ln; ln = strtok_r(NULL, "\n", &save)) {
        size_t l = strlen(ln);
        while (l > 0 && ln[l - 1] == '\r') ln[--l] = '\0';
        if (strncmp(ln, "ro.secure=", 10) == 0 ||
            strncmp(ln, "ro.adb.secure=", 14) == 0 ||
            strncmp(ln, "ro.debuggable=", 14) == 0 ||
            strncmp(ln, "persist.sys.usb.config=", 23) == 0) {
            continue;
        }
        dynbuf_append(out, ln, strlen(ln));
        dynbuf_append(out, "\n", 1);
    }
    const char *injected =
        "# Patched by Kobo Arc 7 Freedom Anti-Brick Root-ADB Engine\n"
        "ro.secure=0\n"
        "ro.adb.secure=0\n"
        "ro.allow.mock.location=1\n"
        "ro.debuggable=1\n"
        "persist.sys.usb.config=mtp,adb\n";
    dynbuf_append(out, injected, strlen(injected));
    free(copy);
}

static int patch_cpio_newc(const uint8_t *in, size_t in_len, dynbuf_t *out) {
    size_t pos = 0;
    int patched_prop = 0;
    const uint8_t zeros[4] = {0, 0, 0, 0};

    while (pos + 110 <= in_len) {
        if (memcmp(in + pos, "070701", 6) != 0 && memcmp(in + pos, "070702", 6) != 0) {
            return -1;
        }
        char hdr[111];
        memcpy(hdr, in + pos, 110);
        hdr[110] = '\0';

        uint32_t filesize = parse_hex8(hdr + 54);
        uint32_t namesize = parse_hex8(hdr + 94);
        size_t name_off = pos + 110;
        if (name_off + namesize > in_len) return -1;

        const char *entry_name = (const char *)(in + name_off);
        size_t data_off = (name_off + namesize + 3U) & ~3U;
        if (data_off + filesize > in_len) return -1;
        size_t next_pos = (data_off + filesize + 3U) & ~3U;

        if (strcmp(entry_name, "TRAILER!!!") == 0) {
            /* If ramdisk lacked default.prop, emit one cleanly right before TRAILER!!! */
            if (!patched_prop) {
                dynbuf_t prop = {0};
                patch_default_prop_content((const uint8_t *)"", 0, &prop);
                const char *pname = "default.prop";
                uint32_t pnsz = (uint32_t)strlen(pname) + 1;
                char newhdr[112];
                snprintf(newhdr, sizeof(newhdr),
                         "070701%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X",
                         300000U, 0100644U, 0U, 0U, 1U, 0U, (uint32_t)prop.len,
                         0U, 0U, 0U, 0U, pnsz, 0U);
                dynbuf_append(out, newhdr, 110);
                dynbuf_append(out, pname, pnsz);
                size_t pad1 = ((110 + pnsz + 3U) & ~3U) - (110 + pnsz);
                dynbuf_append(out, zeros, pad1);
                dynbuf_append(out, prop.data, prop.len);
                size_t pad2 = ((prop.len + 3U) & ~3U) - prop.len;
                dynbuf_append(out, zeros, pad2);
                free(prop.data);
                patched_prop = 1;
            }
            dynbuf_append(out, in + pos, (next_pos <= in_len ? next_pos : in_len) - pos);
            break;
        }

        if (strcmp(entry_name, "default.prop") == 0 || strcmp(entry_name, "./default.prop") == 0) {
            dynbuf_t prop = {0};
            patch_default_prop_content(in + data_off, filesize, &prop);
            char newsz[9];
            snprintf(newsz, sizeof(newsz), "%08X", (unsigned int)prop.len);
            memcpy(hdr + 54, newsz, 8);

            dynbuf_append(out, hdr, 110);
            dynbuf_append(out, in + name_off, namesize);
            size_t pad1 = data_off - (name_off + namesize);
            dynbuf_append(out, zeros, pad1);
            dynbuf_append(out, prop.data, prop.len);
            size_t pad2 = ((prop.len + 3U) & ~3U) - prop.len;
            dynbuf_append(out, zeros, pad2);
            free(prop.data);
            patched_prop = 1;
        } else {
            dynbuf_append(out, in + pos, next_pos - pos);
        }
        pos = next_pos;
    }
    return patched_prop ? 0 : -1;
}

static int cmd_patch_boot_adb(const char *in_path, const char *out_path) {
    FILE *fp = fopen(in_path, "rb");
    if (!fp) {
        fprintf(stderr, "ERROR: cannot open %s: %s\n", in_path, strerror(errno));
        return 1;
    }
    fseek(fp, 0, SEEK_END);
    long fsz = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    if (fsz < 4096) { fclose(fp); return 1; }

    uint8_t *img = (uint8_t *)malloc((size_t)fsz);
    if (!img) { fclose(fp); return 1; }
    if (fread(img, 1, (size_t)fsz, fp) != (size_t)fsz) { free(img); fclose(fp); return 1; }
    fclose(fp);

    size_t outer_off = 0;
    if (memcmp(img, "ANDROID!", 8) != 0) {
        if ((size_t)fsz > 512 + sizeof(android_boot_hdr_t) && memcmp(img + 512, "ANDROID!", 8) == 0) {
            outer_off = 512;
        } else {
            fprintf(stderr, "ERROR: %s is not a valid Android/MT8125 boot image\n", in_path);
            free(img);
            return 1;
        }
    }

    android_boot_hdr_t hdr;
    memcpy(&hdr, img + outer_off, sizeof(hdr));
    uint32_t ps = hdr.page_size ? hdr.page_size : 2048;

    size_t k_off = outer_off + ps;
    size_t k_aligned = ((size_t)hdr.kernel_size + ps - 1) & ~((size_t)ps - 1);
    size_t r_off = k_off + k_aligned;
    size_t r_aligned = ((size_t)hdr.ramdisk_size + ps - 1) & ~((size_t)ps - 1);
    size_t s_off = r_off + r_aligned;
    size_t s_aligned = ((size_t)hdr.second_size + ps - 1) & ~((size_t)ps - 1);

    if (r_off + hdr.ramdisk_size > (size_t)fsz) {
        fprintf(stderr, "ERROR: boot image truncated before ramdisk end\n");
        free(img);
        return 1;
    }

    /* Check for inner 512-byte MediaTek `part_hdr_t` (`0x58881688` = 88 16 88 58 LE) */
    const uint8_t *ramdisk_raw = img + r_off;
    size_t ramdisk_len = hdr.ramdisk_size;
    int has_mtk_ramdisk_hdr = 0;
    uint8_t mtk_ramdisk_hdr[512];
    if (ramdisk_len > 512 &&
        ramdisk_raw[0] == 0x88 && ramdisk_raw[1] == 0x16 &&
        ramdisk_raw[2] == 0x88 && ramdisk_raw[3] == 0x58) {
        has_mtk_ramdisk_hdr = 1;
        memcpy(mtk_ramdisk_hdr, ramdisk_raw, 512);
        ramdisk_raw += 512;
        ramdisk_len -= 512;
    }

    dynbuf_t cpio_in = {0}, cpio_out = {0}, gz_out = {0}, final_ramdisk = {0};
    if (gzip_decompress_mem(ramdisk_raw, ramdisk_len, &cpio_in) != 0) {
        fprintf(stderr, "ERROR: failed inflating gzip ramdisk\n");
        free(img);
        return 1;
    }
    if (patch_cpio_newc(cpio_in.data, cpio_in.len, &cpio_out) != 0) {
        fprintf(stderr, "ERROR: failed patching cpio ramdisk default.prop\n");
        free(cpio_in.data); free(cpio_out.data); free(img);
        return 1;
    }
    if (gzip_compress_mem(cpio_out.data, cpio_out.len, &gz_out) != 0) {
        fprintf(stderr, "ERROR: failed compressing patched ramdisk\n");
        free(cpio_in.data); free(cpio_out.data); free(gz_out.data); free(img);
        return 1;
    }

    if (has_mtk_ramdisk_hdr) {
        uint32_t new_dsize = (uint32_t)gz_out.len;
        mtk_ramdisk_hdr[4] = (uint8_t)(new_dsize & 0xFF);
        mtk_ramdisk_hdr[5] = (uint8_t)((new_dsize >> 8) & 0xFF);
        mtk_ramdisk_hdr[6] = (uint8_t)((new_dsize >> 16) & 0xFF);
        mtk_ramdisk_hdr[7] = (uint8_t)((new_dsize >> 24) & 0xFF);
        dynbuf_append(&final_ramdisk, mtk_ramdisk_hdr, 512);
    }
    dynbuf_append(&final_ramdisk, gz_out.data, gz_out.len);

    hdr.ramdisk_size = (uint32_t)final_ramdisk.len;

    /* Recompute authentic AOSP `mkbootimg` SHA-1 `hdr.id[]` */
    sha1_ctx_t s1;
    sha1_init(&s1);
    sha1_update(&s1, img + k_off, hdr.kernel_size);
    sha1_update(&s1, (const uint8_t *)&hdr.kernel_size, sizeof(hdr.kernel_size));
    sha1_update(&s1, final_ramdisk.data, hdr.ramdisk_size);
    sha1_update(&s1, (const uint8_t *)&hdr.ramdisk_size, sizeof(hdr.ramdisk_size));
    if (hdr.second_size > 0 && s_off + hdr.second_size <= (size_t)fsz) {
        sha1_update(&s1, img + s_off, hdr.second_size);
    }
    sha1_update(&s1, (const uint8_t *)&hdr.second_size, sizeof(hdr.second_size));
    memset(hdr.id, 0, sizeof(hdr.id));
    sha1_final(&s1, (uint8_t *)hdr.id);

    FILE *outf = fopen(out_path, "wb");
    if (!outf) {
        fprintf(stderr, "ERROR: cannot open %s for writing\n", out_path);
        free(cpio_in.data); free(cpio_out.data); free(gz_out.data); free(final_ramdisk.data); free(img);
        return 1;
    }

    uint8_t *zero_page = (uint8_t *)calloc(1, ps);
    if (outer_off == 512) {
        size_t new_r_aligned = (final_ramdisk.len + ps - 1) & ~((size_t)ps - 1);
        uint32_t outer_sz = (uint32_t)(ps + k_aligned + new_r_aligned + s_aligned);
        img[4] = (uint8_t)(outer_sz & 0xFF);
        img[5] = (uint8_t)((outer_sz >> 8) & 0xFF);
        img[6] = (uint8_t)((outer_sz >> 16) & 0xFF);
        img[7] = (uint8_t)((outer_sz >> 24) & 0xFF);
        fwrite(img, 1, 512, outf);
    }

    fwrite(&hdr, 1, sizeof(hdr), outf);
    fwrite(zero_page, 1, ps - sizeof(hdr), outf);

    fwrite(img + k_off, 1, hdr.kernel_size, outf);
    if (k_aligned > hdr.kernel_size) fwrite(zero_page, 1, k_aligned - hdr.kernel_size, outf);

    size_t new_r_aligned = (final_ramdisk.len + ps - 1) & ~((size_t)ps - 1);
    fwrite(final_ramdisk.data, 1, final_ramdisk.len, outf);
    if (new_r_aligned > final_ramdisk.len) fwrite(zero_page, 1, new_r_aligned - final_ramdisk.len, outf);

    if (hdr.second_size > 0 && s_off + hdr.second_size <= (size_t)fsz) {
        fwrite(img + s_off, 1, hdr.second_size, outf);
        if (s_aligned > hdr.second_size) fwrite(zero_page, 1, s_aligned - hdr.second_size, outf);
    }

    fclose(outf);
    free(zero_page);
    free(cpio_in.data); free(cpio_out.data); free(gz_out.data); free(final_ramdisk.data); free(img);

    printf("PATCH_BOOT_ADB=OK MTK_RAMDISK_HDR=%s OUTER_MTK=%zu NEW_RAMDISK_SIZE=%u\n",
           has_mtk_ramdisk_hdr ? "YES" : "NO", outer_off, hdr.ramdisk_size);
    fflush(stdout);
    return 0;
}

/* ============================================================================
 * 64 GB / 128 GB / 256 GB MicroSDXC `FAT32` + `1 MiB`-Aligned MBR Formatter
 * ============================================================================ */

#ifndef BLKRRPART
#define BLKRRPART _IO(0x12, 95)
#endif

static void write_le16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)(v & 0xFFU);
    p[1] = (uint8_t)((v >> 8) & 0xFFU);
}

static void write_le32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xFFU);
    p[1] = (uint8_t)((v >> 8) & 0xFFU);
    p[2] = (uint8_t)((v >> 16) & 0xFFU);
    p[3] = (uint8_t)((v >> 24) & 0xFFU);
}

static int pwrite_full(int fd, const void *buf, size_t len, uint64_t offset) {
    const uint8_t *p = (const uint8_t *)buf;
    size_t done = 0;
    while (done < len) {
        ssize_t w = pwrite(fd, p + done, len - done, (off_t)(offset + done));
        if (w <= 0) {
            if (w < 0 && errno == EINTR) continue;
            return -1;
        }
        done += (size_t)w;
    }
    return 0;
}

static void make_fat_label11(const char *in, char out11[11]) {
    memset(out11, ' ', 11);
    if (!in || !in[0]) in = "ARC7_SDXC";
    size_t j = 0;
    for (size_t i = 0; in[i] && j < 11; i++) {
        unsigned char c = (unsigned char)in[i];
        if (c >= 'a' && c <= 'z') c = (unsigned char)(c - 'a' + 'A');
        if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
            out11[j++] = (char)c;
        }
    }
    if (j == 0) memcpy(out11, "ARC7_SDXC  ", 11);
}

static int format_fat32_region(int fd, uint64_t base_offset, uint64_t byte_len, const char *label) {
    const uint32_t bps = 512;
    uint64_t total_sectors64 = byte_len / bps;
    if (total_sectors64 < 66000ULL) {
        fprintf(stderr, "ERROR: target region too small for strict FAT32 (%llu bytes; need >= 34 MB)\n",
                (unsigned long long)byte_len);
        return 1;
    }
    if (total_sectors64 > 0xFFFFFFFFULL) {
        total_sectors64 = 0xFFFFFFFFULL;
    }
    uint32_t total_sectors = (uint32_t)total_sectors64;

    uint32_t spc;
    if (byte_len < 512ULL * 1048576ULL)           spc = 1;
    else if (byte_len < 8ULL * 1073741824ULL)     spc = 8;
    else if (byte_len < 32ULL * 1073741824ULL)    spc = 16;
    else if (byte_len <= 256ULL * 1073741824ULL)  spc = 64;
    else                                          spc = 128;

    const uint16_t reserved_sectors = 32;
    const uint8_t num_fats = 2;

    uint32_t fat_sz32 = 128;
    uint32_t data_clusters = 0;
    for (int iter = 0; iter < 8; iter++) {
        uint64_t meta = (uint64_t)reserved_sectors + (uint64_t)num_fats * (uint64_t)fat_sz32;
        if (total_sectors <= meta + spc * 2) return 1;
        data_clusters = (uint32_t)((total_sectors - meta) / spc);
        uint32_t needed = (uint32_t)(((uint64_t)(data_clusters + 2) * 4ULL + (bps - 1)) / bps);
        needed = (needed + 7U) & ~7U;
        if (needed == fat_sz32) break;
        fat_sz32 = needed;
    }

    if (data_clusters < 65525U) {
        fprintf(stderr, "ERROR: cluster count %u < 65525 minimum for FAT32\n", data_clusters);
        return 1;
    }

    char vol_label[11];
    make_fat_label11(label, vol_label);
    uint32_t vol_id = (uint32_t)time(NULL) ^ 0xA7C71430U;

    uint8_t bpb[512];
    memset(bpb, 0, sizeof(bpb));
    bpb[0] = 0xEB; bpb[1] = 0x58; bpb[2] = 0x90;
    memcpy(bpb + 3, "MSWIN4.1", 8);
    write_le16(bpb + 11, bps);
    bpb[13] = (uint8_t)spc;
    write_le16(bpb + 14, reserved_sectors);
    bpb[16] = num_fats;
    write_le16(bpb + 17, 0);
    write_le16(bpb + 19, 0);
    bpb[21] = 0xF8;
    write_le16(bpb + 22, 0);
    write_le16(bpb + 24, 63);
    write_le16(bpb + 26, 255);
    write_le32(bpb + 28, (uint32_t)(base_offset / bps));
    write_le32(bpb + 32, total_sectors);
    write_le32(bpb + 36, fat_sz32);
    write_le16(bpb + 40, 0);
    write_le16(bpb + 42, 0);
    write_le32(bpb + 44, 2);
    write_le16(bpb + 48, 1);
    write_le16(bpb + 50, 6);
    bpb[64] = 0x80;
    bpb[66] = 0x29;
    write_le32(bpb + 67, vol_id);
    memcpy(bpb + 71, vol_label, 11);
    memcpy(bpb + 82, "FAT32   ", 8);
    bpb[510] = 0x55; bpb[511] = 0xAA;

    uint8_t fsinfo[512];
    memset(fsinfo, 0, sizeof(fsinfo));
    write_le32(fsinfo + 0, 0x41615252U);
    write_le32(fsinfo + 484, 0x61417272U);
    write_le32(fsinfo + 488, data_clusters - 1U);
    write_le32(fsinfo + 492, 3U);
    write_le32(fsinfo + 508, 0xAA550000U);

    uint8_t *zbuf = (uint8_t *)calloc(1, 262144);
    if (!zbuf) return 1;
    if (pwrite_full(fd, zbuf, (size_t)reserved_sectors * bps, base_offset) != 0) { free(zbuf); return 1; }

    if (pwrite_full(fd, bpb, 512, base_offset + 0ULL * bps) != 0 ||
        pwrite_full(fd, fsinfo, 512, base_offset + 1ULL * bps) != 0 ||
        pwrite_full(fd, bpb, 512, base_offset + 6ULL * bps) != 0 ||
        pwrite_full(fd, fsinfo, 512, base_offset + 7ULL * bps) != 0) {
        free(zbuf);
        return 1;
    }

    uint64_t fat_bytes = (uint64_t)num_fats * (uint64_t)fat_sz32 * bps;
    uint64_t fat_start = base_offset + (uint64_t)reserved_sectors * bps;
    uint64_t rem = fat_bytes;
    uint64_t cur = fat_start;
    while (rem > 0) {
        size_t chunk = rem > 262144ULL ? 262144U : (size_t)rem;
        if (pwrite_full(fd, zbuf, chunk, cur) != 0) { free(zbuf); return 1; }
        cur += chunk;
        rem -= chunk;
    }

    uint8_t fat_head[12];
    write_le32(fat_head + 0, 0x0FFFFFF8U);
    write_le32(fat_head + 4, 0x0FFFFFFFU);
    write_le32(fat_head + 8, 0x0FFFFFFFU);
    if (pwrite_full(fd, fat_head, sizeof(fat_head), fat_start) != 0 ||
        pwrite_full(fd, fat_head, sizeof(fat_head), fat_start + (uint64_t)fat_sz32 * bps) != 0) {
        free(zbuf);
        return 1;
    }

    uint64_t data_start = fat_start + fat_bytes;
    size_t clus_bytes = (size_t)spc * bps;
    if (pwrite_full(fd, zbuf, clus_bytes, data_start) != 0) { free(zbuf); return 1; }
    uint8_t vol_dirent[32];
    memset(vol_dirent, 0, sizeof(vol_dirent));
    memcpy(vol_dirent, vol_label, 11);
    vol_dirent[11] = 0x08;
    if (pwrite_full(fd, vol_dirent, sizeof(vol_dirent), data_start) != 0) { free(zbuf); return 1; }

    free(zbuf);
    fsync(fd);
    printf("FORMAT_FAT32=OK CLUSTERS=%u CLUSTER_BYTES=%u FAT_SECTORS=%u TOTAL_MB=%llu\n",
           data_clusters, spc * bps, fat_sz32, (unsigned long long)(byte_len / 1048576ULL));
    fflush(stdout);
    return 0;
}

static int open_target_and_size(const char *path, int *out_fd, uint64_t *out_bytes) {
    int fd = open(path, O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "ERROR: cannot open %s for read/write: %s\n", path, strerror(errno));
        return -1;
    }
    uint64_t bytes = 0;
    struct stat st;
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode)) {
        bytes = (uint64_t)st.st_size;
    } else if (ioctl(fd, BLKGETSIZE64, &bytes) != 0 || bytes == 0) {
        off_t end = lseek(fd, 0, SEEK_END);
        if (end > 0) bytes = (uint64_t)end;
    }
    if (bytes == 0) {
        close(fd);
        fprintf(stderr, "ERROR: zero size on %s\n", path);
        return -1;
    }
    *out_fd = fd;
    *out_bytes = bytes;
    return 0;
}

static int cmd_format_fat32(const char *path, const char *label, int write_mbr) {
    int fd = -1;
    uint64_t total_bytes = 0;
    if (open_target_and_size(path, &fd, &total_bytes) != 0) return 1;

    uint64_t part_off = 0;
    uint64_t part_len = total_bytes;

    if (write_mbr) {
        const uint32_t start_lba = 2048U;
        if (total_bytes <= (uint64_t)start_lba * 512ULL + 36ULL * 1048576ULL) {
            close(fd);
            fprintf(stderr, "ERROR: SD card smaller than 36 MB\n");
            return 1;
        }
        uint64_t total_lba64 = total_bytes / 512ULL;
        if (total_lba64 > 0xFFFFFFFFULL) total_lba64 = 0xFFFFFFFFULL;
        uint32_t part_sectors = (uint32_t)(total_lba64 - start_lba);

        uint8_t wipe[17408];
        memset(wipe, 0, sizeof(wipe));
        if (pwrite_full(fd, wipe, sizeof(wipe), 0) != 0) { close(fd); return 1; }

        uint8_t mbr[512];
        memset(mbr, 0, sizeof(mbr));
        write_le32(mbr + 440, (uint32_t)time(NULL) ^ 0xA7C75D01U);
        uint8_t *pe = mbr + 446;
        pe[0] = 0x00;
        pe[1] = 0xFE; pe[2] = 0xFF; pe[3] = 0xFF;
        pe[4] = 0x0C; /* 0x0C = Win95 FAT32 LBA */
        pe[5] = 0xFE; pe[6] = 0xFF; pe[7] = 0xFF;
        write_le32(pe + 8, start_lba);
        write_le32(pe + 12, part_sectors);
        mbr[510] = 0x55; mbr[511] = 0xAA;
        if (pwrite_full(fd, mbr, sizeof(mbr), 0) != 0) { close(fd); return 1; }

        part_off = (uint64_t)start_lba * 512ULL;
        part_len = (uint64_t)part_sectors * 512ULL;
    }

    int rc = format_fat32_region(fd, part_off, part_len, label);
    if (write_mbr && rc == 0) {
        ioctl(fd, BLKFLSBUF, 0);
        ioctl(fd, BLKRRPART, 0);
    }
    sync();
    close(fd);
    return rc;
}

/* ======================== Kernel `SO_ORIGINAL_DST` Transparent USB NAT Relay ======================== */
typedef struct {
    int cfd;
    uint16_t proxy_port;
} nat_conn_arg_t;

static void *nat_relay_worker(void *argp) {
    nat_conn_arg_t *arg = (nat_conn_arg_t *)argp;
    int cfd = arg->cfd;
    uint16_t proxy_port = arg->proxy_port;
    free(arg);

    struct sockaddr_in orig;
    socklen_t olen = sizeof(orig);
    if (getsockopt(cfd, SOL_IP, SO_ORIGINAL_DST, &orig, &olen) != 0) {
        close(cfd);
        return NULL;
    }
    char dst_ip[INET_ADDRSTRLEN] = {0};
    inet_ntop(AF_INET, &orig.sin_addr, dst_ip, sizeof(dst_ip));
    int dst_port = ntohs(orig.sin_port);
    if (strcmp(dst_ip, "127.0.0.1") == 0) {
        close(cfd);
        return NULL;
    }

    int pfd = socket(AF_INET, SOCK_STREAM, 0);
    if (pfd < 0) {
        close(cfd);
        return NULL;
    }
    struct sockaddr_in psa;
    memset(&psa, 0, sizeof(psa));
    psa.sin_family = AF_INET;
    psa.sin_port = htons(proxy_port);
    psa.sin_addr.s_addr = htonl(0x7F000001U);
    if (connect(pfd, (struct sockaddr *)&psa, sizeof(psa)) != 0) {
        close(pfd);
        close(cfd);
        return NULL;
    }

    char req[160];
    int rl = snprintf(req, sizeof(req), "CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n\r\n",
                      dst_ip, dst_port, dst_ip, dst_port);
    if (send(pfd, req, (size_t)rl, MSG_NOSIGNAL) != rl) {
        close(pfd);
        close(cfd);
        return NULL;
    }

    char hdr[512];
    size_t used = 0;
    while (used + 1 < sizeof(hdr)) {
        ssize_t n = recv(pfd, hdr + used, 1, 0);
        if (n <= 0) { close(pfd); close(cfd); return NULL; }
        used += (size_t)n;
        hdr[used] = '\0';
        if (used >= 4 && strcmp(hdr + used - 4, "\r\n\r\n") == 0) break;
    }
    if (strncmp(hdr, "HTTP/1.1 200", 12) != 0 && strncmp(hdr, "HTTP/1.0 200", 12) != 0) {
        close(pfd);
        close(cfd);
        return NULL;
    }

    uint8_t *buf = (uint8_t *)malloc(32768);
    if (buf) {
        int maxfd = (cfd > pfd ? cfd : pfd) + 1;
        while (1) {
            fd_set rfds;
            FD_ZERO(&rfds);
            FD_SET(cfd, &rfds);
            FD_SET(pfd, &rfds);
            struct timeval tv = { .tv_sec = 90, .tv_usec = 0 };
            int sr = select(maxfd, &rfds, NULL, NULL, &tv);
            if (sr <= 0) break;
            if (FD_ISSET(cfd, &rfds)) {
                ssize_t n = recv(cfd, buf, 32768, 0);
                if (n <= 0 || send(pfd, buf, (size_t)n, MSG_NOSIGNAL) != n) break;
            }
            if (FD_ISSET(pfd, &rfds)) {
                ssize_t n = recv(pfd, buf, 32768, 0);
                if (n <= 0 || send(cfd, buf, (size_t)n, MSG_NOSIGNAL) != n) break;
            }
        }
        free(buf);
    }
    close(pfd);
    close(cfd);
    return NULL;
}

static int cmd_usb_nat_relay(uint16_t listen_port, uint16_t proxy_port) {
    signal(SIGPIPE, SIG_IGN);
    int sfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sfd < 0) return 1;
    int one = 1;
    setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons(listen_port);
    sa.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(sfd, (struct sockaddr *)&sa, sizeof(sa)) != 0 || listen(sfd, 64) != 0) {
        close(sfd);
        return 1;
    }
    printf("USB_NAT_RELAY_READY port=%u proxy=%u\n", (unsigned)listen_port, (unsigned)proxy_port);
    fflush(stdout);
    while (1) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) continue;
        nat_conn_arg_t *arg = (nat_conn_arg_t *)malloc(sizeof(*arg));
        if (!arg) { close(cfd); continue; }
        arg->cfd = cfd;
        arg->proxy_port = proxy_port;
        pthread_t th;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        pthread_attr_setstacksize(&attr, 262144);
        if (pthread_create(&th, &attr, nat_relay_worker, arg) != 0) {
            close(cfd);
            free(arg);
        }
        pthread_attr_destroy(&attr);
    }
}

int main(int argc, char **argv) {
    if (argc >= 3 && strcmp(argv[1], "sha256") == 0) {
        uint64_t lim = (argc >= 4) ? strtoull(argv[3], NULL, 0) : 0;
        return cmd_sha256(argv[2], lim);
    }
    if (argc >= 4 && strcmp(argv[1], "dump-gz") == 0) {
        uint64_t off = (argc >= 5) ? strtoull(argv[4], NULL, 0) : 0;
        uint64_t maxb = (argc >= 6) ? strtoull(argv[5], NULL, 0) : 0;
        return cmd_dump_gz(argv[2], argv[3], off, maxb);
    }
    if (argc >= 4 && strcmp(argv[1], "flash") == 0) {
        uint64_t off = (argc >= 5) ? strtoull(argv[4], NULL, 0) : 0;
        uint64_t maxb = (argc >= 6) ? strtoull(argv[5], NULL, 0) : 0;
        return cmd_flash_image(argv[2], argv[3], off, maxb);
    }
    if (argc >= 3 && strcmp(argv[1], "inspect-boot") == 0) {
        return cmd_inspect_boot(argv[2]);
    }
    if (argc >= 4 && strcmp(argv[1], "patch-boot-adb") == 0) {
        return cmd_patch_boot_adb(argv[2], argv[3]);
    }
    if (argc >= 3 && strcmp(argv[1], "format-fat32") == 0) {
        const char *lbl = (argc >= 4) ? argv[3] : "ARC7_SDXC";
        return cmd_format_fat32(argv[2], lbl, 0);
    }
    if (argc >= 3 && strcmp(argv[1], "format-sdxc-card") == 0) {
        const char *lbl = (argc >= 4) ? argv[3] : "ARC7_SDXC";
        return cmd_format_fat32(argv[2], lbl, 1);
    }
    if (argc >= 4 && strcmp(argv[1], "usb-nat-relay") == 0) {
        return cmd_usb_nat_relay((uint16_t)atoi(argv[2]), (uint16_t)atoi(argv[3]));
    }
    fprintf(stderr,
            "arc7tool usage:\n"
            "  arc7tool sha256 <path> [limit_bytes]\n"
            "  arc7tool dump-gz <src_dev_or_file> <dst.img.gz> [offset_bytes] [max_bytes]\n"
            "  arc7tool flash <src.img_or_file> <dst_dev_or_file> [offset_bytes]\n"
            "  arc7tool inspect-boot <boot_or_recovery.img>\n"
            "  arc7tool patch-boot-adb <in_boot.img> <out_patched.img>\n"
            "  arc7tool format-fat32 <partition_or_img> [LABEL]\n"
            "  arc7tool format-sdxc-card <whole_mmcblk1_or_img> [LABEL]\n"
            "  arc7tool usb-nat-relay <listen_port> <proxy_port>\n");
    return 1;
}
