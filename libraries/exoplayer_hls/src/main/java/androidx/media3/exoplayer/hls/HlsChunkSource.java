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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static java.lang.Math.max;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Pair;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.UriUtil;
import androidx.media3.common.util.Util;
import androidx.media3.common.util.Log; // Added for logging
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Segment;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.hls.playlist.InterstitialMarker; // Added import
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.source.chunk.BaseMediaChunkIterator;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.exoplayer.source.chunk.DataChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.trackselection.BaseTrackSelection;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.CmcdData;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Source of Hls (possibly adaptive) chunks. */
/* package */ class HlsChunkSource {

  /** Chunk holder that allows the scheduling of retries. */
  public static final class HlsChunkHolder {

    public HlsChunkHolder() {
      clear();
    }

    /** The chunk to be loaded next. */
    @Nullable public Chunk chunk;

    /** Indicates that the end of the stream has been reached. */
    public boolean endOfStream;

    /** Indicates that the chunk source is waiting for the referred playlist to be refreshed. */
    @Nullable public Uri playlistUrl;

    /** Clears the holder. */
    public void clear() {
      chunk = null;
      endOfStream = false;
      playlistUrl = null;
    }
  }

  @Documented
  @Target(TYPE_USE)
  @IntDef({
    CHUNK_PUBLICATION_STATE_PRELOAD,
    CHUNK_PUBLICATION_STATE_PUBLISHED,
    CHUNK_PUBLICATION_STATE_REMOVED
  })
  @Retention(RetentionPolicy.SOURCE)
  @interface ChunkPublicationState {}

  public static final int CHUNK_PUBLICATION_STATE_PRELOAD = 0;
  public static final int CHUNK_PUBLICATION_STATE_PUBLISHED = 1;
  public static final int CHUNK_PUBLICATION_STATE_REMOVED = 2;

  private static final int KEY_CACHE_SIZE = 4;
  private static final String TAG = "HlsChunkSource";

  private final HlsExtractorFactory extractorFactory;
  private final DataSource mediaDataSource;
  private final DataSource encryptionDataSource;
  private final TimestampAdjusterProvider timestampAdjusterProvider;
  private final Uri[] playlistUrls;
  private final Format[] playlistFormats;
  private final HlsPlaylistTracker playlistTracker;
  private final TrackGroup trackGroup;
  @Nullable private final List<Format> muxedCaptionFormats;
  private final FullSegmentEncryptionKeyCache keyCache;
  private final PlayerId playerId;
  @Nullable private final CmcdConfiguration cmcdConfiguration;
  private final long timestampAdjusterInitializationTimeoutMs;

  private boolean isPrimaryTimestampSource;
  private byte[] scratchSpace;
  @Nullable private IOException fatalError;
  @Nullable private Uri expectedPlaylistUrl;
  private boolean independentSegments;
  private ExoTrackSelection trackSelection;
  private long liveEdgeInPeriodTimeUs;
  private boolean seenExpectedPlaylistError;
  private long lastChunkRequestRealtimeMs;

  private static final class TimelineItem {
    public final long timelineStartTimeUs;
    public final long timelineEndTimeUs;
    public final boolean isContent; 
    @Nullable public final Segment contentSegment; 
    @Nullable public final InterstitialMarker interstitialMarker; 
    public final long itemStartInPlaylistRelativeUs; 

    public TimelineItem(long timelineStartTimeUs, long timelineEndTimeUs, Segment contentSegment, long itemStartInPlaylistRelativeUs) {
      this.timelineStartTimeUs = timelineStartTimeUs;
      this.timelineEndTimeUs = timelineEndTimeUs;
      this.isContent = true;
      this.contentSegment = contentSegment;
      this.interstitialMarker = null;
      this.itemStartInPlaylistRelativeUs = itemStartInPlaylistRelativeUs;
    }

    public TimelineItem(long timelineStartTimeUs, long timelineEndTimeUs, InterstitialMarker interstitialMarker) {
      this.timelineStartTimeUs = timelineStartTimeUs;
      this.timelineEndTimeUs = timelineEndTimeUs;
      this.isContent = false;
      this.contentSegment = null;
      this.interstitialMarker = interstitialMarker;
      this.itemStartInPlaylistRelativeUs = interstitialMarker.startTimeUs;
    }
  }


  public HlsChunkSource(
      HlsExtractorFactory extractorFactory,
      HlsPlaylistTracker playlistTracker,
      Uri[] playlistUrls,
      Format[] playlistFormats,
      HlsDataSourceFactory dataSourceFactory,
      @Nullable TransferListener mediaTransferListener,
      TimestampAdjusterProvider timestampAdjusterProvider,
      long timestampAdjusterInitializationTimeoutMs,
      @Nullable List<Format> muxedCaptionFormats,
      PlayerId playerId,
      @Nullable CmcdConfiguration cmcdConfiguration) {
    this.extractorFactory = extractorFactory;
    this.playlistTracker = playlistTracker;
    this.playlistUrls = playlistUrls;
    this.playlistFormats = playlistFormats;
    this.timestampAdjusterProvider = timestampAdjusterProvider;
    this.timestampAdjusterInitializationTimeoutMs = timestampAdjusterInitializationTimeoutMs;
    this.muxedCaptionFormats = muxedCaptionFormats;
    this.playerId = playerId;
    this.cmcdConfiguration = cmcdConfiguration;
    this.lastChunkRequestRealtimeMs = C.TIME_UNSET;
    keyCache = new FullSegmentEncryptionKeyCache(KEY_CACHE_SIZE);
    scratchSpace = Util.EMPTY_BYTE_ARRAY;
    liveEdgeInPeriodTimeUs = C.TIME_UNSET;
    mediaDataSource = dataSourceFactory.createDataSource(C.DATA_TYPE_MEDIA);
    if (mediaTransferListener != null) {
      mediaDataSource.addTransferListener(mediaTransferListener);
    }
    encryptionDataSource = dataSourceFactory.createDataSource(C.DATA_TYPE_DRM);
    trackGroup = new TrackGroup(playlistFormats);
    ArrayList<Integer> initialTrackSelection = new ArrayList<>();
    for (int i = 0; i < playlistUrls.length; i++) {
      if ((playlistFormats[i].roleFlags & C.ROLE_FLAG_TRICK_PLAY) == 0) {
        initialTrackSelection.add(i);
      }
    }
    trackSelection =
        new InitializationTrackSelection(trackGroup, Ints.toArray(initialTrackSelection));
  }

  public void maybeThrowError() throws IOException {
    if (fatalError != null) {
      throw fatalError;
    }
    if (expectedPlaylistUrl != null && seenExpectedPlaylistError) {
      playlistTracker.maybeThrowPlaylistRefreshError(expectedPlaylistUrl);
    }
  }

  public TrackGroup getTrackGroup() {
    return trackGroup;
  }

  public boolean hasIndependentSegments() {
    return independentSegments;
  }

  public void setTrackSelection(ExoTrackSelection trackSelection) {
    this.trackSelection = trackSelection;
  }

  public ExoTrackSelection getTrackSelection() {
    return trackSelection;
  }

  public void reset() {
    fatalError = null;
  }

  public void setIsPrimaryTimestampSource(boolean isPrimaryTimestampSource) {
    this.isPrimaryTimestampSource = isPrimaryTimestampSource;
  }

  public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
    int selectedTrackIndex = trackSelection.getSelectedIndexInTrackGroup();
    HlsMediaPlaylist mediaPlaylist =
        playlistTracker.getPlaylistSnapshot(playlistUrls[selectedTrackIndex], true);

    if (mediaPlaylist == null || mediaPlaylist.segments.isEmpty() || !mediaPlaylist.hasIndependentSegments) {
      return positionUs;
    }

    long startOfPlaylistInPeriodUs = mediaPlaylist.startTimeUs - playlistTracker.getInitialStartTimeUs();
    long targetPlaylistRelativeSeekTimeUs = positionUs - startOfPlaylistInPeriodUs;

    List<TimelineItem> timelineItems = new ArrayList<>();
    for (Segment segment : mediaPlaylist.segments) {
      for (InterstitialMarker marker : segment.interstitialMarkers) {
        timelineItems.add(new TimelineItem(marker.startTimeUs, marker.endTimeUs, marker));
      }
      // Add content segment. Its timeline start might be affected by prior interstitials.
      // This needs accurate timeline construction. For now, use segment.relativeStartTimeUs.
      // A post-sort and merge step will be needed.
      timelineItems.add(new TimelineItem(segment.relativeStartTimeUs, segment.relativeStartTimeUs + segment.durationUs, segment, segment.relativeStartTimeUs));
    }

    Collections.sort(timelineItems, Comparator.comparingLong(item -> item.timelineStartTimeUs));

    List<TimelineItem> mergedTimeline = new ArrayList<>();
    if (!timelineItems.isEmpty()) {
      mergedTimeline.add(timelineItems.get(0));
      for (int i = 1; i < timelineItems.size(); i++) {
        TimelineItem current = timelineItems.get(i);
        TimelineItem lastMerged = mergedTimeline.get(mergedTimeline.size() - 1);
        if (current.timelineStartTimeUs >= lastMerged.timelineEndTimeUs) { // No overlap
          mergedTimeline.add(current);
        } else { // Overlap
          // Prioritize interstitial, or the one that starts earlier if same type.
          // This is a simplification. True overlap resolution is complex.
          if (lastMerged.isContent && !current.isContent) { // Current is interstitial, previous is content
             // Truncate previous content
            if (lastMerged.timelineStartTimeUs < current.timelineStartTimeUs) {
                 mergedTimeline.set(mergedTimeline.size()-1, new TimelineItem(lastMerged.timelineStartTimeUs, current.timelineStartTimeUs, checkNotNull(lastMerged.contentSegment), lastMerged.itemStartInPlaylistRelativeUs));
            } else { // interstitial completely overwrites previous
                 mergedTimeline.remove(mergedTimeline.size()-1);
            }
            mergedTimeline.add(current); // Add interstitial
          } else if (!lastMerged.isContent && current.isContent) { // Current is content, previous is interstitial
            // If content starts during or after interstitial, it's fine if it starts after.
            if (current.timelineStartTimeUs >= lastMerged.timelineEndTimeUs) {
                 mergedTimeline.add(current);
            } // else content is swallowed or starts late, handled by next item.
          } else { // Both content or both interstitial - earlier one wins, no merge.
             // This means the sort order is dominant for same-type overlaps.
             // Or if current starts later and ends later, update end time of last.
             if (current.timelineStartTimeUs >= lastMerged.timelineStartTimeUs && current.timelineEndTimeUs > lastMerged.timelineEndTimeUs && current.isContent == lastMerged.isContent) {
                 // Extend the last one if they are contiguous or current extends.
                 // For now, simple non-overlapping merge from sorted list.
             }
          }
        }
      }
    }
    timelineItems = mergedTimeline; // Use the merged timeline

    TimelineItem targetItem = null;
    int targetItemIndex = -1;

    for (int i = 0; i < timelineItems.size(); i++) {
      TimelineItem item = timelineItems.get(i);
      if (targetPlaylistRelativeSeekTimeUs >= item.timelineStartTimeUs && targetPlaylistRelativeSeekTimeUs < item.timelineEndTimeUs) {
        targetItem = item;
        targetItemIndex = i;
        break;
      }
      if (i == timelineItems.size() - 1 && targetPlaylistRelativeSeekTimeUs >= item.timelineEndTimeUs) {
        targetItem = item; // Snap to end of last item
        targetItemIndex = i;
      }
    }
    
    if (targetItem == null) return positionUs; // Should not happen

    if (!targetItem.isContent) {
      InterstitialMarker marker = checkNotNull(targetItem.interstitialMarker);
      long seekInMarkerUs = targetPlaylistRelativeSeekTimeUs - marker.startTimeUs;
      long resolvedSeekInMarkerUs = seekParameters.resolveSeekPositionUs(seekInMarkerUs, 0, marker.endTimeUs - marker.startTimeUs);
      return startOfPlaylistInPeriodUs + marker.startTimeUs + resolvedSeekInMarkerUs;
    } else {
      Segment segment = checkNotNull(targetItem.contentSegment);
      long seekInContentTimelineUs = targetPlaylistRelativeSeekTimeUs - targetItem.timelineStartTimeUs;
      
      long firstSyncInContentUs = 0;
      long secondSyncInContentUs = segment.durationUs; // Default to its own duration

      // Find start of next content segment on the *content-only* timeline for secondSyncUs
      long currentSegmentContentOnlyStartTime = targetItem.itemStartInPlaylistRelativeUs;
      long nextContentSegmentContentOnlyStartTime = currentSegmentContentOnlyStartTime + segment.durationUs; // Default

      for (int i = targetItemIndex + 1; i < timelineItems.size(); i++) {
          TimelineItem nextItem = timelineItems.get(i);
          if (nextItem.isContent) {
              nextContentSegmentContentOnlyStartTime = nextItem.itemStartInPlaylistRelativeUs;
              break;
          }
      }
      secondSyncInContentUs = nextContentSegmentContentOnlyStartTime - currentSegmentContentOnlyStartTime;
      if(secondSyncInContentUs <=0) secondSyncInContentUs = segment.durationUs;


      long resolvedSeekInContentUs = seekParameters.resolveSeekPositionUs(seekInContentTimelineUs, firstSyncInContentUs, secondSyncInContentUs);
      return startOfPlaylistInPeriodUs + targetItem.timelineStartTimeUs + resolvedSeekInContentUs;
    }
  }
  
  private long resolveTimeToLiveEdgeUs(long playbackPositionUs) {
    if (liveEdgeInPeriodTimeUs == C.TIME_UNSET) {
      return C.TIME_UNSET;
    }
    long timeToLiveEdgeWithoutInterstitialsUs = liveEdgeInPeriodTimeUs - playbackPositionUs;
    if (timeToLiveEdgeWithoutInterstitialsUs <= 0) {
      return 0;
    }

    int selectedTrackIndex = trackSelection.getSelectedIndexInTrackGroup();
    HlsMediaPlaylist mediaPlaylist = playlistTracker.getPlaylistSnapshot(playlistUrls[selectedTrackIndex], true);

    if (mediaPlaylist == null || mediaPlaylist.segments.isEmpty()) {
        return timeToLiveEdgeWithoutInterstitialsUs;
    }
    
    long playlistStartInPeriodUs = mediaPlaylist.startTimeUs - playlistTracker.getInitialStartTimeUs();
    long playbackPositionInPlaylistRelativeUs = playbackPositionUs - playlistStartInPeriodUs;
    long liveEdgeInPlaylistRelativeUs = liveEdgeInPeriodTimeUs - playlistStartInPeriodUs;

    long totalInterstitialDurationInWindowUs = 0;
    for (Segment segment : mediaPlaylist.segments) {
        for (InterstitialMarker marker : segment.interstitialMarkers) {
            // Assuming marker times are relative to playlist start
            long markerStartInPlaylistRelativeUs = marker.startTimeUs;
            long markerEndInPlaylistRelativeUs = marker.endTimeUs;

            // Check if marker overlaps with the window [playbackPositionInPlaylistRelativeUs, liveEdgeInPlaylistRelativeUs)
            long overlapStart = Math.max(playbackPositionInPlaylistRelativeUs, markerStartInPlaylistRelativeUs);
            long overlapEnd = Math.min(liveEdgeInPlaylistRelativeUs, markerEndInPlaylistRelativeUs);

            if (overlapEnd > overlapStart) {
                totalInterstitialDurationInWindowUs += (overlapEnd - overlapStart);
            }
        }
    }
    return Math.max(0, timeToLiveEdgeWithoutInterstitialsUs - totalInterstitialDurationInWindowUs);
}


  public @ChunkPublicationState int getChunkPublicationState(HlsMediaChunk mediaChunk) {
    if (mediaChunk.partIndex == C.INDEX_UNSET) {
      return CHUNK_PUBLICATION_STATE_PUBLISHED;
    }
    Uri playlistUrl = playlistUrls[trackGroup.indexOf(mediaChunk.trackFormat)];
    HlsMediaPlaylist mediaPlaylist =
        checkNotNull(playlistTracker.getPlaylistSnapshot(playlistUrl, false));
    int segmentIndexInPlaylist = (int) (mediaChunk.chunkIndex - mediaPlaylist.mediaSequence);
    if (segmentIndexInPlaylist < 0) {
      return CHUNK_PUBLICATION_STATE_PUBLISHED;
    }
    List<HlsMediaPlaylist.Part> partsInCurrentPlaylist =
        segmentIndexInPlaylist < mediaPlaylist.segments.size()
            ? mediaPlaylist.segments.get(segmentIndexInPlaylist).parts
            : mediaPlaylist.trailingParts;
    if (mediaChunk.partIndex >= partsInCurrentPlaylist.size()) {
      return CHUNK_PUBLICATION_STATE_REMOVED;
    }
    HlsMediaPlaylist.Part newPart = partsInCurrentPlaylist.get(mediaChunk.partIndex);
    if (newPart.isPreload) {
      return CHUNK_PUBLICATION_STATE_PRELOAD;
    }
    Uri newUri = Uri.parse(UriUtil.resolve(mediaPlaylist.baseUri, newPart.url));
    return Util.areEqual(newUri, mediaChunk.dataSpec.uri)
        ? CHUNK_PUBLICATION_STATE_PUBLISHED
        : CHUNK_PUBLICATION_STATE_REMOVED;
  }

  public void getNextChunk(
      LoadingInfo loadingInfo,
      long loadPositionUs,
      List<HlsMediaChunk> queue,
      boolean allowEndOfStream,
      HlsChunkHolder out) {
    @Nullable HlsMediaChunk previous = queue.isEmpty() ? null : Iterables.getLast(queue);
    int oldTrackIndex = previous == null ? C.INDEX_UNSET : trackGroup.indexOf(previous.trackFormat);
    long playbackPositionUs = loadingInfo.playbackPositionUs;
    long bufferedDurationUs = loadPositionUs - playbackPositionUs;
    long timeToLiveEdgeUs = resolveTimeToLiveEdgeUs(playbackPositionUs);
    if (previous != null && !independentSegments) {
      long subtractedDurationUs = previous.getDurationUs();
      bufferedDurationUs = max(0, bufferedDurationUs - subtractedDurationUs);
      if (timeToLiveEdgeUs != C.TIME_UNSET) {
        timeToLiveEdgeUs = max(0, timeToLiveEdgeUs - subtractedDurationUs);
      }
    }

    MediaChunkIterator[] mediaChunkIterators = createMediaChunkIterators(previous, loadPositionUs);
    trackSelection.updateSelectedTrack(
        playbackPositionUs, bufferedDurationUs, timeToLiveEdgeUs, queue, mediaChunkIterators);
    int selectedTrackIndex = trackSelection.getSelectedIndexInTrackGroup();
    boolean switchingTrack = oldTrackIndex != selectedTrackIndex;
    Uri selectedPlaylistUrl = playlistUrls[selectedTrackIndex];
    if (!playlistTracker.isSnapshotValid(selectedPlaylistUrl)) {
      out.playlistUrl = selectedPlaylistUrl;
      seenExpectedPlaylistError &= selectedPlaylistUrl.equals(expectedPlaylistUrl);
      expectedPlaylistUrl = selectedPlaylistUrl;
      return;
    }
    @Nullable
    HlsMediaPlaylist playlist =
        playlistTracker.getPlaylistSnapshot(selectedPlaylistUrl, true);
    checkNotNull(playlist);
    independentSegments = playlist.hasIndependentSegments;

    updateLiveEdgeTimeUs(playlist);

    long startOfPlaylistInPeriodUs = playlist.startTimeUs - playlistTracker.getInitialStartTimeUs();
    Pair<Long, Integer> nextMediaSequenceAndPartIndex =
        getNextMediaSequenceAndPartIndex(
            previous, switchingTrack, playlist, startOfPlaylistInPeriodUs, loadPositionUs);
    long chunkMediaSequence = nextMediaSequenceAndPartIndex.first;
    int partIndex = nextMediaSequenceAndPartIndex.second;
    if (chunkMediaSequence < playlist.mediaSequence && previous != null && switchingTrack) {
      selectedTrackIndex = oldTrackIndex;
      selectedPlaylistUrl = playlistUrls[selectedTrackIndex];
      playlist =
          playlistTracker.getPlaylistSnapshot(selectedPlaylistUrl, true);
      checkNotNull(playlist);
      startOfPlaylistInPeriodUs = playlist.startTimeUs - playlistTracker.getInitialStartTimeUs();
      Pair<Long, Integer> nextMediaSequenceAndPartIndexWithoutAdapting =
          getNextMediaSequenceAndPartIndex(
              previous,
              false,
              playlist,
              startOfPlaylistInPeriodUs,
              loadPositionUs);
      chunkMediaSequence = nextMediaSequenceAndPartIndexWithoutAdapting.first;
      partIndex = nextMediaSequenceAndPartIndexWithoutAdapting.second;
    }

    if (chunkMediaSequence < playlist.mediaSequence) {
      fatalError = new BehindLiveWindowException();
      return;
    }

    @Nullable
    SegmentBaseHolder segmentBaseHolder =
        getNextSegmentHolder(playlist, chunkMediaSequence, partIndex, loadPositionUs, startOfPlaylistInPeriodUs); 
    if (segmentBaseHolder == null) {
      if (!playlist.hasEndTag) {
        out.playlistUrl = selectedPlaylistUrl;
        seenExpectedPlaylistError &= selectedPlaylistUrl.equals(expectedPlaylistUrl);
        expectedPlaylistUrl = selectedPlaylistUrl;
        return;
      } else if (allowEndOfStream || playlist.segments.isEmpty()) {
        out.endOfStream = true;
        return;
      }
      HlsMediaPlaylist.Segment lastSegment = Iterables.getLast(playlist.segments);
      @Nullable InterstitialMarker lastSegmentInterstitialMarker = null;
      if (!lastSegment.interstitialMarkers.isEmpty()){
        InterstitialMarker potentialMarker = lastSegment.interstitialMarkers.get(0);
        long playlistRelativeLoadPos = loadPositionUs - startOfPlaylistInPeriodUs;
        if (playlistRelativeLoadPos >= potentialMarker.startTimeUs && playlistRelativeLoadPos < potentialMarker.endTimeUs) {
            lastSegmentInterstitialMarker = potentialMarker;
        }
      }
      segmentBaseHolder =
          new SegmentBaseHolder(
              lastSegment,
              playlist.mediaSequence + playlist.segments.size() - 1,
              C.INDEX_UNSET,
              lastSegmentInterstitialMarker);
    }

    seenExpectedPlaylistError = false;
    expectedPlaylistUrl = null;

    @Nullable CmcdData.Factory cmcdDataFactory = null;
    if (cmcdConfiguration != null) {
      cmcdDataFactory =
          new CmcdData.Factory(
                  cmcdConfiguration,
                  trackSelection,
                  max(0, bufferedDurationUs),
                  loadingInfo.playbackSpeed,
                  CmcdData.Factory.STREAMING_FORMAT_HLS,
                  !playlist.hasEndTag,
                  loadingInfo.rebufferedSince(lastChunkRequestRealtimeMs),
                  queue.isEmpty())
              .setObjectType(
                  getIsMuxedAudioAndVideo()
                      ? CmcdData.Factory.OBJECT_TYPE_MUXED_AUDIO_AND_VIDEO
                      : CmcdData.Factory.getObjectType(trackSelection));

      long nextChunkMediaSequence =
          partIndex == C.LENGTH_UNSET
              ? (chunkMediaSequence == C.LENGTH_UNSET ? C.LENGTH_UNSET : chunkMediaSequence + 1)
              : chunkMediaSequence;
      int nextPartIndex = partIndex == C.LENGTH_UNSET ? C.LENGTH_UNSET : partIndex + 1;
      SegmentBaseHolder nextSegmentBaseHolder =
          getNextSegmentHolder(playlist, nextChunkMediaSequence, nextPartIndex, loadPositionUs, startOfPlaylistInPeriodUs);
      if (nextSegmentBaseHolder != null) {
        Uri uri = UriUtil.resolveToUri(playlist.baseUri, segmentBaseHolder.segmentBase.url);
        Uri nextUri = UriUtil.resolveToUri(playlist.baseUri, nextSegmentBaseHolder.segmentBase.url);
        cmcdDataFactory.setNextObjectRequest(UriUtil.getRelativePath(uri, nextUri));

        String nextRangeRequest = nextSegmentBaseHolder.segmentBase.byteRangeOffset + "-";
        if (nextSegmentBaseHolder.segmentBase.byteRangeLength != C.LENGTH_UNSET) {
          nextRangeRequest +=
              (nextSegmentBaseHolder.segmentBase.byteRangeOffset
                  + nextSegmentBaseHolder.segmentBase.byteRangeLength);
        }
        cmcdDataFactory.setNextRangeRequest(nextRangeRequest);
      }
    }
    lastChunkRequestRealtimeMs = SystemClock.elapsedRealtime();
    
    if (segmentBaseHolder.interstitialMarker != null) {
      Log.d(TAG, "Interstitial marker active: " + segmentBaseHolder.interstitialMarker.uri + " at time " + segmentBaseHolder.interstitialMarker.startTimeUs);
      Uri chunkUri;
      if (segmentBaseHolder.interstitialMarker.uri != null && !segmentBaseHolder.interstitialMarker.uri.isEmpty()){
          chunkUri = UriUtil.resolveToUri(playlist.baseUri, segmentBaseHolder.interstitialMarker.uri);
      } else {
          chunkUri = UriUtil.resolveToUri(playlist.baseUri, segmentBaseHolder.segmentBase.url); 
      }
      DataSpec dataSpec = new DataSpec(chunkUri, 0, C.LENGTH_UNSET); 

      out.chunk = new InterstitialChunk(
          mediaDataSource, 
          dataSpec,
          playlistFormats[selectedTrackIndex], 
          trackSelection.getSelectionReason(),
          trackSelection.getSelectionData(),
          segmentBaseHolder.interstitialMarker);
      return;
    }

    @Nullable
    Uri initSegmentKeyUri =
        getFullEncryptionKeyUri(playlist, segmentBaseHolder.segmentBase.initializationSegment);
    out.chunk =
        maybeCreateEncryptionChunkFor(
            initSegmentKeyUri, selectedTrackIndex, true, cmcdDataFactory);
    if (out.chunk != null) {
      return;
    }
    @Nullable
    Uri mediaSegmentKeyUri = getFullEncryptionKeyUri(playlist, segmentBaseHolder.segmentBase);
    out.chunk =
        maybeCreateEncryptionChunkFor(
            mediaSegmentKeyUri, selectedTrackIndex, false, cmcdDataFactory);
    if (out.chunk != null) {
      return;
    }

    boolean shouldSpliceIn =
        HlsMediaChunk.shouldSpliceIn(
            previous, selectedPlaylistUrl, playlist, segmentBaseHolder, startOfPlaylistInPeriodUs);
    if (shouldSpliceIn && segmentBaseHolder.isPreload) {
      return;
    }

    out.chunk =
        HlsMediaChunk.createInstance(
            extractorFactory,
            mediaDataSource,
            playlistFormats[selectedTrackIndex],
            startOfPlaylistInPeriodUs,
            playlist,
            segmentBaseHolder,
            selectedPlaylistUrl,
            muxedCaptionFormats,
            trackSelection.getSelectionReason(),
            trackSelection.getSelectionData(),
            isPrimaryTimestampSource,
            timestampAdjusterProvider,
            timestampAdjusterInitializationTimeoutMs,
            previous,
            keyCache.get(mediaSegmentKeyUri),
            keyCache.get(initSegmentKeyUri),
            shouldSpliceIn,
            playerId,
            cmcdDataFactory);
  }

  private boolean getIsMuxedAudioAndVideo() {
    Format format = trackGroup.getFormat(trackSelection.getSelectedIndex());
    String audioMimeType = MimeTypes.getAudioMediaMimeType(format.codecs);
    String videoMimeType = MimeTypes.getVideoMediaMimeType(format.codecs);
    return audioMimeType != null && videoMimeType != null;
  }

  @Nullable
  private static SegmentBaseHolder getNextSegmentHolder(
      HlsMediaPlaylist mediaPlaylist, long nextMediaSequence, int nextPartIndex, long loadPositionUs, long startOfPlaylistInPeriodUs) {
    int segmentIndexInPlaylist = (int) (nextMediaSequence - mediaPlaylist.mediaSequence);
    long playlistRelativeLoadPosUs = loadPositionUs - startOfPlaylistInPeriodUs;

    if (segmentIndexInPlaylist == mediaPlaylist.segments.size()) {
      int index = nextPartIndex != C.INDEX_UNSET ? nextPartIndex : 0;
      return index < mediaPlaylist.trailingParts.size()
          ? new SegmentBaseHolder(
              mediaPlaylist.trailingParts.get(index),
              nextMediaSequence,
              index,
              null)
          : null;
    }

    Segment mediaSegment = mediaPlaylist.segments.get(segmentIndexInPlaylist);
    @Nullable InterstitialMarker activeInterstitialMarker = null;
    if (mediaSegment != null && !mediaSegment.interstitialMarkers.isEmpty()) {
      for (InterstitialMarker marker : mediaSegment.interstitialMarkers) {
        if (playlistRelativeLoadPosUs >= marker.startTimeUs && playlistRelativeLoadPosUs < marker.endTimeUs) {
          activeInterstitialMarker = marker;
          break; 
        }
      }
      // Fallback: if loading the segment as a whole, and it has interstitials, pick the first one
      // that hasn't finished yet relative to loadPositionUs.
      // This helps ensure pre-roll ads are selected if loadPositionUs is at the very start of content.
      if (activeInterstitialMarker == null && nextPartIndex == C.INDEX_UNSET) {
          for (InterstitialMarker marker : mediaSegment.interstitialMarkers) {
              if (playlistRelativeLoadPosUs < marker.endTimeUs) {
                  activeInterstitialMarker = marker;
                  break;
              }
          }
      }
    }

    if (nextPartIndex == C.INDEX_UNSET) {
      return new SegmentBaseHolder(
          mediaSegment,
          nextMediaSequence,
          C.INDEX_UNSET,
          activeInterstitialMarker);
    }

    if (nextPartIndex < mediaSegment.parts.size()) {
      return new SegmentBaseHolder(
          mediaSegment.parts.get(nextPartIndex),
          nextMediaSequence,
          nextPartIndex,
          null); 
    } else if (segmentIndexInPlaylist + 1 < mediaPlaylist.segments.size()) {
      Segment nextFullSegment = mediaPlaylist.segments.get(segmentIndexInPlaylist + 1);
      @Nullable InterstitialMarker nextActiveInterstitialMarker = null;
      if (!nextFullSegment.interstitialMarkers.isEmpty()) {
         for (InterstitialMarker marker : nextFullSegment.interstitialMarkers) {
            if (playlistRelativeLoadPosUs >= marker.startTimeUs && playlistRelativeLoadPosUs < marker.endTimeUs) {
                nextActiveInterstitialMarker = marker;
                break;
            }
         }
         if (nextActiveInterstitialMarker == null && playlistRelativeLoadPosUs < nextFullSegment.interstitialMarkers.get(0).endTimeUs) {
             nextActiveInterstitialMarker = nextFullSegment.interstitialMarkers.get(0);
         }
      }
      return new SegmentBaseHolder(
          nextFullSegment,
          nextMediaSequence + 1,
          C.INDEX_UNSET,
          nextActiveInterstitialMarker);
    } else if (!mediaPlaylist.trailingParts.isEmpty()) {
      return new SegmentBaseHolder(
          mediaPlaylist.trailingParts.get(0),
          nextMediaSequence + 1,
          0,
          null);
    }
    return null;
  }

  public void onChunkLoadCompleted(Chunk chunk) {
    if (chunk instanceof EncryptionKeyChunk) {
      EncryptionKeyChunk encryptionKeyChunk = (EncryptionKeyChunk) chunk;
      scratchSpace = encryptionKeyChunk.getDataHolder();
      keyCache.put(encryptionKeyChunk.dataSpec.uri, checkNotNull(encryptionKeyChunk.getResult()));
    }
  }

  public boolean maybeExcludeTrack(Chunk chunk, long exclusionDurationMs) {
    return trackSelection.excludeTrack(
        trackSelection.indexOf(trackGroup.indexOf(chunk.trackFormat)), exclusionDurationMs);
  }

  public boolean onPlaylistError(Uri playlistUrl, long exclusionDurationMs) {
    int trackGroupIndex = C.INDEX_UNSET;
    for (int i = 0; i < playlistUrls.length; i++) {
      if (playlistUrls[i].equals(playlistUrl)) {
        trackGroupIndex = i;
        break;
      }
    }
    if (trackGroupIndex == C.INDEX_UNSET) {
      return true;
    }
    int trackSelectionIndex = trackSelection.indexOf(trackGroupIndex);
    if (trackSelectionIndex == C.INDEX_UNSET) {
      return true;
    }
    seenExpectedPlaylistError |= playlistUrl.equals(expectedPlaylistUrl);
    return exclusionDurationMs == C.TIME_UNSET
        || (trackSelection.excludeTrack(trackSelectionIndex, exclusionDurationMs)
            && playlistTracker.excludeMediaPlaylist(playlistUrl, exclusionDurationMs));
  }

  public MediaChunkIterator[] createMediaChunkIterators(
      @Nullable HlsMediaChunk previous, long loadPositionUs) {
    int oldTrackIndex = previous == null ? C.INDEX_UNSET : trackGroup.indexOf(previous.trackFormat);
    MediaChunkIterator[] chunkIterators = new MediaChunkIterator[trackSelection.length()];
    for (int i = 0; i < chunkIterators.length; i++) {
      int trackIndex = trackSelection.getIndexInTrackGroup(i);
      Uri playlistUrl = playlistUrls[trackIndex];
      if (!playlistTracker.isSnapshotValid(playlistUrl)) {
        chunkIterators[i] = MediaChunkIterator.EMPTY;
        continue;
      }
      @Nullable
      HlsMediaPlaylist playlist =
          playlistTracker.getPlaylistSnapshot(playlistUrl, false);
      checkNotNull(playlist);
      long startOfPlaylistInPeriodUs =
          playlist.startTimeUs - playlistTracker.getInitialStartTimeUs();
      boolean switchingTrack = trackIndex != oldTrackIndex;
      Pair<Long, Integer> chunkMediaSequenceAndPartIndex =
          getNextMediaSequenceAndPartIndex(
              previous, switchingTrack, playlist, startOfPlaylistInPeriodUs, loadPositionUs);
      long chunkMediaSequence = chunkMediaSequenceAndPartIndex.first;
      int partIndex = chunkMediaSequenceAndPartIndex.second;
      chunkIterators[i] =
          new HlsMediaPlaylistSegmentIterator(
              playlist.baseUri,
              startOfPlaylistInPeriodUs,
              getSegmentBaseList(playlist, chunkMediaSequence, partIndex));
    }
    return chunkIterators;
  }

  public int getPreferredQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
    if (fatalError != null || trackSelection.length() < 2) {
      return queue.size();
    }
    return trackSelection.evaluateQueueSize(playbackPositionUs, queue);
  }

  public boolean shouldCancelLoad(
      long playbackPositionUs, Chunk loadingChunk, List<? extends MediaChunk> queue) {
    if (fatalError != null) {
      return false;
    }
    return trackSelection.shouldCancelChunkLoad(playbackPositionUs, loadingChunk, queue);
  }

  @VisibleForTesting
  /* package */ static List<HlsMediaPlaylist.SegmentBase> getSegmentBaseList(
      HlsMediaPlaylist playlist, long mediaSequence, int partIndex) {
    int firstSegmentIndexInPlaylist = (int) (mediaSequence - playlist.mediaSequence);
    if (firstSegmentIndexInPlaylist < 0 || playlist.segments.size() < firstSegmentIndexInPlaylist) {
      return ImmutableList.of();
    }
    List<HlsMediaPlaylist.SegmentBase> segmentBases = new ArrayList<>();
    if (firstSegmentIndexInPlaylist < playlist.segments.size()) {
      if (partIndex != C.INDEX_UNSET) {
        Segment firstSegment = playlist.segments.get(firstSegmentIndexInPlaylist);
        if (partIndex == 0) {
          segmentBases.add(firstSegment);
        } else if (partIndex < firstSegment.parts.size()) {
          segmentBases.addAll(firstSegment.parts.subList(partIndex, firstSegment.parts.size()));
        }
        firstSegmentIndexInPlaylist++;
      }
      partIndex = 0;
      segmentBases.addAll(
          playlist.segments.subList(firstSegmentIndexInPlaylist, playlist.segments.size()));
    }

    if (playlist.partTargetDurationUs != C.TIME_UNSET) {
      partIndex = partIndex == C.INDEX_UNSET ? 0 : partIndex;
      if (partIndex < playlist.trailingParts.size()) {
        segmentBases.addAll(
            playlist.trailingParts.subList(partIndex, playlist.trailingParts.size()));
      }
    }
    return Collections.unmodifiableList(segmentBases);
  }

  public boolean obtainsChunksForPlaylist(Uri playlistUrl) {
    return Util.contains(playlistUrls, playlistUrl);
  }

  private Pair<Long, Integer> getNextMediaSequenceAndPartIndex(
      @Nullable HlsMediaChunk previous,
      boolean switchingTrack,
      HlsMediaPlaylist mediaPlaylist,
      long startOfPlaylistInPeriodUs,
      long loadPositionUs) {
    if (previous == null || switchingTrack) {
      long endOfPlaylistInPeriodUs = startOfPlaylistInPeriodUs + mediaPlaylist.durationUs;
      long targetPositionInPeriodUs =
          (previous == null || independentSegments) ? loadPositionUs : previous.startTimeUs;
      if (!mediaPlaylist.hasEndTag && targetPositionInPeriodUs >= endOfPlaylistInPeriodUs) {
        return new Pair<>(
            mediaPlaylist.mediaSequence + mediaPlaylist.segments.size(),
            C.INDEX_UNSET);
      }
      long targetPositionInPlaylistUs = targetPositionInPeriodUs - startOfPlaylistInPeriodUs;
      int segmentIndexInPlaylist =
          Util.binarySearchFloor(
              mediaPlaylist.segments,
              targetPositionInPlaylistUs,
              true,
              !playlistTracker.isLive() || previous == null);
      long mediaSequence = segmentIndexInPlaylist + mediaPlaylist.mediaSequence;
      int partIndex = C.INDEX_UNSET;
      if (segmentIndexInPlaylist >= 0) {
        Segment segment = mediaPlaylist.segments.get(segmentIndexInPlaylist);
        List<HlsMediaPlaylist.Part> parts =
            targetPositionInPlaylistUs < segment.relativeStartTimeUs + segment.durationUs
                ? segment.parts
                : mediaPlaylist.trailingParts;
        for (int i = 0; i < parts.size(); i++) {
          HlsMediaPlaylist.Part part = parts.get(i);
          if (targetPositionInPlaylistUs < part.relativeStartTimeUs + part.durationUs) {
            if (part.isIndependent) {
              partIndex = i;
              mediaSequence += parts == mediaPlaylist.trailingParts ? 1 : 0;
            }
            break;
          }
        }
      }
      return new Pair<>(mediaSequence, partIndex);
    }
    return (previous.isLoadCompleted()
        ? new Pair<>(
            previous.partIndex == C.INDEX_UNSET
                ? previous.getNextChunkIndex()
                : previous.chunkIndex,
            previous.partIndex == C.INDEX_UNSET ? C.INDEX_UNSET : previous.partIndex + 1)
        : new Pair<>(previous.chunkIndex, previous.partIndex));
  }


  private void updateLiveEdgeTimeUs(HlsMediaPlaylist mediaPlaylist) {
    liveEdgeInPeriodTimeUs =
        mediaPlaylist.hasEndTag
            ? C.TIME_UNSET
            : (mediaPlaylist.getEndTimeUs() - playlistTracker.getInitialStartTimeUs());
  }

  @Nullable
  private Chunk maybeCreateEncryptionChunkFor(
      @Nullable Uri keyUri,
      int selectedTrackIndex,
      boolean isInitSegment,
      @Nullable CmcdData.Factory cmcdDataFactory) {
    if (keyUri == null) {
      return null;
    }

    @Nullable byte[] encryptionKey = keyCache.remove(keyUri);
    if (encryptionKey != null) {
      keyCache.put(keyUri, encryptionKey);
      return null;
    }

    DataSpec dataSpec =
        new DataSpec.Builder().setUri(keyUri).setFlags(DataSpec.FLAG_ALLOW_GZIP).build();
    if (cmcdDataFactory != null) {
      if (isInitSegment) {
        cmcdDataFactory.setObjectType(CmcdData.Factory.OBJECT_TYPE_INIT_SEGMENT);
      }
      CmcdData cmcdData = cmcdDataFactory.createCmcdData();
      dataSpec = cmcdData.addToDataSpec(dataSpec);
    }

    return new EncryptionKeyChunk(
        encryptionDataSource,
        dataSpec,
        playlistFormats[selectedTrackIndex],
        trackSelection.getSelectionReason(),
        trackSelection.getSelectionData(),
        scratchSpace);
  }

  @Nullable
  private static Uri getFullEncryptionKeyUri(
      HlsMediaPlaylist playlist, @Nullable HlsMediaPlaylist.SegmentBase segmentBase) {
    if (segmentBase == null || segmentBase.fullSegmentEncryptionKeyUri == null) {
      return null;
    }
    return UriUtil.resolveToUri(playlist.baseUri, segmentBase.fullSegmentEncryptionKeyUri);
  }

  /* package */ static final class SegmentBaseHolder {

    public final HlsMediaPlaylist.SegmentBase segmentBase;
    public final long mediaSequence;
    public final int partIndex;
    public final boolean isPreload;
    @Nullable public final InterstitialMarker interstitialMarker;

    public SegmentBaseHolder(
        HlsMediaPlaylist.SegmentBase segmentBase,
        long mediaSequence,
        int partIndex,
        @Nullable InterstitialMarker interstitialMarker) {
      this.segmentBase = segmentBase;
      this.mediaSequence = mediaSequence;
      this.partIndex = partIndex;
      this.isPreload =
          segmentBase instanceof HlsMediaPlaylist.Part
              && ((HlsMediaPlaylist.Part) segmentBase).isPreload;
      this.interstitialMarker = interstitialMarker;
    }
  }

  private static final class InitializationTrackSelection extends BaseTrackSelection {

    private int selectedIndex;

    public InitializationTrackSelection(TrackGroup group, int[] tracks) {
      super(group, tracks);
      selectedIndex = indexOf(group.getFormat(tracks[0]));
    }

    @Override
    public void updateSelectedTrack(
        long playbackPositionUs,
        long bufferedDurationUs,
        long availableDurationUs,
        List<? extends MediaChunk> queue,
        MediaChunkIterator[] mediaChunkIterators) {
      long nowMs = SystemClock.elapsedRealtime();
      if (!isTrackExcluded(selectedIndex, nowMs)) {
        return;
      }
      for (int i = length - 1; i >= 0; i--) {
        if (!isTrackExcluded(i, nowMs)) {
          selectedIndex = i;
          return;
        }
      }
      throw new IllegalStateException();
    }

    @Override
    public int getSelectedIndex() {
      return selectedIndex;
    }

    @Override
    public @C.SelectionReason int getSelectionReason() {
      return C.SELECTION_REASON_UNKNOWN;
    }

    @Override
    @Nullable
    public Object getSelectionData() {
      return null;
    }
  }

  private static final class EncryptionKeyChunk extends DataChunk {

    private byte @MonotonicNonNull [] result;

    public EncryptionKeyChunk(
        DataSource dataSource,
        DataSpec dataSpec,
        Format trackFormat,
        @C.SelectionReason int trackSelectionReason,
        @Nullable Object trackSelectionData,
        byte[] scratchSpace) {
      super(
          dataSource,
          dataSpec,
          C.DATA_TYPE_DRM,
          trackFormat,
          trackSelectionReason,
          trackSelectionData,
          scratchSpace);
    }

    @Override
    protected void consume(byte[] data, int limit) {
      result = Arrays.copyOf(data, limit);
    }

    @Nullable
    public byte[] getResult() {
      return result;
    }
  }

  @VisibleForTesting
  /* package */ static final class HlsMediaPlaylistSegmentIterator extends BaseMediaChunkIterator {

    private final List<HlsMediaPlaylist.SegmentBase> segmentBases;
    private final long startOfPlaylistInPeriodUs;
    private final String playlistBaseUri;

    public HlsMediaPlaylistSegmentIterator(
        String playlistBaseUri,
        long startOfPlaylistInPeriodUs,
        List<HlsMediaPlaylist.SegmentBase> segmentBases) {
      super(0, segmentBases.size() - 1);
      this.playlistBaseUri = playlistBaseUri;
      this.startOfPlaylistInPeriodUs = startOfPlaylistInPeriodUs;
      this.segmentBases = segmentBases;
    }

    @Override
    public DataSpec getDataSpec() {
      checkInBounds();
      HlsMediaPlaylist.SegmentBase segmentBase = segmentBases.get((int) getCurrentIndex());
      Uri chunkUri = UriUtil.resolveToUri(playlistBaseUri, segmentBase.url);
      return new DataSpec(chunkUri, segmentBase.byteRangeOffset, segmentBase.byteRangeLength);
    }

    @Override
    public long getChunkStartTimeUs() {
      checkInBounds();
      return startOfPlaylistInPeriodUs
          + segmentBases.get((int) getCurrentIndex()).relativeStartTimeUs;
    }

    @Override
    public long getChunkEndTimeUs() {
      checkInBounds();
      HlsMediaPlaylist.SegmentBase segmentBase = segmentBases.get((int) getCurrentIndex());
      long segmentStartTimeInPeriodUs = startOfPlaylistInPeriodUs + segmentBase.relativeStartTimeUs;
      return segmentStartTimeInPeriodUs + segmentBase.durationUs;
    }
  }
}
