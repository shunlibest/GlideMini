package com.example.glidemini.loader.model

import com.example.glidemini.cache.key.Key

/**
 * 缓存键指向相同的数据
 */
data class LoadData<Data>(
    val sourceKey: Key,
    val alternateKeys: List<Key> = emptyList(),
    val fetcher: DataFetcher<Data>
)