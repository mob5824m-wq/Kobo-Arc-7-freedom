#include "arc7_common.h"
#include <grp.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>

static void setup_root_credentials(void) {
    gid_t groups[] = {0, 1000, 1003, 1004, 1007, 1011, 1015, 1023, 1028, 3001, 3002, 3003};
    setgroups(sizeof(groups) / sizeof(groups[0]), groups);
    setresgid(0, 0, 0);
    setresuid(0, 0, 0);
    setenv("PATH", "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:/data/local/xbin:/data/local/bin:/usr/bin:/bin", 1);
    setenv("USER", "root", 1);
    setenv("LOGNAME", "root", 1);
    setenv("HOME", "/data", 1);
}

static void handle_daemon_client(int client_fd) {
    /* Protocol: reads null-terminated or EOF command string, runs via /system/bin/sh -c,
       streams stdout+stderr, writes trailing marker \n__ARC7_EXIT_<code>__\n */
    char cmd[16384];
    size_t total = 0;
    while (total + 1 < sizeof(cmd)) {
        ssize_t n = read(client_fd, cmd + total, sizeof(cmd) - 1 - total);
        if (n <= 0) break;
        total += (size_t)n;
        if (memchr(cmd, '\0', total) != NULL) break;
    }
    cmd[total] = '\0';

    setup_root_credentials();

    int pfd[2];
    if (pipe(pfd) != 0) {
        close(client_fd);
        _exit(1);
    }

    pid_t pid = fork();
    if (pid == 0) {
        close(pfd[0]);
        dup2(pfd[1], STDOUT_FILENO);
        dup2(pfd[1], STDERR_FILENO);
        close(pfd[1]);
        close(client_fd);
        if (cmd[0] == '\0') {
            execl("/system/bin/sh", "sh", (char *)NULL);
        } else {
            execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        }
        _exit(127);
    }

    close(pfd[1]);
    char buf[4096];
    ssize_t r;
    while ((r = read(pfd[0], buf, sizeof(buf))) > 0) {
        ssize_t off = 0;
        while (off < r) {
            ssize_t w = write(client_fd, buf + off, (size_t)(r - off));
            if (w <= 0) break;
            off += w;
        }
    }
    close(pfd[0]);

    int status = 0;
    waitpid(pid, &status, 0);
    int code = WIFEXITED(status) ? WEXITSTATUS(status) : 1;
    char trailer[64];
    int tlen = snprintf(trailer, sizeof(trailer), "\n__ARC7_EXIT_%d__\n", code);
    if (tlen > 0) {
        if (write(client_fd, trailer, (size_t)tlen) < 0) { /* ignore */ }
    }
    close(client_fd);
    _exit(0);
}

static int run_daemon(void) {
    setup_root_credentials();
    signal(SIGCHLD, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);

    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) return 1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    /* Abstract Linux UNIX domain socket: sun_path[0] == '\0' */
    strncpy(addr.sun_path + 1, ARC7_SU_SOCKET_NAME, sizeof(addr.sun_path) - 2);
    socklen_t socklen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(ARC7_SU_SOCKET_NAME));

    if (bind(sfd, (struct sockaddr *)&addr, socklen) != 0) {
        /* Daemon already running */
        close(sfd);
        return 0;
    }
    if (listen(sfd, 16) != 0) {
        close(sfd);
        return 1;
    }

    for (;;) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) {
            if (errno == EINTR) continue;
            usleep(100000);
            continue;
        }
        pid_t child = fork();
        if (child == 0) {
            close(sfd);
            handle_daemon_client(cfd);
        }
        close(cfd);
    }
}

