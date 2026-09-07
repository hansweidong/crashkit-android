#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <dlfcn.h>
#include <unwind.h>
#include <ctime>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <atomic>
#include <cerrno>
#include <dirent.h>
#include <semaphore.h>
#include <sys/syscall.h>

#define LOG_TAG "CrashKitNative"
#define ALOG(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

namespace {

constexpr int kCrashByte = 1;
constexpr int kWaitSlices = 30;
constexpr int kWaitNs = 100 * 1000 * 1000;
constexpr int kDumpBuf = 768;
// 必须和 Kotlin 侧 CrashFiles.NATIVE_CRASH_DUMP 一致。
// 多进程靠 g_dump_dir 分目录隔离，所以这里可以用固定名。
constexpr const char* kNativeCrashDump = "native_crash.dmp";

JavaVM* g_vm = nullptr;
jclass g_bridge_clz = nullptr;
char g_dump_dir[256] = {0};
int g_pipe[2] = {-1, -1};
std::atomic<int> g_handled{0};
std::atomic<int> g_java_done{0};
std::atomic<int> g_watchdog_started{0};
std::atomic<int> g_anr_installed{0};
std::atomic<int> g_catcher_tid{-1};
// handler 里只允许做 async-signal-safe 的事，所以信号量在装 handler 之前就 init 好
sem_t g_anr_sem;
std::atomic<int> g_anr_sem_ready{0};
// 装 handler 时没找到 Signal Catcher，只能退回到监听线程上补一次转发
std::atomic<int> g_anr_handoff_pending{0};
struct sigaction g_old_actions[NSIG];
bool g_old_saved[NSIG];

const int kCrashSignals[] = {
        SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP
};

void safe_write(int fd, const char* data, size_t len) {
    if (fd < 0 || data == nullptr || len == 0) {
        return;
    }
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, data + off, len - off);
        if (n <= 0) {
            break;
        }
        off += static_cast<size_t>(n);
    }
}

void write_str(int fd, const char* s) {
    if (s != nullptr) {
        safe_write(fd, s, strlen(s));
    }
}

void write_hex(int fd, unsigned long v) {
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "0x%lx", v);
    if (n > 0) {
        safe_write(fd, buf, static_cast<size_t>(n));
    }
}

void write_dec(int fd, long v) {
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%ld", v);
    if (n > 0) {
        safe_write(fd, buf, static_cast<size_t>(n));
    }
}

struct UnwindState {
    int fd;
    int depth;
};

_Unwind_Reason_Code unwind_cb(struct _Unwind_Context* ctx, void* arg) {
    auto* st = static_cast<UnwindState*>(arg);
    if (st->depth > 64) {
        return _URC_END_OF_STACK;
    }
    uintptr_t pc = _Unwind_GetIP(ctx);
    Dl_info info;
    write_str(st->fd, "  #");
    write_dec(st->fd, st->depth);
    write_str(st->fd, " pc ");
    write_hex(st->fd, pc);
    if (dladdr(reinterpret_cast<void*>(pc), &info) && info.dli_fname != nullptr) {
        write_str(st->fd, "  ");
        write_str(st->fd, info.dli_fname);
        if (info.dli_sname != nullptr) {
            write_str(st->fd, " (");
            write_str(st->fd, info.dli_sname);
            write_str(st->fd, ")");
        }
    }
    write_str(st->fd, "\n");
    st->depth++;
    return _URC_NO_REASON;
}

void dump_maps(int fd) {
    int mapfd = open("/proc/self/maps", O_RDONLY);
    if (mapfd < 0) {
        return;
    }
    write_str(fd, "maps:\n");
    char buf[512];
    ssize_t n;
    int lines = 0;
    while ((n = read(mapfd, buf, sizeof(buf))) > 0 && lines < 80) {
        safe_write(fd, buf, static_cast<size_t>(n));
        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] == '\n') {
                lines++;
            }
        }
    }
    close(mapfd);
}

void build_dump_path(char* out, size_t cap, const char* name) {
    snprintf(out, cap, "%s/%s", g_dump_dir, name);
}

