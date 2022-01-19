package com.example.glidemini.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.glidemini.cache.key.Key;

import java.io.File;

/**
 * 磁盘cache
 */
public interface DiskCache {

    interface Factory {
        //250 MB of cache.
        int DEFAULT_DISK_CACHE_SIZE = 250 * 1024 * 1024;

        String DEFAULT_DISK_CACHE_DIR = "image_manager_disk_cache";

        /**
         * Returns a new disk cache, or {@code null} if no disk cache could be created.
         */
        @Nullable
        DiskCache build();
    }

    interface Writer {
        //向文件写入数据，如果写入成功并且应该提交，则返回true，如果写入应该中止，则返回false。
        boolean write(@NonNull File file);
    }

    //根据Key, 获取对应的缓存文件
    @Nullable
    File get(Key key);

    //添加到缓存
    void put(Key key, Writer writer);

    //移除缓存
    void delete(Key key);

    //清除所有缓存
    void clear();
}
