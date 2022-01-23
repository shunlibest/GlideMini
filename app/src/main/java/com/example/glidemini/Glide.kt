package com.example.glidemini

import com.example.glidemini.withManager.RequestManagerRetriever
import com.example.glidemini.Glide.RequestOptionsFactory
import android.content.ComponentCallbacks2
import com.example.glidemini.bitmapRecycle.BitmapPool
import com.example.glidemini.bitmapRecycle.LruBitmapPool
import com.example.glidemini.cache.memoryCache.LruResourceCache
import com.example.glidemini.bitmapRecycle.ArrayPool
import com.example.glidemini.bitmapRecycle.LruArrayPool
import com.example.glidemini.withManager.RequestManager
import kotlin.jvm.Synchronized
import kotlin.jvm.Volatile
import com.example.glidemini.Glide
import android.os.Build
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import ResourceLoader.UriFactory
import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import com.example.glidemini.cache.memoryCache.MemoryCache
import com.example.glidemini.load.engine.Engine
import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.util.ArrayList

@RequiresApi(Build.VERSION_CODES.KITKAT)
class Glide private constructor(
    context: Context,
    private val engine: Engine,
    /**
     * Internal method.
     */

    connectivityMonitorFactory: ConnectivityMonitorFactory,
    logLevel: Int,
    defaultRequestOptionsFactory: RequestOptionsFactory,
    defaultTransitionOptions: Map<Class<*>, TransitionOptions<*, *>>,
    defaultRequestListeners: List<RequestListener<Any>>,
    experiments: GlideExperiments
) : ComponentCallbacks2 {
    //BitmapPool 一共有两种实现 BitmapPoolAdapter 和 LruBitmapPool
    //其中, BitmapPoolAdapter 是空实现,因为在3.0以前 Bitmap 的数据是存在 native 区域，
    // 3.0以后存在 Dalvik 内存区域， API11 后 系统提供了 Bitmap 复用的 API
    //https://developer.android.com/topic/performance/graphics/manage-memory.html
    //在Android O+位图是本地分配的，ART在管理垃圾方面效率更高，我们严重依赖硬件位图，使得位图重用变得不那么重要。
    // 我们倾向于在这些设备上保留RAM，并在加载非常小的图像或生成缩略图时不重用位图和纹理，从而降低性能
    //    private final BitmapPool bitmapPool = new BitmapPoolAdapter();
    //LRU会存储4个或1个屏幕分辨率的内存大小
    private val bitmapPool: BitmapPool = LruBitmapPool(4 * (1920 * 1080 * 4))
    private val memoryCache: MemoryCache = LruResourceCache(4 * 1024 * 1024)
    private val glideContext: GlideContext
    private val registry: Registry

    //默认4M,低端机2M
    private val arrayPool: ArrayPool = LruArrayPool(4 * 1024 * 1024)
    private val connectivityMonitorFactory: ConnectivityMonitorFactory

    private val requestManagerRetriever: RequestManagerRetriever = RequestManagerRetriever()

    @GuardedBy("managers")
    private val managers: MutableList<RequestManager> = ArrayList()
    private val defaultRequestOptionsFactory: RequestOptionsFactory
    private val memoryCategory: MemoryCategory = MemoryCategory.NORMAL

    @GuardedBy("this")
    private var bitmapPreFiller: BitmapPreFiller? = null

    /**
     * Pre-fills the [BitmapPool] using the given sizes.
     *
     *
     * Enough Bitmaps are added to completely fill the pool, so most or all of the Bitmaps
     * currently in the pool will be evicted. Bitmaps are allocated according to the weights of the
     * given sizes, where each size gets (weight / prefillWeightSum) percent of the pool to fill.
     *
     *
     * Note - Pre-filling is done asynchronously using and [IdleHandler]. Any currently
     * running pre-fill will be cancelled and replaced by a call to this method.
     *
     *
     * This method should be used with caution, overly aggressive pre-filling is substantially
     * worse than not pre-filling at all. Pre-filling should only be started in onCreate to avoid
     * constantly clearing and re-filling the [BitmapPool]. Rotation should be carefully
     * considered as well. It may be worth calling this method only when no saved instance state
     * exists so that pre-filling only happens when the Activity is first created, rather than on
     * every rotation.
     *
     * @param bitmapAttributeBuilders The list of [Builders][Builder] representing individual
     * sizes and configurations of [Bitmap]s to be pre-filled.
     */
    @Synchronized  // Public API
    fun preFillBitmapPool(
        vararg bitmapAttributeBuilders: PreFillType.Builder
    ) {
        if (bitmapPreFiller == null) {
            val decodeFormat: DecodeFormat =
                defaultRequestOptionsFactory.build().getOptions().get(Downsampler.DECODE_FORMAT)
            bitmapPreFiller = BitmapPreFiller(memoryCache, bitmapPool, decodeFormat)
        }
        bitmapPreFiller.preFill(bitmapAttributeBuilders)
    }

    //清空内存    public void clearMemory()
    //缩容(内存)  public void trimMemory(int level)
    //清空磁盘缓存 public void clearDiskCache()
    //设置内存策略 public setMemoryCategory
    fun getRegistry(): Registry {
        return registry
    }

    fun removeFromManagers(target: Target<*>): Boolean {
        synchronized(managers) {
            for (requestManager in managers) {
                if (requestManager.untrack(target)) {
                    return true
                }
            }
        }
        return false
    }

    fun registerRequestManager(requestManager: RequestManager) {
        synchronized(managers) {
            check(!managers.contains(requestManager)) { "Cannot register already registered manager" }
            managers.add(requestManager)
        }
    }

    fun unregisterRequestManager(requestManager: RequestManager) {
        synchronized(managers) {
            check(managers.contains(requestManager)) { "Cannot unregister not yet registered manager" }
            managers.remove(requestManager)
        }
    }

    override fun onTrimMemory(level: Int) {
//        trimMemory(level);
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Do nothing.
    }

    override fun onLowMemory() {
//        clearMemory();
    }

    /**
     * Creates a new instance of [RequestOptions].
     */
    interface RequestOptionsFactory {
        /**
         * Returns a non-null [RequestOptions] object.
         */
        fun build(): RequestOptions
    }

    companion object {
        private const val DEFAULT_DISK_CACHE_DIR = "image_manager_disk_cache"
        private const val TAG = "Glide"

        @GuardedBy("Glide.class")
        @Volatile
        private var glide: Glide? = null

        //返回一个单例的glide, 并且会进行初始化
        operator fun get(context: Context): Glide {
            if (glide == null) {
                synchronized(Glide::class.java) {
                    if (glide == null) {
                        val applicationContext = context.applicationContext
                        applicationContext.registerComponentCallbacks(glide)
                        glide = Glide(context)
                    }
                }
            }
            return glide!!
        }

        fun with(context: Context): RequestManager {
            return Companion[context].requestManagerRetriever[context]
        }
    }

    init {
        this.connectivityMonitorFactory = connectivityMonitorFactory
        this.defaultRequestOptionsFactory = defaultRequestOptionsFactory
        val resources = context.resources
        registry = Registry()
        registry.register(DefaultImageHeaderParser())
        // Right now we're only using this parser for HEIF images, which are only supported on OMR1+.
        // If we need this for other file types, we should consider removing this restriction.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            registry.register(ExifInterfaceImageHeaderParser())
        }
        val imageHeaderParsers: List<ImageHeaderParser> = registry.getImageHeaderParsers()
        val byteBufferGifDecoder =
            ByteBufferGifDecoder(context, imageHeaderParsers, bitmapPool, arrayPool)
        val parcelFileDescriptorVideoDecoder: ResourceDecoder<ParcelFileDescriptor, Bitmap> =
            VideoDecoder.parcel(bitmapPool)

        // TODO(judds): Make ParcelFileDescriptorBitmapDecoder work with ImageDecoder.
        val downsampler = Downsampler(
            registry.getImageHeaderParsers(), resources.displayMetrics, bitmapPool, arrayPool
        )
        val byteBufferBitmapDecoder: ResourceDecoder<ByteBuffer, Bitmap>
        val streamBitmapDecoder: ResourceDecoder<InputStream, Bitmap>
        if (experiments.isEnabled(EnableImageDecoderForBitmaps::class.java)
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ) {
            streamBitmapDecoder = InputStreamBitmapImageDecoderResourceDecoder()
            byteBufferBitmapDecoder = ByteBufferBitmapImageDecoderResourceDecoder()
        } else {
            byteBufferBitmapDecoder = ByteBufferBitmapDecoder(downsampler)
            streamBitmapDecoder = StreamBitmapDecoder(downsampler, arrayPool)
        }
        val resourceDrawableDecoder = ResourceDrawableDecoder(context)
        val resourceLoaderStreamFactory: ResourceLoader.StreamFactory = StreamFactory(resources)
        val resourceLoaderUriFactory = UriFactory(resources)
        val resourceLoaderFileDescriptorFactory: ResourceLoader.FileDescriptorFactory =
            FileDescriptorFactory(resources)
        val resourceLoaderAssetFileDescriptorFactory: ResourceLoader.AssetFileDescriptorFactory =
            AssetFileDescriptorFactory(resources)
        val bitmapEncoder = BitmapEncoder(arrayPool)
        val bitmapBytesTranscoder = BitmapBytesTranscoder()
        val gifDrawableBytesTranscoder = GifDrawableBytesTranscoder()
        val contentResolver = context.contentResolver
        registry
            .append(ByteBuffer::class.java, ByteBufferEncoder())
            .append(InputStream::class.java, StreamEncoder(arrayPool)) /* Bitmaps */
            .append(
                Registry.BUCKET_BITMAP,
                ByteBuffer::class.java,
                Bitmap::class.java,
                byteBufferBitmapDecoder
            )
            .append(
                Registry.BUCKET_BITMAP,
                InputStream::class.java,
                Bitmap::class.java,
                streamBitmapDecoder
            )
        if (ParcelFileDescriptorRewinder.isSupported()) {
            registry.append(
                Registry.BUCKET_BITMAP,
                ParcelFileDescriptor::class.java,
                Bitmap::class.java,
                ParcelFileDescriptorBitmapDecoder(downsampler)
            )
        }
        registry
            .append(
                Registry.BUCKET_BITMAP,
                ParcelFileDescriptor::class.java,
                Bitmap::class.java,
                parcelFileDescriptorVideoDecoder
            )
            .append(
                Registry.BUCKET_BITMAP,
                AssetFileDescriptor::class.java,
                Bitmap::class.java,
                VideoDecoder.asset(bitmapPool)
            )
            .append(
                Bitmap::class.java,
                Bitmap::class.java,
                UnitModelLoader.Factory.< Bitmap > getInstance < Bitmap ? > ()
            )
            .append(
                Registry.BUCKET_BITMAP,
                Bitmap::class.java,
                Bitmap::class.java,
                UnitBitmapDecoder()
            )
            .append(Bitmap::class.java, bitmapEncoder) /* BitmapDrawables */
            .append(
                Registry.BUCKET_BITMAP_DRAWABLE,
                ByteBuffer::class.java,
                BitmapDrawable::class.java,
                BitmapDrawableDecoder(resources, byteBufferBitmapDecoder)
            )
            .append(
                Registry.BUCKET_BITMAP_DRAWABLE,
                InputStream::class.java,
                BitmapDrawable::class.java,
                BitmapDrawableDecoder(resources, streamBitmapDecoder)
            )
            .append(
                Registry.BUCKET_BITMAP_DRAWABLE,
                ParcelFileDescriptor::class.java,
                BitmapDrawable::class.java,
                BitmapDrawableDecoder(resources, parcelFileDescriptorVideoDecoder)
            )
            .append(
                BitmapDrawable::class.java,
                BitmapDrawableEncoder(bitmapPool, bitmapEncoder)
            ) /* GIFs */
            .append(
                Registry.BUCKET_GIF,
                InputStream::class.java,
                GifDrawable::class.java,
                StreamGifDecoder(imageHeaderParsers, byteBufferGifDecoder, arrayPool)
            )
            .append(
                Registry.BUCKET_GIF,
                ByteBuffer::class.java,
                GifDrawable::class.java,
                byteBufferGifDecoder
            )
            .append(
                GifDrawable::class.java,
                GifDrawableEncoder()
            ) /* GIF Frames */ // Compilation with Gradle requires the type to be specified for UnitModelLoader here.
            .append(
                GifDecoder::class.java,
                GifDecoder::class.java,
                UnitModelLoader.Factory.< GifDecoder > getInstance < GifDecoder ? > ()
            )
            .append(
                Registry.BUCKET_BITMAP,
                GifDecoder::class.java,
                Bitmap::class.java,
                GifFrameResourceDecoder(bitmapPool)
            ) /* Drawables */
            .append(Uri::class.java, Drawable::class.java, resourceDrawableDecoder)
            .append(
                Uri::class.java,
                Bitmap::class.java,
                ResourceBitmapDecoder(resourceDrawableDecoder, bitmapPool)
            ) /* Files */
            .register(Factory())
            .append(File::class.java, ByteBuffer::class.java, Factory())
            .append(File::class.java, InputStream::class.java, StreamFactory())
            .append(File::class.java, File::class.java, FileDecoder())
            .append(
                File::class.java,
                ParcelFileDescriptor::class.java,
                FileDescriptorFactory()
            ) // Compilation with Gradle requires the type to be specified for UnitModelLoader here.
            .append(
                File::class.java,
                File::class.java,
                UnitModelLoader.Factory.< File > getInstance < java . io . File ? > ()
            ) /* Models */
            .register(Factory(arrayPool))
        if (ParcelFileDescriptorRewinder.isSupported()) {
            registry.register(Factory())
        }
        registry
            .append(
                Int::class.javaPrimitiveType,
                InputStream::class.java,
                resourceLoaderStreamFactory
            )
            .append(
                Int::class.javaPrimitiveType,
                ParcelFileDescriptor::class.java,
                resourceLoaderFileDescriptorFactory
            )
            .append(Int::class.java, InputStream::class.java, resourceLoaderStreamFactory)
            .append(
                Int::class.java,
                ParcelFileDescriptor::class.java,
                resourceLoaderFileDescriptorFactory
            )
            .append(Int::class.java, Uri::class.java, resourceLoaderUriFactory)
            .append(
                Int::class.javaPrimitiveType,
                AssetFileDescriptor::class.java,
                resourceLoaderAssetFileDescriptorFactory
            )
            .append(
                Int::class.java,
                AssetFileDescriptor::class.java,
                resourceLoaderAssetFileDescriptorFactory
            )
            .append(Int::class.javaPrimitiveType, Uri::class.java, resourceLoaderUriFactory)
            .append(String::class.java, InputStream::class.java, StreamFactory<String>())
            .append(Uri::class.java, InputStream::class.java, StreamFactory<Uri>())
            .append(String::class.java, InputStream::class.java, StreamFactory())
            .append(String::class.java, ParcelFileDescriptor::class.java, FileDescriptorFactory())
            .append(
                String::class.java, AssetFileDescriptor::class.java, AssetFileDescriptorFactory()
            )
            .append(Uri::class.java, InputStream::class.java, StreamFactory(context.assets))
            .append(
                Uri::class.java,
                ParcelFileDescriptor::class.java,
                FileDescriptorFactory(context.assets)
            )
            .append(Uri::class.java, InputStream::class.java, Factory(context))
            .append(Uri::class.java, InputStream::class.java, Factory(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registry.append(
                Uri::class.java, InputStream::class.java, InputStreamFactory(context)
            )
            registry.append(
                Uri::class.java,
                ParcelFileDescriptor::class.java,
                FileDescriptorFactory(context)
            )
        }
        registry
            .append(Uri::class.java, InputStream::class.java, StreamFactory(contentResolver))
            .append(
                Uri::class.java,
                ParcelFileDescriptor::class.java,
                FileDescriptorFactory(contentResolver)
            )
            .append(
                Uri::class.java,
                AssetFileDescriptor::class.java,
                AssetFileDescriptorFactory(contentResolver)
            )
            .append(Uri::class.java, InputStream::class.java, StreamFactory())
            .append(URL::class.java, InputStream::class.java, StreamFactory())
            .append(Uri::class.java, File::class.java, Factory(context))
            .append(GlideUrl::class.java, InputStream::class.java, Factory())
            .append(ByteArray::class.java, ByteBuffer::class.java, ByteBufferFactory())
            .append(ByteArray::class.java, InputStream::class.java, StreamFactory())
            .append(
                Uri::class.java,
                Uri::class.java,
                UnitModelLoader.Factory.< Uri > getInstance < android . net . Uri ? > ()
            )
            .append(
                Drawable::class.java,
                Drawable::class.java,
                UnitModelLoader.Factory.< Drawable > getInstance < android . graphics . drawable . Drawable ? > ()
            )
            .append(
                Drawable::class.java,
                Drawable::class.java,
                UnitDrawableDecoder()
            ) /* Transcoders */
            .register(
                Bitmap::class.java,
                BitmapDrawable::class.java,
                BitmapDrawableTranscoder(resources)
            )
            .register(Bitmap::class.java, ByteArray::class.java, bitmapBytesTranscoder)
            .register(
                Drawable::class.java,
                ByteArray::class.java,
                DrawableBytesTranscoder(
                    bitmapPool, bitmapBytesTranscoder, gifDrawableBytesTranscoder
                )
            )
            .register(GifDrawable::class.java, ByteArray::class.java, gifDrawableBytesTranscoder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val byteBufferVideoDecoder: ResourceDecoder<ByteBuffer, Bitmap> =
                VideoDecoder.byteBuffer(bitmapPool)
            registry.append(ByteBuffer::class.java, Bitmap::class.java, byteBufferVideoDecoder)
            registry.append(
                ByteBuffer::class.java,
                BitmapDrawable::class.java,
                BitmapDrawableDecoder(resources, byteBufferVideoDecoder)
            )
        }
        val imageViewTargetFactory = ImageViewTargetFactory()
        glideContext = GlideContext(
            context,
            arrayPool,
            registry,
            imageViewTargetFactory,
            defaultRequestOptionsFactory,
            defaultTransitionOptions,
            defaultRequestListeners,
            engine,
            experiments,
            logLevel
        )
    }
}