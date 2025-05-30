package androidx.media3.exoplayer.hls;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.StatsDataSource;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.InterstitialMarker;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import java.io.IOException;
import java.util.List; // Added for muxedCaptionFormats
import androidx.media3.exoplayer.analytics.PlayerId; // Added for playerId
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;


/** A {@link Chunk} for loading an HLS interstitial. */
public final class InterstitialChunk extends Chunk {

  public final InterstitialMarker interstitialMarker;
  private final HlsExtractorFactory extractorFactory;
  private final @Nullable List<Format> muxedCaptionFormats; 
  private final TimestampAdjuster timestampAdjuster; 
  private final boolean isMasterTimestampSource; 
  private final PlayerId playerId;

  private volatile boolean loadCanceled;
  private boolean loadCompleted;
  private @MonotonicNonNull HlsMediaChunkExtractor mediaChunkExtractor;
  private @MonotonicNonNull ExtractorOutput output;
  private long bytesLoaded;


  /**
   * Creates a new instance.
   *
   * @param extractorFactory The factory to create the extractor for the interstitial.
   * @param dataSource The source from which the data should be loaded.
   * @param dataSpec Defines the data to be loaded.
   * @param trackFormat The format of the track to which this chunk belongs.
   * @param trackSelectionReason The reason for the track selection.
   * @param trackSelectionData The adaptive track selection data.
   * @param startTimeUs The start time of the interstitial in microseconds, relative to the start of the period.
   * @param endTimeUs The end time of the interstitial in microseconds, relative to the start of the period.
   * @param interstitialMarker The interstitial marker.
   * @param muxedCaptionFormats The muxed caption formats.
   * @param isMasterTimestampSource Whether this chunk is the primary source of timestamps.
   * @param timestampAdjuster The timestamp adjuster for this interstitial.
   * @param playerId The {@link PlayerId} of the player using this chunk source.
   */
  public InterstitialChunk(
      HlsExtractorFactory extractorFactory,
      DataSource dataSource,
      DataSpec dataSpec,
      Format trackFormat,
      @C.SelectionReason int trackSelectionReason,
      @Nullable Object trackSelectionData,
      long startTimeUs,
      long endTimeUs,
      InterstitialMarker interstitialMarker,
      @Nullable List<Format> muxedCaptionFormats,
      boolean isMasterTimestampSource,
      TimestampAdjuster timestampAdjuster,
      PlayerId playerId) {
    super(
        dataSource,
        dataSpec,
        trackFormat,
        trackSelectionReason,
        trackSelectionData,
        startTimeUs,
        endTimeUs);
    this.extractorFactory = extractorFactory;
    this.interstitialMarker = interstitialMarker;
    this.muxedCaptionFormats = muxedCaptionFormats;
    this.isMasterTimestampSource = isMasterTimestampSource;
    this.timestampAdjuster = timestampAdjuster;
    this.playerId = playerId;
  }

  /**
   * Initializes the chunk for loading, setting the {@link ExtractorOutput} that will receive samples.
   *
   * @param output The output that will receive the samples.
   */
  public void init(ExtractorOutput output) {
    this.output = output;
  }

  @Override
  public boolean isLoadCompleted() {
    return loadCompleted;
  }

  @Override
  public long getBytesLoaded() {
    return bytesLoaded;
  }

  @Override
  public void cancelLoad() {
    loadCanceled = true;
    if (mediaChunkExtractor != null) {
      mediaChunkExtractor.cancelLoad();
    }
  }

  @Override
  public void load() throws IOException {
    if (output == null) {
      // init() must be called before load().
      // This implies an internal error if HlsSampleStreamWrapper doesn't call it.
      // Consider throwing an IllegalStateException or logging a more severe error.
      return;
    }

    if (loadCanceled) {
      return;
    }
    
    HlsMediaPlaylist.Segment initSegment = null; // Interstitials are typically single files, no separate init segment needed from playlist.
    boolean isPackedAudioExtractor = false; // Assume standard extractors unless interstitial format dictates otherwise.
    boolean reusingExtractor = false; // For now, create a new extractor each time.

    if (mediaChunkExtractor == null) {
      mediaChunkExtractor =
          extractorFactory.createExtractor(
              dataSpec.uri, 
              trackFormat, 
              muxedCaptionFormats,
              timestampAdjuster, // This needs to be correctly set up for interstitials
              dataSource.getResponseHeaders(), 
              output,
              playerId);
       // For interstitials, they are often treated as fresh content from timestamp 0.
       // The actual presentation time is handled by the chunk's startTimeUs.
      mediaChunkExtractor.init(output, 0, C.TIME_UNSET); 
    }
    
    StatsDataSource statsDataSource = new StatsDataSource(dataSource);
    ExtractorInput input = new HlsChunkExtractor.HlsExtractorInput(statsDataSource, dataSpec.position + bytesLoaded, C.LENGTH_UNSET /* Let extractor find end */);

    try {
      // If bytesLoaded > 0, it implies a resumed load, try to seek.
      if (bytesLoaded > 0) {
         // This seek logic might not be applicable if the interstitial format doesn't support it,
         // or if the extractor doesn't handle seeking after partial reads well.
         // For simplicity, if resuming, we might rely on the extractor to handle it or
         // simply re-download if interstitials are small.
         // input.seek(bytesLoaded, false); // This is not a method on ExtractorInput
      }

      while (!loadCanceled && mediaChunkExtractor.read(input)) {
        // Loop until all data is consumed or load is canceled.
      }
    } catch (IOException e) {
      bytesLoaded = statsDataSource.getBytesRead(); // Update bytesLoaded even on error
      throw e;
    } finally {
      bytesLoaded = statsDataSource.getBytesRead();
      Util.closeQuietly(statsDataSource);
    }
    loadCompleted = !loadCanceled;
  }
}
