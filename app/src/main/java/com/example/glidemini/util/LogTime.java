package com.example.glidemini.util;

import android.os.Build;
import android.os.SystemClock;

/**
 * A class for logging elapsed real time in millis.
 */
public final class LogTime {

    //Android API 16
    private static final double MILLIS_MULTIPLIER =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ? 1d / Math.pow(10, 6) : 1d;

    private LogTime() {
    }

    /**
     * uptimeMillis()表示自系统启动时开始计数，以毫秒为单位。返回的是从系统启动到现在这个过程中的处于非休眠期的时间。
     * 当系统进入深度睡眠时(CPU关闭，设备变黑，等待外部输入装置)该时钟会停止。但是该时钟不会被时钟调整。
     * 这是大多数间隔时间的基本点，例如Thread.sleep(millls)、Object.wait(millis)和System.nanoTime()。
     * 该时钟被保证是单调的，适用于检测不包含休眠的间隔时间的情况。大多数的方法接受一个时间戳的值除了uptimeMillis()时钟。
     * <p>
     * elapsedRealtime() and elapsedRealtimeNanos() 返回系统启动到现在的时间，包含设备深度休眠的时间。
     * 即使CPU在省电模式下，该时间也会继续计时
     */
    public static long getLogTime() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return SystemClock.elapsedRealtimeNanos();
        } else {
            return SystemClock.uptimeMillis();
        }
    }

    //用于计算某一段代码运行时间
    public static double getElapsedMillis(long logTime) {
        return (getLogTime() - logTime) * MILLIS_MULTIPLIER;
    }
}
