package com.example.glidemini.bitmapRecycle

import android.content.ComponentCallbacks2
import android.util.Log
import com.example.glidemini.util.Synthetic
import java.lang.IllegalArgumentException
import java.lang.NullPointerException
import java.util.*

/**
 * 一个固定大小的Array Pool，它使用LRU策略驱逐数组
 */
class LruArrayPool(
    private val maxSize: Int = 4 * 1024 * 1024, //4M
) : ArrayPool {
    private val groupedMap = GroupedLinkedMap<Key?, Any>()
    private val keyPool = KeyPool()
    private val sortedSizes: MutableMap<Class<*>, NavigableMap<Int, Int>> = HashMap()
    private val adapters: MutableMap<Class<*>, ArrayAdapterInterface<*>> = HashMap()

    private var currentSize = 0


    @Synchronized
    override fun <T> put(array: T) {
        val arrayClass: Class<T> = array!!.javaClass as Class<T>
        val arrayAdapter = getAdapterFromType(arrayClass)
        val size = arrayAdapter.getArrayLength(array)
        val arrayBytes = size * arrayAdapter.elementSizeInBytes
        if (!isSmallEnoughForReuse(arrayBytes)) {
            return
        }
        val key = keyPool[size, arrayClass]
        groupedMap.put(key, array)
        val sizes = getSizesForAdapter(arrayClass)
        val current = sizes[key!!.size]
        sizes[key.size] = if (current == null) 1 else current + 1
        currentSize += arrayBytes
        evict()
    }

    @Synchronized
    override fun <T> getExact(size: Int, arrayClass: Class<T>): T? {
        val key = keyPool[size, arrayClass]
        return getForKey(key, arrayClass)
    }

    @Synchronized
    override fun <T> get(size: Int, arrayClass: Class<T>): T? {
        val possibleSize = getSizesForAdapter(arrayClass).ceilingKey(size)
        val key: Key?
        key = if (mayFillRequest(size, possibleSize)) {
            keyPool[possibleSize, arrayClass]
        } else {
            keyPool[size, arrayClass]
        }
        return getForKey(key, arrayClass)
    }

    private fun <T> getForKey(key: Key?, arrayClass: Class<T>): T? {
        val arrayAdapter = getAdapterFromType(arrayClass)
        var result: T? = getArrayForKey(key)
        if (result != null) {
            currentSize -= arrayAdapter.getArrayLength(result) * arrayAdapter.elementSizeInBytes
            decrementArrayOfSize(arrayAdapter.getArrayLength(result), arrayClass)
        }
        if (result == null) {
            if (Log.isLoggable(arrayAdapter.tag, Log.VERBOSE)) {
                Log.v(arrayAdapter.tag, "Allocated " + key!!.size + " bytes")
            }
            result = arrayAdapter.newArray(key!!.size)
        }
        return result
    }

    // Our cast is safe because the Key is based on the type.
    private fun <T> getArrayForKey(key: Key?): T? {
        return groupedMap[key] as T?
    }

    private fun isSmallEnoughForReuse(byteSize: Int): Boolean {
        return byteSize <= maxSize / SINGLE_ARRAY_MAX_SIZE_DIVISOR
    }

    private fun mayFillRequest(requestedSize: Int, actualSize: Int?): Boolean {
        return (actualSize != null
                && (isNoMoreThanHalfFull || actualSize <= 8 * requestedSize))
    }

    private val isNoMoreThanHalfFull: Boolean
        private get() = currentSize == 0 || maxSize / currentSize >= 2

    @Synchronized
    override fun clearMemory() {
        evictToSize(0)
    }

    @Synchronized
    override fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            clearMemory()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
            || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            evictToSize(maxSize / 2)
        }
    }

    private fun evict() {
        evictToSize(maxSize)
    }

    private fun evictToSize(size: Int) {
        while (currentSize > size) {
            val evicted = groupedMap.removeLast()
            val arrayAdapter = getAdapterFromObject(evicted)
            currentSize -= arrayAdapter.getArrayLength(evicted) * arrayAdapter.elementSizeInBytes
            decrementArrayOfSize(arrayAdapter.getArrayLength(evicted), evicted!!.javaClass)
            if (Log.isLoggable(arrayAdapter.tag, Log.VERBOSE)) {
                Log.v(arrayAdapter.tag, "evicted: " + arrayAdapter.getArrayLength(evicted))
            }
        }
    }

    private fun decrementArrayOfSize(size: Int, arrayClass: Class<*>) {
        val sizes = getSizesForAdapter(arrayClass)
        val current = sizes[size]
            ?: throw NullPointerException(
                "Tried to decrement empty size, size: $size, this: $this"
            )
        if (current == 1) {
            sizes.remove(size)
        } else {
            sizes[size] = current - 1
        }
    }

    private fun getSizesForAdapter(arrayClass: Class<*>): NavigableMap<Int, Int> {
        var sizes = sortedSizes[arrayClass]
        if (sizes == null) {
            sizes = TreeMap()
            sortedSizes[arrayClass] = sizes
        }
        return sizes
    }

    private fun <T> getAdapterFromObject(`object`: T): ArrayAdapterInterface<T> {
        return getAdapterFromType(`object`!!.javaClass) as ArrayAdapterInterface<T>
    }

    private fun <T> getAdapterFromType(arrayPoolClass: Class<T>): ArrayAdapterInterface<T> {
        var adapter = adapters[arrayPoolClass]
        if (adapter == null) {
            adapter = when (arrayPoolClass) {
                IntArray::class.java -> {
                    IntegerArrayAdapter()
                }
                ByteArray::class.java -> {
                    ByteArrayAdapter()
                }
                else -> {
                    throw IllegalArgumentException("No array pool found for:$arrayPoolClass.simpleName")
                }
            }
            adapters[arrayPoolClass] = adapter
        }
        return adapter as ArrayAdapterInterface<T>
    }

    // VisibleForTesting
    fun getCurrentSize(): Int {
        var currentSize = 0
        for (type in sortedSizes.keys) {
            for (size in sortedSizes[type]!!.keys) {
                val adapter = getAdapterFromType(type)
                currentSize += size * sortedSizes[type]!![size]!! * adapter.elementSizeInBytes
            }
        }
        return currentSize
    }

    @Synthetic
    private class KeyPool internal constructor() : BaseKeyPool<Key?>() {
        operator fun get(size: Int, arrayClass: Class<*>?): Key? {
            val result = get()
            result!!.init(size, arrayClass)
            return result
        }

        override fun create(): Key {
            return Key(this)
        }
    }

    private class Key internal constructor(private val pool: KeyPool) : Poolable {
        @Synthetic
        var size = 0
        private var arrayClass: Class<*>? = null
        fun init(length: Int, arrayClass: Class<*>?) {
            size = length
            this.arrayClass = arrayClass
        }

        override fun equals(o: Any?): Boolean {
            if (o is Key) {
                val other = o
                return size == other.size && arrayClass == other.arrayClass
            }
            return false
        }

        override fun toString(): String {
            return "Key{" + "size=" + size + "array=" + arrayClass + '}'
        }

        override fun offer() {
            pool.offer(this)
        }

        override fun hashCode(): Int {
            var result = size
            result = 31 * result + if (arrayClass != null) arrayClass.hashCode() else 0
            return result
        }
    }

    companion object {

        /**
         * Used to calculate the maximum % of the total pool size a single byte array may consume.
         */
        private const val SINGLE_ARRAY_MAX_SIZE_DIVISOR = 2
    }
}