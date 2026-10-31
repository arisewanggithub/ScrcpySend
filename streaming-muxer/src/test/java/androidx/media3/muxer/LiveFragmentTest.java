package androidx.media3.muxer;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.container.OpusUtil;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LiveFragmentTest {
  private static final byte[] SPS = hex("000000016742001ef40501ec80");
  private static final byte[] PPS = hex("0000000168ce3c80");
  private static final byte[] IDR = hex("0000000165888400");
  private static final byte[] DELTA = hex("0000000141888401");
  private static final byte[] HEAD = hex("4f707573486561640102380180bb000000ff00");
  private static final byte[] OPUS = hex("f8fffe");
  // AndroidX Media3 Apache-2.0 BoxesTest HEVC CSD; host samples exercise framing, not decoding.
  private static final byte[] HEVC_CSD = hex(
      "0000000140010c01ffff0408000003009fc800000300001e959809"
      + "000000014201010408000003009fc800000300001ec1882165959ae4cae68080000003008000000c84"
      + "000000014401c173d089");

  @Test public void staticFirstFrameAndAudioFlushBeforeNextKeyOrClose() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    FragmentedMp4Muxer muxer = muxer(output);
    int video = muxer.addTrack(videoFormat());
    int audio = muxer.addTrack(audioFormat());
    sample(muxer, video, 0, true, IDR);
    sample(muxer, audio, 100_000, false, OPUS);
    muxer.flushFragment();
    byte[] firstFragment = output.toByteArray();
    for (String type : Arrays.asList("ftyp", "moov", "moof", "mdat", "tfdt", "dOps")) {
      assertTrue(type, contains(firstFragment, type.getBytes(StandardCharsets.US_ASCII)));
    }
    // dOps stores multibyte fields big endian, unlike the little-endian OpusHead.
    assertTrue(contains(firstFragment, hex("00000013644f7073000201380000bb80ff0000")));
    Parsed first = parse(firstFragment);
    assertEquals(Arrays.asList(0L), first.video().times);
    assertEquals(Arrays.asList(100_000L), first.audio().times);
    assertArrayEquals(IDR, first.video().samples.get(0));
    assertArrayEquals(OPUS, first.audio().samples.get(0));
    // Media3's MP4 extractor leaves dOps byte order in its prefixed OpusHead.
    // The receiver normalizes this Format before passing it to the Opus decoder.
    assertArrayEquals(hex("4f70757348656164000201380000bb80ff0000"),
        first.audio().format.initializationData.get(0));
    assertEquals(312, ByteBuffer.wrap(first.audio().format.initializationData.get(0)).getShort(10));

    sample(muxer, audio, 120_000, false, OPUS);
    sample(muxer, audio, 140_000, false, OPUS);
    muxer.flushFragment();
    muxer.flushFragment(); // An idle tick must not emit a duplicate init or empty fragment.
    sample(muxer, video, 2_000_000, false, DELTA);
    muxer.flushFragment();
    Parsed all = parse(output.toByteArray());
    assertEquals(Arrays.asList(0L, 2_000_000L), all.video().times);
    assertEquals(Arrays.asList(100_000L, 120_000L, 140_000L), all.audio().times);
    assertEquals(3, all.audio().samples.size());
    for (byte[] bytes : all.audio().samples) assertArrayEquals(OPUS, bytes);
    assertArrayEquals(DELTA, all.video().samples.get(1));
    int bytesBeforeClose = output.size();
    muxer.close();
    assertEquals(bytesBeforeClose, output.size());
  }

  @Test public void audioFirstOffsetVariablePacketDurationsAndVideoOnlyAreRetained() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (FragmentedMp4Muxer muxer = muxer(output)) {
      int video = muxer.addTrack(videoFormat());
      int audio = muxer.addTrack(audioFormat());
      sample(muxer, video, 75_000, true, IDR);
      sample(muxer, audio, 0, false, hex("80"));
      muxer.flushFragment();
      sample(muxer, audio, 2_500, false, OPUS);
      muxer.flushFragment();
      Parsed parsed = parse(output.toByteArray());
      assertEquals(Arrays.asList(75_000L), parsed.video().times);
      assertEquals(Arrays.asList(0L, 2_500L), parsed.audio().times);
    }
    output.reset();
    try (FragmentedMp4Muxer muxer = muxer(output)) {
      int video = muxer.addTrack(videoFormat());
      sample(muxer, video, 0, true, IDR);
      muxer.flushFragment();
      sample(muxer, video, 3_000_000, true, IDR);
      muxer.flushFragment();
      Parsed parsed = parse(output.toByteArray());
      assertEquals(Arrays.asList(0L, 3_000_000L), parsed.video().times);
      assertNull(parsed.tracks.get(C.TRACK_TYPE_AUDIO));
    }
  }

  @Test public void canonicalPacketDurationsAndTfdtValidationAreExplicit() {
    assertEquals(960, StreamingBoxes.singleSampleDuration(audioFormat(), ByteBuffer.wrap(OPUS), 48_000));
    assertEquals(120, StreamingBoxes.singleSampleDuration(audioFormat(), ByteBuffer.wrap(hex("80")), 48_000));
    assertEquals(1500, StreamingBoxes.singleSampleDuration(videoFormat(), ByteBuffer.wrap(IDR), 90_000));
    assertThrows(IllegalArgumentException.class, () ->
        StreamingBoxes.traf(videoFormat(), -1, ByteBuffer.allocate(0), ByteBuffer.allocate(0)));
  }

  @Test public void avc3RotationRetainsInBandParameterSetsWithOneInitAndRawTimeline() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] portrait = hex("000000016742001ef40f028c800000000168ce3c800000000165888400");
    byte[] landscape = hex("000000016742001ef40501ec800000000168ce3c800000000165888400");
    try (FragmentedMp4Muxer muxer = muxer(output)) {
      int video = muxer.addTrack(videoFormat());
      int audio = muxer.addTrack(audioFormat());
      sample(muxer, video, 0, true, IDR);
      sample(muxer, audio, 100_000, true, OPUS);
      muxer.flushFragment();
      sample(muxer, video, 4_000_123, true, portrait);
      sample(muxer, audio, 120_000, true, OPUS);
      muxer.flushFragment();
      sample(muxer, video, 8_000_987, true, landscape);
      sample(muxer, audio, 140_000, true, OPUS);
      muxer.flushFragment();
    }

    byte[] bytes = output.toByteArray();
    assertTrue(contains(bytes, "avc3".getBytes(StandardCharsets.US_ASCII)));
    int moov = 0;
    int ftyp = 0;
    for (int at = 0; at < bytes.length; ) {
      int size = ByteBuffer.wrap(bytes, at, 4).getInt();
      assertTrue(size >= 8 && size <= bytes.length - at);
      String type = new String(bytes, at + 4, 4, StandardCharsets.US_ASCII);
      if (type.equals("moov")) moov++;
      if (type.equals("ftyp")) ftyp++;
      at += size;
    }
    assertEquals(1, moov);
    assertEquals(1, ftyp);
    Parsed parsed = parse(bytes); // Unmodified official extractor recognizes avc3; no seeking.
    // tfdt rounds HALF_UP to 90 kHz: 360011.07 -> 360011, 720088.83 -> 720089 ticks.
    assertEquals(Arrays.asList(0L, 4_000_122L, 8_000_988L), parsed.video().times);
    assertArrayEquals(IDR, parsed.video().samples.get(0));
    assertArrayEquals(portrait, parsed.video().samples.get(1));
    assertArrayEquals(landscape, parsed.video().samples.get(2));
    assertEquals(Arrays.asList(100_000L, 120_000L, 140_000L), parsed.audio().times);
    for (byte[] sample : parsed.audio().samples) assertArrayEquals(OPUS, sample);
  }

  @Test
  public void hev1UsesOfficialHvccArraysAndExtractsRawNalsPtsAndKeysWithOneInit()
      throws Exception {
    byte[] key = hex("000000012601888400");
    byte[] delta = hex("000000010201888401");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteBuffer spsBuffer = AnnexBUtils.findNalUnits(ByteBuffer.wrap(HEVC_CSD)).get(1);
    byte[] sps = new byte[spsBuffer.remaining()];
    spsBuffer.get(sps);
    NalUnitUtil.H265SpsData actual = NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.length, null);
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
        .setWidth(actual.width).setHeight(actual.height).setFrameRate(30)
        .setInitializationData(Arrays.asList(HEVC_CSD)).build();
    try (FragmentedMp4Muxer muxer = muxer(output)) {
      int video = muxer.addTrack(format);
      sample(muxer, video, 0, true, key);
      muxer.flushFragment();
      sample(muxer, video, 2_000_000, false, delta);
      muxer.flushFragment();
    }
    byte[] bytes = output.toByteArray();
    assertTrue(contains(bytes, "hev1".getBytes(StandardCharsets.US_ASCII)));
    assertFalse(contains(bytes, "hvc1".getBytes(StandardCharsets.US_ASCII)));
    assertFalse(contains(bytes, "avcC".getBytes(StandardCharsets.US_ASCII)));
    int hvcc = -1;
    for (int i = 4; i + 4 < bytes.length; i++) {
      if (new String(bytes, i, 4, StandardCharsets.US_ASCII).equals("hvcC")) { hvcc = i + 4; break; }
    }
    assertTrue(hvcc > 0);
    assertEquals(1, bytes[hvcc]);
    assertEquals(0x0f, bytes[hvcc + 21] & 255);
    assertEquals(3, bytes[hvcc + 22]);
    List<ByteBuffer> parameters = AnnexBUtils.findNalUnits(ByteBuffer.wrap(HEVC_CSD));
    int at = hvcc + 23;
    for (int i = 0; i < 3; i++) {
      assertEquals(32 + i, bytes[at++] & 255);
      assertEquals(1, ByteBuffer.wrap(bytes, at, 2).getShort()); at += 2;
      int length = ByteBuffer.wrap(bytes, at, 2).getShort() & 65535; at += 2;
      byte[] original = new byte[parameters.get(i).remaining()];
      parameters.get(i).get(original);
      assertEquals(original.length, length);
      assertArrayEquals(original, Arrays.copyOfRange(bytes, at, at + length));
      at += length;
    }
    Parsed parsed = parse(bytes);
    assertEquals(MimeTypes.VIDEO_H265, parsed.video().format.sampleMimeType);
    assertEquals(actual.width, parsed.video().format.width);
    assertEquals(actual.height, parsed.video().format.height);
    assertArrayEquals(HEVC_CSD, parsed.video().format.initializationData.get(0));
    assertArrayEquals(key, parsed.video().samples.get(0));
    assertArrayEquals(delta, parsed.video().samples.get(1));
    assertEquals(Arrays.asList(0L, 2_000_000L), parsed.video().times);
    assertEquals(Arrays.asList(C.BUFFER_FLAG_KEY_FRAME, 0), parsed.video().flags);
    int init = 0;
    for (int offset = 0; offset < bytes.length; ) {
      int size = ByteBuffer.wrap(bytes, offset, 4).getInt();
      assertTrue(size >= 8 && size <= bytes.length - offset);
      if (new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII).equals("moov")) init++;
      offset += size;
    }
    assertEquals(1, init);
  }

  private static FragmentedMp4Muxer muxer(ByteArrayOutputStream output) {
    return new FragmentedMp4Muxer.Builder(Channels.newChannel(output))
        .setFragmentDurationMs(Long.MAX_VALUE / 1000).build();
  }

  @Test public void hev1RotationKeepsOneInitActualParametersRawSyncTimelineAndContinuousAudio() throws Exception {
    byte[] portrait = hex("0000000140010c01ffff016000000300b0000003000003007bac09"
        + "00000001420101016000000300b0000003000003007ba00220801e072f96bb9324ba948281010176850940"
        + "000000014401c0e30f09418f6108");
    byte[] landscape = hex("0000000140010c01ffff016000000300b0000003000003007bac09"
        + "00000001420101016000000300b0000003000003007ba003c0801107cb96bb9324ba948281010176850940"
        + "000000014401c0e30f09418f6108");
    byte[] irap = hex("000000012601888400");
    List<byte[]> configurations = Arrays.asList(portrait, landscape, portrait);
    List<byte[]> samples = new ArrayList<>();
    long[] pts = {0, 4_000_123, 8_000_987};
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (FragmentedMp4Muxer muxer = muxer(output)) {
      Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
          .setWidth(1080).setHeight(1920).setFrameRate(30)
          .setInitializationData(Arrays.asList(portrait)).build();
      int video = muxer.addTrack(format);
      int audio = muxer.addTrack(audioFormat());
      for (int i = 0; i < configurations.size(); i++) {
        byte[] csd = configurations.get(i);
        ByteBuffer spsBuffer = AnnexBUtils.findNalUnits(ByteBuffer.wrap(csd)).get(1);
        byte[] sps = new byte[spsBuffer.remaining()];
        spsBuffer.get(sps);
        NalUnitUtil.H265SpsData actual = NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.length, null);
        assertEquals(i == 1 ? 1920 : 1080, actual.width);
        assertEquals(i == 1 ? 1080 : 1920, actual.height);
        byte[] accessUnit = new byte[csd.length + irap.length];
        System.arraycopy(csd, 0, accessUnit, 0, csd.length);
        System.arraycopy(irap, 0, accessUnit, csd.length, irap.length);
        samples.add(accessUnit);
        sample(muxer, video, pts[i], true, accessUnit);
        sample(muxer, audio, 100_000 + i * 20_000L, true, OPUS);
        muxer.flushFragment();
      }
    }
    byte[] bytes = output.toByteArray();
    assertTrue(contains(bytes, "hev1".getBytes(StandardCharsets.US_ASCII)));
    int moov = 0;
    int ftyp = 0;
    for (int at = 0; at < bytes.length; ) {
      int size = ByteBuffer.wrap(bytes, at, 4).getInt();
      assertTrue(size >= 8 && size <= bytes.length - at);
      String type = new String(bytes, at + 4, 4, StandardCharsets.US_ASCII);
      if (type.equals("moov")) moov++;
      if (type.equals("ftyp")) ftyp++;
      at += size;
    }
    assertEquals(1, moov); assertEquals(1, ftyp);
    Parsed parsed = parse(bytes);
    assertEquals(MimeTypes.VIDEO_H265, parsed.video().format.sampleMimeType);
    assertArrayEquals(portrait, parsed.video().format.initializationData.get(0));
    assertEquals(Arrays.asList(0L, 4_000_122L, 8_000_988L), parsed.video().times);
    assertEquals(Arrays.asList(C.BUFFER_FLAG_KEY_FRAME, C.BUFFER_FLAG_KEY_FRAME,
        C.BUFFER_FLAG_KEY_FRAME), parsed.video().flags);
    for (int i = 0; i < samples.size(); i++) assertArrayEquals(samples.get(i), parsed.video().samples.get(i));
    assertEquals(Arrays.asList(100_000L, 120_000L, 140_000L), parsed.audio().times);
    for (byte[] audio : parsed.audio().samples) assertArrayEquals(OPUS, audio);
  }

  @Test public void aacAscSampleEntryDurationsAndPayloadRetainedByOfficialExtractor() throws Exception {
    byte[] asc = hex("1190");
    byte[] aac = hex("211004608c1c");
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setSampleRate(48000).setChannelCount(2).setInitializationData(Arrays.asList(asc)).build();
    assertEquals(1024, StreamingBoxes.singleSampleDuration(format, ByteBuffer.wrap(aac), 48000));
    assertEquals(960, StreamingBoxes.singleSampleDuration(
        format.buildUpon().setInitializationData(Arrays.asList(hex("1194"))).build(),
        ByteBuffer.wrap(aac), 48000));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (FragmentedMp4Muxer muxer = muxer(output)) {
      int video = muxer.addTrack(videoFormat());
      int audio = muxer.addTrack(format);
      sample(muxer, video, 0, true, IDR);
      sample(muxer, audio, 100000, true, aac);
      sample(muxer, audio, 121333, true, aac);
      sample(muxer, audio, 142667, true, aac);
      muxer.flushFragment();
      sample(muxer, audio, 164000, true, aac);
      muxer.flushFragment();
    }
    byte[] bytes = output.toByteArray();
    assertTrue(contains(bytes, "mp4a".getBytes(StandardCharsets.US_ASCII)));
    assertTrue(contains(bytes, "esds".getBytes(StandardCharsets.US_ASCII)));
    Parsed parsed = parse(bytes);
    assertEquals(MimeTypes.AUDIO_AAC, parsed.audio().format.sampleMimeType);
    assertArrayEquals(asc, parsed.audio().format.initializationData.get(0));
    assertEquals(Arrays.asList(100000L, 121333L, 142666L, 164000L), parsed.audio().times);
    for (byte[] payload : parsed.audio().samples) assertArrayEquals(aac, payload);
  }

  @Test public void av1OfficialAv1CAndAv01ExtractionPreserveObusAndEncoderKeyFlags() throws Exception {
    // AndroidX Media3 Apache-2.0 sequence-header test vector, 854x480.
    byte[] sequence = hex("0a0e00000024c6abdf3efe2404040410");
    byte[] key = hex("0a0e00000024c6abdf3efe2404040410"
        + "32321000c8c600000c0000001203ce0a5c9bb67c3488823e0d3ec298916a5c8003ce0a5c9bb67c483554d89d6c37d34c4ed46ff4");
    byte[] delta = hex("321a30c0001d666846c9380060102080200000018b7a87f9aa2d0f2c");
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1)
        .setWidth(854).setHeight(480).setFrameRate(60).build();
    assertArrayEquals(sequence, StreamingCodecConfig.av1ConfigObus(sequence));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (FragmentedMp4Muxer muxer = muxer(output)) {
      int video = muxer.addTrack(format);
      sample(muxer, video, 0, true, key);
      muxer.flushFragment();
      sample(muxer, video, 16667, false, delta);
      muxer.flushFragment();
    }
    byte[] bytes = output.toByteArray();
    assertTrue(contains(bytes, "av01".getBytes(StandardCharsets.US_ASCII)));
    assertTrue(contains(bytes, "av1C".getBytes(StandardCharsets.US_ASCII)));
    assertTrue(contains(bytes, hex("81040c00") ));
    Parsed parsed = parse(bytes);
    assertEquals(MimeTypes.VIDEO_AV1, parsed.video().format.sampleMimeType);
    assertEquals(854, parsed.video().format.width);
    assertEquals(480, parsed.video().format.height);
    assertArrayEquals(key, parsed.video().samples.get(0));
    assertArrayEquals(delta, parsed.video().samples.get(1));
    assertEquals(Arrays.asList(C.BUFFER_FLAG_KEY_FRAME, 0), parsed.video().flags);
    assertThrows(RuntimeException.class, () -> Av1ConfigUtil.createAv1CodecConfigurationRecord(ByteBuffer.wrap(delta)));
  }

  private static Format videoFormat() {
    return new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(640).setHeight(480).setFrameRate(60)
        .setInitializationData(Arrays.asList(SPS, PPS)).build();
  }

  private static Format audioFormat() {
    return new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS)
        .setSampleRate(48_000).setChannelCount(2)
        .setInitializationData(OpusUtil.buildInitializationData(HEAD)).build();
  }

  private static void sample(FragmentedMp4Muxer muxer, int track, long pts, boolean key, byte[] bytes)
      throws MuxerException {
    muxer.writeSampleData(track, ByteBuffer.wrap(bytes),
        new BufferInfo(pts, bytes.length, key ? C.BUFFER_FLAG_KEY_FRAME : 0));
  }

  private static Parsed parse(byte[] bytes) throws IOException {
    Parsed parsed = new Parsed();
    ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
    DefaultExtractorInput input = new DefaultExtractorInput(stream::read, 0, bytes.length);
    FragmentedMp4Extractor extractor = new FragmentedMp4Extractor();
    extractor.init(parsed);
    PositionHolder position = new PositionHolder();
    int result;
    do {
      result = extractor.read(input, position);
      assertNotEquals("Live output must never seek", Extractor.RESULT_SEEK, result);
    } while (result != Extractor.RESULT_END_OF_INPUT);
    extractor.release();
    return parsed;
  }

  private static final class Parsed implements ExtractorOutput {
    final Map<Integer, Samples> tracks = new HashMap<>();
    Samples video() { return tracks.get(C.TRACK_TYPE_VIDEO); }
    Samples audio() { return tracks.get(C.TRACK_TYPE_AUDIO); }
    @Override public TrackOutput track(int id, int type) {
      Samples samples = new Samples();
      tracks.put(type, samples);
      return samples;
    }
    @Override public void endTracks() {}
    @Override public void seekMap(SeekMap seekMap) {}
  }

  private static final class Samples implements TrackOutput {
    Format format;
    final List<Long> times = new ArrayList<>();
    final List<Integer> flags = new ArrayList<>();
    final List<byte[]> samples = new ArrayList<>();
    final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    @Override public void format(Format format) { this.format = format; }
    @Override public int sampleData(DataReader input, int length, boolean allowEndOfInput, int part)
        throws IOException {
      byte[] bytes = new byte[length];
      int count = input.read(bytes, 0, length);
      if (count < 0) {
        if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
        throw new EOFException();
      }
      pending.write(bytes, 0, count);
      return count;
    }
    @Override public void sampleData(ParsableByteArray data, int length, int part) {
      byte[] bytes = new byte[length];
      data.readBytes(bytes, 0, length);
      pending.write(bytes, 0, length);
    }
    @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData crypto) {
      assertEquals(0, offset);
      assertEquals(size, pending.size());
      times.add(timeUs);
      this.flags.add(flags);
      samples.add(pending.toByteArray());
      pending.reset();
    }
  }

  private static boolean contains(byte[] bytes, byte[] pattern) {
    outer: for (int i = 0; i <= bytes.length - pattern.length; i++) {
      for (int j = 0; j < pattern.length; j++) if (bytes[i + j] != pattern[j]) continue outer;
      return true;
    }
    return false;
  }

  private static byte[] hex(String value) {
    byte[] bytes = new byte[value.length() / 2];
    for (int i = 0; i < bytes.length; i++)
      bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
    return bytes;
  }
}
