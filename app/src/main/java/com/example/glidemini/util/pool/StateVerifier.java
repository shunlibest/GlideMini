package com.example.glidemini.util.pool;

import androidx.annotation.NonNull;

import com.example.glidemini.util.Synthetic;

/**
 * 这里用来判断该对象是否被回收，
 * Verifies that the job is not in the recycled state.
 */
public abstract class StateVerifier {
    private static final boolean DEBUG = false;


    @NonNull
    public static StateVerifier newInstance() {
        if (DEBUG) {
            return new DebugStateVerifier();
        } else {
            return new DefaultStateVerifier();
        }
    }

    private StateVerifier() {
    }

    //当此对象被回收了，将会抛出一个异常： TODO：在线程池的对象是否属于被回收对象
    public abstract void throwIfRecycled();

    //此对象被回收了，或者可用了；需要手动设置
    abstract void setRecycled(boolean isRecycled);

    //一个默认的的对象状态验证器，标准用法，可以学习一下
    private static class DefaultStateVerifier extends StateVerifier {
        private volatile boolean isReleased;

        @Synthetic
        DefaultStateVerifier() {
        }

        @Override
        public void throwIfRecycled() {
            if (isReleased) {
                throw new IllegalStateException("Already released");
            }
        }

        @Override
        public void setRecycled(boolean isRecycled) {
            this.isReleased = isRecycled;
        }
    }

    //个人感觉，这里完全不需要这么写啊，可以把上面的异常改一下即可
    private static class DebugStateVerifier extends StateVerifier {
        private volatile RuntimeException recycledAtStackTraceException;

        @Synthetic
        DebugStateVerifier() {
        }

        @Override
        public void throwIfRecycled() {
            if (recycledAtStackTraceException != null) {
                throw new IllegalStateException("Already released", recycledAtStackTraceException);
            }
        }

        @Override
        void setRecycled(boolean isRecycled) {
            if (isRecycled) {
                recycledAtStackTraceException = new RuntimeException("Released");
            } else {
                recycledAtStackTraceException = null;
            }
        }
    }
}
