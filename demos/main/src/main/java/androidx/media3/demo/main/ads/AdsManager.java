package androidx.media3.demo.main.ads;


import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;

@UnstableApi
public class AdsManager {

  private final HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader;
  private boolean firstUpdate = true;

  public AdsManager(HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader) {
    this.hlsInterstitialsAdsLoader = hlsInterstitialsAdsLoader;
  }

  public void setPlayer(ExoPlayer player) {
    player.addListener(new Player.Listener() {

      @Override
      public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
          Player.PositionInfo newPosition, int reason) {
        if (!isAd(oldPosition.adGroupIndex) && !isAd(newPosition.adGroupIndex)
            && reason == DISCONTINUITY_REASON_SEEK) {
          Timeline timeline = player.getCurrentTimeline();
          Timeline.Period period = new Timeline.Period();
          AdPlaybackState adPlaybackState = timeline.getPeriod(
              player.getCurrentPeriodIndex(),
              period,
              true
          ).adPlaybackState;
          int adGroupCount = adPlaybackState.adGroupCount;
          if (adGroupCount > 0) {
            firstUpdate = false;
            Log.d("AdsManager", "onPositionDiscontinuity adGroupCount " + adGroupCount);
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
          Timeline.Period period = new Timeline.Period();
          AdPlaybackState adPlaybackState = timeline.getPeriod(
              player.getCurrentPeriodIndex(),
              period,
              true
          ).adPlaybackState;
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
    });
  }
}
