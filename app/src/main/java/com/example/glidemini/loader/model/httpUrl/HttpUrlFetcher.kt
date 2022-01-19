package com.example.glidemini.loader.model.httpUrl

import android.text.TextUtils
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.glidemini.load.DataSource
import com.example.glidemini.load.HttpException
import com.example.glidemini.cache.key.GlideUrl
import com.example.glidemini.loader.model.DataFetcher
import com.example.glidemini.loader.model.Priority
import com.example.glidemini.util.ContentLengthInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 获取URL流
 */
class HttpUrlFetcher internal constructor(
    private val glideUrl: GlideUrl,
    private val timeout: Int,
    private val connectionFactory: HttpUrlConnectionFactory = DEFAULT_CONNECTION_FACTORY
) : DataFetcher<InputStream> {

    private var urlConnection: HttpURLConnection? = null
    private var stream: InputStream? = null

    @Volatile
    private var isCancelled = false


    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        try {
            val result = loadDataWithRedirects(glideUrl.toURL(), 0, null, glideUrl.getHeaders())
            callback.onDataReady(result)
        } catch (e: IOException) {
            Log.d(TAG, "Failed to load data for url", e)
            callback.onLoadFailed(e)
        }
    }

    @Throws(HttpException::class)
    private fun loadDataWithRedirects(
        url: URL, redirects: Int, lastUrl: URL?, headers: Map<String, String>
    ): InputStream? {
        //这里会会使用到重定向的功能
        //需要处理递归调用, 避免循环定向
        val connection = buildAndConfigureConnection(url, headers)
        urlConnection = connection
        //直接获取连接流
        stream = try {
            // Connect explicitly to avoid errors in decoders if connection fails.
            connection.connect()
            // Set the stream so that it's closed in cleanup to avoid resource leaks. See #2352.
            connection.inputStream
        } catch (e: IOException) {
            throw HttpException(
                "Failed to connect or obtain data", getHttpStatusCodeOrInvalid(urlConnection), e
            )
        }
        if (isCancelled) {
            return null
        }
        val statusCode = getHttpStatusCodeOrInvalid(urlConnection)
        return when {
            isHttpOk(statusCode) -> {
                getStreamForSuccessfulRequest(urlConnection)
            }
            isHttpRedirect(statusCode) -> {
                val redirectUrlString = urlConnection!!.getHeaderField(REDIRECT_HEADER_FIELD)
                if (TextUtils.isEmpty(redirectUrlString)) {
                    throw HttpException("Received empty or null redirect url", statusCode)
                }
                val redirectUrl = URL(url, redirectUrlString)
                // 这里需要关闭好,避免泄漏
                cleanup()
                loadDataWithRedirects(redirectUrl, redirects + 1, url, headers)
            }
            else -> {
                try {
                    throw HttpException(urlConnection?.responseMessage, statusCode)
                } catch (e: IOException) {
                    throw HttpException("Failed to get a response message", statusCode, e)
                }
            }
        }
    }

    private fun buildAndConfigureConnection(
        url: URL,
        headers: Map<String, String>
    ): HttpURLConnection {
        val urlConnection = connectionFactory.build(url)

        for ((key, value) in headers) {
            urlConnection.addRequestProperty(key, value)
        }
        urlConnection.connectTimeout = timeout
        urlConnection.readTimeout = timeout
        urlConnection.useCaches = false
        urlConnection.doInput = true
        // 不使用urlConnection通过的重定向功能, 而是自己实现了重定向功能
        urlConnection.instanceFollowRedirects = false
        return urlConnection
    }

    //处理成功情况下的输入URL流
    @Throws(HttpException::class)
    private fun getStreamForSuccessfulRequest(urlConnection: HttpURLConnection?): InputStream? {
        if (urlConnection == null) {
            return null
        }
        stream = try {
            if (urlConnection.contentEncoding.isEmpty()) {
                val contentLength: Long = urlConnection.contentLength.toLong()
                ContentLengthInputStream.obtain(urlConnection.inputStream, contentLength)
            } else {
                urlConnection.inputStream
            }
        } catch (e: IOException) {
            throw HttpException(
                "Failed to obtain InputStream",
                getHttpStatusCodeOrInvalid(urlConnection), e
            )
        }
        return stream
    }

    override fun cleanup() {
        stream?.close()
        urlConnection?.disconnect()
        urlConnection = null
    }

    override fun cancel() {
        isCancelled = true
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.REMOTE
    }

    interface HttpUrlConnectionFactory {
        @Throws(IOException::class)
        fun build(url: URL?): HttpURLConnection
    }

    companion object {
        private const val TAG = "HttpUrlFetcher"

        @VisibleForTesting
        val REDIRECT_HEADER_FIELD = "Location"

        @VisibleForTesting
        val DEFAULT_CONNECTION_FACTORY: HttpUrlConnectionFactory = DefaultHttpUrlConnectionFactory()


        private fun getHttpStatusCodeOrInvalid(urlConnection: HttpURLConnection?): Int {
            if (urlConnection == null) {
                return -1
            }
            try {
                return urlConnection.responseCode
            } catch (e: IOException) {
                Log.d(TAG, "Failed to get a response code", e)
            }
            return -1
        }

        // 2开头的都是成功系列
        private fun isHttpOk(statusCode: Int): Boolean {
            return statusCode / 100 == 2
        }

        // 3开头的都是重定向
        private fun isHttpRedirect(statusCode: Int): Boolean {
            return statusCode / 100 == 3
        }
    }
}