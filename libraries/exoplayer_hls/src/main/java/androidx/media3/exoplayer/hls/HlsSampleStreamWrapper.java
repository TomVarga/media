/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.hls;

import static androidx.media3.exoplayer.hls.HlsChunkSource.CHUNK_PUBLICATION_STATE_PRELOAD;
import static androidx.media3.exoplayer.hls.HlsChunkSource.CHUNK_PUBLICATION_STATE_PUBLISHED;
import static androidx.media3.exoplayer.hls.HlsChunkSource.CHUNK_PUBLICATION_STATE_REMOVED;
import static androidx.media3.exoplayer.trackselection.TrackSelectionUtil.createFallbackOptions;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.net.Uri;
import android.os.Handler;
import android.util.SparseIntArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.SampleQueue.UpstreamFormatChangedListener;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SampleStream.ReadFlags;
import androidx.media3.exoplayer.source.SequenceableLoader;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.exoplayer.upstream.Loader.LoadErrorAction;
import androidx.media3.extractor.DummyTrackOutput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.metadata.emsg.EventMessage;
import androidx.media3.extractor.metadata.emsg.EventMessageDecoder;
import androidx.media3.extractor.metadata.id3.PrivFrame;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * Loads {@link HlsMediaChunk}s obtained from a {@link HlsChunkSource}, and provides {@link
 * SampleStream}s from which the loaded media can be consumed.
 */
