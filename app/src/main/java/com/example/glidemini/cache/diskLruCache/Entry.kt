package com.example.glidemini.cache.diskLruCache

import java.io.File
import java.io.IOException
import java.lang.NumberFormatException
import java.lang.StringBuilder
import java.util.*

class Entry(
    val key: String,
    private val valueCount: Int,
    private val directory: File,
) {
    /**
     * Lengths of this entry's files.
     */
    val lengths: LongArray

    /**
     * Memoized File objects for this entry to avoid char[] allocations.
     */
    var cleanFiles: Array<File>
    var dirtyFiles: Array<File>

    //改文件是否可读
    var readable = false

    //正在进行的编辑;如果该条目未被编辑，则为空。
    var currentEditor: DiskLruCache.Editor? = null

    /**
     * The sequence number of the most recently committed edit to this entry.
     */
    val sequenceNumber: Long = 0

    @Throws(IOException::class)
    fun getLengths(): String {
        val result = StringBuilder()
        for (size in lengths) {
            result.append(' ').append(size)
        }
        return result.toString()
    }

    /**
     * Set lengths using decimal numbers like "10123".
     */
    @Throws(IOException::class)
    fun setLengths(strings: Array<String>) {
        if (strings.size != valueCount) {
            throw invalidLengths(strings)
        }
        try {
            for (i in strings.indices) {
                lengths[i] = strings[i].toLong()
            }
        } catch (e: NumberFormatException) {
            throw invalidLengths(strings)
        }
    }

    @Throws(IOException::class)
    private fun invalidLengths(strings: Array<String>): IOException {
        throw IOException("unexpected journal line: " + Arrays.toString(strings))
    }

    fun getCleanFile(i: Int): File? {
        return cleanFiles[i]
    }

    fun getDirtyFile(i: Int): File? {
        return dirtyFiles[i]
    }

    init {
        lengths = LongArray(valueCount)
        cleanFiles = arrayOfNulls(valueCount)
        dirtyFiles = arrayOfNulls(valueCount)

        // The names are repetitive so re-use the same builder to avoid allocations.
        val fileBuilder = StringBuilder(key).append('.')
        val truncateTo = fileBuilder.length
        for (i in 0 until valueCount) {
            fileBuilder.append(i)
            cleanFiles[i] = File(directory, fileBuilder.toString())
            fileBuilder.append(".tmp")
            dirtyFiles[i] = File(directory, fileBuilder.toString())
            fileBuilder.setLength(truncateTo)
        }
    }
}
