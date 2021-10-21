package com.example.glidemini.load.model;

import android.util.Log;
import androidx.annotation.NonNull;
import com.example.glidemini.load.Encoder;
import com.example.glidemini.load.Options;
import com.bumptech.glide.util.ByteBufferUtil;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Writes {@link ByteBuffer ByteBuffers} to {@link File Files}. */
public class ByteBufferEncoder implements Encoder<ByteBuffer> {
  private static final String TAG = "ByteBufferEncoder";

  @Override
  public boolean encode(@NonNull ByteBuffer data, @NonNull File file, @NonNull Options options) {
    boolean success = false;
    try {
      ByteBufferUtil.toFile(data, file);
      success = true;
    } catch (IOException e) {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "Failed to write data", e);
      }
    }
    return success;
  }
}
