package com.example.glidemini.cache.memoryCache

import com.example.glidemini.cache.memoryCache.MemoryCache.ResourceRemovedListener
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import com.example.glidemini.cache.key.Key
import com.example.glidemini.load.engine.Resource

/**
 * LRU存储资源
 */
class LruResourceCache(size: Long) : LruCache<Key, Resource<*>?>(size), MemoryCache {
    private var listener: ResourceRemovedListener? = null

    override fun setResourceRemovedListener(listener: ResourceRemovedListener) {
        this.listener = listener
    }

    override fun onItemEvicted(key: Key, item: Resource<*>) {
        listener?.onResourceRemoved(item)
    }

    override fun getSize(item: Resource<*>?): Int {
        return item?.size ?: super.getSize(null)
    }


    override fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // Entering list of cached background apps
            // Evict our entire bitmap cache
            clearMemory()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
            || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            // The app's UI is no longer visible, or app is in the foreground but system is running
            // critically low on memory
            // Evict oldest half of our bitmap cache
            trimToSize(maxSize / 2)
        }
    }
}