package com.example.glidemini.cache.diskLruCache

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A snapshot of the values for an entry.
 */

class Value(
    private val key: String,
    private val sequenceNumber: Long,
    private val files: Array<File>,
    private val lengths: LongArray
) {
    /**
     * Returns an editor for this snapshot's entry, or null if either the
     * entry has changed since this snapshot was created or if another edit
     * is in progress.
     */
    @Throws(IOException::class)
    fun edit(): Editor {
        return this@DiskLruCache.edit(key, sequenceNumber)
    }

    fun getFile(index: Int): File {
        return files[index]
    }

    /**
     * Returns the string value for `index`.
     */
    @Throws(IOException::class)
    fun getString(index: Int): String {
        val `is`: InputStream = FileInputStream(files[index])
        return DiskLruCache.inputStreamToString(`is`)
    }

    /**
     * Returns the byte length of the value for `index`.
     */
    fun getLength(index: Int): Long {
        return lengths[index]
    }
}