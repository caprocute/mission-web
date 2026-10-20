package vn.viettel.okrkpi.shared.crypto;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * Dịch vụ quản lý vòng đời khoá dữ liệu (DEK) theo mô hình phong bì (ADR-06, ADR-17, TL-01, TL-03):
 *
 * <ul>
 *   <li>Tra cứu trước trong {@link DekCache} (TTL 15 phút, tối đa 10.000 mục).
 *   <li>Khi trượt bộ đệm lúc mã hoá: đọc DEK {@code ACTIVE} của {@code (tenant_id, purpose,
 *       valid_month)} từ bảng {@code keyring.dek}; nếu chưa có thì sinh khoá AES-256 32 byte mới,
 *       bọc qua {@link OpenBaoTransitClient}, chèn vào {@code keyring.dek} với {@code ON CONFLICT
 *       (tenant_id, purpose, valid_month) DO NOTHING} để chống sinh trùng giữa nhiều pod, rồi đọc
 *       lại dòng thắng cuộc.
 *   <li>Khi giải mã: tra theo {@code dek_id} ghi ở phần đầu bản mã; cho phép giải mã cả khoá {@code
 *       ACTIVE}, {@code DECRYPT_ONLY} và {@code RETIRED}; nếu trạng thái là {@code DESTROYED} thì
 *       ném {@link DekDestroyedException} (xoá bằng mật mã).
 * </ul>
 */
public class DekService {

  /** Tháng neo cố định cho khoá gốc chỉ mục mù ({@link DekPurpose#BLIND_INDEX}). */
  public static final LocalDate BLIND_INDEX_ANCHOR_MONTH = LocalDate.of(1970, 1, 1);

  private static final String SQL_CURRENT_TENANT = "SELECT current_setting('app.tenant_id', true)";

  private static final String SQL_SET_TENANT = "SELECT set_config('app.tenant_id', ?, true)";

  private static final String SQL_SELECT_BY_SCOPE =
      "SELECT id, tenant_id, purpose, valid_month, wrapped_dek, kek_ref, alg_version, status"
          + " FROM keyring.dek"
          + " WHERE tenant_id = ? AND purpose = ? AND valid_month = ?";

  private static final String SQL_SELECT_BY_ID =
      "SELECT id, tenant_id, purpose, valid_month, wrapped_dek, kek_ref, alg_version, status"
          + " FROM keyring.dek"
          + " WHERE tenant_id = ? AND id = ?";

  private static final String SQL_INSERT_DEK =
      "INSERT INTO keyring.dek"
          + " (id, tenant_id, purpose, valid_month, wrapped_dek, kek_ref, alg_version, status)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')"
          + " ON CONFLICT (tenant_id, purpose, valid_month) DO NOTHING";

  private final JdbcTemplate jdbc;
  private final OpenBaoTransitClient transitClient;
  private final CryptoProvider cryptoProvider;
  private final DekCache dekCache;
  private volatile Clock clock;

  public DekService(
      JdbcTemplate jdbc,
      OpenBaoTransitClient transitClient,
      CryptoProvider cryptoProvider,
      DekCache dekCache) {
    this(jdbc, transitClient, cryptoProvider, dekCache, Clock.systemUTC());
  }

