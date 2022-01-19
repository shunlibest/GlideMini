/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.glidemini.cache.diskLruCache

import kotlin.Throws
import java.io.*
import java.lang.AssertionError
import java.nio.charset.Charset

/**
 * 从InputStream中缓冲输入，以便读取行。 这个类用于行读取的缓冲。在本类中，一行以" n"或" r\n"结尾。
 * 只支持US-ASCII、UTF-8和ISO-8859-1
 */
class StrictLineReader(
    private val inputStream: InputStream,
    private val capacity: Int = 8192,
    private val charset: Charset
) : Closeable {

    /*
     * Buffered data is stored in {@code buf}. As long as no exception occurs, 0 <= pos <= end
     * and the data in the range [pos, end) is buffered for reading. At end of input, if there is
     * an unterminated line, we set end == -1, otherwise end == pos. If the underlying
     * {@code InputStream} throws an {@code IOException}, end may remain as either pos or -1.
     */
    private val buf: ByteArray? by lazy {
        ByteArray(capacity)
    }
    private var pos = 0
    private var end = 0


    @Throws(IOException::class)
    override fun close() {
        synchronized(inputStream) {
            if (buf != null) {
//                buf = null
                inputStream.close()
            }
        }
    }

    //读下一行。以“\n”或“\r\n”结尾的行
    @Throws(IOException::class)
    fun readLine(): String {
        synchronized(inputStream) {
            val buf = buf ?: throw IOException("LineReader is closed")

            // Read more data if we are at the end of the buffered data.
            // Though it's an error to read after an exception, we will let {@code fillBuf()}
            // throw again if that happens; thus we need to handle end == -1 as well as end == pos.
            if (pos >= end) {
                fillBuf()
            }
            // 尝试在缓冲的数据中找到LF，如果成功，返回该行
            for (i in pos until end) {
                if (buf[i] == LF) {
                    val lineEnd = if (i != pos && buf[i - 1] == CR) i - 1 else i
                    val res = String(buf, pos, lineEnd - pos, charset)
                    pos = i + 1
                    return res
                }
            }

            // Let's anticipate up to 80 characters on top of those already read.
            val out: ByteArrayOutputStream = object : ByteArrayOutputStream(end - pos + 80) {
                override fun toString(): String {
                    val length = if (count > 0 && buf[count - 1] == CR) count - 1 else count
                    return try {
                        String(buf, 0, length, charset)
                    } catch (e: UnsupportedEncodingException) {
                        throw AssertionError(e) // Since we control the charset this will never happen.
                    }
                }
            }
            while (true) {
                out.write(buf, pos, end - pos)
                // Mark unterminated line in case fillBuf throws EOFException or IOException.
                end = -1
                fillBuf()
                // Try to find LF in the buffered data and return the line if successful.
                for (i in pos until end) {
                    if (buf[i] == LF) {
                        if (i != pos) {
                            out.write(buf, pos, i - pos)
                        }
                        pos = i + 1
                        return out.toString()
                    }
                }
            }
        }
    }

    fun hasUnterminatedLine(): Boolean {
        return end == -1
    }

    /**
     * Reads new input data into the buffer. Call only with pos == end or end == -1,
     * depending on the desired outcome if the function throws.
     */
    @Throws(IOException::class)
    private fun fillBuf() {
        val result = inputStream.read(buf, 0, buf!!.size)
        if (result == -1) {
            throw EOFException()
        }
        pos = 0
        end = result
    }

    companion object {
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
    }
}