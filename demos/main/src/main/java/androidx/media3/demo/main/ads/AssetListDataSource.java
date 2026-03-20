package androidx.media3.demo.main.ads;

import android.net.Uri;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@UnstableApi
class AssetListDataSource extends BaseDataSource {

  private Uri currentUri;
  private ByteArrayInputStream inputStream;
  private boolean opened = false;
  private static final String BASE_URL = "https://tomvarga.github.io/HLS-test-stream/";
  private static final long DURATION_5 = 5039000;
  private static final long DURATION_10 = 10005000;

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
    ArrayList<HlsInterstitialsAdsLoader.Asset> assetList = new ArrayList<>();

    String podDuration = dataSpec.uri.getQueryParameter("_pod_duration");
    if (podDuration == null) {
      return dataSpec.length;
    }
    Double podDurationS = Double.valueOf(podDuration);

    handlePart(podDurationS, assetList);
    StringBuilder assetListString = new StringBuilder();
    for (HlsInterstitialsAdsLoader.Asset asset : assetList) {
      assetListString.append(asset.uri).append("\n");
    }
    Log.w(
        "tvarga",
        "podDurationS " + podDurationS + " AssetListDataSource " + assetListString);

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

  private static void handlePart(
      Double podDurationS, ArrayList<HlsInterstitialsAdsLoader.Asset> assetList) {
    double remainingDuration = podDurationS * 1_000_000;
    boolean useFiveSecond = true;

    while (remainingDuration > 0) {
      if (useFiveSecond && remainingDuration >= DURATION_5) {
        assetList.add(
            new HlsInterstitialsAdsLoader.Asset(Uri.parse(BASE_URL + "5/audio.m3u8"), DURATION_5));
        remainingDuration -= DURATION_5;
      } else if (!useFiveSecond && remainingDuration >= DURATION_10) {
        assetList.add(
            new HlsInterstitialsAdsLoader.Asset(
                Uri.parse(BASE_URL + "10/audio.m3u8"), DURATION_10));
        remainingDuration -= DURATION_10;
      } else if (remainingDuration >= DURATION_5) {
        // If we can't fit the current choice, try the 5-second asset
        assetList.add(
            new HlsInterstitialsAdsLoader.Asset(Uri.parse(BASE_URL + "5/audio.m3u8"), DURATION_5));
        remainingDuration -= DURATION_5;
      } else {
        // Can't fit any more assets
        break;
      }

      useFiveSecond = !useFiveSecond;
    }
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
