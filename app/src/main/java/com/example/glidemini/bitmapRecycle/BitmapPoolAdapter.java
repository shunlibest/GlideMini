package com.example.glidemini.bitmapRecycle;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

/**
 * Bitmap缓存池的空实现, 每次都创建新的bitmap, 不用了,就直接回收了
 */
public class BitmapPoolAdapter implements BitmapPool {
    @Override
    public long getMaxSize() {
        return 0;
    }

    @Override
    public void setSizeMultiplier(float sizeMultiplier) {
        // Do nothing.
    }

    @Override
    public void put(Bitmap bitmap) {
        bitmap.recycle();
    }

    @NonNull
    @Override
    public Bitmap get(int width, int height, Bitmap.Config config) {
        return Bitmap.createBitmap(width, height, config);
    }

    @NonNull
    @Override
    public Bitmap getDirty(int width, int height, Bitmap.Config config) {
        return get(width, height, config);
    }

    @Override
    public void clearMemory() {
        // Do nothing.
    }

    @Override
    public void trimMemory(int level) {
        // Do nothing.
    }
}
