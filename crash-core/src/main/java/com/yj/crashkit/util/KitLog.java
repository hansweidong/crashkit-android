package com.yj.crashkit.util;

import android.util.Log;

/**
 * SDK 日志口。方法全部是 Java static，宿主可直接 {@code KitLog.i(tag, msg)}。
 * {@link ILog} 对齐原 crashreport：v / d / i / w / e，未实现的级别有 default。
 */
public final class KitLog {
    private static final ILog FALLBACK = new AndroidLog();
    private static volatile ILog logger = FALLBACK;

    private KitLog() {
    }

    public static void setLogger(ILog log) {
        if (log != null) {
            logger = log;
        }
    }

    public static void v(String tag, String msg) {
        emit(() -> current().v(safe(tag), safe(msg)));
    }

    public static void v(String tag, String msg, Throwable t) {
        emit(() -> current().v(safe(tag), safe(msg), t));
    }

    public static void d(String tag, String msg) {
        emit(() -> current().d(safe(tag), safe(msg)));
    }

    public static void d(String tag, String msg, Throwable t) {
        emit(() -> current().d(safe(tag), safe(msg), t));
    }

    public static void i(String tag, String msg) {
        emit(() -> current().i(safe(tag), safe(msg)));
    }

    public static void i(String tag, String msg, Throwable t) {
        emit(() -> current().i(safe(tag), safe(msg), t));
    }

    public static void w(String tag, String msg) {
        emit(() -> current().w(safe(tag), safe(msg)));
    }

    public static void w(String tag, String msg, Throwable t) {
        emit(() -> current().w(safe(tag), safe(msg), t));
    }

    public static void w(String tag, Throwable t) {
        emit(() -> current().w(safe(tag), t));
    }

    public static void e(String tag, String msg) {
        emit(() -> current().e(safe(tag), safe(msg)));
    }

    public static void e(String tag, String msg, Throwable t) {
        emit(() -> current().e(safe(tag), safe(msg), t));
    }

    private static ILog current() {
        ILog log = logger;
        return log != null ? log : FALLBACK;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void emit(Runnable action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            try {
                FALLBACK.e("KitLog", "host logger threw", ignored);
            } catch (Throwable ignoredAgain) {
            }
        }
    }

    /**
     * 宿主适配器。只需实现 {@link #i(String, String)} 和 {@link #e(String, String, Throwable)}；
     * 其余级别有 default，原 crashreport 那套 v/d/w 全量 Override 也能编过。
     */
    public interface ILog {
        default void v(String tag, String msg) {
        }

        default void v(String tag, String msg, Throwable throwable) {
            v(tag, msg);
        }

        default void d(String tag, String msg) {
        }

        default void d(String tag, String msg, Throwable throwable) {
            d(tag, msg);
        }

        void i(String tag, String msg);

        default void i(String tag, String msg, Throwable throwable) {
            i(tag, msg);
        }

        default void w(String tag, String msg) {
            i(tag, msg);
        }

        default void w(String tag, String msg, Throwable throwable) {
            e(tag, msg, throwable);
        }

        default void w(String tag, Throwable throwable) {
            e(tag, "", throwable);
        }

        default void e(String tag, String msg) {
            e(tag, msg, null);
        }

        void e(String tag, String msg, Throwable throwable);
    }

    private static final class AndroidLog implements ILog {
        @Override
        public void v(String tag, String msg) {
            Log.v(tag, msg);
        }

        @Override
        public void v(String tag, String msg, Throwable throwable) {
            Log.v(tag, msg, throwable);
        }

        @Override
        public void d(String tag, String msg) {
            Log.d(tag, msg);
        }

        @Override
        public void d(String tag, String msg, Throwable throwable) {
            Log.d(tag, msg, throwable);
        }

        @Override
        public void i(String tag, String msg) {
            Log.i(tag, msg);
        }

        @Override
        public void i(String tag, String msg, Throwable throwable) {
            if (throwable == null) {
                Log.i(tag, msg);
            } else {
                Log.i(tag, msg, throwable);
            }
        }

        @Override
        public void w(String tag, String msg) {
            Log.w(tag, msg);
        }

        @Override
        public void w(String tag, String msg, Throwable throwable) {
            if (throwable == null) {
                Log.w(tag, msg);
            } else {
                Log.w(tag, msg, throwable);
            }
        }

        @Override
        public void w(String tag, Throwable throwable) {
            Log.w(tag, throwable);
        }

        @Override
        public void e(String tag, String msg) {
            Log.e(tag, msg);
        }

        @Override
        public void e(String tag, String msg, Throwable throwable) {
            if (throwable == null) {
                Log.e(tag, msg);
            } else {
                Log.e(tag, msg, throwable);
            }
        }
    }
}
