package com.example.glidemini.load.model.header

import kotlin.jvm.Volatile
import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import java.lang.StringBuilder
import java.util.*

/**
 * 用于在Glide请求中包含一组报头的包装类，允许延迟构造报头。
 * 理想情况下，头文件只构造一次，然后在多个加载中重用，而不是在每个加载中单独构造
 * 这里考虑到线程安全
 */
class LazyHeaders(headers: Map<String, MutableList<LazyHeaderFactory>>?) : Headers {
    private val headers: Map<String, MutableList<LazyHeaderFactory>>

    @Volatile
    private var combinedHeaders: Map<String, String>? = null

    override fun getHeaders(): Map<String, String> {
        if (combinedHeaders == null) {
            synchronized(this) {
                if (combinedHeaders == null) {
                    combinedHeaders = Collections.unmodifiableMap(generateHeaders())
                }
            }
        }
        return combinedHeaders!!
    }

    private fun generateHeaders(): Map<String, String> {
        val combinedHeaders: MutableMap<String, String> = HashMap()
        for ((key, value) in headers) {
            val values = buildHeaderValue(value)
            if (!TextUtils.isEmpty(values)) {
                combinedHeaders[key] = values
            }
        }
        return combinedHeaders
    }

    private fun buildHeaderValue(factories: List<LazyHeaderFactory>): String {
        val headerString: ArrayList<String> = ArrayList()
        factories.forEach {
            val header: String? = it.buildHeader()
            if (!header.isNullOrBlank()) {
                headerString.add(header)
            }
        }
        return headerString.joinToString(",")
    }

    override fun toString(): String {
        return "LazyHeaders{headers=$headers}"
    }

    override fun equals(other: Any?): Boolean {
        if (other is LazyHeaders) {
            return headers == other.headers
        }
        return false
    }

    override fun hashCode(): Int {
        return headers.hashCode()
    }

    /**
     * Adds an [LazyHeaderFactory] that will be used to construct a value for the given key*
     * lazily on a background thread.
     *
     *
     * This class is not thread safe.
     *
     *
     * This class may include default values for User-Agent and Accept-Encoding headers. These will
     * be replaced by calls to either [.setHeader] or [ ][.addHeader], even though [.addHeader] would
     * usually append an additional value.
     */
    class Builder {
        companion object {
            private const val USER_AGENT_HEADER = "User-Agent"
            private val DEFAULT_USER_AGENT = sanitizedUserAgent
            private var DEFAULT_HEADERS: MutableMap<String, MutableList<LazyHeaderFactory>>? = null

            //构造合法字符的ua, 不能使用中文, 否则可能有问题
            @get:VisibleForTesting
            val sanitizedUserAgent: String
                get() {
                    val defaultUserAgent = System.getProperty("http.agent") ?: ""
                    if (TextUtils.isEmpty(defaultUserAgent)) {
                        return defaultUserAgent
                    }
                    val length = defaultUserAgent.length
                    val sb = StringBuilder(defaultUserAgent.length)
                    for (i in 0 until length) {
                        val c = defaultUserAgent[i]
                        if ((c > '\u001f' || c == '\t') && c < '\u007f') {
                            sb.append(c)
                        } else {
                            sb.append('?')
                        }
                    }
                    return sb.toString()
                }

            // Set Accept-Encoding header to do our best to avoid gzip since it's both inefficient for
            // images and also makes it more difficult for us to detect and prevent partial content
            // rendering. See #440.
            init {
                val temp: HashMap<String, MutableList<LazyHeaderFactory>> = HashMap(2)
                if (DEFAULT_USER_AGENT.isNotEmpty()) {
                    temp[USER_AGENT_HEADER] = mutableListOf(
                        StringHeaderFactory(DEFAULT_USER_AGENT)
                    )
                }
                DEFAULT_HEADERS = Collections.unmodifiableMap(temp)
            }
        }

        private var copyOnModify = true
        private var headers = DEFAULT_HEADERS
        private var isUserAgentDefault = true


        fun addHeader(key: String, value: String): Builder {
            return addHeader(key, StringHeaderFactory(value))
        }

        private fun addHeader(key: String, factory: LazyHeaderFactory): Builder {
            if (isUserAgentDefault && USER_AGENT_HEADER.equals(key, ignoreCase = true)) {
                return setHeader(key, factory)
            }
            copyIfNecessary()
            getFactories(key).add(factory)
            return this
        }

        fun setHeader(key: String, value: String?): Builder {
            return setHeader(key, value?.let { StringHeaderFactory(it) })
        }

        private fun setHeader(key: String, factory: LazyHeaderFactory?): Builder {
            copyIfNecessary()
            if (factory == null) {
                headers!!.remove(key)
            } else {
                val factories = getFactories(key)
                factories.clear()
                factories.add(factory)
            }
            if (isUserAgentDefault && USER_AGENT_HEADER.equals(key, ignoreCase = true)) {
                isUserAgentDefault = false
            }
            return this
        }

        private fun getFactories(key: String): MutableList<LazyHeaderFactory> {
            var factories = headers!![key]
            if (factories == null) {
                factories = ArrayList()
                headers!![key] = factories
            }
            return factories
        }

        private fun copyIfNecessary() {
            if (copyOnModify) {
                copyOnModify = false
                headers = copyHeaders()
            }
        }


        fun build(): LazyHeaders {
            copyOnModify = true
            return LazyHeaders(headers)
        }

        private fun copyHeaders(): MutableMap<String, MutableList<LazyHeaderFactory>> {
            val result: MutableMap<String, MutableList<LazyHeaderFactory>> = HashMap(
                headers!!.size
            )
            for ((key, value) in headers!!) {
                val valueCopy: MutableList<LazyHeaderFactory> = ArrayList(
                    value
                )
                result[key] = valueCopy
            }
            return result
        }
    }

    init {
        this.headers = Collections.unmodifiableMap(headers)
    }
}