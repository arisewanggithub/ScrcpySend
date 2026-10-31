/* Freestanding host tests of the exact production stop function, with a retained-child kernel model. */
typedef int pid_t;
typedef struct { int si_pid; } siginfo_t;
struct timespec { long tv_sec; long tv_nsec; };
enum { EINVAL = 22, ECHILD = 10, ESRCH = 3, EINTR = 4, EIO = 5, CLOCK_MONOTONIC = 1, P_PID = 1,
       WEXITED = 4, WNOHANG = 1, WNOWAIT = 0x1000000, SIGTERM = 15, SIGKILL = 9 };
static int errno;
static int retained, signals, reaped, invalid_signal, foreign_child, missing, interrupted, sleeps;
static int time_ms, exit_at, term_exits, wait_interrupted;
static int clock_gettime(int clock, struct timespec *now) {
    if (clock != CLOCK_MONOTONIC) return -1;
    now->tv_sec = time_ms / 1000; now->tv_nsec = (time_ms % 1000) * 1000000;
    return 0;
}
static pid_t getpid(void) { return 100; }
static int waitid(int kind, pid_t pid, siginfo_t *info, int flags) {
    if (kind != P_PID || pid != 41 || flags != (WEXITED | WNOHANG | WNOWAIT)) return -1;
    if (foreign_child) { errno = ECHILD; return -1; }
    if (wait_interrupted) { wait_interrupted = 0; errno = EINTR; return -1; }
    retained = 1;
    info->si_pid = missing || (exit_at >= 0 && time_ms >= exit_at) || (term_exits && signals) ? pid : 0;
    return 0;
}
static int kill(pid_t pid, int signal) {
    if (!retained || reaped || pid <= 1 || pid == getpid() || pid != 41 ||
        (signals == 0 ? signal != SIGTERM : signal != SIGKILL)) invalid_signal = 1;
    signals++;
    if ((signals == 1 && time_ms < 2000) || (signals == 2 && time_ms < 2200)) invalid_signal = 1;
    if (missing) { errno = ESRCH; return -1; }
    return 0;
}
static int nanosleep(struct timespec *time, struct timespec *remaining) {
    (void)time; (void)remaining;
    sleeps++;
    if (interrupted && sleeps == 1) { errno = EINTR; return -1; }
    time_ms += 10;
    return 0;
}
static int waitpid(pid_t pid, int *status, int flags) {
    (void)status; (void)flags;
    if (!retained || pid != 41 || reaped) invalid_signal = 1;
    reaped = 1;
    return pid;
}
#include "../../main/cpp/owned_child.h"

static void reset(void) {
    errno = retained = signals = reaped = invalid_signal = foreign_child = missing = interrupted = sleeps = 0;
    time_ms = term_exits = wait_interrupted = 0; exit_at = -1;
}
#define CHECK(expression) do { if (!(expression)) return __LINE__; } while (0)
int run_tests(void) {
    unsigned startup = 0;
    CHECK(supervisor_start_byte(&startup, 'K') == -1); CHECK(startup == 0);
    for (unsigned i = 0; i < 6; ++i)
        CHECK(supervisor_start_byte(&startup, "START\n"[i]) == (i == 5 ? 1 : 0));
    CHECK(supervisor_start_byte(&startup, '\n') == -1);
    CHECK(supervisor_uid_allowed(2000)); CHECK(supervisor_uid_allowed(0));
    CHECK(!supervisor_uid_allowed(1000)); CHECK(!supervisor_uid_allowed(10111)); CHECK(!supervisor_uid_allowed(-1));
    CHECK(supervisor_isolated(100, 100, 100));
    CHECK(!supervisor_isolated(0, 0, 0)); CHECK(!supervisor_isolated(1, 1, 1));
    CHECK(!supervisor_isolated(100, 100, 0)); CHECK(!supervisor_isolated(100, 100, -100));
    CHECK(!supervisor_isolated(100, 1, 100)); CHECK(!supervisor_isolated(100, 100, 99));
    const int unsafe[] = {-100, -1, 0, 1, 100};
    for (unsigned i = 0; i < sizeof(unsafe) / sizeof(unsafe[0]); ++i) {
        reset(); struct owned_child child = {unsafe[i], 1};
        CHECK(stop_owned_child(&child) == EINVAL); CHECK(signals == 0);
    }
    reset(); foreign_child = 1; struct owned_child wrong = {41, 1};
    CHECK(stop_owned_child(&wrong) == ECHILD); CHECK(signals == 0);
    reset(); struct owned_child live = {41, 1};
    CHECK(stop_owned_child(&live) == 0); CHECK(!live.retained && reaped && signals == 2 && !invalid_signal);
    CHECK(stop_owned_child(&live) == 0); CHECK(signals == 2);
    reset(); missing = 1; struct owned_child zombie = {41, 1};
    CHECK(stop_owned_child(&zombie) == 0); CHECK(reaped && signals == 0 && !invalid_signal);
    reset(); exit_at = 350; struct owned_child graceful = {41, 1};
    CHECK(stop_owned_child(&graceful) == 0); CHECK(reaped && signals == 0 && time_ms == 350 && !invalid_signal);
    reset(); term_exits = 1; struct owned_child stalled = {41, 1};
    CHECK(stop_owned_child(&stalled) == 0); CHECK(reaped && signals == 1 && time_ms == 2000 && !invalid_signal);
    reset(); interrupted = 1; struct owned_child interrupted_child = {41, 1};
    CHECK(stop_owned_child(&interrupted_child) == 0); CHECK(time_ms == 2200 && sleeps == 221 && !invalid_signal);
    reset(); wait_interrupted = 1; exit_at = 0; struct owned_child interrupted_wait = {41, 1};
    CHECK(stop_owned_child(&interrupted_wait) == 0); CHECK(signals == 0 && reaped && !invalid_signal);
    return 0;
}