  public DekService(
      JdbcTemplate jdbc,
      OpenBaoTransitClient transitClient,
      CryptoProvider cryptoProvider,
      DekCache dekCache,
      Clock clock) {
    this.jdbc = jdbc;
    this.transitClient = transitClient;
    this.cryptoProvider = Objects.requireNonNull(cryptoProvider, "cryptoProvider");
    this.dekCache = Objects.requireNonNull(dekCache, "dekCache");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Thay đồng hồ (phục vụ kiểm thử xoay vòng DEK khi sang tháng mới). */
  public void setClock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Clock getClock() {
    return clock;
  }

  public DekCache getDekCache() {
    return dekCache;
  }

  /** Lấy hoặc sinh mới DEK {@code ACTIVE} của tháng hiện hành cho {@code (tenantId, purpose)}. */
  public CachedDek getOrCreateActiveDek(UUID tenantId, DekPurpose purpose) {
    LocalDate currentMonth = LocalDate.now(clock).withDayOfMonth(1);
    return getOrCreateActiveDek(tenantId, purpose, currentMonth);
  }

  /**
   * Lấy hoặc sinh mới DEK {@code ACTIVE} của tháng chỉ định {@code validMonth} (tự động chuẩn hoá
   * về ngày đầu tháng).
   */
  public CachedDek getOrCreateActiveDek(UUID tenantId, DekPurpose purpose, LocalDate validMonth) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(validMonth, "validMonth");
    LocalDate normalizedMonth = validMonth.withDayOfMonth(1);

    Optional<CachedDek> cached = dekCache.getActive(tenantId, purpose, normalizedMonth);
    if (cached.isPresent()) {
      return cached.get();
    }

    requireInfrastructure();

    DekRow existing =
        inTenantConnection(
            tenantId, conn -> selectByScope(conn, tenantId, purpose, normalizedMonth));
    if (existing != null) {
      return unwrapAndCacheForEncryption(existing);
    }

    byte[] candidateKey = cryptoProvider.generateKey();
    try {
      byte[] wrapped = transitClient.wrapDek(tenantId, candidateKey);
      UUID candidateId = Ids.newUuidV7();
      String kekRef = transitClient.kekRef(tenantId);
      short algVersion = cryptoProvider.algorithmVersion();

      DekRow resolved =
          inTenantConnection(
              tenantId,
              conn -> {
                try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_DEK)) {
                  ps.setObject(1, candidateId);
                  ps.setObject(2, tenantId);
                  ps.setString(3, purpose.name());
                  ps.setDate(4, Date.valueOf(normalizedMonth));
                  ps.setBytes(5, wrapped);
                  ps.setString(6, kekRef);
                  ps.setShort(7, algVersion);
                  int inserted = ps.executeUpdate();
                  if (inserted == 1) {
                    return new DekRow(
                        candidateId,
                        tenantId,
                        purpose,
                        normalizedMonth,
                        wrapped,
                        kekRef,
                        algVersion,
                        DekStatus.ACTIVE);
                  }
                }
                return selectByScope(conn, tenantId, purpose, normalizedMonth);
              });