/* package */ final class HlsSampleStreamWrapper
    implements Loader.Callback<Chunk>,
        Loader.ReleaseCallback,
        SequenceableLoader,
        ExtractorOutput,
        UpstreamFormatChangedListener {

  /** A callback to be notified of events. */
  public interface Callback extends SequenceableLoader.Callback<HlsSampleStreamWrapper> {

    /**
     * Called when the wrapper has been prepared.
     *
     * <p>Note: This method will be called on a later handler loop than the one on which either
     * {@link #prepareWithMultivariantPlaylistInfo} or {@link #continuePreparing} are invoked.
     */
    void onPrepared();

    /**
     * Called to schedule a {@link #continueLoading(LoadingInfo)} call when the playlist referred by
     * the given url changes, or it requires a refresh to check whether the hinted resource has been
     * published or removed.
     *
     * <p>Note: This method will be called on a later handler loop than the one on which {@link
     * #onPlaylistUpdated()} is invoked.
     */
    void onPlaylistRefreshRequired(Uri playlistUrl);
  }

  private static final String TAG = "HlsSampleStreamWrapper";

  public static final int SAMPLE_QUEUE_INDEX_PENDING = -1;
  public static final int SAMPLE_QUEUE_INDEX_NO_MAPPING_FATAL = -2;
  public static final int SAMPLE_QUEUE_INDEX_NO_MAPPING_NON_FATAL = -3;

  private static final Set<Integer> MAPPABLE_TYPES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_METADATA)));

  private final String uid;
  private final @C.TrackType int trackType;
  private final Callback callback;
  private final HlsChunkSource chunkSource;
  private final Allocator allocator;
  @Nullable private final Format muxedAudioFormat;
  private final DrmSessionManager drmSessionManager;
  private final DrmSessionEventListener.EventDispatcher drmEventDispatcher;
  private final LoadErrorHandlingPolicy loadErrorHandlingPolicy;
  private final Loader loader;
  private final MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher;
  private final @HlsMediaSource.MetadataType int metadataType;
  private final HlsChunkSource.HlsChunkHolder nextChunkHolder;
  private final ArrayList<HlsMediaChunk> mediaChunks;
  private final List<HlsMediaChunk> readOnlyMediaChunks;
  private final Runnable maybeFinishPrepareRunnable;
  private final Runnable onTracksEndedRunnable;
  private final Handler handler;
  private final ArrayList<HlsSampleStream> hlsSampleStreams;
  private final Map<String, DrmInitData> overridingDrmInitData;

  @Nullable private Chunk loadingChunk;
  private HlsSampleQueue[] sampleQueues;
  private int[] sampleQueueTrackIds;
  private Set<Integer> sampleQueueMappingDoneByType;
  private SparseIntArray sampleQueueIndicesByType;
  private @MonotonicNonNull TrackOutput emsgUnwrappingTrackOutput;
  private int primarySampleQueueType;
  private int primarySampleQueueIndex;
  private boolean sampleQueuesBuilt;
  private boolean prepared;
  private int enabledTrackGroupCount;
  private @MonotonicNonNull Format upstreamTrackFormat;
  @Nullable private Format downstreamTrackFormat;
  private boolean released;

  private @MonotonicNonNull TrackGroupArray trackGroups;
  private @MonotonicNonNull Set<TrackGroup> optionalTrackGroups;
  private int @MonotonicNonNull [] trackGroupToSampleQueueIndex;
  private int primaryTrackGroupIndex;
  private boolean haveAudioVideoSampleQueues;
  private boolean[] sampleQueuesEnabledStates;
  private boolean[] sampleQueueIsAudioVideoFlags;

  private long lastSeekPositionUs;
  private long pendingResetPositionUs;
  private boolean pendingResetUpstreamFormats;
  private boolean seenFirstTrackSelection;
  private boolean loadingFinished;

  private boolean tracksEnded;
  private long sampleOffsetUs;
  @Nullable private DrmInitData drmInitData;
  @Nullable private HlsMediaChunk sourceChunk;
  private @MonotonicNonNull Class<? extends Chunk> lastChunkType;


  public HlsSampleStreamWrapper(
      String uid,
      @C.TrackType int trackType,
      Callback callback,
      HlsChunkSource chunkSource,
      Map<String, DrmInitData> overridingDrmInitData,
      Allocator allocator,
      long positionUs,
      @Nullable Format muxedAudioFormat,
      DrmSessionManager drmSessionManager,
      DrmSessionEventListener.EventDispatcher drmEventDispatcher,
      LoadErrorHandlingPolicy loadErrorHandlingPolicy,
      MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher,
      @HlsMediaSource.MetadataType int metadataType) {
    this.uid = uid;
    this.trackType = trackType;
    this.callback = callback;
    this.chunkSource = chunkSource;
    this.overridingDrmInitData = overridingDrmInitData;
    this.allocator = allocator;
    this.muxedAudioFormat = muxedAudioFormat;
    this.drmSessionManager = drmSessionManager;
    this.drmEventDispatcher = drmEventDispatcher;
    this.loadErrorHandlingPolicy = loadErrorHandlingPolicy;
    this.mediaSourceEventDispatcher = mediaSourceEventDispatcher;
    this.metadataType = metadataType;
    loader = new Loader("Loader:HlsSampleStreamWrapper");
    nextChunkHolder = new HlsChunkSource.HlsChunkHolder();
    sampleQueueTrackIds = new int[0];
    sampleQueueMappingDoneByType = new HashSet<>(MAPPABLE_TYPES.size());
    sampleQueueIndicesByType = new SparseIntArray(MAPPABLE_TYPES.size());
    sampleQueues = new HlsSampleQueue[0];
    sampleQueueIsAudioVideoFlags = new boolean[0];
    sampleQueuesEnabledStates = new boolean[0];
    mediaChunks = new ArrayList<>();
    readOnlyMediaChunks = Collections.unmodifiableList(mediaChunks);
    hlsSampleStreams = new ArrayList<>();
    @SuppressWarnings("nullness:methodref.receiver.bound")
    Runnable maybeFinishPrepareRunnable = this::maybeFinishPrepare;
    this.maybeFinishPrepareRunnable = maybeFinishPrepareRunnable;
    @SuppressWarnings("nullness:methodref.receiver.bound")
    Runnable onTracksEndedRunnable = this::onTracksEnded;
    this.onTracksEndedRunnable = onTracksEndedRunnable;
    handler = Util.createHandlerForCurrentLooper();
    lastSeekPositionUs = positionUs;
    pendingResetPositionUs = positionUs;
  }

  public void continuePreparing() {
    if (!prepared) {
      continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(lastSeekPositionUs).build());
    }
  }

  public void prepareWithMultivariantPlaylistInfo(
      TrackGroup[] trackGroups, int primaryTrackGroupIndex, int... optionalTrackGroupsIndices) {
    this.trackGroups = createTrackGroupArrayWithDrmInfo(trackGroups);
    optionalTrackGroups = new HashSet<>();
    for (int optionalTrackGroupIndex : optionalTrackGroupsIndices) {
      optionalTrackGroups.add(this.trackGroups.get(optionalTrackGroupIndex));
    }
    this.primaryTrackGroupIndex = primaryTrackGroupIndex;
    handler.post(callback::onPrepared);
    setIsPrepared();
  }

  public void maybeThrowPrepareError() throws IOException {
    maybeThrowError();
    if (loadingFinished && !prepared) {
      throw ParserException.createForMalformedContainer(
          "Loading finished before preparation is complete.", null);
    }
  }

  public TrackGroupArray getTrackGroups() {
    assertIsPrepared();
    return trackGroups;
  }

  public int getPrimaryTrackGroupIndex() {
    return primaryTrackGroupIndex;
  }

  public int bindSampleQueueToSampleStream(int trackGroupIndex) {
    assertIsPrepared();
    Assertions.checkNotNull(trackGroupToSampleQueueIndex);

    int sampleQueueIndex = trackGroupToSampleQueueIndex[trackGroupIndex];
    if (sampleQueueIndex == C.INDEX_UNSET) {
      return optionalTrackGroups.contains(trackGroups.get(trackGroupIndex))
          ? SAMPLE_QUEUE_INDEX_NO_MAPPING_NON_FATAL
          : SAMPLE_QUEUE_INDEX_NO_MAPPING_FATAL;
    }
    if (sampleQueuesEnabledStates[sampleQueueIndex]) {
      return SAMPLE_QUEUE_INDEX_NO_MAPPING_FATAL;
    }
    sampleQueuesEnabledStates[sampleQueueIndex] = true;
    return sampleQueueIndex;
  }

  public void unbindSampleQueue(int trackGroupIndex) {
    assertIsPrepared();
    Assertions.checkNotNull(trackGroupToSampleQueueIndex);
    int sampleQueueIndex = trackGroupToSampleQueueIndex[trackGroupIndex];
    Assertions.checkState(sampleQueuesEnabledStates[sampleQueueIndex]);
    sampleQueuesEnabledStates[sampleQueueIndex] = false;
  }

  public boolean selectTracks(
      @NullableType ExoTrackSelection[] selections,
      boolean[] mayRetainStreamFlags,
      @NullableType SampleStream[] streams,
      boolean[] streamResetFlags,
      long positionUs,
      boolean forceReset) {
    assertIsPrepared();
    int oldEnabledTrackGroupCount = enabledTrackGroupCount;
    for (int i = 0; i < selections.length; i++) {
      HlsSampleStream stream = (HlsSampleStream) streams[i];
      if (stream != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
        enabledTrackGroupCount--;
        stream.unbindSampleQueue();
        streams[i] = null;
      }
    }
    boolean seekRequired =
        forceReset
            || (seenFirstTrackSelection
                ? oldEnabledTrackGroupCount == 0
                : positionUs != lastSeekPositionUs);
    ExoTrackSelection oldPrimaryTrackSelection = chunkSource.getTrackSelection();
    ExoTrackSelection primaryTrackSelection = oldPrimaryTrackSelection;
    for (int i = 0; i < selections.length; i++) {
      ExoTrackSelection selection = selections[i];
      if (selection == null) {
        continue;
      }
      int trackGroupIndex = trackGroups.indexOf(selection.getTrackGroup());
      if (trackGroupIndex == primaryTrackGroupIndex) {
        primaryTrackSelection = selection;
        chunkSource.setTrackSelection(selection);
      }
      if (streams[i] == null) {
        enabledTrackGroupCount++;
        streams[i] = new HlsSampleStream(this, trackGroupIndex);
        streamResetFlags[i] = true;
        if (trackGroupToSampleQueueIndex != null) {
          ((HlsSampleStream) streams[i]).bindSampleQueue();
          if (!seekRequired) {
            SampleQueue sampleQueue = sampleQueues[trackGroupToSampleQueueIndex[trackGroupIndex]];
            seekRequired =
                sampleQueue.getReadIndex() != 0
                    && !sampleQueue.seekTo(positionUs, true);
          }
        }
      }
    }

    if (enabledTrackGroupCount == 0) {
      chunkSource.reset();
      downstreamTrackFormat = null;
      pendingResetUpstreamFormats = true;
      mediaChunks.clear();
      if (loader.isLoading()) {
        if (sampleQueuesBuilt) {
          for (SampleQueue sampleQueue : sampleQueues) {
            sampleQueue.discardToEnd();
          }
        }
        loader.cancelLoading();
      } else {
        loader.clearFatalError();
        resetSampleQueues();
      }
    } else {
      if (!mediaChunks.isEmpty()
          && !Util.areEqual(primaryTrackSelection, oldPrimaryTrackSelection)) {
        boolean primarySampleQueueDirty = false;
        if (!seenFirstTrackSelection) {
          long bufferedDurationUs = positionUs < 0 ? -positionUs : 0;
          HlsMediaChunk lastMediaChunk = getLastMediaChunk();
          MediaChunkIterator[] mediaChunkIterators =
              chunkSource.createMediaChunkIterators(lastMediaChunk, positionUs);
          primaryTrackSelection.updateSelectedTrack(
              positionUs,
              bufferedDurationUs,
              C.TIME_UNSET,
              readOnlyMediaChunks,
              mediaChunkIterators);
          int chunkIndex = chunkSource.getTrackGroup().indexOf(lastMediaChunk.trackFormat);
          if (primaryTrackSelection.getSelectedIndexInTrackGroup() != chunkIndex) {
            primarySampleQueueDirty = true;
          }
        } else {
          primarySampleQueueDirty = true;
        }
        if (primarySampleQueueDirty) {
          forceReset = true;
          seekRequired = true;
          pendingResetUpstreamFormats = true;
        }
      }
      if (seekRequired) {
        seekToUs(positionUs, forceReset);
        for (int i = 0; i < streams.length; i++) {
          if (streams[i] != null) {
            streamResetFlags[i] = true;
          }
        }
      }
    }

    updateSampleStreams(streams);
    seenFirstTrackSelection = true;
    return seekRequired;
  }

  public void discardBuffer(long positionUs, boolean toKeyframe) {
    if (!sampleQueuesBuilt || isPendingReset()) {
      return;
    }
    int sampleQueueCount = sampleQueues.length;
    for (int i = 0; i < sampleQueueCount; i++) {
      sampleQueues[i].discardTo(positionUs, toKeyframe, sampleQueuesEnabledStates[i]);
    }
  }

  public boolean seekToUs(long positionUs, boolean forceReset) {
    lastSeekPositionUs = positionUs;
    if (isPendingReset()) {
      pendingResetPositionUs = positionUs;
      return true;
    }

    @Nullable HlsMediaChunk seekToMediaChunk = null;
    if (chunkSource.hasIndependentSegments()) {
      for (int i = 0; i < mediaChunks.size(); i++) {
        HlsMediaChunk mediaChunk = mediaChunks.get(i);
        long mediaChunkStartTimeUs = mediaChunk.startTimeUs;
        if (mediaChunkStartTimeUs == positionUs) {
          seekToMediaChunk = mediaChunk;
          break;
        }
      }
    }
    
    if (sampleQueuesBuilt && !forceReset && seekInsideBufferUs(positionUs, seekToMediaChunk)) {
      return false;
    }

    pendingResetPositionUs = positionUs;
    loadingFinished = false;
    mediaChunks.clear();
    if (loader.isLoading()) {
      if (sampleQueuesBuilt) {
        for (SampleQueue sampleQueue : sampleQueues) {
          sampleQueue.discardToEnd();
        }
      }
      loader.cancelLoading();
    } else {
      loader.clearFatalError();
      resetSampleQueues();
    }
    lastChunkType = null; // Reset last chunk type on seek
    return true;
  }

  public void onPlaylistUpdated() {
    if (mediaChunks.isEmpty()) {
      return;
    }
    HlsMediaChunk lastMediaChunk = Iterables.getLast(mediaChunks);
    @HlsChunkSource.ChunkPublicationState
    int chunkState = chunkSource.getChunkPublicationState(lastMediaChunk);
    if (chunkState == CHUNK_PUBLICATION_STATE_PUBLISHED) {
      lastMediaChunk.publish();
    } else if (chunkState == CHUNK_PUBLICATION_STATE_PRELOAD) {
      handler.post(() -> callback.onPlaylistRefreshRequired(lastMediaChunk.playlistUrl));
    } else if (chunkState == CHUNK_PUBLICATION_STATE_REMOVED
        && !loadingFinished
        && loader.isLoading()) {
      loader.cancelLoading();
    }
  }

  public void release() {
    if (prepared) {
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.preRelease();
      }
    }
    loader.release(this);
    handler.removeCallbacksAndMessages(null);
    released = true;
    hlsSampleStreams.clear();
  }

  @Override
  public void onLoaderReleased() {
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.release();
    }
  }

  public void setIsPrimaryTimestampSource(boolean isPrimaryTimestampSource) {
    chunkSource.setIsPrimaryTimestampSource(isPrimaryTimestampSource);
  }

  public boolean onPlaylistError(Uri playlistUrl, LoadErrorInfo loadErrorInfo, boolean forceRetry) {
    if (!chunkSource.obtainsChunksForPlaylist(playlistUrl)) {
      return true;
    }
    long exclusionDurationMs = C.TIME_UNSET;
    if (!forceRetry) {
      @Nullable
      LoadErrorHandlingPolicy.FallbackSelection fallbackSelection =
          loadErrorHandlingPolicy.getFallbackSelectionFor(
              createFallbackOptions(chunkSource.getTrackSelection()), loadErrorInfo);
      if (fallbackSelection != null
          && fallbackSelection.type == LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK) {
        exclusionDurationMs = fallbackSelection.exclusionDurationMs;
      }
    }
    return chunkSource.onPlaylistError(playlistUrl, exclusionDurationMs)
        && exclusionDurationMs != C.TIME_UNSET;
  }

  public boolean isVideoSampleStream() {
    return primarySampleQueueType == C.TRACK_TYPE_VIDEO;
  }

  public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
    return chunkSource.getAdjustedSeekPositionUs(positionUs, seekParameters);
  }

  public boolean isReady(int sampleQueueIndex) {
    return !isPendingReset() && sampleQueues[sampleQueueIndex].isReady(loadingFinished);
  }

  public void maybeThrowError(int sampleQueueIndex) throws IOException {
    maybeThrowError();
    sampleQueues[sampleQueueIndex].maybeThrowError();
  }

  public void maybeThrowError() throws IOException {
    loader.maybeThrowError();
    chunkSource.maybeThrowError();
  }

  public int readData(
      int sampleQueueIndex,
      FormatHolder formatHolder,
      DecoderInputBuffer buffer,
      @ReadFlags int readFlags) {
    if (isPendingReset()) {
      return C.RESULT_NOTHING_READ;
    }

    if (!mediaChunks.isEmpty()) {
      int discardToMediaChunkIndex = 0;
      while (discardToMediaChunkIndex < mediaChunks.size() - 1
          && finishedReadingChunk(mediaChunks.get(discardToMediaChunkIndex))) {
        discardToMediaChunkIndex++;
      }
      Util.removeRange(mediaChunks, 0, discardToMediaChunkIndex);
      HlsMediaChunk currentChunk = mediaChunks.get(0);
      Format trackFormat = currentChunk.trackFormat;
      if (!trackFormat.equals(downstreamTrackFormat)) {
        mediaSourceEventDispatcher.downstreamFormatChanged(
            trackType,
            trackFormat,
            currentChunk.trackSelectionReason,
            currentChunk.trackSelectionData,
            currentChunk.startTimeUs);
      }
      downstreamTrackFormat = trackFormat;
    }

    if (!mediaChunks.isEmpty() && !mediaChunks.get(0).isPublished()) {
      return C.RESULT_NOTHING_READ;
    }

    int result =
        sampleQueues[sampleQueueIndex].read(formatHolder, buffer, readFlags, loadingFinished);
    if (result == C.RESULT_FORMAT_READ) {
      Format format = Assertions.checkNotNull(formatHolder.format);
      if (sampleQueueIndex == primarySampleQueueIndex) {
        int chunkUid = Ints.checkedCast(sampleQueues[sampleQueueIndex].peekSourceId());
        int chunkIndex = 0;
        while (chunkIndex < mediaChunks.size() && mediaChunks.get(chunkIndex).uid != chunkUid) {
          chunkIndex++;
        }
        Format trackFormat =
            chunkIndex < mediaChunks.size()
                ? mediaChunks.get(chunkIndex).trackFormat
                : Assertions.checkNotNull(upstreamTrackFormat);
        format = format.withManifestFormatInfo(trackFormat);
      }
      formatHolder.format = format;
    }
    return result;
  }

  public int skipData(int sampleQueueIndex, long positionUs) {
    if (isPendingReset()) {
      return 0;
    }

    SampleQueue sampleQueue = sampleQueues[sampleQueueIndex];
    int skipCount = sampleQueue.getSkipCount(positionUs, loadingFinished);

    @Nullable HlsMediaChunk lastChunk = Iterables.getLast(mediaChunks, null);
    if (lastChunk != null && !lastChunk.isPublished()) {
      int readIndex = sampleQueue.getReadIndex();
      int firstSampleIndex = lastChunk.getFirstSampleIndex(sampleQueueIndex);
      skipCount = min(skipCount, firstSampleIndex - readIndex);
    }

    sampleQueue.skip(skipCount);
    return skipCount;
  }

  @Override
  public long getBufferedPositionUs() {
    if (loadingFinished) {
      return C.TIME_END_OF_SOURCE;
    } else if (isPendingReset()) {
      return pendingResetPositionUs;
    } else {
      long bufferedPositionUs = lastSeekPositionUs;
      HlsMediaChunk lastMediaChunk = getLastMediaChunk();
      HlsMediaChunk lastCompletedMediaChunk =
          lastMediaChunk.isLoadCompleted()
              ? lastMediaChunk
              : mediaChunks.size() > 1 ? mediaChunks.get(mediaChunks.size() - 2) : null;
      if (lastCompletedMediaChunk != null) {
        bufferedPositionUs = max(bufferedPositionUs, lastCompletedMediaChunk.endTimeUs);
      }
      if (sampleQueuesBuilt) {
        for (SampleQueue sampleQueue : sampleQueues) {
          bufferedPositionUs = max(bufferedPositionUs, sampleQueue.getLargestQueuedTimestampUs());
        }
      }
      return bufferedPositionUs;
    }
  }

  @Override
  public long getNextLoadPositionUs() {
    if (isPendingReset()) {
      return pendingResetPositionUs;
    } else {
      return loadingFinished ? C.TIME_END_OF_SOURCE : getLastMediaChunk().endTimeUs;
    }
  }

  @Override
  public boolean continueLoading(LoadingInfo loadingInfo) { // Changed from long to LoadingInfo
    long loadPositionUs = loadingInfo.playbackPositionUs; // Assuming this is the intended use
    if (isPendingReset()) {
         loadPositionUs = pendingResetPositionUs; // Use pending reset position if available
    } else {
        HlsMediaChunk lastMediaChunk = getLastMediaChunk();
        loadPositionUs = lastMediaChunk.isLoadCompleted() ? lastMediaChunk.endTimeUs : max(lastSeekPositionUs, lastMediaChunk.startTimeUs);
    }
    return continueLoadingWithPosition(loadingInfo, loadPositionUs); // Call helper
  }

  // Renamed original continueLoading to avoid signature clash if LoadingInfo was intended for future use
  private boolean continueLoadingWithPosition(LoadingInfo loadingInfo, long loadPositionUs) {
    if (loadingFinished || loader.isLoading() || loader.hasFatalError()) {
      return false;
    }

    List<HlsMediaChunk> chunkQueue;
    if (isPendingReset()) {
      chunkQueue = Collections.emptyList();
      // loadPositionUs is already set correctly
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.setStartTimeUs(pendingResetPositionUs);
      }
    } else {
      chunkQueue = readOnlyMediaChunks;
    }
    nextChunkHolder.clear();
    chunkSource.getNextChunk(
        loadingInfo, // Pass LoadingInfo
        loadPositionUs,
        chunkQueue,
        prepared || !chunkQueue.isEmpty(),
        nextChunkHolder);
    boolean endOfStream = nextChunkHolder.endOfStream;
    @Nullable Chunk currentChunk = nextChunkHolder.chunk;
    @Nullable Uri playlistUrlToLoad = nextChunkHolder.playlistUrl;

    if (endOfStream) {
      pendingResetPositionUs = C.TIME_UNSET;
      loadingFinished = true;
      return true;
    }

    if (currentChunk == null) {
      if (playlistUrlToLoad != null) {
        callback.onPlaylistRefreshRequired(playlistUrlToLoad);
      }
      return false;
    }
    
    // Handle chunk initialization and splicing
    if (currentChunk instanceof HlsMediaChunk) {
      initMediaChunkLoad((HlsMediaChunk) currentChunk);
    } else if (currentChunk instanceof InterstitialChunk) {
      initInterstitialChunkLoad((InterstitialChunk) currentChunk);
    }

    loadingChunk = currentChunk;
    long elapsedRealtimeMs =
        loader.startLoading(
            currentChunk, this, loadErrorHandlingPolicy.getMinimumLoadableRetryCount(currentChunk.type));
    mediaSourceEventDispatcher.loadStarted(
        new LoadEventInfo(currentChunk.loadTaskId, currentChunk.dataSpec, elapsedRealtimeMs),
        currentChunk.type,
        trackType,
        currentChunk.trackFormat,
        currentChunk.trackSelectionReason,
        currentChunk.trackSelectionData,
        currentChunk.startTimeUs,
        currentChunk.endTimeUs);
    return true;
  }


  @Override
  public boolean isLoading() {
    return loader.isLoading();
  }

  @Override
  public void reevaluateBuffer(long positionUs) {
    if (loader.hasFatalError() || isPendingReset()) {
      return;
    }

    if (loader.isLoading()) {
      Assertions.checkNotNull(loadingChunk);
      if (chunkSource.shouldCancelLoad(positionUs, loadingChunk, readOnlyMediaChunks)) {
        loader.cancelLoading();
      }
      return;
    }

    int newQueueSize = readOnlyMediaChunks.size();
    while (newQueueSize > 0
        && chunkSource.getChunkPublicationState(readOnlyMediaChunks.get(newQueueSize - 1))
            == CHUNK_PUBLICATION_STATE_REMOVED) {
      newQueueSize--;
    }
    if (newQueueSize < readOnlyMediaChunks.size()) {
      discardUpstream(newQueueSize);
    }

    int preferredQueueSize = chunkSource.getPreferredQueueSize(positionUs, readOnlyMediaChunks);
    if (preferredQueueSize < mediaChunks.size()) {
      discardUpstream(preferredQueueSize);
    }
  }

  @Override
  public void onLoadCompleted(Chunk loadable, long elapsedRealtimeMs, long loadDurationMs) {
    loadingChunk = null;
    chunkSource.onChunkLoadCompleted(loadable);
    LoadEventInfo loadEventInfo =
        new LoadEventInfo(
            loadable.loadTaskId,
            loadable.dataSpec,
            loadable.getUri(),
            loadable.getResponseHeaders(),
            elapsedRealtimeMs,
            loadDurationMs,
            loadable.bytesLoaded());
    loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId);
    mediaSourceEventDispatcher.loadCompleted(
        loadEventInfo,
        loadable.type,
        trackType,
        loadable.trackFormat,
        loadable.trackSelectionReason,
        loadable.trackSelectionData,
        loadable.startTimeUs,
        loadable.endTimeUs);
    if (!prepared) {
      continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(lastSeekPositionUs).build());
    } else {
      callback.onContinueLoadingRequested(this);
    }
  }

  @Override
  public void onLoadCanceled(
      Chunk loadable, long elapsedRealtimeMs, long loadDurationMs, boolean released) {
    loadingChunk = null;
    LoadEventInfo loadEventInfo =
        new LoadEventInfo(
            loadable.loadTaskId,
            loadable.dataSpec,
            loadable.getUri(),
            loadable.getResponseHeaders(),
            elapsedRealtimeMs,
            loadDurationMs,
            loadable.bytesLoaded());
    loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId);
    mediaSourceEventDispatcher.loadCanceled(
        loadEventInfo,
        loadable.type,
        trackType,
        loadable.trackFormat,
        loadable.trackSelectionReason,
        loadable.trackSelectionData,
        loadable.startTimeUs,
        loadable.endTimeUs);
    if (!released) {
      if (isPendingReset() || enabledTrackGroupCount == 0) {
        resetSampleQueues();
      }
      if (enabledTrackGroupCount > 0) {
        callback.onContinueLoadingRequested(this);
      }
    }
  }

  @Override
  public LoadErrorAction onLoadError(
      Chunk loadable,
      long elapsedRealtimeMs,
      long loadDurationMs,
      IOException error,
      int errorCount) {
    boolean isMediaChunk = isMediaChunk(loadable);
    if (isMediaChunk
        && !((HlsMediaChunk) loadable).isPublished()
        && error instanceof HttpDataSource.InvalidResponseCodeException) {
      int responseCode = ((HttpDataSource.InvalidResponseCodeException) error).responseCode;
      if (responseCode == 410 || responseCode == 404) {
        return Loader.RETRY;
      }
    }
    long bytesLoaded = loadable.bytesLoaded();
    boolean exclusionSucceeded = false;
    LoadEventInfo loadEventInfo =
        new LoadEventInfo(
            loadable.loadTaskId,
            loadable.dataSpec,
            loadable.getUri(),
            loadable.getResponseHeaders(),
            elapsedRealtimeMs,
            loadDurationMs,
            bytesLoaded);
    MediaLoadData mediaLoadData =
        new MediaLoadData(
            loadable.type,
            trackType,
            loadable.trackFormat,
            loadable.trackSelectionReason,
            loadable.trackSelectionData,
            Util.usToMs(loadable.startTimeUs),
            Util.usToMs(loadable.endTimeUs));
    LoadErrorInfo loadErrorInfo =
        new LoadErrorInfo(loadEventInfo, mediaLoadData, error, errorCount);
    LoadErrorAction loadErrorAction;
    @Nullable
    LoadErrorHandlingPolicy.FallbackSelection fallbackSelection =
        loadErrorHandlingPolicy.getFallbackSelectionFor(
            createFallbackOptions(chunkSource.getTrackSelection()), loadErrorInfo);
    if (fallbackSelection != null
        && fallbackSelection.type == LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK) {
      exclusionSucceeded =
          chunkSource.maybeExcludeTrack(loadable, fallbackSelection.exclusionDurationMs);
    }

    if (exclusionSucceeded) {
      if (isMediaChunk && bytesLoaded == 0) {
        HlsMediaChunk removed = mediaChunks.remove(mediaChunks.size() - 1);
        Assertions.checkState(removed == loadable);
        if (mediaChunks.isEmpty()) {
          pendingResetPositionUs = lastSeekPositionUs;
        } else {
          Iterables.getLast(mediaChunks).invalidateExtractor();
        }
      }
      loadErrorAction = Loader.DONT_RETRY;
    } else {
      long retryDelayMs = loadErrorHandlingPolicy.getRetryDelayMsFor(loadErrorInfo);
      loadErrorAction =
          retryDelayMs != C.TIME_UNSET
              ? Loader.createRetryAction(false, retryDelayMs)
              : Loader.DONT_RETRY_FATAL;
    }

    boolean wasCanceled = !loadErrorAction.isRetry();
    mediaSourceEventDispatcher.loadError(
        loadEventInfo,
        loadable.type,
        trackType,
        loadable.trackFormat,
        loadable.trackSelectionReason,
        loadable.trackSelectionData,
        loadable.startTimeUs,
        loadable.endTimeUs,
        error,
        wasCanceled);
    if (wasCanceled) {
      loadingChunk = null;
      loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId);
    }

    if (exclusionSucceeded) {
      if (!prepared) {
        continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(lastSeekPositionUs).build());
      } else {
        callback.onContinueLoadingRequested(this);
      }
    }
    return loadErrorAction;
  }
  
  private void spliceSampleQueues() {
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.splice();
    }
  }

  private void initMediaChunkLoad(HlsMediaChunk chunk) {
    if (lastChunkType != null && lastChunkType != HlsMediaChunk.class) {
      spliceSampleQueues();
    }
    lastChunkType = HlsMediaChunk.class;

    sourceChunk = chunk;
    upstreamTrackFormat = chunk.trackFormat;
    pendingResetPositionUs = C.TIME_UNSET;
    mediaChunks.add(chunk);
    ImmutableList.Builder<Integer> sampleQueueWriteIndicesBuilder = ImmutableList.builder();
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueueWriteIndicesBuilder.add(sampleQueue.getWriteIndex());
    }
    chunk.init(this, sampleQueueWriteIndicesBuilder.build());
    for (HlsSampleQueue sampleQueue : sampleQueues) {
      sampleQueue.setSourceChunk(chunk);
      if (chunk.shouldSpliceIn) {
        sampleQueue.splice();
      }
    }
  }

  private void initInterstitialChunkLoad(InterstitialChunk chunk) {
    if (lastChunkType != null && lastChunkType != InterstitialChunk.class) {
      spliceSampleQueues();
    }
    lastChunkType = InterstitialChunk.class;
    // No sourceChunk = chunk for InterstitialChunk as it's not an HlsMediaChunk
    // No upstreamTrackFormat update from InterstitialChunk
    pendingResetPositionUs = C.TIME_UNSET; 
    // mediaChunks.add(chunk); // Should InterstitialChunks be added to mediaChunks list?
                             // For now, no, as it's typed to HlsMediaChunk.
                             // This might affect getBufferedPositionUs if it relies on this list.
    chunk.init(this);
  }


  private void discardUpstream(int preferredQueueSize) {
    Assertions.checkState(!loader.isLoading());

    int newQueueSize = C.LENGTH_UNSET;
    for (int i = preferredQueueSize; i < mediaChunks.size(); i++) {
      if (canDiscardUpstreamMediaChunksFromIndex(i)) {
        newQueueSize = i;
        break;
      }
    }
    if (newQueueSize == C.LENGTH_UNSET) {
      return;
    }

    long endTimeUs = getLastMediaChunk().endTimeUs;
    HlsMediaChunk firstRemovedChunk = discardUpstreamMediaChunksFromIndex(newQueueSize);
    if (mediaChunks.isEmpty()) {
      pendingResetPositionUs = lastSeekPositionUs;
    } else {
      Iterables.getLast(mediaChunks).invalidateExtractor();
    }
    loadingFinished = false;

    mediaSourceEventDispatcher.upstreamDiscarded(
        primarySampleQueueType, firstRemovedChunk.startTimeUs, endTimeUs);
  }

  @Override
  public TrackOutput track(int id, int type) {
    @Nullable TrackOutput trackOutput = null;
    if (MAPPABLE_TYPES.contains(type)) {
      trackOutput = getMappedTrackOutput(id, type);
    } else {
      for (int i = 0; i < sampleQueues.length; i++) {
        if (sampleQueueTrackIds[i] == id) {
          trackOutput = sampleQueues[i];
          break;
        }
      }
    }

    if (trackOutput == null) {
      if (tracksEnded) {
        return createFakeTrackOutput(id, type);
      } else {
        trackOutput = createSampleQueue(id, type);
      }
    }

    if (type == C.TRACK_TYPE_METADATA) {
      if (emsgUnwrappingTrackOutput == null) {
        emsgUnwrappingTrackOutput = new EmsgUnwrappingTrackOutput(trackOutput, metadataType);
      }
      return emsgUnwrappingTrackOutput;
    }
    return trackOutput;
  }

  @Nullable
  private TrackOutput getMappedTrackOutput(int id, int type) {
    Assertions.checkArgument(MAPPABLE_TYPES.contains(type));
    int sampleQueueIndex = sampleQueueIndicesByType.get(type, C.INDEX_UNSET);
    if (sampleQueueIndex == C.INDEX_UNSET) {
      return null;
    }

    if (sampleQueueMappingDoneByType.add(type)) {
      sampleQueueTrackIds[sampleQueueIndex] = id;
    }
    return sampleQueueTrackIds[sampleQueueIndex] == id
        ? sampleQueues[sampleQueueIndex]
        : createFakeTrackOutput(id, type);
  }

  private SampleQueue createSampleQueue(int id, int type) {
    int trackCount = sampleQueues.length;

    boolean isAudioVideo = type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_VIDEO;
    HlsSampleQueue sampleQueue =
        new HlsSampleQueue(allocator, drmSessionManager, drmEventDispatcher, overridingDrmInitData);
    sampleQueue.setStartTimeUs(lastSeekPositionUs);
    if (isAudioVideo) {
      sampleQueue.setDrmInitData(drmInitData);
    }
    sampleQueue.setSampleOffsetUs(sampleOffsetUs);
    if (sourceChunk != null) {
      sampleQueue.setSourceChunk(sourceChunk);
    }
    sampleQueue.setUpstreamFormatChangeListener(this);
    sampleQueueTrackIds = Arrays.copyOf(sampleQueueTrackIds, trackCount + 1);
    sampleQueueTrackIds[trackCount] = id;
    sampleQueues = Util.nullSafeArrayAppend(sampleQueues, sampleQueue);
    sampleQueueIsAudioVideoFlags = Arrays.copyOf(sampleQueueIsAudioVideoFlags, trackCount + 1);
    sampleQueueIsAudioVideoFlags[trackCount] = isAudioVideo;
    haveAudioVideoSampleQueues |= sampleQueueIsAudioVideoFlags[trackCount];
    sampleQueueMappingDoneByType.add(type);
    sampleQueueIndicesByType.append(type, trackCount);
    if (getTrackTypeScore(type) > getTrackTypeScore(primarySampleQueueType)) {
      primarySampleQueueIndex = trackCount;
      primarySampleQueueType = type;
    }
    sampleQueuesEnabledStates = Arrays.copyOf(sampleQueuesEnabledStates, trackCount + 1);
    return sampleQueue;
  }

  @Override
  public void endTracks() {
    tracksEnded = true;
    handler.post(onTracksEndedRunnable);
  }

  @Override
  public void seekMap(SeekMap seekMap) {
    // Do nothing.
  }

  @Override
  public void onUpstreamFormatChanged(Format format) {
    handler.post(maybeFinishPrepareRunnable);
  }

  public void onNewExtractor() {
    sampleQueueMappingDoneByType.clear();
  }

  public void setSampleOffsetUs(long sampleOffsetUs) {
    if (this.sampleOffsetUs != sampleOffsetUs) {
      this.sampleOffsetUs = sampleOffsetUs;
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.setSampleOffsetUs(sampleOffsetUs);
      }
    }
  }

  public void setDrmInitData(@Nullable DrmInitData drmInitData) {
    if (!Util.areEqual(this.drmInitData, drmInitData)) {
      this.drmInitData = drmInitData;
      for (int i = 0; i < sampleQueues.length; i++) {
        if (sampleQueueIsAudioVideoFlags[i]) {
          sampleQueues[i].setDrmInitData(drmInitData);
        }
      }
    }
  }

  private void updateSampleStreams(@NullableType SampleStream[] streams) {
    hlsSampleStreams.clear();
    for (@Nullable SampleStream stream : streams) {
      if (stream != null) {
        hlsSampleStreams.add((HlsSampleStream) stream);
      }
    }
  }

  private boolean finishedReadingChunk(HlsMediaChunk chunk) {
    int chunkUid = chunk.uid;
    int sampleQueueCount = sampleQueues.length;
    for (int i = 0; i < sampleQueueCount; i++) {
      if (sampleQueuesEnabledStates[i] && sampleQueues[i].peekSourceId() == chunkUid) {
        return false;
      }
    }
    return true;
  }

  private boolean canDiscardUpstreamMediaChunksFromIndex(int mediaChunkIndex) {
    for (int i = mediaChunkIndex; i < mediaChunks.size(); i++) {
      if (mediaChunks.get(i).shouldSpliceIn) {
        return false;
      }
    }
    HlsMediaChunk mediaChunk = mediaChunks.get(mediaChunkIndex);
    for (int i = 0; i < sampleQueues.length; i++) {
      int discardFromIndex = mediaChunk.getFirstSampleIndex(i);
      if (sampleQueues[i].getReadIndex() > discardFromIndex) {
        return false;
      }
    }
    return true;
  }

  private HlsMediaChunk discardUpstreamMediaChunksFromIndex(int chunkIndex) {
    HlsMediaChunk firstRemovedChunk = mediaChunks.get(chunkIndex);
    Util.removeRange(mediaChunks, chunkIndex, mediaChunks.size());
    for (int i = 0; i < sampleQueues.length; i++) {
      int discardFromIndex = firstRemovedChunk.getFirstSampleIndex(i);
      sampleQueues[i].discardUpstreamSamples(discardFromIndex);
    }
    return firstRemovedChunk;
  }

  private void resetSampleQueues() {
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.reset(pendingResetUpstreamFormats);
    }
    pendingResetUpstreamFormats = false;
  }

  private void onTracksEnded() {
    sampleQueuesBuilt = true;
    maybeFinishPrepare();
  }

  private void maybeFinishPrepare() {
    if (released || trackGroupToSampleQueueIndex != null || !sampleQueuesBuilt) {
      return;
    }
    for (SampleQueue sampleQueue : sampleQueues) {
      if (sampleQueue.getUpstreamFormat() == null) {
        return;
      }
    }
    if (trackGroups != null) {
      mapSampleQueuesToMatchTrackGroups();
    } else {
      buildTracksFromSampleStreams();
      setIsPrepared();
      callback.onPrepared();
    }
  }

  @RequiresNonNull("trackGroups")
  @EnsuresNonNull("trackGroupToSampleQueueIndex")
  private void mapSampleQueuesToMatchTrackGroups() {
    int trackGroupCount = trackGroups.length;
    trackGroupToSampleQueueIndex = new int[trackGroupCount];
    Arrays.fill(trackGroupToSampleQueueIndex, C.INDEX_UNSET);
    for (int i = 0; i < trackGroupCount; i++) {
      for (int queueIndex = 0; queueIndex < sampleQueues.length; queueIndex++) {
        SampleQueue sampleQueue = sampleQueues[queueIndex];
        Format upstreamFormat = Assertions.checkStateNotNull(sampleQueue.getUpstreamFormat());
        if (formatsMatch(upstreamFormat, trackGroups.get(i).getFormat(0))) {
          trackGroupToSampleQueueIndex[i] = queueIndex;
          break;
        }
      }
    }
    for (HlsSampleStream sampleStream : hlsSampleStreams) {
      sampleStream.bindSampleQueue();
    }
  }

  @EnsuresNonNull({"trackGroups", "optionalTrackGroups", "trackGroupToSampleQueueIndex"})
  private void buildTracksFromSampleStreams() {
    int primaryExtractorTrackType = C.TRACK_TYPE_NONE;
    int primaryExtractorTrackIndex = C.INDEX_UNSET;
    int extractorTrackCount = sampleQueues.length;
    for (int i = 0; i < extractorTrackCount; i++) {
      @Nullable
      String sampleMimeType =
          Assertions.checkStateNotNull(sampleQueues[i].getUpstreamFormat()).sampleMimeType;
      int trackType;
      if (MimeTypes.isVideo(sampleMimeType)) {
        trackType = C.TRACK_TYPE_VIDEO;
      } else if (MimeTypes.isAudio(sampleMimeType)) {
        trackType = C.TRACK_TYPE_AUDIO;
      } else if (MimeTypes.isText(sampleMimeType)) {
        trackType = C.TRACK_TYPE_TEXT;
      } else {
        trackType = C.TRACK_TYPE_NONE;
      }
      if (getTrackTypeScore(trackType) > getTrackTypeScore(primaryExtractorTrackType)) {
        primaryExtractorTrackType = trackType;
        primaryExtractorTrackIndex = i;
      } else if (trackType == primaryExtractorTrackType
          && primaryExtractorTrackIndex != C.INDEX_UNSET) {
        primaryExtractorTrackIndex = C.INDEX_UNSET;
      }
    }

    TrackGroup chunkSourceTrackGroup = chunkSource.getTrackGroup();
    int chunkSourceTrackCount = chunkSourceTrackGroup.length;

    primaryTrackGroupIndex = C.INDEX_UNSET;
    trackGroupToSampleQueueIndex = new int[extractorTrackCount];
    for (int i = 0; i < extractorTrackCount; i++) {
      trackGroupToSampleQueueIndex[i] = i;
    }

    TrackGroup[] trackGroups = new TrackGroup[extractorTrackCount];
    for (int i = 0; i < extractorTrackCount; i++) {
      Format sampleFormat = Assertions.checkStateNotNull(sampleQueues[i].getUpstreamFormat());
      if (i == primaryExtractorTrackIndex) {
        Format[] formats = new Format[chunkSourceTrackCount];
        for (int j = 0; j < chunkSourceTrackCount; j++) {
          Format playlistFormat = chunkSourceTrackGroup.getFormat(j);
          if (primaryExtractorTrackType == C.TRACK_TYPE_AUDIO && muxedAudioFormat != null) {
            playlistFormat = playlistFormat.withManifestFormatInfo(muxedAudioFormat);
          }
          formats[j] =
              chunkSourceTrackCount == 1
                  ? sampleFormat.withManifestFormatInfo(playlistFormat)
                  : deriveFormat(playlistFormat, sampleFormat, true);
        }
        trackGroups[i] = new TrackGroup(uid, formats);
        primaryTrackGroupIndex = i;
      } else {
        @Nullable
        Format playlistFormat =
            primaryExtractorTrackType == C.TRACK_TYPE_VIDEO
                    && MimeTypes.isAudio(sampleFormat.sampleMimeType)
                ? muxedAudioFormat
                : null;
        String muxedTrackGroupId = uid + ":muxed:" + (i < primaryExtractorTrackIndex ? i : i - 1);
        trackGroups[i] =
            new TrackGroup(
                muxedTrackGroupId,
                deriveFormat(playlistFormat, sampleFormat, false));
      }
    }
    this.trackGroups = createTrackGroupArrayWithDrmInfo(trackGroups);
    Assertions.checkState(optionalTrackGroups == null);
    optionalTrackGroups = Collections.emptySet();
  }

  private TrackGroupArray createTrackGroupArrayWithDrmInfo(TrackGroup[] trackGroups) {
    for (int i = 0; i < trackGroups.length; i++) {
      TrackGroup trackGroup = trackGroups[i];
      Format[] exposedFormats = new Format[trackGroup.length];
      for (int j = 0; j < trackGroup.length; j++) {
        Format format = trackGroup.getFormat(j);
        exposedFormats[j] = format.copyWithCryptoType(drmSessionManager.getCryptoType(format));
      }
      trackGroups[i] = new TrackGroup(trackGroup.id, exposedFormats);
    }
    return new TrackGroupArray(trackGroups);
  }

  private HlsMediaChunk getLastMediaChunk() {
    return mediaChunks.get(mediaChunks.size() - 1);
  }

  private boolean isPendingReset() {
    return pendingResetPositionUs != C.TIME_UNSET;
  }

  private boolean seekInsideBufferUs(long positionUs, @Nullable HlsMediaChunk chunk) {
    int sampleQueueCount = sampleQueues.length;
    for (int i = 0; i < sampleQueueCount; i++) {
      SampleQueue sampleQueue = sampleQueues[i];
      boolean seekInsideQueue;
      if (chunk != null) {
        seekInsideQueue = sampleQueue.seekTo(chunk.getFirstSampleIndex(i));
      } else {
        seekInsideQueue = sampleQueue.seekTo(positionUs, false);
      }
      if (!seekInsideQueue && (sampleQueueIsAudioVideoFlags[i] || !haveAudioVideoSampleQueues)) {
        return false;
      }
    }
    return true;
  }

  @RequiresNonNull({"trackGroups", "optionalTrackGroups"})
  private void setIsPrepared() {
    prepared = true;
  }

  @EnsuresNonNull({"trackGroups", "optionalTrackGroups"})
  private void assertIsPrepared() {
    Assertions.checkState(prepared);
    Assertions.checkNotNull(trackGroups);
    Assertions.checkNotNull(optionalTrackGroups);
  }

  private static int getTrackTypeScore(int trackType) {
    switch (trackType) {
      case C.TRACK_TYPE_VIDEO:
        return 3;
      case C.TRACK_TYPE_AUDIO:
        return 2;
      case C.TRACK_TYPE_TEXT:
        return 1;
      default:
        return 0;
    }
  }

  private static Format deriveFormat(
      @Nullable Format playlistFormat, Format sampleFormat, boolean propagateBitrates) {
    if (playlistFormat == null) {
      return sampleFormat;
    }

    int sampleTrackType = MimeTypes.getTrackType(sampleFormat.sampleMimeType);
    @Nullable String sampleMimeType;
    @Nullable String codecs;
    if (Util.getCodecCountOfType(playlistFormat.codecs, sampleTrackType) == 1) {
      codecs = Util.getCodecsOfType(playlistFormat.codecs, sampleTrackType);
      sampleMimeType = MimeTypes.getMediaMimeType(codecs);
    } else {
      codecs =
          MimeTypes.getCodecsCorrespondingToMimeType(
              playlistFormat.codecs, sampleFormat.sampleMimeType);
      sampleMimeType = sampleFormat.sampleMimeType;
    }

    Format.Builder formatBuilder =
        sampleFormat
            .buildUpon()
            .setId(playlistFormat.id)
            .setLabel(playlistFormat.label)
            .setLabels(playlistFormat.labels)
            .setLanguage(playlistFormat.language)
            .setSelectionFlags(playlistFormat.selectionFlags)
            .setRoleFlags(playlistFormat.roleFlags)
            .setAverageBitrate(propagateBitrates ? playlistFormat.averageBitrate : Format.NO_VALUE)
            .setPeakBitrate(propagateBitrates ? playlistFormat.peakBitrate : Format.NO_VALUE)
            .setCodecs(codecs);

    if (sampleTrackType == C.TRACK_TYPE_VIDEO) {
      formatBuilder
          .setWidth(playlistFormat.width)
          .setHeight(playlistFormat.height)
          .setFrameRate(playlistFormat.frameRate);
    }

    if (sampleMimeType != null) {
      formatBuilder.setSampleMimeType(sampleMimeType);
    }

    if (playlistFormat.channelCount != Format.NO_VALUE && sampleTrackType == C.TRACK_TYPE_AUDIO) {
      formatBuilder.setChannelCount(playlistFormat.channelCount);
    }

    if (playlistFormat.metadata != null) {
      Metadata metadata = playlistFormat.metadata;
      if (sampleFormat.metadata != null) {
        metadata = sampleFormat.metadata.copyWithAppendedEntriesFrom(metadata);
      }
      formatBuilder.setMetadata(metadata);
    }

    return formatBuilder.build();
  }

  private static boolean isMediaChunk(Chunk chunk) {
    return chunk instanceof HlsMediaChunk;
  }

  private static boolean formatsMatch(Format manifestFormat, Format sampleFormat) {
    @Nullable String manifestFormatMimeType = manifestFormat.sampleMimeType;
    @Nullable String sampleFormatMimeType = sampleFormat.sampleMimeType;
    int manifestFormatTrackType = MimeTypes.getTrackType(manifestFormatMimeType);
    if (manifestFormatTrackType != C.TRACK_TYPE_TEXT) {
      return manifestFormatTrackType == MimeTypes.getTrackType(sampleFormatMimeType);
    } else if (!Util.areEqual(manifestFormatMimeType, sampleFormatMimeType)) {
      return false;
    }
    if (MimeTypes.APPLICATION_CEA608.equals(manifestFormatMimeType)
        || MimeTypes.APPLICATION_CEA708.equals(manifestFormatMimeType)) {
      return manifestFormat.accessibilityChannel == sampleFormat.accessibilityChannel;
    }
    return true;
  }

  private static DummyTrackOutput createFakeTrackOutput(int id, int type) {
    Log.w(TAG, "Unmapped track with id " + id + " of type " + type);
    return new DummyTrackOutput();
  }

  private static final class HlsSampleQueue extends SampleQueue {
    private final Map<String, DrmInitData> overridingDrmInitData;
    @Nullable private DrmInitData drmInitData;

    private HlsSampleQueue(
        Allocator allocator,
        DrmSessionManager drmSessionManager,
        DrmSessionEventListener.EventDispatcher eventDispatcher,
        Map<String, DrmInitData> overridingDrmInitData) {
      super(allocator, drmSessionManager, eventDispatcher);
      this.overridingDrmInitData = overridingDrmInitData;
    }

    public void setSourceChunk(HlsMediaChunk chunk) {
      sourceId(chunk.uid);
    }

    public void setDrmInitData(@Nullable DrmInitData drmInitData) {
      this.drmInitData = drmInitData;
      invalidateUpstreamFormatAdjustment();
    }

    @SuppressWarnings("ReferenceEquality")
    @Override
    public Format getAdjustedUpstreamFormat(Format format) {
      @Nullable
      DrmInitData drmInitData = this.drmInitData != null ? this.drmInitData : format.drmInitData;
      if (drmInitData != null) {
        @Nullable
        DrmInitData overridingDrmInitData = this.overridingDrmInitData.get(drmInitData.schemeType);
        if (overridingDrmInitData != null) {
          drmInitData = overridingDrmInitData;
        }
      }
      @Nullable Metadata metadata = getAdjustedMetadata(format.metadata);
      if (drmInitData != format.drmInitData || metadata != format.metadata) {
        format = format.buildUpon().setDrmInitData(drmInitData).setMetadata(metadata).build();
      }
      return super.getAdjustedUpstreamFormat(format);
    }

    @Nullable
    private Metadata getAdjustedMetadata(@Nullable Metadata metadata) {
      if (metadata == null) {
        return null;
      }
      int length = metadata.length();
      int transportStreamTimestampMetadataIndex = C.INDEX_UNSET;
      for (int i = 0; i < length; i++) {
        Metadata.Entry metadataEntry = metadata.get(i);
        if (metadataEntry instanceof PrivFrame) {
          PrivFrame privFrame = (PrivFrame) metadataEntry;
          if (HlsMediaChunk.PRIV_TIMESTAMP_FRAME_OWNER.equals(privFrame.owner)) {
            transportStreamTimestampMetadataIndex = i;
            break;
          }
        }
      }
      if (transportStreamTimestampMetadataIndex == C.INDEX_UNSET) {
        return metadata;
      }
      if (length == 1) {
        return null;
      }
      Metadata.Entry[] newMetadataEntries = new Metadata.Entry[length - 1];
      for (int i = 0; i < length; i++) {
        if (i != transportStreamTimestampMetadataIndex) {
          int newIndex = i < transportStreamTimestampMetadataIndex ? i : i - 1;
          newMetadataEntries[newIndex] = metadata.get(i);
        }
      }
      return new Metadata(newMetadataEntries);
    }

    @Override
    public void sampleMetadata(
        long timeUs,
        @C.BufferFlags int flags,
        int size,
        int offset,
        @Nullable CryptoData cryptoData) {
      super.sampleMetadata(timeUs, flags, size, offset, cryptoData);
    }
  }

  private static class EmsgUnwrappingTrackOutput implements TrackOutput {

    private static final Format ID3_FORMAT =
        new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build();
    private static final Format EMSG_FORMAT =
        new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();

    private final EventMessageDecoder emsgDecoder;
    private final TrackOutput delegate;
    private final Format delegateFormat;
    private @MonotonicNonNull Format format;

    private byte[] buffer;
    private int bufferPosition;

    public EmsgUnwrappingTrackOutput(
        TrackOutput delegate, @HlsMediaSource.MetadataType int metadataType) {
      this.emsgDecoder = new EventMessageDecoder();
      this.delegate = delegate;
      switch (metadataType) {
        case HlsMediaSource.METADATA_TYPE_ID3:
          delegateFormat = ID3_FORMAT;
          break;
        case HlsMediaSource.METADATA_TYPE_EMSG:
          delegateFormat = EMSG_FORMAT;
          break;
        default:
          throw new IllegalArgumentException("Unknown metadataType: " + metadataType);
      }

      this.buffer = new byte[0];
      this.bufferPosition = 0;
    }

    @Override
    public void format(Format format) {
      this.format = format;
      delegate.format(delegateFormat);
    }

    @Override
    public int sampleData(
        DataReader input, int length, boolean allowEndOfInput, @SampleDataPart int sampleDataPart)
        throws IOException {
      ensureBufferCapacity(bufferPosition + length);
      int numBytesRead = input.read(buffer, bufferPosition, length);
      if (numBytesRead == C.RESULT_END_OF_INPUT) {
        if (allowEndOfInput) {
          return C.RESULT_END_OF_INPUT;
        } else {
          throw new EOFException();
        }
      }
      bufferPosition += numBytesRead;
      return numBytesRead;
    }

    @Override
    public void sampleData(ParsableByteArray data, int length, @SampleDataPart int sampleDataPart) {
      ensureBufferCapacity(bufferPosition + length);
      data.readBytes(this.buffer, bufferPosition, length);
      bufferPosition += length;
    }

    @Override
    public void sampleMetadata(
        long timeUs,
        @C.BufferFlags int flags,
        int size,
        int offset,
        @Nullable CryptoData cryptoData) {
      Assertions.checkNotNull(format);
      ParsableByteArray sample = getSampleAndTrimBuffer(size, offset);
      ParsableByteArray sampleForDelegate;
      if (Util.areEqual(format.sampleMimeType, delegateFormat.sampleMimeType)) {
        sampleForDelegate = sample;
      } else if (MimeTypes.APPLICATION_EMSG.equals(format.sampleMimeType)) {
        EventMessage emsg = emsgDecoder.decode(sample);
        if (!emsgContainsExpectedWrappedFormat(emsg)) {
          Log.w(
              TAG,
              String.format(
                  "Ignoring EMSG. Expected it to contain wrapped %s but actual wrapped format: %s",
                  delegateFormat.sampleMimeType, emsg.getWrappedMetadataFormat()));
          return;
        }
        sampleForDelegate =
            new ParsableByteArray(Assertions.checkNotNull(emsg.getWrappedMetadataBytes()));
      } else {
        Log.w(TAG, "Ignoring sample for unsupported format: " + format.sampleMimeType);
        return;
      }

      int sampleSize = sampleForDelegate.bytesLeft();

      delegate.sampleData(sampleForDelegate, sampleSize);
      delegate.sampleMetadata(timeUs, flags, sampleSize, 0, cryptoData);
    }

    private boolean emsgContainsExpectedWrappedFormat(EventMessage emsg) {
      @Nullable Format wrappedMetadataFormat = emsg.getWrappedMetadataFormat();
      return wrappedMetadataFormat != null
          && Util.areEqual(delegateFormat.sampleMimeType, wrappedMetadataFormat.sampleMimeType);
    }

    private void ensureBufferCapacity(int requiredLength) {
      if (buffer.length < requiredLength) {
        buffer = Arrays.copyOf(buffer, requiredLength + requiredLength / 2);
      }
    }

    private ParsableByteArray getSampleAndTrimBuffer(int size, int offset) {
      int sampleEnd = bufferPosition - offset;
      int sampleStart = sampleEnd - size;

      byte[] sampleBytes = Arrays.copyOfRange(buffer, sampleStart, sampleEnd);
      ParsableByteArray sample = new ParsableByteArray(sampleBytes);

      System.arraycopy(buffer, sampleEnd, buffer, 0, offset);
      bufferPosition = offset;
      return sample;
    }
  }
}
