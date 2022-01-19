package com.example.glidemini.cache.diskLruCache

import java.util.concurrent.ThreadFactory


/**
 * A [ThreadFactory] that builds a thread with a specific thread name
 * and with minimum priority.
 */
class DiskLruCacheThreadFactory : ThreadFactory {
    @Synchronized
    override fun newThread(runnable: Runnable): Thread {
        val result = Thread(runnable, "glide-disk-lru-cache-thread")
        result.priority = Thread.MIN_PRIORITY
        return result
    }
}