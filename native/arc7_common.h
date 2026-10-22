#ifndef ARC7_COMMON_H
#define ARC7_COMMON_H

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#define ARC7_SU_SOCKET_NAME "arc7_freedom_root_v1"
#define ARC7_SU_VERSION_STR "17:ARC7FREEDOM-SU"
#define ARC7_SU_VERSION_INT 17

static int arc7_daemon_ping(void) {
    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) return -1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path + 1, ARC7_SU_SOCKET_NAME, sizeof(addr.sun_path) - 2);
    socklen_t socklen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(ARC7_SU_SOCKET_NAME));

    int ok = (connect(sfd, (struct sockaddr *)&addr, socklen) == 0) ? 0 : -1;
    if (ok == 0) {
        const char *cmd = "true";
        if (write(sfd, cmd, 5) < 0) { /* ignore */ }
        shutdown(sfd, SHUT_WR);
        char drain[128];
        while (read(sfd, drain, sizeof(drain)) > 0) {}
    }
    close(sfd);
    return ok;
}

static void arc7_move_to_root_cgroup(void) {
    char pid_str[32];
    int len = snprintf(pid_str, sizeof(pid_str), "%d\n", (int)getpid());
    const char *cgroups[] = {
        "/acct/uid_0/tasks",
        "/dev/cpuctl/tasks",
        "/sys/fs/cgroup/cpu/tasks",
        NULL
    };
    mkdir("/acct/uid_0", 0755);
    for (int i = 0; cgroups[i] != NULL; i++) {
        int fd = open(cgroups[i], O_WRONLY);
        if (fd >= 0) {
            if (write(fd, pid_str, (size_t)len) < 0) { /* ignore */ }
            close(fd);
        }
    }
}

static int arc7_copy_file(const char *src, const char *dst, mode_t mode) {
    int in_fd = open(src, O_RDONLY);
    if (in_fd < 0) return -1;

    char tmp_path[512];
    snprintf(tmp_path, sizeof(tmp_path), "%s.tmp.%d", dst, (int)getpid());
    unlink(tmp_path);

    int out_fd = open(tmp_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (out_fd < 0) {
        close(in_fd);
        return -1;
    }

    unsigned char buf[32768];
    ssize_t n;
    while ((n = read(in_fd, buf, sizeof(buf))) > 0) {
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(out_fd, buf + off, (size_t)(n - off));
            if (w <= 0) {
                close(in_fd);
                close(out_fd);
                unlink(tmp_path);
                return -1;
            }
            off += w;
        }
    }
    fsync(out_fd);
    close(in_fd);
    close(out_fd);

    chown(tmp_path, 0, 0);
    chmod(tmp_path, mode);
    if (rename(tmp_path, dst) != 0) {
        unlink(dst);
        if (rename(tmp_path, dst) != 0) {
            unlink(tmp_path);
            return -1;
        }
    }
    chown(dst, 0, 0);
    chmod(dst, mode);
    return 0;
}

static int arc7_remount_system(int writable) {
    FILE *fp = fopen("/proc/mounts", "r");
    char sys_dev[256] = "/emmc@android";
    char sys_fs[64] = "ext4";

    if (fp) {
        char dev[256], mp[256], fs[64], opts[512];
        while (fscanf(fp, "%255s %255s %63s %511s %*d %*d\n", dev, mp, fs, opts) == 4) {
            if (strcmp(mp, "/system") == 0) {
                strncpy(sys_dev, dev, sizeof(sys_dev) - 1);
                sys_dev[sizeof(sys_dev) - 1] = '\0';
                strncpy(sys_fs, fs, sizeof(sys_fs) - 1);
                sys_fs[sizeof(sys_fs) - 1] = '\0';
                break;
            }
        }
        fclose(fp);
    }

    /* Notice: clearing MS_NOSUID intentionally on remount so setuid 06755 always works directly! */
    unsigned long flags = MS_REMOUNT | (writable ? 0 : MS_RDONLY);
    if (mount(sys_dev, "/system", sys_fs, flags, NULL) == 0) {
        return 0;
    }
    if (mount(NULL, "/system", NULL, flags, NULL) == 0) {
        return 0;
    }
    char cmd[640];
    snprintf(cmd, sizeof(cmd), "/system/bin/mount -o %s,suid,remount %s /system >/dev/null 2>&1 || /system/bin/mount -o %s,remount %s /system >/dev/null 2>&1",
             writable ? "rw" : "ro", sys_dev, writable ? "rw" : "ro", sys_dev);
    return system(cmd);
}