static int exec_via_daemon(const char *cmd) {
    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) return -1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path + 1, ARC7_SU_SOCKET_NAME, sizeof(addr.sun_path) - 2);
    socklen_t socklen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(ARC7_SU_SOCKET_NAME));

    if (connect(sfd, (struct sockaddr *)&addr, socklen) != 0) {
        close(sfd);
        return -1;
    }

    const char *payload = (cmd && cmd[0]) ? cmd : "id";
    size_t len = strlen(payload) + 1;
    if (write(sfd, payload, len) < 0) {
        close(sfd);
        return -1;
    }
    shutdown(sfd, SHUT_WR);

    char acc[65536];
    char buf[4096];
    size_t tail_len = 0;
    ssize_t n;
    int exit_code = 0;

    while ((n = read(sfd, buf, sizeof(buf))) > 0) {
        if (tail_len + (size_t)n < sizeof(acc) - 1) {
            memcpy(acc + tail_len, buf, (size_t)n);
            tail_len += (size_t)n;
            acc[tail_len] = '\0';
        } else {
            /* Flush older half */
            size_t flush_n = tail_len - 128;
            if (write(STDOUT_FILENO, acc, flush_n) < 0) { /* ignore */ }
            memmove(acc, acc + flush_n, tail_len - flush_n);
            tail_len -= flush_n;
            memcpy(acc + tail_len, buf, (size_t)n);
            tail_len += (size_t)n;
            acc[tail_len] = '\0';
        }
    }
    close(sfd);

    char *marker = strstr(acc, "\n__ARC7_EXIT_");
    if (marker != NULL) {
        exit_code = atoi(marker + strlen("\n__ARC7_EXIT_"));
        *marker = '\0';
        tail_len = (size_t)(marker - acc);
    }
    if (tail_len > 0) {
        if (write(STDOUT_FILENO, acc, tail_len) < 0) { /* ignore */ }
    }
    return exit_code;
}

int main(int argc, char **argv) {
    const char *shell = "/system/bin/sh";
    char cmd_buf[16384];
    cmd_buf[0] = '\0';
    int has_cmd = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-v") == 0 || strcmp(argv[i], "--version") == 0) {
            printf("%s\n", ARC7_SU_VERSION_STR);
            return 0;
        }
        if (strcmp(argv[i], "-V") == 0) {
            printf("%d\n", ARC7_SU_VERSION_INT);
            return 0;
        }
        if (strcmp(argv[i], "--daemon") == 0 || strcmp(argv[i], "-d") == 0) {
            if (geteuid() != 0 && getuid() != 0) {
                fprintf(stderr, "su --daemon requires uid 0\n");
                return 1;
            }
            if (fork() > 0) return 0;
            setsid();
            return run_daemon();
        }
        if (strcmp(argv[i], "-s") == 0 || strcmp(argv[i], "--shell") == 0) {
            if (i + 1 < argc) shell = argv[++i];
            continue;
        }
        if (strcmp(argv[i], "-p") == 0 || strcmp(argv[i], "-m") == 0 ||
            strcmp(argv[i], "--preserve-environment") == 0 ||
            strcmp(argv[i], "-mm") == 0 || strcmp(argv[i], "--mount-master") == 0 ||
            strcmp(argv[i], "-") == 0 || strcmp(argv[i], "-l") == 0) {
            continue;
        }
        if (strcmp(argv[i], "-c") == 0 || strcmp(argv[i], "--command") == 0) {
            has_cmd = 1;
            for (int j = i + 1; j < argc; j++) {
                if (cmd_buf[0] != '\0') strncat(cmd_buf, " ", sizeof(cmd_buf) - strlen(cmd_buf) - 1);
                strncat(cmd_buf, argv[j], sizeof(cmd_buf) - strlen(cmd_buf) - 1);
            }
            break;
        }
        /* Ignore target user specification like "root" or "0" */
        if (strcmp(argv[i], "root") == 0 || strcmp(argv[i], "0") == 0) {
            continue;
        }
    }

    if (geteuid() == 0 || getuid() == 0) {
        setup_root_credentials();
        if (has_cmd) {
            execl(shell, "sh", "-c", cmd_buf, (char *)NULL);
            perror("execl");
            return 127;
        }
        /* Interactive shell or stdin pipe (`su` with script fed on stdin!) */
        execl(shell, "sh", (char *)NULL);
        perror("execl");
        return 127;
    }

    /* Fallback: if binary wasn't setuid or filesystem had nosuid, talk to root daemon! */
    if (!has_cmd && !isatty(STDIN_FILENO)) {
        /* Read piped stdin commands (`Runtime.getRuntime().exec("su")` writing to OutputStream!) */
        size_t total = 0;
        ssize_t n;
        while (total + 1 < sizeof(cmd_buf) &&
               (n = read(STDIN_FILENO, cmd_buf + total, sizeof(cmd_buf) - 1 - total)) > 0) {
            total += (size_t)n;
        }
        cmd_buf[total] = '\0';
        has_cmd = 1;
    }

    int rc = exec_via_daemon(has_cmd ? cmd_buf : "id");
    if (rc >= 0) return rc;

    fprintf(stderr, "su: permission denied (uid=%d euid=%d, root daemon not running)\n",
            (int)getuid(), (int)geteuid());
    return 1;
}
