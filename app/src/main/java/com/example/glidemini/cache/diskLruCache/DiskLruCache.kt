package com.example.glidemini.cache.diskLruCache

import kotlin.Throws
import kotlin.jvm.Synchronized
import android.annotation.TargetApi
import android.os.Build.VERSION_CODES
import android.os.Build.VERSION
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode
import com.example.glidemini.util.Util
import java.io.*
import java.lang.IllegalStateException
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.*


/**
 *磁盘LRU缓存, 在glide中属于独立的第三方模块, 由大神 Jake Wharton所写
 * 这个缓存使用一个名为“journal”的日志文件
 * DiskLruCache在本地有一个journal的日志文件
 *     libcore.io.DiskLruCache
 *     1
 *     100
 *     2
 *
 *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
 *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
 *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
 *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
 *     DIRTY 1ab96a171faeeee38496d8b330771a7a
 *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
 *     READ 335c4c6028171cfddfbaae1a9c313c52
 *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
 *
 * 其中1表示diskCache的版本，100表示应用的版本，2表示一个key对应多少个缓存文件。
 * 接下来每一行，对应[状态] [key] [缓存文件1的size] [缓存文件2的size]
 * 会有四种状态：
 * DIRTY 创建或者修改一个缓存的时候，会有一条DIRTY记录，后面会跟一个CLEAN或REMOVE的记录。如果没有CLEAN或REMOVE，对应的缓存文件是无效的，会被删掉
 * CLEAN 表示对应的缓存操作成功了，后面会带上缓存文件的大小
 * REMOVE 表示对应的缓存被删除了
 * READ 表示对应的缓存被访问了，因为LRU需要READ记录来调整缓存的顺序
 *
 * T当缓存操作发生时，日志文件被追加到其中。日记账有时可以通过去掉多余的行来压缩。
 * 在压缩过程中会使用一个名为"journal.tmp"的临时文件;如果在打开缓存时存在该文件，则应该删除该文件。
 */
