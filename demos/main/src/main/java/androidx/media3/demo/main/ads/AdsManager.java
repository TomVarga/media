package androidx.media3.demo.main.ads;

import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;
import androidx.media3.exoplayer.hls.HlsManifest;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import com.google.common.collect.ImmutableList;

@UnstableApi
public class AdsManager {

  private final HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader;
  private boolean firstUpdate = true;

  public AdsManager(HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader) {
    this.hlsInterstitialsAdsLoader = hlsInterstitialsAdsLoader;
  }

  private ExoPlayer player;

  public void setPlayer(ExoPlayer player) {
    this.player = player;
    player.addListener(
        new Player.Listener() {

          @Override
          public void onPositionDiscontinuity(
              Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
            if (!isAd(oldPosition.adGroupIndex)
                && !isAd(newPosition.adGroupIndex)
                && reason == DISCONTINUITY_REASON_SEEK) {
              Timeline timeline = player.getCurrentTimeline();
              Timeline.Period period = new Timeline.Period();
              AdPlaybackState adPlaybackState =
                  timeline.getPeriod(player.getCurrentPeriodIndex(), period, true).adPlaybackState;
              int adGroupCount = adPlaybackState.adGroupCount;
              if (adGroupCount > 0) {
                //            firstUpdate = false;
                Log.d("AdsManager", "onPositionDiscontinuity adGroupCount " + adGroupCount);
                StringBuilder adAssetUrls = new StringBuilder();
                for (int i = 0; i < adGroupCount; i++) {
                  AdPlaybackState.AdGroup adGroup = adPlaybackState.getAdGroup(i);
                  for (int j = 0; j < adGroup.mediaItems.length; j++) {
                    if (adGroup.mediaItems[j] != null) {
                      adAssetUrls
                          .append(" [Group ")
                          .append(i)
                          .append(" Ad ")
                          .append(j)
                          .append(": ")
                          .append(adGroup.mediaItems[j])
                          .append("]");
                    }
                  }
                }
                Log.d(
                    "AdsManager",
                    "onPositionDiscontinuity adGroupCount "
                        + adGroupCount
                        + " adAssetUrls"
                        + adAssetUrls);
                for (int i = 0; i < adGroupCount; i++) {
                  if (!adPlaybackState.isLivePostrollPlaceholder(i)) {
                    hlsInterstitialsAdsLoader.setWithAvailableAdGroup(i);
                  }
                }
              }
            }
          }

          private boolean isAd(int index) {
            return index != -1;
          }

          @Override
          public void onTimelineChanged(Timeline timeline, int reason) {
            if (!timeline.isEmpty() && firstUpdate) {
              Log.d("AdsManager", "setWithSkippedAdGroup for firstUpdate");
              skip(timeline, player);
            }
          }
        });
  }

  public void skipAd() {
    Log.d("AdsManager", "setWithSkippedAdGroup for skip");
    Timeline timeline = player.getCurrentTimeline();
    skip(timeline, player);
  }

  public void seekToAd() {
    Log.d("AdsManager", "seekToAdd");
    if (player.isPlayingAd()) return;

    Timeline timeline = player.getCurrentTimeline();
    Timeline.Window window = timeline.getWindow(0, new Timeline.Window());
    if (window.manifest instanceof HlsManifest) {
      HlsMediaPlaylist mediaPlaylist = ((HlsManifest) window.manifest).mediaPlaylist;
      long seekPosition = getSeekPositionMs(mediaPlaylist);

      player.seekTo(seekPosition);
    }
  }

  private long getSeekPositionMs(HlsMediaPlaylist playlist) {
    long playlistStartTimeUs = playlist.startTimeUs;
    ImmutableList<HlsMediaPlaylist.Interstitial> interstitials = playlist.interstitials;
    long positionUs = Util.msToUs(player.getContentPosition());

    // Find the closest interstitial before the current position
    HlsMediaPlaylist.Interstitial closestInterstitial = getClosestInterstitial(interstitials,
        playlistStartTimeUs, positionUs);

    // Calculate seek position in milliseconds
    long seekPositionMs = 0;
    if (closestInterstitial != null) {
      long interstitialPositionUs = closestInterstitial.startDateUnixUs - playlistStartTimeUs;
      seekPositionMs = Util.usToMs(interstitialPositionUs);
    }

    return seekPositionMs - 5_000;
  }

  private static HlsMediaPlaylist.Interstitial getClosestInterstitial(
      ImmutableList<HlsMediaPlaylist.Interstitial> interstitials, long playlistStartTimeUs,
      long positionUs) {
    HlsMediaPlaylist.Interstitial closestInterstitial = null;
    long minDistance = Long.MAX_VALUE;

    for (HlsMediaPlaylist.Interstitial interstitial : interstitials) {
      // Calculate the actual position of this interstitial in the playlist
      long interstitialPositionUs = interstitial.startDateUnixUs - playlistStartTimeUs;

      // Only consider interstitials that are before the current position
      if (interstitialPositionUs < positionUs - 5_000_000) {
        long distance = positionUs - interstitialPositionUs;

        // Update if this is closer than previous closest
        if (distance < minDistance) {
          minDistance = distance;
          closestInterstitial = interstitial;
        }
      }
    }
    return closestInterstitial;
  }

  private void skip(Timeline timeline, ExoPlayer player) {
    Timeline.Period period = new Timeline.Period();
    AdPlaybackState adPlaybackState =
        timeline.getPeriod(player.getCurrentPeriodIndex(), period, true).adPlaybackState;
    int adGroupCount = adPlaybackState.adGroupCount;
    if (adGroupCount > 0) {
      firstUpdate = false;
      Log.d("AdsManager", "setWithSkippedAdGroup adGroupCount " + adGroupCount);
      for (int i = 0; i < adGroupCount; i++) {
        if (!adPlaybackState.isLivePostrollPlaceholder(i)) {
          hlsInterstitialsAdsLoader.setWithSkippedAdGroup(i);
        }
      }
    }
  }
}
