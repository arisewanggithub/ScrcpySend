/*
 * ScrcpySend live-fragment extensions to AndroidX Media3 1.11.1.
 * Licensed under the Apache License, Version 2.0, like the upstream module.
 */
package androidx.media3.muxer;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.container.OpusUtil;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.math.RoundingMode;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class StreamingBoxes {
  static final int TFDT_BOX_SIZE = 20;

  static ByteBuffer traf(Format format, long firstPresentationTimeUs,
      ByteBuffer tfhd, ByteBuffer trun) {
    if (firstPresentationTimeUs < 0) throw new IllegalArgumentException("Negative live fragment time");
    int scale = MimeTypes.isAudio(format.sampleMimeType) ? format.sampleRate : 90_000;
    if (scale <= 0) throw new IllegalArgumentException("Missing live track timebase");
    ByteBuffer contents = ByteBuffer.allocate(12);
    contents.putInt(0x01000000);
    contents.putLong(Util.scaleLargeValue(
        firstPresentationTimeUs, scale, 1_000_000, RoundingMode.HALF_UP));
    contents.flip();
    return BoxUtils.wrapBoxesIntoBox("traf",
        ImmutableList.of(tfhd, BoxUtils.wrapIntoBox("tfdt", contents), trun));
  }

  static byte[] opusSpecificFields(byte[] head) {
    if (head.length != 19 || head[18] != 0) {
      throw new IllegalArgumentException("Only canonical mapping-family-zero OpusHead is supported");
    }
    ByteBuffer input = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
    ByteBuffer output = ByteBuffer.allocate(11);
    output.put(head[8]).put(head[9]);
    output.putShort(input.getShort(10));
    output.putInt(input.getInt(12));
    output.putShort(input.getShort(16));
    output.put(head[18]);
    return output.array();
  }

  static int singleSampleDuration(Format format, ByteBuffer sample, int scale) {
    if (MimeTypes.AUDIO_AAC.equals(format.sampleMimeType)) {
      if (format.initializationData.size() != 1 || scale != format.sampleRate) {
        throw new IllegalArgumentException("Missing AAC configuration or mismatched timebase");
      }
      byte[] asc = format.initializationData.get(0);
      if (asc.length < 2 || ((asc[0] & 255) >> 3) != 2) {
        throw new IllegalArgumentException("AAC-LC AudioSpecificConfig required");
      }
      int frequencyIndex = ((asc[0] & 7) << 1) | ((asc[1] & 255) >> 7);
      int flagBit = frequencyIndex == 15 ? 37 : 13;
      if (asc.length * 8 < flagBit + 3) {
        throw new IllegalArgumentException("Truncated AAC AudioSpecificConfig");
      }
      return ((asc[flagBit / 8] >> (7 - flagBit % 8)) & 1) == 0 ? 1024 : 960;
    }
    long durationUs;
    if (MimeTypes.AUDIO_OPUS.equals(format.sampleMimeType)) {
      byte[] bytes = new byte[sample.remaining()];
      sample.duplicate().get(bytes);
      durationUs = OpusUtil.getPacketDurationUs(bytes);
    } else if (MimeTypes.isVideo(format.sampleMimeType) && format.frameRate > 0) {
      durationUs = Math.round(1_000_000.0 / format.frameRate);
    } else {
      throw new IllegalArgumentException("Missing single-sample duration");
    }
    if (durationUs <= 0) throw new IllegalArgumentException("Invalid encoded sample duration");
    return (int) Util.scaleLargeTimestamp(durationUs, scale, 1_000_000);
  }
}