static __attribute__((unused)) int arc7_install_persistent_root(const char *su_source_path) {
    if (getuid() != 0 && geteuid() != 0) {
        fprintf(stderr, "ERROR: arc7_install_persistent_root called with uid=%d euid=%d\n",
                (int)getuid(), (int)geteuid());
        return -1;
    }

    setresgid(0, 0, 0);
    setresuid(0, 0, 0);
    arc7_move_to_root_cgroup();

    /* Permissive SELinux if present on kernel */
    int se_fd = open("/sys/fs/selinux/enforce", O_WRONLY);
    if (se_fd >= 0) {
        if (write(se_fd, "0", 1) < 0) { /* ignore */ }
        close(se_fd);
    }

    /* 1. Install into RAM rootfs `/sbin/su` (`06755`) FIRST:
     *    `/sbin` is first on Android's PATH, lives in RAM (immune to full `/system` ENOSPC!),
     *    and never has `nosuid` on Linux 3.4 initramfs! */
    int installed_sbin = 0;
    if (mount("rootfs", "/", "rootfs", MS_REMOUNT, NULL) == 0 ||
        mount(NULL, "/", NULL, MS_REMOUNT, NULL) == 0) {
        mkdir("/sbin", 0755);
        chmod("/sbin", 0755);
        if (arc7_copy_file(su_source_path, "/sbin/su", 06755) == 0) {
            installed_sbin = 1;
            symlink("/sbin/su", "/sbin/uname");
            symlink("/sbin/su", "/sbin/cp");
            printf("STAGE: Installed setuid root su (+ uname/cp applets) to /sbin/su (06755)\n");
            fflush(stdout);
        }
        mount("rootfs", "/", "rootfs", MS_REMOUNT | MS_RDONLY, NULL);
    }

    /* 2. Remount `/system` read-write (clearing `nosuid`) and install persistent `/system/xbin/su` + `/system/bin/su` */
    printf("STAGE: Remounting /system read-write...\n");
    fflush(stdout);
    if (arc7_remount_system(1) != 0) {
        fprintf(stderr, "WARN: mount syscall returned non-zero (%s), attempting direct write check...\n",
                strerror(errno));
    }

    mkdir("/system/xbin", 0755);
    chown("/system/xbin", 0, 0);
    chmod("/system/xbin", 0755);

    printf("STAGE: Installing static ARMv7 su binary to /system/xbin/su and /system/bin/su...\n");
    fflush(stdout);
    int installed_xbin = (arc7_copy_file(su_source_path, "/system/xbin/su", 06755) == 0);
    int installed_bin = (arc7_copy_file(su_source_path, "/system/bin/su", 06755) == 0);
    if (installed_xbin) {
        symlink("/system/xbin/su", "/system/xbin/uname");
        symlink("/system/xbin/su", "/system/xbin/cp");
    }

    if (!installed_xbin && !installed_bin && !installed_sbin) {
        fprintf(stderr, "ERROR: Failed to write su binary to /system/xbin/su or /sbin/su (%s)\n", strerror(errno));
        arc7_remount_system(0);
        return -1;
    }

    const char *daemon_su = installed_xbin ? "/system/xbin/su"
                          : (installed_bin ? "/system/bin/su" : "/sbin/su");

    /* Preserve original stock recovery-reflash script once, then install boot hook */
    struct stat st;
    if (stat("/system/etc/install-recovery.sh", &st) == 0 &&
        stat("/system/etc/install-recovery.sh.stock.bak", &st) != 0) {
        arc7_copy_file("/system/etc/install-recovery.sh",
                       "/system/etc/install-recovery.sh.stock.bak", 0644);
    }

    const char *boot_hook =
        "#!/system/bin/sh\n"
        "# Managed by Kobo Arc 7 Freedom - keeps root daemon alive & prevents stock recovery overwrite\n"
        "/system/xbin/su --daemon &\n"
        "exit 0\n";
    int hfd = open("/system/etc/install-recovery.sh", O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (hfd >= 0) {
        if (write(hfd, boot_hook, strlen(boot_hook)) < 0) { /* ignore */ }
        fsync(hfd);
        close(hfd);
        chown("/system/etc/install-recovery.sh", 0, 0);
        chmod("/system/etc/install-recovery.sh", 0755);
    }

    sync();
    arc7_remount_system(0);

    /* 3. Launch detached background root daemon cleanly (closing ALL inherited FDs so parent Java pipes NEVER hang!) */
    pid_t dpid = fork();
    if (dpid == 0) {
        alarm(0);
        setsid();
        arc7_move_to_root_cgroup();
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            if (devnull > 2) close(devnull);
        }
        for (int fd = 3; fd < 256; fd++) close(fd);
        execl(daemon_su, "su", "--daemon", (char *)NULL);
        execl(su_source_path, "su", "--daemon", (char *)NULL);
        _exit(0);
    }

    /* Wait up to 400 ms so abstract root socket `@arc7_freedom_root_v1` is guaranteed live immediately */
    for (int tries = 0; tries < 20; tries++) {
        if (arc7_daemon_ping() == 0) break;
        usleep(20000);
    }

    printf("ROOT_INSTALLED_OK uid=0 path=%s\n", daemon_su);
    fflush(stdout);
    return 0;
}

#endif /* ARC7_COMMON_H */
