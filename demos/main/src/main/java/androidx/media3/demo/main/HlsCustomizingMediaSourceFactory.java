package androidx.media3.demo.main;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;

@UnstableApi
public class HlsCustomizingMediaSourceFactory extends DefaultMediaSourceFactory {

  public HlsCustomizingMediaSourceFactory(Context context) {
    super(context);
  }

  @Override
  public MediaSource createMediaSource(MediaItem mediaItem) {
    @C.ContentType
    int type =
        Util.inferContentTypeForUriAndMimeType(
            mediaItem.localConfiguration.uri, mediaItem.localConfiguration.mimeType);

    if (type == C.CONTENT_TYPE_HLS) {
      return new AdsMediaSource(
          new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem),
          new DataSpec(Uri.parse(
              "https://raw.githubusercontent.com/TomVarga/HLS-test-stream/refs/heads/main/10_m/mv.m3u8")),
          mediaItem.localConfiguration.uri,
          this,
          new NoOpAdsLoader(),
          null,
          false);
    } else {
      return super.createMediaSource(mediaItem);
    }
  }
}
