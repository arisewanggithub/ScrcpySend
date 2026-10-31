#ifndef SCREENCOPY_OWNED_CHILD_H
#define SCREENCOPY_OWNED_CHILD_H

/* The single-threaded supervisor must never reap this child before the last signal.
 * A live child or its unreaped zombie reserves the PID in the kernel. */
struct owned_child {
    pid_t pid;
    int retained;
};

static int supervisor_uid_allowed(int uid) { return uid == 2000 || uid == 0; }
static int supervisor_isolated(pid_t self, pid_t session, pid_t group) {
    return self > 1 && session == self && group == self;
}
static int supervisor_start_byte(unsigned *used, char byte) {
    if (*used >= 6 || byte != "START\n"[*used]) return -1;
    return ++*used == 6 ? 1 : 0;
}

static long long monotonic_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

/* Observe without reaping: even an exit racing the final signal cannot reuse this PID. */
static int await_owned_exit(struct owned_child *child, int milliseconds) {
    long long begin = monotonic_ms();
    if (begin < 0) return -(errno ? errno : EIO);
    for (;;) {
        siginfo_t info = {0};
        int observed;
        do { observed = waitid(P_PID, child->pid, &info, WEXITED | WNOHANG | WNOWAIT); }
        while (observed != 0 && errno == EINTR);
        if (observed != 0) return -errno;
        if (info.si_pid == child->pid) return 1;
        long long now = monotonic_ms();
        if (now < 0) return -(errno ? errno : EIO);
        if (now - begin >= milliseconds) return 0;
        struct timespec remaining = {0, 10000000};
        while (nanosleep(&remaining, &remaining) != 0 && errno == EINTR) {}
    }
}

static int stop_owned_child(struct owned_child *child) {
    if (!child->retained) return 0;
    if (child->pid <= 1 || child->pid == getpid()) return EINVAL;
    /* Media sockets are already shut down. Let pinned scrcpy stop/release codecs itself
     * on broken pipe before using signals for stalled startup/teardown only. */
    int exited = await_owned_exit(child, 2000);
    if (exited < 0) return -exited;
    if (!exited) {
        if (kill(child->pid, SIGTERM) != 0 && errno != ESRCH) return errno;
        exited = await_owned_exit(child, 200);
        if (exited < 0) return -exited;
        if (!exited && kill(child->pid, SIGKILL) != 0 && errno != ESRCH) return errno;
    }
    pid_t result;
    do { result = waitpid(child->pid, 0, 0); } while (result < 0 && errno == EINTR);
    if (result != child->pid) return errno ? errno : ECHILD;
    child->retained = 0;
    return 0;
}
#endif
