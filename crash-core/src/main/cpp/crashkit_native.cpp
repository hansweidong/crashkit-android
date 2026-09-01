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
#include <sys/syscall.h>

#define LOG_TAG "CrashKitNative"
#define ALOG(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

namespace {

constexpr int kCrashByte = 1;
constexpr int kAnrByte = 2;
constexpr int kWaitSlices = 30;
constexpr int kWaitNs = 100 * 1000 * 1000;
constexpr int kDumpBuf = 768;

JavaVM* g_vm = nullptr;
jclass g_bridge_clz = nullptr;
char g_dump_dir[256] = {0};
int g_pipe[2] = {-1, -1};
std::atomic<int> g_handled{0};
std::atomic<int> g_java_done{0};
std::atomic<int> g_watchdog_started{0};
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
    build_dump_path(path, sizeof(path), "native_crash.dmp");
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

void write_anr_traces() {
    char path[kDumpBuf];
    build_dump_path(path, sizeof(path), "traces.txt");
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        return;
    }
    write_str(fd, "*** CrashKit ANR traces ***\n");
    write_str(fd, "pid: ");
    write_dec(fd, getpid());
    write_str(fd, " tid: ");
    write_dec(fd, syscall(__NR_gettid));
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

void anr_handler(int sig, siginfo_t* info, void* uctx) {
    char token = kAnrByte;
    if (g_pipe[1] >= 0) {
        safe_write(g_pipe[1], &token, 1);
    }
    if (sig > 0 && sig < NSIG && g_old_saved[sig]) {
        if (g_old_actions[sig].sa_flags & SA_SIGINFO) {
            if (g_old_actions[sig].sa_sigaction != nullptr) {
                g_old_actions[sig].sa_sigaction(sig, info, uctx);
                return;
            }
        } else if (g_old_actions[sig].sa_handler != nullptr &&
                   g_old_actions[sig].sa_handler != SIG_IGN &&
                   g_old_actions[sig].sa_handler != SIG_DFL) {
            g_old_actions[sig].sa_handler(sig);
            return;
        }
    }
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
            build_dump_path(path, sizeof(path), "native_crash.dmp");
            call_java("onNativeDumpFinished", path);
        } else if (token == kAnrByte) {
            write_anr_traces();
            build_dump_path(path, sizeof(path), "traces.txt");
            call_java("onAnrTraces", path);
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
    install_handler(SIGQUIT, anr_handler);
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
