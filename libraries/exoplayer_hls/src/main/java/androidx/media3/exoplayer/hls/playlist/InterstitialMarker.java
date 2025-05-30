package androidx.media3.exoplayer.hls.playlist;

import androidx.media3.common.util.UnstableApi;
import androidx.annotation.Nullable;

/** Represents an interstitial marker in an HLS media playlist. */
@UnstableApi
public final class InterstitialMarker {

  /** The start time of the interstitial in microseconds, relative to the start of the playlist. */
  public final long startTimeUs;

  /** The end time of the interstitial in microseconds, relative to the start of the playlist. */
  public final long endTimeUs;

  /** The URI for the interstitial creative. May be null if the interstitial is embedded or metadata only. */
  @Nullable public final String uri;

  // TODO: Add other relevant fields like type, etc.

  /**
   * Creates a new instance.
   *
   * @param startTimeUs The start time of the interstitial in microseconds.
   * @param endTimeUs The end time of the interstitial in microseconds.
   * @param uri The URI for the interstitial creative.
   */
  public InterstitialMarker(long startTimeUs, long endTimeUs, @Nullable String uri) {
    this.startTimeUs = startTimeUs;
    this.endTimeUs = endTimeUs;
    this.uri = uri;
  }
}
