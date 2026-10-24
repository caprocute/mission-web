package vn.viettel.okrkpi.shared.routing;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@link DataSource} định tuyến động kết nối PostgreSQL/PgBouncer theo ngữ cảnh khách thuê hiện
 * hành ({@link TenantContext}) và danh bạ {@link TenantDirectory} (ADR-07, TL-03 §2.1).
 *
 * <ul>
 *   <li>Khách thuê {@link TenancyModel#POOL}: trỏ tới pool dùng chung {@code td_pool}.
 *   <li>Khách thuê {@link TenancyModel#BRIDGE} hoặc {@link TenancyModel#SILO}: trỏ tới pool riêng
 *       {@code td_<code_lower>} được khởi tạo lười (lazy) qua nhà máy {@code dataSourceFactory}.
 *   <li>Từ chối cấp kết nối nếu khách thuê thuộc cell khác hoặc đang ở trạng thái {@code
 *       DANG_CHUYEN_CELL} / {@code DA_CHAM_DUT}. Khách thuê {@code TAM_NGUNG} vẫn được cấp kết nối
 *       để đọc; lệnh ghi của người dùng bị {@link TenantCellRoutingFilter} chặn trước (TL-04 mục
 *       11.4).
 * </ul>
 */
public final class TenantRoutingDataSource extends AbstractDataSource implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(TenantRoutingDataSource.class);
  public static final String POOL_DATABASE_NAME = "td_pool";

  private final TenantDirectory directory;
  private final String localCellCode;
  private final Function<String, DataSource> dataSourceFactory;
  private final ConcurrentHashMap<String, DataSource> poolsByDatabase = new ConcurrentHashMap<>();

  /**
   * Khởi tạo bộ định tuyến CSDL.
   *
   * @param directory danh bạ định tuyến khách thuê
   * @param localCellCode mã cell nơi dịch vụ hiện tại đang chạy (ví dụ {@code "cell-hcm-01"})
   * @param poolDataSource {@link DataSource} mặc định cho {@code td_pool} (tùy chọn; nếu {@code
   *     null} sẽ tạo qua {@code dataSourceFactory})
   * @param dataSourceFactory hàm tạo {@link DataSource} theo tên CSDL ({@code td_pool}, {@code
   *     td_hcm_a}...)
   */
  public TenantRoutingDataSource(
      TenantDirectory directory,
      String localCellCode,
      DataSource poolDataSource,
      Function<String, DataSource> dataSourceFactory) {
    this.directory = Objects.requireNonNull(directory, "directory must not be null");
    this.localCellCode = Objects.requireNonNull(localCellCode, "localCellCode must not be null");
    this.dataSourceFactory =
        Objects.requireNonNull(dataSourceFactory, "dataSourceFactory must not be null");
    if (poolDataSource != null) {
      this.poolsByDatabase.put(POOL_DATABASE_NAME, poolDataSource);
    }
  }

  /** Đăng ký trước một {@link DataSource} cho tên CSDL cụ thể. */
  public void registerDataSource(String databaseName, DataSource dataSource) {
    Objects.requireNonNull(databaseName, "databaseName must not be null");
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    poolsByDatabase.put(databaseName, dataSource);
  }

  @Override
  public Connection getConnection() throws SQLException {
    return resolveTargetDataSource().getConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return resolveTargetDataSource().getConnection(username, password);
  }

  /**
   * Xác định tên CSDL đích ({@code td_pool} hoặc {@code td_<code_lower>}) cho khách thuê hiện hành
   * trong {@link TenantContext}, đồng thời kiểm tra tính hợp lệ của cell và trạng thái khách thuê.
   */
  public String resolveTargetDatabaseName() {
    TenantId tenantId =
        TenantContext.current()
            .orElseThrow(
                () ->
                    new TenantRoutingException(
                        TenantRoutingException.Reason.MISSING_TENANT_CONTEXT,
                        null,
                        "Missing TenantContext when acquiring database connection"));

    Optional<TenantCellRoute> routeOpt = directory.findByTenantId(tenantId.value());
    if (routeOpt.isEmpty()) {
      throw new TenantRoutingException(
          TenantRoutingException.Reason.TENANT_NOT_FOUND,
          tenantId.toString(),
          "Tenant route not found in TenantDirectory for tenant " + tenantId);
    }

    TenantCellRoute route = routeOpt.get();
    if (route.isUnavailableStatus()) {
      throw new TenantRoutingException(
          TenantRoutingException.Reason.TENANT_MIGRATING_OR_UNAVAILABLE,
          route.tenantCode(),
          "Tenant '"
              + route.tenantCode()
              + "' is currently unavailable or migrating cell (status="
              + route.status()
              + ")");
    }

    if (!localCellCode.equalsIgnoreCase(route.cellCode())) {
      throw new TenantRoutingException(
          TenantRoutingException.Reason.WRONG_CELL,
          route.tenantCode(),
          route.cellCode(),
          localCellCode,
          "Tenant '"
              + route.tenantCode()
              + "' belongs to cell '"
              + route.cellCode()
              + "', not local cell '"
              + localCellCode
              + "'");
    }

    return route.databaseName();
  }

  /** Phân giải {@link DataSource} đích cho ngữ cảnh khách thuê hiện tại. */
  public DataSource resolveTargetDataSource() {
    String dbName = resolveTargetDatabaseName();
    return poolsByDatabase.computeIfAbsent(dbName, dataSourceFactory);
  }

  /** Bản chụp các pool CSDL đang được quản lý (phục vụ kiểm thử và giám sát). */
  public Map<String, DataSource> managedDataSources() {
    return Map.copyOf(poolsByDatabase);
  }

  @Override
  public void close() {
    for (Map.Entry<String, DataSource> entry : poolsByDatabase.entrySet()) {
      DataSource ds = entry.getValue();
      if (ds instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (Exception ex) {
          log.warn("Error closing DataSource for database {}", entry.getKey(), ex);
        }
      }
    }
    poolsByDatabase.clear();
  }
}
