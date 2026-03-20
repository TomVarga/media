package androidx.media3.demo.main.ads;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;

@UnstableApi
public class AssetListLoadingDataSourceFactory implements DataSource.Factory {

  @Override
  public DataSource createDataSource() {
    return new AssetListDataSource(true);
  }
}
