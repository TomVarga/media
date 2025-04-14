package androidx.media3.demo.main;

import androidx.annotation.Nullable;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import java.io.IOException;

@UnstableApi public class NoOpAdsLoader implements AdsLoader {

  @Override
  public void setPlayer(@Nullable Player player) {

  }

  @Override
  public void release() {

  }

  @Override
  public void setSupportedContentTypes(@C.ContentType int... contentTypes) {

  }

  @Override
  public void start(AdsMediaSource adsMediaSource, DataSpec adTagDataSpec, Object adsId,
      AdViewProvider adViewProvider, EventListener eventListener) {

  }

  @Override
  public void stop(AdsMediaSource adsMediaSource, EventListener eventListener) {

  }

  @Override
  public void handlePrepareComplete(AdsMediaSource adsMediaSource, int adGroupIndex,
      int adIndexInAdGroup) {

  }

  @Override
  public void handlePrepareError(AdsMediaSource adsMediaSource, int adGroupIndex,
      int adIndexInAdGroup, IOException exception) {

  }
}
