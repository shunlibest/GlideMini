package com.example.glidemini.loader.model.fileLoader

import com.example.glidemini.cache.key.ObjectKey
import com.example.glidemini.load.Options
import com.example.glidemini.loader.model.LoadData
import com.example.glidemini.loader.model.ModelLoader
import java.io.*

/**
 * 一个简单的模型加载器，用于从文件加载数据。
 */
class FileLoader<Data>(private val fileOpener: FileOpener<Data>) : ModelLoader<File?, Data> {
    override fun buildLoadData(
        model: File, width: Int, height: Int, options: Options
    ): LoadData<Data> {
        return LoadData(ObjectKey(model), fetcher = FileFetcher(model, fileOpener))
    }

    override fun handles(model: File): Boolean {
        return true
    }

    /**
     * Allows opening a specific type of data from a [java.io.File].
     *
     * @param <Data> The type of data that can be opened.
    </Data> */
    interface FileOpener<Data> {
        @Throws(FileNotFoundException::class)
        fun open(file: File?): Data

        @Throws(IOException::class)
        fun close(data: Data)
        val dataClass: Class<Data>
    }


    /**
     * Base factory for loading data from [files][java.io.File].
     *
     * @param <Data> The type of data that will be loaded for a given [java.io.File].
    </Data> */
    open class Factory<Data>(private val opener: FileOpener<Data>) :
        ModelLoaderFactory<File?, Data> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<File, Data> {
            return FileLoader(opener)
        }

        override fun teardown() {
            // Do nothing.
        }
    }

    /**
     * Factory for loading [InputStream]s from [File]s.
     */
    class StreamFactory : Factory<InputStream?>(
        object : FileOpener<InputStream> {
            @Throws(FileNotFoundException::class)
            override fun open(file: File?): InputStream {
                return FileInputStream(file)
            }

            @Throws(IOException::class)
            override fun close(inputStream: InputStream) {
                inputStream.close()
            }

            override val dataClass: Class<Data>
                get() = InputStream::class.java
        })

    /**
     * Factory for loading [ParcelFileDescriptor]s from [File]s.
     */
    class FileDescriptorFactory : Factory<ParcelFileDescriptor?>(
        object : FileOpener<ParcelFileDescriptor> {
            @Throws(FileNotFoundException::class)
            override fun open(file: File?): ParcelFileDescriptor {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            @Throws(IOException::class)
            override fun close(parcelFileDescriptor: ParcelFileDescriptor) {
                parcelFileDescriptor.close()
            }

            override val dataClass: Class<Data>
                get() = ParcelFileDescriptor::class.java
        })

    companion object {
        private const val TAG = "FileLoader"
    }
}