package com.example.glidemini.loader.model.fileLoader

import android.util.Log
import com.example.glidemini.load.DataSource
import com.example.glidemini.loader.model.DataFetcher
import com.example.glidemini.loader.model.Priority
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/*
 * 加载数据
 */
class FileFetcher<Data> internal constructor(
    private val file: File,
    private val opener: FileLoader.FileOpener<Data>
) : DataFetcher<Data> {

    private val TAG = "FileFetcher"
    private var data: Data? = null


    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Data>) {
        try {
            data = opener.open(file)
            callback.onDataReady(data)
        } catch (e: FileNotFoundException) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Failed to open file", e)
            }
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        data?.let{
            try {
                opener.close(it)
            } catch (e: IOException) {
                // Ignored.
            }
        }
    }

    override fun cancel() {
        // Do nothing.
    }

    override fun getDataClass(): Class<Data> {
        return opener.dataClass
    }

    override fun getDataSource(): DataSource {
        return DataSource.LOCAL
    }
}
