package com.example.glidemini.bitmapRecycle

import android.graphics.Bitmap
import kotlin.jvm.Synchronized
import android.content.ComponentCallbacks2
import android.os.Build
import android.annotation.TargetApi
import android.graphics.Color
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.glidemini.util.Synthetic
import java.util.*
import kotlin.math.roundToLong

/**
 * LRU的bitmap缓存池
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
class LruBitmapPool constructor(
    private val initialMaxSize: Long,   //唯一的作用就是缩容和扩容的基准值
    private val strategy: LruPoolStrategy = SizeConfigStrategy(), //4.4之前是AttributeStrategy()
    private val allowedConfigs: Set<Bitmap.Config?> = HashSet(listOf(*Bitmap.Config.values())).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            //处理一下GIF图
            add(null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            remove(Bitmap.Config.HARDWARE)
        }
    }
) : BitmapPool {

    private val tracker: BitmapTracker = NullBitmapTracker()
    private var maxSize: Long = initialMaxSize


    //当前的使用内存大小
    var currentSize: Long = 0
        private set
    var hits = 0L
        private set
    var misses = 0L
        private set
    var puts = 0
        private set
    var evictions = 0
        private set


    override fun getMaxSize(): Long {
        return maxSize
    }

    @Synchronized
    override fun setSizeMultiplier(sizeMultiplier: Float) {
        maxSize = (initialMaxSize * sizeMultiplier).roundToLong()
        evict()
    }

    @Synchronized
    override fun put(bitmap: Bitmap) {
        //确保bitmap没有被回收
        check(!bitmap.isRecycled) { "Cannot pool recycled bitmap" }
        if (!bitmap.isMutable
            || strategy.getSize(bitmap) > maxSize || !allowedConfigs.contains(bitmap.config)
        ) {
            Log.v(TAG, "Reject bitmap from pool bitmap:${strategy.logBitmap(bitmap)}")
            bitmap.recycle()
            return
        }
        val size = strategy.getSize(bitmap)
        strategy.put(bitmap)
        tracker.add(bitmap)
        puts++
        currentSize += size.toLong()
        Log.v(TAG, "Put bitmap in pool=" + strategy.logBitmap(bitmap))
        dumpInfo()
        evict()
    }

    private fun evict() {
        trimToSize(maxSize)
    }

    override fun get(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        var result = getDirtyOrNull(width, height, config)
        if (result != null) {
            // 擦除数据
            result.eraseColor(Color.TRANSPARENT)
        } else {
            result = createBitmap(width, height, config)
        }
        return result
    }

    override fun getDirty(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        return getDirtyOrNull(width, height, config) ?: createBitmap(width, height, config)
    }

    @Synchronized
    private fun getDirtyOrNull(
        width: Int, height: Int, config: Bitmap.Config?
    ): Bitmap? {
        assertNotHardwareConfig(config)
        // Config will be null for non public config types, which can lead to transformations naively
        // passing in null as the requested config here. See issue #194.
        val result = strategy[width, height, config ?: DEFAULT_CONFIG]
        if (result == null) {
            Log.d(TAG, "Missing bitmap=" + strategy.logBitmap(width, height, config))
            misses++
        } else {
            hits++
            currentSize -= strategy.getSize(result).toLong()
            tracker.remove(result)
            normalize(result)
        }
        Log.v(TAG, "Get bitmap=" + strategy.logBitmap(width, height, config))
        dumpInfo()
        return result
    }

    override fun clearMemory() {
        Log.d(TAG, "clearMemory")
        trimToSize(0)
    }


    override fun trimMemory(level: Int) {
        Log.d(TAG, "trimMemory, level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        ) {
            clearMemory()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
            || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            trimToSize(getMaxSize() / 2)
        }
    }

    @Synchronized
    private fun trimToSize(size: Long) {
        while (currentSize > size) {
            val removed = strategy.removeLast()
            // TODO: This shouldn't ever happen, see #331.
            if (removed == null) {
                Log.w(TAG, "Size mismatch, resetting")
                dumpInfo()
                currentSize = 0
                return
            }
            tracker.remove(removed)
            currentSize -= strategy.getSize(removed).toLong()
            evictions++
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Evicting bitmap=" + strategy.logBitmap(removed))
            }
            dumpInfo()
            removed.recycle()
        }
    }

    private fun createBitmap(width: Int, height: Int, config: Bitmap.Config?): Bitmap {
        return Bitmap.createBitmap(width, height, config ?: DEFAULT_CONFIG)
    }

    private fun dumpInfo() {
        Log.v(
            TAG,
            """
                Hits=$hits, misses=$misses, puts=$puts, evictions=$evictions, currentSize=$currentSize, maxSize=$initialMaxSize
                Strategy=$strategy
                """.trimIndent()
        )
    }

    private interface BitmapTracker {
        fun add(bitmap: Bitmap)
        fun remove(bitmap: Bitmap)
    }

    // Only used for debugging
    private class ThrowingBitmapTracker : BitmapTracker {
        private val bitmaps = Collections.synchronizedSet(HashSet<Bitmap>())
        override fun add(bitmap: Bitmap) {
            check(!bitmaps.contains(bitmap)) {
                ("Can't add already added bitmap: "
                        + bitmap
                        + " ["
                        + bitmap.width
                        + "x"
                        + bitmap.height
                        + "]")
            }
            bitmaps.add(bitmap)
        }

        override fun remove(bitmap: Bitmap) {
            check(bitmaps.contains(bitmap)) { "Cannot remove bitmap not in tracker" }
            bitmaps.remove(bitmap)
        }
    }

    @Synthetic
    private class NullBitmapTracker() : BitmapTracker {
        override fun add(bitmap: Bitmap) {
            // Do nothing.
        }

        override fun remove(bitmap: Bitmap) {
            // Do nothing.
        }
    }

    companion object {
        private const val TAG = "LruBitmapPool"
        private val DEFAULT_CONFIG = Bitmap.Config.ARGB_8888


        @TargetApi(Build.VERSION_CODES.O)
        private fun assertNotHardwareConfig(config: Bitmap.Config?) {
            // Avoid short circuiting on sdk int since it breaks on some versions of Android.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }
            require(config != Bitmap.Config.HARDWARE) {
                ("Cannot create a mutable Bitmap with config: "
                        + config
                        + ". Consider setting Downsampler#ALLOW_HARDWARE_CONFIG to false in your"
                        + " RequestOptions and/or in GlideBuilder.setDefaultRequestOptions")
            }
        }

        // Setting these two values provides Bitmaps that are essentially equivalent to those returned
        // from Bitmap.createBitmap.
        private fun normalize(bitmap: Bitmap) {
            bitmap.setHasAlpha(true)
            maybeSetPreMultiplied(bitmap)
        }

        @TargetApi(Build.VERSION_CODES.KITKAT)
        private fun maybeSetPreMultiplied(bitmap: Bitmap) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                bitmap.isPremultiplied = true
            }
        }


    }


}