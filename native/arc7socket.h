#ifndef ARC7_SOCKET_H
#define ARC7_SOCKET_H

#include <errno.h>
#include <stddef.h>
#include <sys/socket.h>

/* A positive send() may be SHORT, including on a blocking loopback socket.
 * Dropping its tail breaks Content-Length/chunk framing and Android reports -1004.
 * Retry EINTR, but never spin on timeout/EAGAIN, a closed peer or a zero write. */
static int arc7_send_all(int fd, const void *buffer, size_t length) {
    const unsigned char *bytes = (const unsigned char *)buffer;
    size_t offset = 0;
    while (offset < length) {
        ssize_t sent = send(fd, bytes + offset, length - offset, MSG_NOSIGNAL);
        if (sent < 0 && errno == EINTR) continue;
        if (sent <= 0) return -1;
        offset += (size_t)sent;
    }
    return 0;
}

#endif
