package com.example.glidemini.cache.memoryCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.glidemini.util.Synthetic;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于LRU算法的cache，一般情况下，一个对象占用一个单位的空间，当然，可以重写getSize，实现自定义的功能
 * 这个类可以说是核心吧，建议认真学习一下
 */
public class LruCache<T, Y> {
    private final Map<T, Entry<Y>> cache = new LinkedHashMap<>(100, 0.75f, true);
    private final long initialMaxSize;
    private long maxSize;
    private long currentSize;

    //初始化大小和最大空间
    public LruCache(long size) {
        this.initialMaxSize = size;
        this.maxSize = size;
    }

    /**
     * 这里相当于是设置新的最大大小，为初始大小的倍数
     */
    public synchronized void setSizeMultiplier(float multiplier) {
        if (multiplier < 0) {
            throw new IllegalArgumentException("Multiplier must be >= 0");
        }
        maxSize = Math.round(initialMaxSize * multiplier);
        evict();
    }

    //一个对象所占的空间，一般写成单位1 即可；比较方便理解；
    // 当然也可以按照图片的大小空间计算
    protected int getSize(@Nullable Y item) {
        return 1;
    }

    //cache的大小
    protected synchronized int getCount() {
        return cache.size();
    }

    //缓存数据被移除, 触发回调
    protected void onItemEvicted(@NonNull T key, @NonNull Y item) {
        // optional override
    }


    public synchronized long getMaxSize() {
        return maxSize;
    }

    public synchronized long getCurrentSize() {
        return currentSize;
    }

    public synchronized boolean contains(@NonNull T key) {
        return cache.containsKey(key);
    }

    @Nullable
    public synchronized Y get(@NonNull T key) {
        Entry<Y> entry = cache.get(key);
        return entry != null ? entry.value : null;
    }

    //添加元素
    @Nullable
    public synchronized Y put(@NonNull T key, @Nullable Y item) {
        final int itemSize = getSize(item);

        //当前要存储的元素，比总共的存储空间都大，没法处理；那就抛给子类处理吧
        if (itemSize >= maxSize) {
            onItemEvicted(key, item);
            return null;
        }
        if (item != null) {
            currentSize += itemSize;
        }

        @Nullable
        Entry<Y> old = cache.put(key, item == null ? null : new Entry<>(item, itemSize));
        if (old != null) {
            currentSize -= old.size;

            if (!old.value.equals(item)) {
                onItemEvicted(key, old.value);
            }
        }
        evict();

        return old != null ? old.value : null;
    }

    // 移除一个元素
    @Nullable
    public synchronized Y remove(@NonNull T key) {
        Entry<Y> entry = cache.remove(key);
        if (entry == null) {
            return null;
        }
        currentSize -= entry.size;
        return entry.value;
    }



    public void clearMemory() {
        trimToSize(0);
    }

    // 缩容至指定大小，需要按照LRU的规则，每次移除尾节点
    protected synchronized void trimToSize(long size) {
        Map.Entry<T, Entry<Y>> last;
        Iterator<Map.Entry<T, Entry<Y>>> cacheIterator;
        while (currentSize > size) {
            cacheIterator = cache.entrySet().iterator();
            last = cacheIterator.next();
            final Entry<Y> toRemove = last.getValue();
            currentSize -= toRemove.size;
            final T key = last.getKey();
            cacheIterator.remove();
            onItemEvicted(key, toRemove.value);
        }
    }

    private void evict() {
        trimToSize(maxSize);
    }

    @Synthetic
    static final class Entry<Y> {
        final Y value;
        final int size;

        @Synthetic
        Entry(Y value, int size) {
            this.value = value;
            this.size = size;
        }
    }
}
