package com.example.glidemini.load.resource.gif;

import com.example.glidemini.load.DecodeFormat;
import com.example.glidemini.load.Option;
import com.example.glidemini.load.Options;
import com.example.glidemini.load.ResourceDecoder;

/** Options related to decoding GIFs. */
public final class GifOptions {

  /**
   * Indicates the {@link DecodeFormat} that will be used in conjunction
   * with the particular GIF to determine the {@link android.graphics.Bitmap.Config} to use when
   * decoding frames of GIFs.
   */
  public static final Option<DecodeFormat> DECODE_FORMAT =
      Option.memory(
          "com.bumptech.glide.load.resource.gif.GifOptions.DecodeFormat", DecodeFormat.DEFAULT);

  /**
   * If set to {@code true}, disables the GIF {@link ResourceDecoder}s
   * ({@link ResourceDecoder#handles(Object, Options)} will return {@code false}). Defaults to
   * {@code false}.
   */
  public static final Option<Boolean> DISABLE_ANIMATION =
      Option.memory("com.bumptech.glide.load.resource.gif.GifOptions.DisableAnimation", false);

  private GifOptions() {
    // Utility class.
  }
}
