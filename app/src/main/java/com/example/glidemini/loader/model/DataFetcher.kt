package com.example.glidemini.loader.model

import com.example.glidemini.load.DataSource
import java.lang.Exception

/**
 * 延迟检索可用于加载资源的数据。
 */
interface DataFetcher<T> {

    //加载数据
    fun loadData(priority: Priority, callback: DataCallback<in T>)

    //清除或回收此数据获取程序使用的任何资源
    fun cleanup()

    //取消加载
    fun cancel()

    //返回此获取器试图获取的数据的类。
    fun getDataClass(): Class<T>

    //返回[DataSource]
    fun getDataSource(): DataSource


    //数据加载成功或者失败的回调
    interface DataCallback<T> {
        //如果加载成功，则用已加载的数据调用;如果加载失败，则用null调用。
        fun onDataReady(data: T?)

        //加载失败时调用
        fun onLoadFailed(e: Exception)
    }
}