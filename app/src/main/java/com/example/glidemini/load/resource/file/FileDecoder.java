package com.example.glidemini.load.resource.file;

import androidx.annotation.NonNull;
import com.example.glidemini.load.Options;
import com.example.glidemini.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import java.io.File;

/**
 * A simple {@link ResourceDecoder} that creates resource for a given {@link
 * java.io.File}.
 */
public class FileDecoder implements ResourceDecoder<File, File> {

  @Override
  public boolean handles(@NonNull File source, @NonNull Options options) {
    return true;
  }

  @Override
  public Resource<File> decode(
      @NonNull File source, int width, int height, @NonNull Options options) {
    return new FileResource(source);
  }
}
