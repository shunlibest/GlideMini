package com.example.glidemini.cache.memoryCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.glidemini.cache.key.Key;
import com.example.glidemini.load.engine.Resource;

//内存缓存
public interface MemoryCache {

    //返回缓存中所有内容的大小之和，以字节为单位。
    long getCurrentSize();

    //返回当前缓存的允许的最大字节数
    long getMaxSize();

    // 改变最大缓存字节数的大小, 如果<1, 则可能会移除缓存数据
    void setSizeMultiplier(float multiplier);

    //移除某一缓存值
    @Nullable
    Resource<?> remove(@NonNull Key key);

    //根据key, 放入缓存资源; key的旧值不为空, 则返回旧值
    @Nullable
    Resource<?> put(@NonNull Key key, @Nullable Resource<?> resource);

    //添加资源移除的监听器
    void setResourceRemovedListener(@NonNull ResourceRemovedListener listener);

    //清空所有缓存
    void clearMemory();

    //将内存缓存修剪到适当的级别
    void trimMemory(int level);

    //资源移除的回调
    interface ResourceRemovedListener {
        void onResourceRemoved(@NonNull Resource<?> removed);
    }

}
