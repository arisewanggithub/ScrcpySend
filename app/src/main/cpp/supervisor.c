#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "owned_child.h"

static volatile sig_atomic_t stopping;
static void stop_signal(int signal) { (void)signal; stopping = 1; }

static int start_authorized(void) {
    struct timespec begin, now;
    if (clock_gettime(CLOCK_MONOTONIC, &begin) != 0) return 0;
    struct pollfd input = {STDIN_FILENO, POLLIN | POLLHUP, 0};
    unsigned used = 0;
    while (!stopping) {
        if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
        long remaining = 5000 - (now.tv_sec - begin.tv_sec) * 1000 -
            (now.tv_nsec - begin.tv_nsec) / 1000000;
        if (remaining <= 0) return 0;
        int ready = poll(&input, 1, (int)remaining);
        if (ready < 0 && errno == EINTR) continue;
        char byte;
        if (ready <= 0 || read(STDIN_FILENO, &byte, 1) != 1) return 0;
        int state = supervisor_start_byte(&used, byte);
        if (state < 0) return 0;
        if (state > 0) return !stopping;
    }
    return 0;
}

static int record_pid(const char *jar, const char *suffix, pid_t pid) {
    char path[128], value[32];
    if (snprintf(path, sizeof(path), "%s.%s", jar, suffix) >= (int)sizeof(path)) return -1;
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    int length = snprintf(value, sizeof(value), "%d\n", pid);
    int ok = write(fd, value, length) == length && fsync(fd) == 0;
    if (close(fd) != 0) ok = 0;
    return ok ? 0 : -1;
}

int main(int argc, char **argv) {
    const char *prefix = "/data/local/tmp/screencopy-";
    if (argc < 3 || strlen(argv[1]) != strlen(prefix) + 12 ||
        strncmp(argv[1], prefix, strlen(prefix)) != 0 ||
        strspn(argv[1] + strlen(prefix), "0123456789abcdef") != 8 ||
        strcmp(argv[1] + strlen(prefix) + 8, ".jar") != 0) return 64;
    int probe = strcmp(argv[2], "probe-wait") == 0 ? 1 :
        strcmp(argv[2], "probe-exit") == 0 ? 2 : strcmp(argv[2], "probe-ignore-term") == 0 ? 3 : 0;
    if (!probe && strcmp(argv[2], "3.3.4") != 0) return 64;
    if (!supervisor_uid_allowed(getuid())) return 65;
    pid_t parent = getppid();
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != parent) return 66;
    if (setsid() < 0 || !supervisor_isolated(getpid(), getsid(0), getpgrp())) return 67;
    struct sigaction action = {0};
    sigemptyset(&action.sa_mask);
    action.sa_handler = stop_signal;
    if (sigaction(SIGTERM, &action, 0) || sigaction(SIGHUP, &action, 0) ||
        sigaction(SIGINT, &action, 0) || sigaction(SIGCHLD, &action, 0)) return 68;
    /* No automatic SIGCHLD reaping: the owned child's PID stays reserved through KILL. */
    signal(SIGPIPE, SIG_IGN);
    if (setenv("CLASSPATH", argv[1], 1) != 0 ||
        record_pid(argv[1], probe ? "probe.guardian" : "guardian", getpid()) != 0) return 69;
    char **arguments = calloc((size_t)argc + 3, sizeof(char *));
    if (!arguments) return 70;
    arguments[0] = "/system/bin/app_process";
    arguments[1] = "/";
    arguments[2] = "com.genymobile.scrcpy.Server";
    for (int i = 2; i < argc; ++i) arguments[i + 1] = argv[i];
    int null_input = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (null_input < 0) return 71;
    /* This small write precedes fork: the new pipe has no other producer or prior output. */
    if (dprintf(STDOUT_FILENO, "SCREENCOPY_GUARDIAN_READY:%d\n", getpid()) < 0 || stopping) return 76;
    if (!start_authorized()) return 77;
    pid_t supervisor = getpid();
    struct owned_child child = {fork(), 1};
    if (child.pid == 0) {
        /* Only async-signal-safe syscalls between fork and exec. Never invoke a shell. */
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != supervisor ||
            dup2(null_input, STDIN_FILENO) < 0) _exit(72);
        close(null_input);
        if (probe) {
            if (probe == 2) _exit(0);
            struct sigaction child_action = {0};
            sigemptyset(&child_action.sa_mask);
            child_action.sa_handler = probe == 3 ? SIG_IGN : SIG_DFL;
            sigaction(SIGTERM, &child_action, 0);
            for (;;) pause();
        }
        execv(arguments[0], arguments);
        _exit(73);
    }
    close(null_input);
    free(arguments);
    if (child.pid < 0) return 74;
    if (record_pid(argv[1], probe ? "probe.pid" : "pid", child.pid) != 0) stopping = 1;
    struct pollfd input = {STDIN_FILENO, POLLIN | POLLHUP, 0};
    char line[5];
    size_t used = 0;
    while (!stopping) {
        int ready = poll(&input, 1, 5000);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0 || (input.revents & (POLLERR | POLLNVAL))) break;
        char byte;
        if (read(STDIN_FILENO, &byte, 1) != 1) break;
        if (used >= sizeof(line) || byte != "KEEP\n"[used]) break;
        line[used++] = byte;
        if (used == sizeof(line)) used = 0;
    }
    int result = stop_owned_child(&child);
    return result ? 75 : 0;
}