class DiskLruCache private constructor(
    val directory: File,
    private val appVersion: Int,
    valueCount: Int,
    maxSize: Long
) : Closeable {
    private val journalFile: File
    private val journalFileTmp: File
    private val journalFileBackup: File
    private var maxSize: Long
    private val valueCount: Int
    private var size: Long = 0
    private var journalWriter: Writer? = null
    private val lruEntries = LinkedHashMap<String, Entry?>(0, 0.75f, true)
    private var redundantOpCount = 0

    //为了区分旧快照和当前快照，每次编辑时都会给每个条目一个序列号。
    //如果快照的序列号与其条目的序列号不相等，那么快照就是过时的。
    private var nextSequenceNumber: Long = 0

    //这个缓存使用一个后台线程
    private val executorService = ThreadPoolExecutor(
        0, 1, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(),
        DiskLruCacheThreadFactory()
    )

    private val cleanupCallable: Callable<Void> = Callable {
        synchronized(this@DiskLruCache) {
            if (journalWriter == null) {
                return@Callable null // Closed.
            }
            trimToSize()
            if (journalRebuildRequired()) {
                rebuildJournal()
                redundantOpCount = 0
            }
        }
        null
    }

    //内部维护一个日志文件, 用于APP再次启动之后的缓存
    @Throws(IOException::class)
    private fun readJournal() {
        val reader =
            StrictLineReader(FileInputStream(journalFile), charset = Charset.forName("US-ASCII"))
        try {
            val magic: String = reader.readLine()
            val version: String = reader.readLine()
            val appVersionString: String = reader.readLine()
            val valueCountString: String = reader.readLine()
            val blank: String = reader.readLine()
            //日志文件, 合法性校验
            if (MAGIC != magic || VERSION_1 != version
                || appVersion.toString() != appVersionString
                || valueCount.toString() != valueCountString
                || blank.isNotEmpty()
            ) {
                throw IOException("unexpected journal header: [ $magic, $version $valueCountString")
            }
            var lineCount = 0
            while (true) {
                try {
                    readJournalLine(reader.readLine())
                    lineCount++
                } catch (endOfJournal: EOFException) {
                    break
                }
            }
            redundantOpCount = lineCount - lruEntries.size

            // 当检测到文件最后结尾处,不正常截断，则会重新构建日志文件.
            if (reader.hasUnterminatedLine()) {
                rebuildJournal()
            } else {
                journalWriter = BufferedWriter(
                    OutputStreamWriter(
                        FileOutputStream(journalFile, true),
                        Charset.forName("US-ASCII")
                    )
                )
            }
        } finally {
            Util.closeQuietly(reader)
        }
    }

    //格式: CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
    @Throws(IOException::class)
    private fun readJournalLine(line: String) {
        val stringArray = line.splitToSequence(' ')
        val option: String = stringArray.elementAt(0)
        val key: String = stringArray.elementAt(1)
        if (option == REMOVE) {
            lruEntries.remove(key)
            return
        }
        if (lruEntries[key] == null) {
            lruEntries[key] = Entry(key, valueCount, directory)
        }
        val entry = lruEntries[key]!!
        when (option) {
            CLEAN -> {
                val parts = stringArray.drop(2).toList().toTypedArray()
                entry.readable = true
                entry.currentEditor = null
                entry.setLengths(parts)
            }
            DIRTY -> {
                entry.currentEditor = Editor(entry)
            }
            READ -> {
                //已经通过调用lruEntries.get()完成了。
            }
        }
    }

    /**
     * Computes the initial size and collects garbage as a part of opening the
     * cache. Dirty entries are assumed to be inconsistent and will be deleted.
     */
    @Throws(IOException::class)
    private fun processJournal() {
        deleteIfExists(journalFileTmp)
        val i = lruEntries.values.iterator()
        while (i.hasNext()) {
            val entry = i.next()
            if (entry!!.currentEditor == null) {
                for (t in 0 until valueCount) {
                    size += entry.lengths[t]
                }
            } else {
                entry.currentEditor = null
                for (t in 0 until valueCount) {
                    deleteIfExists(entry.getCleanFile(t))
                    deleteIfExists(entry.getDirtyFile(t))
                }
                i.remove()
            }
        }
    }

    //创建一个省略冗余信息的新日志。这将替换当前日志
    @Synchronized
    @Throws(IOException::class)
    private fun rebuildJournal() {
        if (journalWriter != null) {
            closeWriter(journalWriter!!)
        }
        val writer: Writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(journalFileTmp), Charset.forName("US-ASCII"))
        )
        try {
            writer.write(MAGIC)
            writer.write("\n")
            writer.write(VERSION_1)
            writer.write("\n")
            writer.write(Integer.toString(appVersion))
            writer.write("\n")
            writer.write(Integer.toString(valueCount))
            writer.write("\n")
            writer.write("\n")
            for (entry in lruEntries.values) {
                if (entry!!.currentEditor != null) {
                    writer.write(
                        """$DIRTY ${entry.key}
"""
                    )
                } else {
                    writer.write(
                        """$CLEAN ${entry.key}${entry.getLengths()}
"""
                    )
                }
            }
        } finally {
            closeWriter(writer)
        }
        if (journalFile.exists()) {
            renameTo(journalFile, journalFileBackup, true)
        }
        renameTo(journalFileTmp, journalFile, false)
        journalFileBackup.delete()
        journalWriter = BufferedWriter(
            OutputStreamWriter(FileOutputStream(journalFile, true), Charset.forName("US-ASCII"))
        )
    }

    // 返回名为key的条目的快照，如果该条目不存在，则返回null，当前不可读。
    // 如果返回一个值，它将被移动到LRU队列的头部
    @Synchronized
    @Throws(IOException::class)
    operator fun get(key: String): Value? {
        val journalWriter = journalWriter ?: return null
        val entry = lruEntries[key] ?: return null
        if (!entry.readable) {
            return null
        }
        for (file in entry.cleanFiles) {
            if (!file.exists()) {
                return null
            }
        }
        redundantOpCount++
        journalWriter.appendLine("$READ $key")
        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable)
        }
        return Value(key, entry.sequenceNumber, entry.cleanFiles, entry.lengths)
    }

    /**
     * Returns an editor for the entry named `key`, or null if another
     * edit is in progress.
     */
    @Throws(IOException::class)
    fun edit(key: String): Editor? {
        return edit(key, ANY_SEQUENCE_NUMBER)
    }

    @Synchronized
    @Throws(IOException::class)
    private fun edit(key: String, expectedSequenceNumber: Long): Editor? {
        checkNotClosed()
        var entry = lruEntries[key]
        if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER && (entry == null
                    || entry.sequenceNumber != expectedSequenceNumber)
        ) {
            return null // Value is stale.
        }
        if (entry == null) {
            entry = Entry(key)
            lruEntries[key] = entry
        } else if (entry.currentEditor != null) {
            return null // Another edit is in progress.
        }
        val editor: Editor = Editor(entry)
        entry!!.currentEditor = editor

        // Flush the journal before creating files to prevent file leaks.
        journalWriter!!.append(DIRTY)
        journalWriter!!.append(' ')
        journalWriter!!.append(key)
        journalWriter!!.append('\n')
        flushWriter(journalWriter)
        return editor
    }

    /**
     * Returns the maximum number of bytes that this cache should use to store
     * its data.
     */
    @Synchronized
    fun getMaxSize(): Long {
        return maxSize
    }

    /**
     * Changes the maximum number of bytes the cache can store and queues a job
     * to trim the existing store, if necessary.
     */
    @Synchronized
    fun setMaxSize(maxSize: Long) {
        this.maxSize = maxSize
        executorService.submit(cleanupCallable)
    }

    /**
     * Returns the number of bytes currently being used to store the values in
     * this cache. This may be greater than the max size if a background
     * deletion is pending.
     */
    @Synchronized
    fun size(): Long {
        return size
    }

    @Synchronized
    @Throws(IOException::class)
    private fun completeEdit(editor: Editor, success: Boolean) {
        val entry = editor.entry
        check(entry.currentEditor == editor)

        // If this edit is creating the entry for the first time, every index must have a value.
        if (success && !entry.readable) {
            for (i in 0 until valueCount) {
                if (!editor.written!![i]) {
                    editor.abort()
                    throw IllegalStateException("Newly created entry didn't create value for index $i")
                }
                if (!entry.getDirtyFile(i)!!.exists()) {
                    editor.abort()
                    return
                }
            }
        }
        for (i in 0 until valueCount) {
            val dirty = entry.getDirtyFile(i)
            if (success) {
                if (dirty!!.exists()) {
                    val clean = entry.getCleanFile(i)
                    dirty.renameTo(clean)
                    val oldLength = entry.lengths[i]
                    val newLength = clean!!.length()
                    entry.lengths[i] = newLength
                    size = size - oldLength + newLength
                }
            } else {
                deleteIfExists(dirty)
            }
        }
        redundantOpCount++
        entry.currentEditor = null
        if (entry.readable or success) {
            entry.readable = true
            journalWriter!!.append(CLEAN)
            journalWriter!!.append(' ')
            journalWriter!!.append(entry.key)
            journalWriter!!.append(entry.getLengths())
            journalWriter!!.append('\n')
            if (success) {
                entry.sequenceNumber = nextSequenceNumber++
            }
        } else {
            lruEntries.remove(entry.key)
            journalWriter!!.append(REMOVE)
            journalWriter!!.append(' ')
            journalWriter!!.append(entry.key)
            journalWriter!!.append('\n')
        }
        flushWriter(journalWriter)
        if (size > maxSize || journalRebuildRequired()) {
            executorService.submit(cleanupCallable)
        }
    }

    /**
     * We only rebuild the journal when it will halve the size of the journal
     * and eliminate at least 2000 ops.
     */
    private fun journalRebuildRequired(): Boolean {
        val redundantOpCompactThreshold = 2000
        return (redundantOpCount >= redundantOpCompactThreshold //
                && redundantOpCount >= lruEntries.size)
    }

    /**
     * Drops the entry for `key` if it exists and can be removed. Entries
     * actively being edited cannot be removed.
     *
     * @return true if an entry was removed.
     */
    @Synchronized
    @Throws(IOException::class)
    fun remove(key: String): Boolean {
        checkNotClosed()
        val entry = lruEntries[key]
        if (entry == null || entry.currentEditor != null) {
            return false
        }
        for (i in 0 until valueCount) {
            val file = entry.getCleanFile(i)
            if (file!!.exists() && !file.delete()) {
                throw IOException("failed to delete $file")
            }
            size -= entry.lengths[i]
            entry.lengths[i] = 0
        }
        redundantOpCount++
        journalWriter!!.append(REMOVE)
        journalWriter!!.append(' ')
        journalWriter!!.append(key)
        journalWriter!!.append('\n')
        lruEntries.remove(key)
        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable)
        }
        return true
    }

    /**
     * Returns true if this cache has been closed.
     */
    @get:Synchronized
    val isClosed: Boolean
        get() = journalWriter == null

    /**
     * Force buffered operations to the filesystem.
     */
    @Synchronized
    @Throws(IOException::class)
    fun flush() {
        checkNotClosed()
        trimToSize()
        flushWriter(journalWriter)
    }

    /**
     * Closes this cache. Stored values will remain on the filesystem.
     */
    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        if (journalWriter == null) {
            return  // Already closed.
        }
        for (entry in ArrayList(lruEntries.values)) {
            if (entry!!.currentEditor != null) {
                entry.currentEditor.abort()
            }
        }
        trimToSize()
        closeWriter(journalWriter!!)
        journalWriter = null
    }

    @Throws(IOException::class)
    private fun trimToSize() {
        while (size > maxSize) {
            val toEvict: Map.Entry<String, Entry?> = lruEntries.entries.iterator().next()
            remove(toEvict.key)
        }
    }

    /**
     * Closes the cache and deletes all of its stored values. This will delete
     * all files in the cache directory including files that weren't created by
     * the cache.
     */
    @Throws(IOException::class)
    fun delete() {
        close()
        Util.deleteContents(directory)
    }




    companion object {
        const val JOURNAL_FILE = "journal"
        const val JOURNAL_FILE_TEMP = "journal.tmp"
        const val JOURNAL_FILE_BACKUP = "journal.bkp"
        const val MAGIC = "libcore.io.DiskLruCache"
        const val VERSION_1 = "1"
        const val ANY_SEQUENCE_NUMBER: Long = -1
        private const val CLEAN = "CLEAN"
        private const val DIRTY = "DIRTY"
        private const val REMOVE = "REMOVE"
        private const val READ = "READ"

        /**
         * Opens the cache in `directory`, creating a cache if none exists
         * there.
         *
         * @param directory  a writable directory
         * @param valueCount the number of values per cache entry. Must be positive.
         * @param maxSize    the maximum number of bytes this cache should use to store
         * @throws IOException if reading or writing the cache directory fails
         */
        @Throws(IOException::class)
        fun open(directory: File, appVersion: Int, valueCount: Int, maxSize: Long): DiskLruCache {
            require(maxSize > 0) { "maxSize <= 0" }
            require(valueCount > 0) { "valueCount <= 0" }

            // If a bkp file exists, use it instead.
            val backupFile = File(directory, JOURNAL_FILE_BACKUP)
            if (backupFile.exists()) {
                val journalFile = File(directory, JOURNAL_FILE)
                // If journal file also exists just delete backup file.
                if (journalFile.exists()) {
                    backupFile.delete()
                } else {
                    renameTo(backupFile, journalFile, false)
                }
            }

            // Prefer to pick up where we left off.
            var cache = DiskLruCache(directory, appVersion, valueCount, maxSize)
            if (cache.journalFile.exists()) {
                try {
                    cache.readJournal()
                    cache.processJournal()
                    return cache
                } catch (journalIsCorrupt: IOException) {
                    println(
                        "DiskLruCache "
                                + directory
                                + " is corrupt: "
                                + journalIsCorrupt.message
                                + ", removing"
                    )
                    cache.delete()
                }
            }

            // Create a new empty cache.
            directory.mkdirs()
            cache = DiskLruCache(directory, appVersion, valueCount, maxSize)
            cache.rebuildJournal()
            return cache
        }

        @Throws(IOException::class)
        private fun deleteIfExists(file: File?) {
            if (file!!.exists() && !file.delete()) {
                throw IOException()
            }
        }

        @Throws(IOException::class)
        private fun renameTo(from: File, to: File, deleteDestination: Boolean) {
            if (deleteDestination) {
                deleteIfExists(to)
            }
            if (!from.renameTo(to)) {
                throw IOException()
            }
        }

        @Throws(IOException::class)
        fun inputStreamToString(`in`: InputStream): String {
            return Util.readFully(InputStreamReader(`in`, Charset.forName("UTF-8")))
        }

        /**
         * Closes the writer while whitelisting with StrictMode if necessary.
         *
         *
         * Analogous to b/71520172.
         */
        @TargetApi(VERSION_CODES.O)
        @Throws(IOException::class)
        private fun closeWriter(writer: Writer) {
            // If API is less than 26, we don't need to whitelist with StrictMode.
            if (VERSION.SDK_INT < VERSION_CODES.O) {
                writer.close()
                return
            }
            val oldPolicy = StrictMode.getThreadPolicy()
            val unbufferedIoPolicy = ThreadPolicy.Builder(oldPolicy).permitUnbufferedIo().build()
            StrictMode.setThreadPolicy(unbufferedIoPolicy)
            try {
                writer.close()
            } finally {
                StrictMode.setThreadPolicy(oldPolicy)
            }
        }

        /**
         * Flushes the writer while whitelisting with StrictMode if necessary.
         *
         *
         * See b/71520172.
         */
        @TargetApi(VERSION_CODES.O)
        @Throws(IOException::class)
        private fun flushWriter(writer: Writer?) {
            // If API is less than 26, we don't need to whitelist with StrictMode.
            if (VERSION.SDK_INT < VERSION_CODES.O) {
                writer!!.flush()
                return
            }
            val oldPolicy = StrictMode.getThreadPolicy()
            val unbufferedIoPolicy = ThreadPolicy.Builder(oldPolicy).permitUnbufferedIo().build()
            StrictMode.setThreadPolicy(unbufferedIoPolicy)
            try {
                writer!!.flush()
            } finally {
                StrictMode.setThreadPolicy(oldPolicy)
            }
        }
    }

    init {
        journalFile = File(directory, JOURNAL_FILE)
        journalFileTmp = File(directory, JOURNAL_FILE_TEMP)
        journalFileBackup = File(directory, JOURNAL_FILE_BACKUP)
        this.valueCount = valueCount
        this.maxSize = maxSize
    }
}