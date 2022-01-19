package com.example.glidemini.load.model.header;

import androidx.annotation.NonNull;

import java.util.Map;

/**
 * 用于在Glide请求中包含一组报头的包装器的接口。
 */
public interface Headers {
    //默认Headers对象
    Headers DEFAULT = new LazyHeaders.Builder().build();

    //返回一个非空Map，包含一组应用于http请求的头。
    @NonNull
    Map<String, String> getHeaders();
}
