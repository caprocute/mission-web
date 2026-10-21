package vn.viettel.okrkpi.shared.datasource;

import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bộ định tuyến {@link DataSource} giữa cụm chính ({@link DataSourceRoute#RW}) và bản sao chỉ đọc
 * ({@link DataSourceRoute#RO}) cho từng mô-đun của {@code mission-core} (TL-01 L896–902).
 *
 * <p>Quy tắc định tuyến:
 *
 * <ol>
 *   <li>Nếu bản sao đọc không bật hoặc giao dịch hiện tại không phải {@code readOnly = true} →
 *       {@link DataSourceRoute#RW}.
 *   <li>Nếu người dùng hiện tại vừa ghi trong cửa sổ 2 giây ({@link
 *       RecentWriteTracker#hasRecentWrite()}) → {@link DataSourceRoute#RW}
 *       (*read-your-own-writes*).
 *   <li>Nếu độ trễ nhân bản vượt ngưỡng {@code maxReplicaLagMs} (mặc định 1.000 ms) → {@link
 *       DataSourceRoute#RW}.
 *   <li>Các trường hợp còn lại → {@link DataSourceRoute#RO}.
 * </ol>
 */
public final class ReadWriteRoutingDataSource extends AbstractRoutingDataSource
    implements AutoCloseable {

  private final ModuleName moduleName;
  private final DataSource primaryDataSource;
  private final DataSource replicaDataSource;
  private final boolean readReplicaEnabled;
  private final long maxReplicaLagMs;
  private final RecentWriteTracker recentWriteTracker;

  public ReadWriteRoutingDataSource(
      ModuleName moduleName,
      DataSource primaryDataSource,
      DataSource replicaDataSource,
      boolean readReplicaEnabled,
      long maxReplicaLagMs,
      RecentWriteTracker recentWriteTracker) {
    this.moduleName = Objects.requireNonNull(moduleName, "moduleName");
    this.primaryDataSource = Objects.requireNonNull(primaryDataSource, "primaryDataSource");
    this.replicaDataSource = replicaDataSource != null ? replicaDataSource : primaryDataSource;
    this.readReplicaEnabled = readReplicaEnabled && replicaDataSource != null;
    this.maxReplicaLagMs =
        maxReplicaLagMs > 0 ? maxReplicaLagMs : RecentWriteTracker.DEFAULT_MAX_REPLICA_LAG_MS;
    this.recentWriteTracker = Objects.requireNonNull(recentWriteTracker, "recentWriteTracker");

    setTargetDataSources(
        Map.of(
            DataSourceRoute.RW, this.primaryDataSource,
            DataSourceRoute.RO, this.replicaDataSource));
    setDefaultTargetDataSource(this.primaryDataSource);
    afterPropertiesSet();
  }

  @Override
  protected Object determineCurrentLookupKey() {
    return resolveRoute();
  }

  /** Xác định đích định tuyến cho trạng thái giao dịch và ngữ cảnh hiện tại. */
  public DataSourceRoute resolveRoute() {
    if (!readReplicaEnabled) {
      return DataSourceRoute.RW;
    }
    boolean txActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    if (!txActive || !readOnly) {
      return DataSourceRoute.RW;
    }
    if (recentWriteTracker.hasRecentWrite()) {
      return DataSourceRoute.RW;
    }
    if (recentWriteTracker.currentReplicaLagMs() > maxReplicaLagMs) {
      return DataSourceRoute.RW;
    }
    return DataSourceRoute.RO;
  }

  /** Mô-đun nghiệp vụ sở hữu {@link DataSource} này. */
  public ModuleName moduleName() {
    return moduleName;
  }

  /** Pool kết nối chính (RW). */
  public DataSource primaryDataSource() {
    return primaryDataSource;
  }

  /** Pool kết nối bản sao chỉ đọc (RO), hoặc trùng RW khi không bật replica. */
  public DataSource replicaDataSource() {
    return replicaDataSource;
  }

  /** Bản sao đọc có đang được bật riêng hay không. */
  public boolean isReadReplicaEnabled() {
    return readReplicaEnabled;
  }

  @Override
  public void close() {
    if (replicaDataSource != null
        && replicaDataSource != primaryDataSource
        && replicaDataSource instanceof com.zaxxer.hikari.HikariDataSource hikariReplica) {
      hikariReplica.close();
    }
    if (primaryDataSource instanceof com.zaxxer.hikari.HikariDataSource hikariPrimary) {
      hikariPrimary.close();
    }
  }
}
