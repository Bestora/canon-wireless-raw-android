// Android/bionic reports a SO_RCVTIMEO timeout as errno 11 (EAGAIN); macOS uses 35.
// libpict's ip.c checks for the literal 11, so remap to see Android's code path.
#include <errno.h>
#include <unistd.h>
ssize_t bionic_read(int fd, void *buf, size_t n) {
    ssize_t r = read(fd, buf, n);
    if (r < 0 && errno == EAGAIN) errno = 11;
    return r;
}
