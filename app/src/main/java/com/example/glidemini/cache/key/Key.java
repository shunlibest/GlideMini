package com.example.glidemini.cache.key;

import androidx.annotation.NonNull;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * 唯一标识数据存放的一种接口
 */
public interface Key {
    String STRING_CHARSET_NAME = "UTF-8";
    Charset CHARSET = Charset.forName(STRING_CHARSET_NAME);

    //将所有唯一标识信息添加到给定摘要中。
    void updateDiskCacheKey(@NonNull MessageDigest messageDigest);

    @Override
    boolean equals(Object o);

    @Override
    int hashCode();
}