void write_crash_dump(int sig, siginfo_t* info) {
    char path[kDumpBuf];
    build_dump_path(path, sizeof(path), kNativeCrashDump);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        return;
    }
    write_str(fd, "*** CrashKit native dump ***\n");
    write_str(fd, "pid: ");
    write_dec(fd, getpid());
    write_str(fd, " tid: ");
    write_dec(fd, syscall(__NR_gettid));
    write_str(fd, "\nsignal: ");
    write_dec(fd, sig);
    write_str(fd, " code: ");
    write_dec(fd, info != nullptr ? info->si_code : 0);
    write_str(fd, " faultaddr: ");
    write_hex(fd, info != nullptr ? reinterpret_cast<unsigned long>(info->si_addr) : 0);
    write_str(fd, "\nbacktrace:\n");
    UnwindState st{fd, 0};
    _Unwind_Backtrace(unwind_cb, &st);
    write_str(fd, "\n");
    dump_maps(fd);
    close(fd);
}

void restore_and_reraise(int sig) {
    if (sig > 0 && sig < NSIG && g_old_saved[sig]) {
        sigaction(sig, &g_old_actions[sig], nullptr);
    } else {
        signal(sig, SIG_DFL);
    }
    raise(sig);
}

void crash_handler(int sig, siginfo_t* info, void* uctx) {
    (void) uctx;
    if (g_handled.exchange(1) != 0) {
        restore_and_reraise(sig);
        return;
    }
    write_crash_dump(sig, info);
    char token = kCrashByte;
    if (g_pipe[1] >= 0) {
        safe_write(g_pipe[1], &token, 1);
    }
    struct timespec ts;
    ts.tv_sec = 0;
    ts.tv_nsec = kWaitNs;
    for (int i = 0; i < kWaitSlices && g_java_done.load() == 0; i++) {
        nanosleep(&ts, nullptr);
    }
    restore_and_reraise(sig);
}

void install_handler(int sig, void (*fn)(int, siginfo_t*, void*)) {
    struct sigaction act;
    memset(&act, 0, sizeof(act));
    act.sa_sigaction = fn;
    act.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigfillset(&act.sa_mask);
    if (sigaction(sig, &act, &g_old_actions[sig]) == 0) {
        g_old_saved[sig] = true;
    }
}

