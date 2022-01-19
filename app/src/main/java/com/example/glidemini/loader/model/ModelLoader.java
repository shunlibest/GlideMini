package com.example.glidemini.loader.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.util.Preconditions;
import com.example.glidemini.load.Options;

/**
 * 一个工厂接口，用于将任意复杂的数据Model换为具体的数据类型，DataFetcher可以使用该数据类型来获取由模型表示的资源的数据。
 * <p>
 * 这个界面有两个目的:
 * 1. 将特定Model转换为可解码为资源的数据类型。
 * 2. 允许Model与View结合，以获取特定大小的资源。
 * 避免重复的维度在xml和代码中为了确定大小的设备上有不同的密度,
 * 而且还允许您使用布局权重或通过编程将视图的尺寸没有强迫你去拿一个通用的资源大小。
 * 获取的资源越少，使用的带宽和电池寿命就越短，每个资源的内存占用就越低
 */
public interface ModelLoader<Model, Data> {

    //构造数据
    @Nullable
    LoadData<Data> buildLoadData(@NonNull Model model, int width, int height, @NonNull Options options);

    //如果给定的模型是此加载器可能加载的可识别类型，则返回true。
    boolean handles(@NonNull Model model);
}
