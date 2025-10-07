package androidx.media3.demo.main.ads;

import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@UnstableApi class AssetListDataSource extends BaseDataSource {

  private Uri currentUri;
  private ByteArrayInputStream inputStream;
  private boolean opened = false;

  /**
   * Creates base data source.
   *
   * @param isNetwork Whether the data source loads data through a network.
   */
  protected AssetListDataSource(boolean isNetwork) {
    super(isNetwork);
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    currentUri = dataSpec.uri;
    opened = true;

    // Assuming loadAssetList returns a List<Asset>
    List<HlsInterstitialsAdsLoader.Asset> assetList = new ArrayList<>();
    assetList.add(
        new HlsInterstitialsAdsLoader.Asset(
          Uri.parse("https://tomvarga.github.io/HLS-test-stream/5/audio.m3u8"),
          5039000
    ));
    assetList.add(
        new HlsInterstitialsAdsLoader.Asset(
            Uri.parse("https://tomvarga.github.io/HLS-test-stream/10/audio.m3u8"),
            10005000
        ));
    assetList.add(
        new HlsInterstitialsAdsLoader.Asset(
            Uri.parse("https://tomvarga.github.io/HLS-test-stream/10/audio.m3u8"),
            10005000
        ));

    JSONArray innerJsonArray = new JSONArray();
    try {
      for (HlsInterstitialsAdsLoader.Asset asset : assetList) {
        JSONObject assetJsonObject = new JSONObject();
        assetJsonObject.put("DURATION", asset.durationUs);
        assetJsonObject.put("URI", asset.uri.toString());
        innerJsonArray.put(assetJsonObject);
      }

      JSONObject topLevelJsonObject = new JSONObject();
      topLevelJsonObject.put("ASSETS", innerJsonArray);

      String jsonString = topLevelJsonObject.toString(2); // Using 2 for pretty printing in logs

      byte[] bytes = jsonString.getBytes(StandardCharsets.UTF_8);
      inputStream = new ByteArrayInputStream(bytes);

    } catch (JSONException e) {
      throw new IOException("Error creating JSON for asset list", e);
    }

    return dataSpec.length;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (!opened || inputStream == null) {
      throw new IllegalStateException("DataSource not opened. Call open() first.");
    }
    return inputStream.read(buffer, offset, length);
  }

  @Override
  public Uri getUri() {
    return currentUri;
  }

  @Override
  public void close() throws IOException {
    if (inputStream != null) {
      inputStream.close();
    }
    inputStream = null;
    currentUri = null;
    opened = false;
  }
}
