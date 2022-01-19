package com.example.glidemini.cache.key

import android.net.Uri
import kotlin.jvm.Volatile
import kotlin.jvm.JvmOverloads
import kotlin.Throws
import com.example.glidemini.load.model.header.Headers
import java.net.MalformedURLException
import java.net.URL
import java.security.MessageDigest

/**
 * 这里自己实现了一个URL类, 用于表示http/https,
 * 这里提升了使用性能, 已经使用安全性, 有不同级别的URL, 考虑到多种case;
 * 并且使用了缓存, 不构造对象的方式
 */
class GlideUrl : Key {
    private val headers: Headers
    private val url: URL?
    private val stringUrl: String?
    private var safeStringUrl: String? = null
        get() {
            if (field.isNullOrEmpty()) {
                val unsafeStringUrl: String = stringUrl ?: url?.toString() ?: ""
                field = Uri.encode(unsafeStringUrl, ALLOWED_URI_CHARS)
            }
            return field
        }

    // 对于URL会涉及到转义的问题,具体可以详细了解一下
    @get:Throws(MalformedURLException::class)
    private var safeUrl: URL? = null
        get() {
            if (field == null) {
                field = URL(safeStringUrl)
            }
            return field!!
        }

    @Volatile
    private var cacheKeyBytes: ByteArray? = null
        get() {
            if (field == null) {
                field = cacheKey.toByteArray(Key.CHARSET)
            }
            return field
        }
    private var hashCode = 0

    @JvmOverloads
    constructor(url: URL, headers: Headers = Headers.DEFAULT) {
        this.url = url
        this.stringUrl = null
        this.headers = headers
    }

    @JvmOverloads
    constructor(url: String, headers: Headers = Headers.DEFAULT) {
        this.url = null
        this.stringUrl = url
        this.headers = headers
    }

    @Throws(MalformedURLException::class)
    fun toURL(): URL {
        return safeUrl!!
    }

    fun toStringUrl(): String? {
        return safeStringUrl
    }


    fun getHeaders(): Map<String, String> {
        return headers.headers
    }

    private val cacheKey: String
        get() = stringUrl ?: url.toString()

    override fun toString(): String {
        return cacheKey
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(cacheKeyBytes!!)
    }

    override fun equals(other: Any?): Boolean {
        if (other is GlideUrl) {
            return cacheKey == other.cacheKey && headers == other.headers
        }
        return false
    }

    override fun hashCode(): Int {
        if (hashCode == 0) {
            hashCode = cacheKey.hashCode()
            hashCode = 31 * hashCode + headers.hashCode()
        }
        return hashCode
    }

    companion object {
        private const val ALLOWED_URI_CHARS = "@#&=*+-_.,:!?()/~'%;$"
    }
}