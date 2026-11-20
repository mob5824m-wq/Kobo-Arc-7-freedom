#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>

static unsigned char received[4096];
static size_t used;
static int calls, fail_at, failure;
static ssize_t short_send(int fd, const void *buffer, size_t length, int flags) {
    (void)fd;
    assert(flags == MSG_NOSIGNAL);
    calls++;
    if (calls == fail_at) {
        if (failure == 0) return 0;
        errno = failure;
        return -1;
    }
    size_t count = length > 7 ? 7 : length;
    assert(used + count <= sizeof(received));
    memcpy(received + used, buffer, count);
    used += count;
    return (ssize_t)count;
}
#define send short_send
#include "arc7socket.h"
#undef send

int main(void) {
    unsigned char data[4096];
    for (size_t i = 0; i < sizeof(data); i++) data[i] = (unsigned char)(i % 251);
    for (int interrupted = 0; interrupted < 2; interrupted++) {
        used = 0; calls = 0; fail_at = interrupted ? 4 : 0; failure = EINTR;
        assert(arc7_send_all(42, data, sizeof(data)) == 0);
        assert(used == sizeof(data) && memcmp(data, received, used) == 0);
        assert(calls > 500);
    }
    for (int kind = 0; kind < 3; kind++) {
        used = 0; calls = 0; fail_at = 3; failure = kind == 0 ? 0 : kind == 1 ? EPIPE : EAGAIN;
        assert(arc7_send_all(42, data, sizeof(data)) == -1);
        assert(calls == 3 && used == 14); /* no spin on close/timeout */
    }
    calls = 0;
    assert(arc7_send_all(42, data, 0) == 0 && calls == 0);
    puts("PASS production native socket writer: 7-byte short sends + EINTR are byte-exact; zero/closed/timeout sends terminate without spinning");
    return 0;
}
