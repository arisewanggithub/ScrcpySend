/*
 * ScreenCopy adapter to AndroidX Media3 1.11.1 AV1 configuration parsing.
 * Licensed under the Apache License, Version 2.0, like the upstream module.
 */
package androidx.media3.muxer;

import java.nio.ByteBuffer;
import java.util.Arrays;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class StreamingCodecConfig {
  private StreamingCodecConfig() {}

  /** Officially serialized real sequence/metadata OBUs, excluding the four-byte av1C record header. */
  public static byte[] av1ConfigObus(byte[] config) {
    byte[] record = Av1ConfigUtil.createAv1CodecConfigurationRecord(ByteBuffer.wrap(config));
    return Arrays.copyOfRange(record, 4, record.length);
  }
}