      if (resolved == null) {
        throw new CryptoException(
            "Không đọc được DEK sau khi chèn vào keyring.dek cho tenant " + tenantId);
      }
      if (resolved.id().equals(candidateId)) {
        dekCache.put(
            candidateId, tenantId, purpose, normalizedMonth, DekStatus.ACTIVE, candidateKey);
        return new CachedDek(
            candidateId, tenantId, purpose, normalizedMonth, DekStatus.ACTIVE, candidateKey);
      }
      return unwrapAndCacheForEncryption(resolved);
    } finally {
      DekCache.zeroKey(candidateKey);
    }
  }

  /**
   * Lấy DEK theo {@code dekId} ghi trong header bản mã để giải mã. Cho phép {@code ACTIVE}, {@code
   * DECRYPT_ONLY}, {@code RETIRED}; ném {@link DekDestroyedException} nếu {@code DESTROYED}.
   */
  public CachedDek getDekForDecryption(UUID tenantId, UUID dekId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(dekId, "dekId");

    Optional<CachedDek> cached = dekCache.getById(tenantId, dekId);
    if (cached.isPresent()) {
      return cached.get();
    }

    requireInfrastructure();

    DekRow row = inTenantConnection(tenantId, conn -> selectById(conn, tenantId, dekId));
    if (row == null) {
      throw new CryptoException(
          "Không tìm thấy DEK " + dekId + " trong keyring.dek cho tenant " + tenantId);
    }
    if (!row.status().canDecrypt()) {
      throw new DekDestroyedException(tenantId, dekId);
    }
    byte[] rawKey = transitClient.unwrapDek(tenantId, row.wrappedDek());
    try {
      dekCache.put(row.id(), row.tenantId(), row.purpose(), row.validMonth(), row.status(), rawKey);
      return new CachedDek(
          row.id(), row.tenantId(), row.purpose(), row.validMonth(), row.status(), rawKey);
    } finally {
      DekCache.zeroKey(rawKey);
    }
  }

  /** Lấy hoặc sinh mới khoá gốc {@link DekPurpose#BLIND_INDEX} cho một tenant. */
  public CachedDek getOrCreateBlindIndexKey(UUID tenantId) {
    return getOrCreateActiveDek(tenantId, DekPurpose.BLIND_INDEX, BLIND_INDEX_ANCHOR_MONTH);
  }

  private CachedDek unwrapAndCacheForEncryption(DekRow row) {
    if (row.status() == DekStatus.DESTROYED) {
      throw new DekDestroyedException(row.tenantId(), row.id());
    }
    if (!row.status().canEncrypt()) {
      throw new CryptoException(
          "DEK "
              + row.id()
              + " của tháng "
              + row.validMonth()
              + " đang ở trạng thái "
              + row.status()
              + ", không được phép mã hoá mới");
    }
    byte[] rawKey = transitClient.unwrapDek(row.tenantId(), row.wrappedDek());
    try {
      dekCache.put(row.id(), row.tenantId(), row.purpose(), row.validMonth(), row.status(), rawKey);
      return new CachedDek(
          row.id(), row.tenantId(), row.purpose(), row.validMonth(), row.status(), rawKey);
    } finally {
      DekCache.zeroKey(rawKey);
    }
  }

  private void requireInfrastructure() {
    if (jdbc == null || transitClient == null) {
      throw new CryptoException(
          "DekCache trượt nhưng DekService chưa được cấu hình JdbcTemplate/OpenBaoTransitClient");
    }
  }

  private <T> T inTenantConnection(UUID tenantId, SqlFunction<T> callback) {
    String expectedTenant = tenantId.toString();
    return jdbc.execute(
        (ConnectionCallback<T>)
            conn -> {
              boolean manageTx = conn.getAutoCommit();
              if (manageTx) {
                conn.setAutoCommit(false);
              }
              try {
                ensureTenantRlsSetting(conn, expectedTenant);
                T result = callback.apply(conn);
                if (manageTx) {
                  conn.commit();
                }
                return result;
              } catch (SQLException | RuntimeException e) {
                if (manageTx) {
                  try {
                    conn.rollback();
                  } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                  }
                }
                throw e;
              } finally {
                if (manageTx) {
                  try {
                    conn.setAutoCommit(true);
                  } catch (SQLException ignored) {
                    // Không nuốt ngoại lệ chính; kết nối sẽ được pool xử lý.
                  }
                }
              }
            });
  }

  private static void ensureTenantRlsSetting(Connection conn, String expectedTenant)
      throws SQLException {
    String current = null;
    try (PreparedStatement ps = conn.prepareStatement(SQL_CURRENT_TENANT);
        ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        current = rs.getString(1);
      }
    }
    if (current == null || current.isEmpty()) {
      try (PreparedStatement ps = conn.prepareStatement(SQL_SET_TENANT)) {
        ps.setString(1, expectedTenant);
        try (ResultSet ignored = ps.executeQuery()) {
          // Đã đặt app.tenant_id cục bộ giao dịch.
        }
      }
    } else if (!current.equals(expectedTenant)) {
      throw new CryptoException(
          "Ngữ cảnh giao dịch đang thuộc tenant "
              + current
              + ", không khớp tenant yêu cầu DEK "
              + expectedTenant);
    }
  }

  private static DekRow selectByScope(
      Connection conn, UUID tenantId, DekPurpose purpose, LocalDate validMonth)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BY_SCOPE)) {
      ps.setObject(1, tenantId);
      ps.setString(2, purpose.name());
      ps.setDate(3, Date.valueOf(validMonth));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapRow(rs) : null;
      }
    }
  }

  private static DekRow selectById(Connection conn, UUID tenantId, UUID dekId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BY_ID)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, dekId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapRow(rs) : null;
      }
    }
  }

  private static DekRow mapRow(ResultSet rs) throws SQLException {
    return new DekRow(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        DekPurpose.valueOf(rs.getString(3)),
        rs.getDate(4).toLocalDate(),
        rs.getBytes(5),
        rs.getString(6),
        rs.getShort(7),
        DekStatus.valueOf(rs.getString(8)));
  }

  @FunctionalInterface
  private interface SqlFunction<T> {
    T apply(Connection conn) throws SQLException;
  }

  private record DekRow(
      UUID id,
      UUID tenantId,
      DekPurpose purpose,
      LocalDate validMonth,
      byte[] wrappedDek,
      String kekRef,
      short algVersion,
      DekStatus status) {}
}
