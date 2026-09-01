#include "resource_hook.h"

#include <dlfcn.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <unwind.h>
#include <cstdio>
#include <cstring>
#include <malloc.h>
#include <jni.h>

namespace {

struct UnwindState {
    char* buf;
    size_t cap;
    size_t used;
    int depth;
};

_Unwind_Reason_Code unwind_cb(struct _Unwind_Context* ctx, void* arg) {
    auto* st = static_cast<UnwindState*>(arg);
    if (st->depth > 32 || st->used + 64 >= st->cap) {
        return _URC_END_OF_STACK;
    }
    uintptr_t pc = _Unwind_GetIP(ctx);
    Dl_info info;
    int n;
    if (dladdr(reinterpret_cast<void*>(pc), &info) && info.dli_sname != nullptr) {
        n = snprintf(st->buf + st->used, st->cap - st->used, "  #%d %s\n", st->depth,
                     info.dli_sname);
    } else {
        n = snprintf(st->buf + st->used, st->cap - st->used, "  #%d pc %p\n", st->depth,
                     reinterpret_cast<void*>(pc));
    }
    if (n > 0) {
        st->used += static_cast<size_t>(n);
    }
    st->depth++;
    return _URC_NO_REASON;
}

}  // namespace

void crashkit_set_fd_hook(int type, bool inline_mode, bool java_stack) {
    (void) type;
    (void) inline_mode;
    (void) java_stack;
}

void crashkit_set_mem_hook(bool enable) {
    (void) enable;
}

void crashkit_set_thread_hook(bool enable) {
    (void) enable;
}

void crashkit_format_fd_hook(char* out, size_t cap) {
    if (out == nullptr || cap == 0) {
        return;
    }
    snprintf(out, cap, "live_hook=off\n");
}

void crashkit_format_mem_hook(char* out, size_t cap) {
    if (out == nullptr || cap == 0) {
        return;
    }
    struct mallinfo mi = mallinfo();
    snprintf(out, cap, "live_hook=off\nmallinfo_uordblks=%d\nmallinfo_fordblks=%d\n",
             mi.uordblks, mi.fordblks);
}

void crashkit_format_native_stack(char* out, size_t cap) {
    if (out == nullptr || cap == 0) {
        return;
    }
    UnwindState st{out, cap, 0, 0};
    int n = snprintf(out, cap, "tid=%ld\n", static_cast<long>(syscall(__NR_gettid)));
    if (n > 0) {
        st.used = static_cast<size_t>(n);
    }
    _Unwind_Backtrace(unwind_cb, &st);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeInstallFdHook(
        JNIEnv*, jclass, jint type, jboolean inlineMode, jboolean javaStack) {
    crashkit_set_fd_hook(type, inlineMode == JNI_TRUE, javaStack == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeInstallMemHook(
        JNIEnv*, jclass, jboolean enable) {
    crashkit_set_mem_hook(enable == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeInstallThreadHook(
        JNIEnv*, jclass, jboolean enable) {
    crashkit_set_thread_hook(enable == JNI_TRUE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeDumpHookedFd(
        JNIEnv* env, jclass) {
    char buf[256];
    crashkit_format_fd_hook(buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeDumpHookedMem(
        JNIEnv* env, jclass) {
    char buf[256];
    crashkit_format_mem_hook(buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yj_crashkit_nativecrash_NativeCrashBridge_nativeDumpNativeThreadStack(
        JNIEnv* env, jclass) {
    char buf[4096];
    crashkit_format_native_stack(buf, sizeof(buf));
    return env->NewStringUTF(buf);
}
