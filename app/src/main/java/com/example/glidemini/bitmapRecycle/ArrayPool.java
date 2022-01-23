package com.example.glidemini.bitmapRecycle;

/**
 * 数组缓存池
 */
public interface ArrayPool {
    //默认缓存大小
    int STANDARD_BUFFER_SIZE_BYTES = 64 * 1024;


    //添加到缓存区, 也有可能直接废弃
    <T> void put(T array);

    // 返回一个数组, 可以大于所需数组的大小, 也可以新建一个数组
    <T> T get(int size, Class<T> arrayClass);

    // 返回的数组大小, 和预期值一样
    <T> T getExact(int size, Class<T> arrayClass);

    //清空内存
    void clearMemory();

    //缩容
    void trimMemory(int level);
}
