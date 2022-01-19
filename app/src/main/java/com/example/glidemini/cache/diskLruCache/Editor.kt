package com.example.glidemini.cache.diskLruCache

import com.example.glidemini.util.Util
import java.io.*

/**
 * 编辑条目的值
 */
class Editor(val entry: Entry) {
    val written: BooleanArray?
    private var committed = false

    //返回一个未缓冲的输入流来读取最后一个提交的值，如果没有提交值，则返回null。
    @Throws(IOException::class)
    private fun newInputStream(index: Int): InputStream? {
        synchronized(this) {
            check(entry.currentEditor == this)
            return if (!entry.readable) {
                null
            } else try {
                FileInputStream(entry.getCleanFile(index))
            } catch (e: FileNotFoundException) {
                null
            }
        }
    }

    //以字符串的形式返回最近提交的值，如果没有提交值，则返回null。
    @Throws(IOException::class)
    fun getString(index: Int): String? {
        val inputStream = newInputStream(index) ?: return null
        return DiskLruCache.inputStreamToString(inputStream)
    }

    @Throws(IOException::class)
    fun getFile(index: Int): File? {
        synchronized(this) {
            check(entry.currentEditor == this)
            if (!entry.readable) {
                written!![index] = true
            }
            val dirtyFile = entry.getDirtyFile(index)
            directory.mkdirs()
            return dirtyFile
        }
    }

    /**
     * Sets the value at `index` to `value`.
     */
    @Throws(IOException::class)
    operator fun set(index: Int, value: String?) {
        var writer: Writer? = null
        try {
            val os: OutputStream = FileOutputStream(getFile(index))
            writer = OutputStreamWriter(os, Util.UTF_8)
            writer!!.write(value)
        } finally {
            Util.closeQuietly(writer)
        }
    }

    /**
     * Commits this edit so it is visible to readers.  This releases the
     * edit lock so another edit may be started on the same key.
     */
    @Throws(IOException::class)
    fun commit() {
        // The object using this Editor must catch and handle any errors
        // during the write. If there is an error and they call commit
        // anyway, we will assume whatever they managed to write was valid.
        // Normally they should call abort.
        completeEdit(this, true)
        committed = true
    }

    /**
     * Aborts this edit. This releases the edit lock so another edit may be
     * started on the same key.
     */
    @Throws(IOException::class)
    fun abort() {
        completeEdit(this, false)
    }

    fun abortUnlessCommitted() {
        if (!committed) {
            try {
                abort()
            } catch (ignored: IOException) {
            }
        }
    }

    init {
        written = if (entry.readable) null else BooleanArray(valueCount)
    }
}