void call_java(const char* method, const char* path) {
    if (g_vm == nullptr || g_bridge_clz == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    int rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    } else if (rc != JNI_OK || env == nullptr) {
        return;
    }
    jmethodID mid = env->GetStaticMethodID(g_bridge_clz, method, "(Ljava/lang/String;)V");
    if (mid == nullptr) {
        env->ExceptionClear();
        if (attached) {
            g_vm->DetachCurrentThread();
        }
        return;
    }
    jstring jpath = env->NewStringUTF(path != nullptr ? path : "");
    env->CallStaticVoidMethod(g_bridge_clz, mid, jpath);
    env->ExceptionClear();
    if (jpath != nullptr) {
        env->DeleteLocalRef(jpath);
    }
    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

void* watchdog_main(void*) {
    char token = 0;
    while (true) {
        ssize_t n = read(g_pipe[0], &token, 1);
        if (n <= 0) {
            break;
        }
        char path[kDumpBuf];
        if (token == kCrashByte) {
            build_dump_path(path, sizeof(path), kNativeCrashDump);
            call_java("onNativeDumpFinished", path);
        }
    }
    return nullptr;
}

bool start_watchdog() {
    if (g_watchdog_started.exchange(1) != 0) {
        return true;
    }
    if (pipe(g_pipe) != 0) {
        g_watchdog_started.store(0);
        return false;
    }
    pthread_t th;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    int rc = pthread_create(&th, &attr, watchdog_main, nullptr);
    pthread_attr_destroy(&attr);
    return rc == 0;
}

stack_t g_altstack;

bool install_altstack() {
    g_altstack.ss_sp = malloc(SIGSTKSZ * 2);
    if (g_altstack.ss_sp == nullptr) {
        return false;
    }
    g_altstack.ss_size = SIGSTKSZ * 2;
    g_altstack.ss_flags = 0;
    return sigaltstack(&g_altstack, nullptr) == 0;
}

void call_java_void(const char* method) {
    if (g_vm == nullptr || g_bridge_clz == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    int rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    } else if (rc != JNI_OK || env == nullptr) {
        return;
    }
    jmethodID mid = env->GetStaticMethodID(g_bridge_clz, method, "()V");
    if (mid == nullptr) {
        env->ExceptionClear();
        if (attached) {
            g_vm->DetachCurrentThread();
        }
        return;
    }
    env->CallStaticVoidMethod(g_bridge_clz, mid);
    env->ExceptionClear();
    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

int signal_catcher_tid() {
    DIR* dir = opendir("/proc/self/task");
    if (dir == nullptr) {
        return -1;
    }
    int first = -1;
    int matched = -1;
    struct dirent* ent;
    while ((ent = readdir(dir)) != nullptr) {
        int tid = atoi(ent->d_name);
        if (tid <= 0) {
            continue;
        }
        char comm_path[64];
        snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%d/comm", tid);
        char name[32] = {0};
        int fd = open(comm_path, O_RDONLY);
        if (fd < 0) {
            continue;
        }
        ssize_t n = read(fd, name, sizeof(name) - 1);
        close(fd);
        if (n <= 0) {
            continue;
        }
        if (name[n - 1] == '\n') {
            name[n - 1] = 0;
        }
        if (strcmp(name, "Signal Catcher") != 0) {
            continue;
        }
        if (first < 0) {
            first = tid;
        }
        char status_path[64];
        snprintf(status_path, sizeof(status_path), "/proc/self/task/%d/status", tid);
        FILE* fp = fopen(status_path, "r");
        if (fp == nullptr) {
            continue;
        }
        char line[128];
        unsigned long sigblk = 0;
        while (fgets(line, sizeof(line), fp) != nullptr) {
            if (sscanf(line, "SigBlk: %lx", &sigblk) == 1) {
                break;
            }
        }
        fclose(fp);
        // SigBlk 里 signal n 占 bit n-1。这里的判定方向和直觉相反：
        //
        // Signal Catcher 卡在 sigwait / rt_sigtimedwait 里时，内核 do_sigtimedwait()
        // 会执行 sigandnsets(&tsk->blocked, &tsk->blocked, &mask)，把它**正在等待的**
        // 信号从 blocked 里临时摘掉。所以真正在等 SIGQUIT 的那个线程，SigBlk 反而不含
        // SIGQUIT；普通业务线程才是含 SIGQUIT 的那一批。
        //
        // 实测 Android 10 / EMUI：Signal Catcher SigBlk=0x1000（只剩 SIGPIPE），
        // 普通线程 SigBlk=0x80001204。Matrix 的取法是全等匹配 0x1000，这里放宽成
        // 「SIGQUIT 位为 0」，避免各 ROM 上其余位不一致导致匹配不上。
        if ((sigblk & (1UL << (SIGQUIT - 1))) == 0UL) {
            matched = tid;
            break;
        }
    }
    closedir(dir);
    return matched >= 0 ? matched : first;
}

void send_sigquit_to_signal_catcher() {
    int tid = g_catcher_tid.load();
    if (tid <= 0) {
        tid = signal_catcher_tid();
        if (tid > 0) {
            g_catcher_tid.store(tid);
        }
    }
    if (tid <= 0) {
        ALOG("Signal Catcher tid not found");
        return;
    }
    syscall(__NR_tgkill, getpid(), tid, SIGQUIT);
}

/**
 * SIGQUIT 旁路。整个函数只做 async-signal-safe 的事：
 *
 * 1. tgkill 把信号交回 Signal Catcher —— 先交回，系统 traces / ANR 弹窗链路不被我们拖慢
 * 2. sem_post 唤醒早就建好的监听线程去抓快照
 *
 * 这里**不能** pthread_create：它内部会 malloc，主线程若正卡在 malloc 锁上就是死锁。
 */
void anr_handler(int sig, siginfo_t* info, void* uctx) {
    (void) uctx;
    if (sig != SIGQUIT) {
        return;
    }
    // 只忽略「我们自己转发给 Signal Catcher、且此刻正跑在 Signal Catcher 上」的那一发。
    // 不能只判 si_pid == getpid()：那样连本进程内其它来源发的 SIGQUIT（宿主自检、
    // 测试用例里的 Process.sendSignal）都会被一并丢掉，旁路成了黑盒没法验证。
    pid_t sender = info != nullptr ? info->si_pid : 0;
    if (sender > 0 && sender == getpid() &&
        static_cast<int>(syscall(__NR_gettid)) == g_catcher_tid.load()) {
        return;
    }
    int tid = g_catcher_tid.load();
    if (tid > 0) {
        syscall(__NR_tgkill, getpid(), tid, SIGQUIT);
    } else {
        // tid 要读 /proc（opendir/fopen 会 malloc），只能挪到监听线程上做
        g_anr_handoff_pending.store(1);
    }
    if (g_anr_sem_ready.load() != 0) {
        sem_post(&g_anr_sem);
    }
}

/**
 * 唯一的 ANR 工作线程：拿到 handler post 的令牌后，在普通线程上下文里调 JNI。
 *
 * SIGQUIT 的接收方是**主线程**（原因见 [install_anr_signal]）。这里也 UNBLOCK 只是兜底：
 * 主线程若正卡在不可中断的状态上收不下信号，还能由本线程顶上。
 * 每次 SIGQUIT 复用同一个线程，不按次 pthread_create。
 */
void* anr_listen_main(void*) {
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGQUIT);
    pthread_sigmask(SIG_UNBLOCK, &set, nullptr);
    while (true) {
        if (sem_wait(&g_anr_sem) != 0) {
            if (errno == EINTR) {
                // handler 刚在本线程上跑过，重进 sem_wait 就能拿到它 post 的令牌
                continue;
            }
            ALOG("anr sem_wait failed errno=%d", errno);
            break;
        }
        if (g_anr_handoff_pending.exchange(0) != 0) {
            send_sigquit_to_signal_catcher();
        }
        call_java_void("onAnrSignal");
    }
    return nullptr;
}

int install_anr_signal() {
    if (g_anr_installed.exchange(1) != 0) {
        return 0;
    }
    if (sem_init(&g_anr_sem, 0, 0) != 0) {
        ALOG("anr sem_init failed");
        g_anr_installed.store(0);
        return -1;
    }
    g_anr_sem_ready.store(1);
    // 先解析好 tid，handler 里就只剩一次 tgkill
    g_catcher_tid.store(signal_catcher_tid());
    // handler 必须先于任何 UNBLOCK 装好：中间只要漏一发 SIGQUIT，默认动作是 terminate + core。
    // sem 已经 ready，此刻来信号也只是把令牌攒在 sem 上，监听线程起来后照样取得到。
    install_handler(SIGQUIT, anr_handler);
    pthread_t th;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    int rc = pthread_create(&th, &attr, anr_listen_main, nullptr);
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        ALOG("anr listen thread failed");
        if (g_old_saved[SIGQUIT]) {
            sigaction(SIGQUIT, &g_old_actions[SIGQUIT], nullptr);
            g_old_saved[SIGQUIT] = false;
        }
        g_anr_sem_ready.store(0);
        sem_destroy(&g_anr_sem);
        g_anr_installed.store(0);
        return -1;
    }
    // 必须在**主线程**上解除 SIGQUIT 屏蔽，否则整条旁路收不到信号。
    //
    // 内核 complete_signal() 派发进程定向信号时是「主线程优先」：
    //     if (wants_signal(sig, p)) t = p;   // p 为线程组组长，即主线程
    //     else { t = signal->curr_target; while (!wants_signal(sig, t)) t = next_thread(t); }
    // 主线程只要还屏蔽着 SIGQUIT，就会退到轮询分支，而 ART 的 Signal Catcher 正卡在
    // sigwait 上（blocked 里没有 SIGQUIT，wants_signal 为真），于是它先被选中，
    // 我们的 handler 一次都不会跑。实测 Android 10：发 SIGQUIT 只有 Signal Catcher
    // 响应，AnrDetector 零日志。Matrix 也是在主线程上做这一步。
    //
    // handler 因此跑在主线程上，里面只有 tgkill + sem_post，都是 async-signal-safe。
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGQUIT);
    pthread_sigmask(SIG_UNBLOCK, &set, nullptr);
    if (syscall(__NR_gettid) != getpid()) {
        ALOG("install_anr_signal not on main thread, SIGQUIT may be taken by Signal Catcher");
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeInit(
        JNIEnv* env, jclass clazz, jstring dumpDir) {
    if (env->GetJavaVM(&g_vm) != JNI_OK) {
        return -1;
    }
    const char* dir = env->GetStringUTFChars(dumpDir, nullptr);
    if (dir == nullptr) {
        return -1;
    }
    strncpy(g_dump_dir, dir, sizeof(g_dump_dir) - 1);
    env->ReleaseStringUTFChars(dumpDir, dir);

    if (g_bridge_clz == nullptr) {
        g_bridge_clz = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    }
    if (!install_altstack()) {
        ALOG("sigaltstack failed");
    }
    if (!start_watchdog()) {
        ALOG("watchdog failed");
        return -2;
    }
    for (int sig : kCrashSignals) {
        install_handler(sig, crash_handler);
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeMarkHandled(
        JNIEnv*, jclass) {
    g_handled.store(1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeNotifyJavaDone(
        JNIEnv*, jclass) {
    g_java_done.store(1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeTestCrash(
        JNIEnv*, jclass) {
    volatile int* p = nullptr;
    *p = 42;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeInstallAnrSignal(
        JNIEnv*, jclass) {
    return install_anr_signal();
}
