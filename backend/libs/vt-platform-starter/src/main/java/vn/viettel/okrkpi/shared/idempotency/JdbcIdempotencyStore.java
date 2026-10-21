package vn.viettel.okrkpi.shared.idempotency;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link IdempotencyStore} trên JDBC, bảng {@code <schema>.idempotency_key} của NEN-032: PK {@code
 * (tenant_id, actor_id, idem_key, created_day)} phân vùng RANGE ngày, RLS FORCE theo {@code
 * app.tenant_id}.
 *
 * <ul>
 *   <li>Mọi thao tác đòi giao dịch đang mở (khoá ghi cùng giao dịch với tác dụng) và đặt {@code
 *       app.tenant_id} cục bộ giao dịch nếu chưa có; ngữ cảnh đã đặt cho tenant khác thì từ chối.
 *   <li>Hai yêu cầu đồng thời cùng khoá: khoá tư vấn {@code pg_try_advisory_xact_lock} theo
 *       (schema, tenant, người gọi, khoá) không chờ; yêu cầu thua nhận {@link
 *       BeginResult.InProgress} ngay thay vì chờ giao dịch kia. Khoá tư vấn mức giao dịch dùng được
 *       với PgBouncer chế độ transaction.
 *   <li>Vì PK có {@code created_day}, khoá được tìm trên mọi phân vùng còn trong 48 giờ.
 * </ul>
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

  private static final int MAX_KEY = 64;
  private static final int MAX_REF = 128;
  private static final String COLUMNS =
      "idem_key, created_day, request_hash, status, response_code, response_body::text,"
          + " resource_ref, created_at";

  private final JdbcTemplate jdbc;

  public JdbcIdempotencyStore(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<IdempotencyRecord> find(IdempotencyScope scope, String key) {
    prepare(scope, key);
    return findCurrent(scope, key);
  }

  @Override
  public BeginResult begin(IdempotencyScope scope, String key, byte[] requestHash) {
    Objects.requireNonNull(requestHash, "requestHash");
    prepare(scope, key);
    Boolean locked =
        jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
            Boolean.class,
            scope.schema() + '|' + scope.tenant() + '|' + scope.actor() + '|' + key);
    if (!Boolean.TRUE.equals(locked)) {
      return new BeginResult.InProgress();
    }
    Optional<IdempotencyRecord> existing = findCurrent(scope, key);
    if (existing.isPresent()) {
      IdempotencyRecord r = existing.get();
      if (!r.sameRequest(requestHash)) {
        return new BeginResult.Mismatch();
      }
      return r.status() == IdempotencyRecord.Status.XONG
          ? new BeginResult.Replay(r)
          : new BeginResult.InProgress();
    }
    List<Date> day =
        jdbc.query(
            "INSERT INTO "
                + table(scope)
                + " (tenant_id, actor_id, idem_key, request_hash, status)"
                + " VALUES (?, ?, ?, ?, 'DANG_XU_LY') ON CONFLICT DO NOTHING RETURNING created_day",
            (rs, i) -> rs.getDate(1),
            scope.tenant().value(),
            scope.actor().value(),
            key,
            requestHash);
    if (day.isEmpty()) {
      return new BeginResult.InProgress();
    }
    return new BeginResult.Started(scope, key, day.get(0).toLocalDate());
  }

  @Override
  public void complete(
      BeginResult.Started started, int responseCode, String responseBody, String resourceRef) {
    if (resourceRef != null && resourceRef.length() > MAX_REF) {
      throw new IllegalArgumentException("resource_ref dài quá " + MAX_REF + " ký tự");
    }
    if (responseCode < 100 || responseCode > 599) {
      throw new IllegalArgumentException("Mã HTTP không hợp lệ: " + responseCode);
    }
    IdempotencyScope scope = started.scope();
    prepare(scope, started.key());
    int n =
        jdbc.update(
            "UPDATE "
                + table(scope)
                + " SET status = 'XONG', response_code = ?, response_body = ?::jsonb,"
                + " resource_ref = ? WHERE tenant_id = ? AND actor_id = ? AND idem_key = ?"
                + " AND created_day = ? AND status = 'DANG_XU_LY'",
            (short) responseCode,
            responseBody,
            resourceRef,
            scope.tenant().value(),
            scope.actor().value(),
            started.key(),
            Date.valueOf(started.createdDay()));
    if (n != 1) {
      throw new IllegalStateException("Không tìm thấy khoá đang xử lý để hoàn tất");
    }
  }

  @Override
  public void abandon(BeginResult.Started started) {
    IdempotencyScope scope = started.scope();
    prepare(scope, started.key());
    jdbc.update(
        "DELETE FROM "
            + table(scope)
            + " WHERE tenant_id = ? AND actor_id = ? AND idem_key = ? AND created_day = ?"
            + " AND status = 'DANG_XU_LY'",
        scope.tenant().value(),
        scope.actor().value(),
        started.key(),
        Date.valueOf(started.createdDay()));
  }

  private Optional<IdempotencyRecord> findCurrent(IdempotencyScope scope, String key) {
    // created_day >= current_date - 2 để chỉ quét các phân vùng có thể còn hạn.
    List<IdempotencyRecord> rows =
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM "
                + table(scope)
                + " WHERE tenant_id = ? AND actor_id = ? AND idem_key = ?"
                + " AND created_day >= current_date - 2"
                + " AND created_at > now() - make_interval(hours => ?)"
                + " ORDER BY created_at DESC LIMIT 1",
            JdbcIdempotencyStore::map,
            scope.tenant().value(),
            scope.actor().value(),
            key,
            (int) RETENTION.toHours());
    return rows.stream().findFirst();
  }

  private static IdempotencyRecord map(ResultSet rs, int row) throws SQLException {
    int code = rs.getInt(5);
    Integer responseCode = rs.wasNull() ? null : code;
    Timestamp created = rs.getTimestamp(8);
    return new IdempotencyRecord(
        rs.getString(1),
        rs.getDate(2).toLocalDate(),
        rs.getBytes(3),
        IdempotencyRecord.Status.valueOf(rs.getString(4)),
        responseCode,
        rs.getString(6),
        rs.getString(7),
        created == null ? null : created.toInstant());
  }

  /** Kiểm đầu vào, giao dịch, và ngữ cảnh tenant của giao dịch. */
  private void prepare(IdempotencyScope scope, String key) {
    Objects.requireNonNull(scope, "scope");
    validateKey(key);
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "IdempotencyStore phải chạy trong giao dịch của thao tác nghiệp vụ");
    }
    String tenant = scope.tenant().toString();
    String current =
        jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class);
    if (current == null || current.isEmpty()) {
      jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant);
    } else if (!current.equals(tenant)) {
      throw new IllegalStateException("Ngữ cảnh tenant của giao dịch khác tenant của khoá");
    }
  }

  /** Khoá hợp lệ: 1..64 ký tự ASCII in được (cột {@code varchar(64)}). */
  static void validateKey(String key) {
    if (!isValidKey(key)) {
      throw new IllegalArgumentException("Idempotency-Key không hợp lệ");
    }
  }

  static boolean isValidKey(String key) {
    if (key == null || key.isEmpty() || key.length() > MAX_KEY) {
      return false;
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c < 0x21 || c > 0x7e) {
        return false;
      }
    }
    return true;
  }

  private static String table(IdempotencyScope scope) {
    // Tên schema đã kiểm bằng IdempotencyScope.SCHEMA nên ghép chuỗi an toàn.
    return scope.schema() + ".idempotency_key";
  }
}
