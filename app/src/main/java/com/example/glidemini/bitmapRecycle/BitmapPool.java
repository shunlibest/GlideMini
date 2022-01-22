package com.example.glidemini.bitmapRecycle;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

/**
 * bitmap缓存池，bitmap可以被重复使用
 */
public interface BitmapPool {

    //缓存池最大的存储空间
    long getMaxSize();

    /**
     * 将池的初始大小乘以给定的乘数
     *
     * @param sizeMultiplier 系数应该在 0 - 1.
     */
    void setSizeMultiplier(float sizeMultiplier);

    //代表了该图片已经废弃了;可以放到回收池里,也可以直接丢弃Bitmap.recycle()
    void put(Bitmap bitmap);

    // 返回给定宽高以及Config的Bitmap，并且已经被擦除所有像素,显示透明像素。(bitmap.eraseColor (Color.TRANSPARENT))
    // 有些情况下,不需要擦除,而且直接覆盖复制,所以可以用getDirty(int, int, Bitmap. config), 所以比较快
    @NonNull
    Bitmap get(int width, int height, Bitmap.Config config);

    // 和上面的差不多
    @NonNull
    Bitmap getDirty(int width, int height, Bitmap.Config config);

    //清空所有缓存
    void clearMemory();

    //缩减大小
    void trimMemory(int level);
}
