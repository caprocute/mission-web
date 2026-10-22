package vn.viettel.okrkpi.platform.keyrotation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.crypto.DekCache;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.DekStatus;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;

/**
 * Job mã hoá lại dữ liệu khi nghi một DEK bị lộ (TL-01 "Xoay vòng khoá", TL-03 chương 9): đánh dấu
 * DEK nghi lộ {@code DECRYPT_ONLY} để không còn dùng cho dữ liệu mới, đọc theo lô nhỏ các dòng có
 * {@code dek_id} đó ở mọi bảng có cột phong bì ({@link EncryptedColumnCatalog}), giải mã và mã hoá
 * lại bằng DEK {@code ACTIVE} hiện hành qua {@link FieldEncryptor} (nên dùng đúng {@code
 * CryptoProvider} của bản triển khai, kể cả nhà cung cấp của Ban Cơ yếu), cập nhật kèm {@code
 * row_version}. Mỗi lô là một giao dịch với {@code FOR UPDATE SKIP LOCKED}; dừng giữa chừng thì
 * chạy lại sẽ tiếp tục từ các dòng còn tham chiếu DEK cũ. Khi không còn dòng nào, DEK cũ sang
 * {@code RETIRED}. Chỉ chạy khi có phê duyệt hai người ({@link Approval}).
 *
 * <p>Giới hạn (wiki 14): ràng buộc UQ ({@code tenant_id}, {@code purpose}, {@code valid_month})
 * không cho có DEK thứ hai trong cùng tháng, nên job từ chối khi DEK nghi lộ chính là DEK {@code
 * ACTIVE} của tháng hiện hành; khoá gốc chỉ mục mù cần tính lại mọi cột {@code _bidx} nên cũng bị
 * từ chối.
 */
public class DekReencryptJob {

  static final String JOB = "dek_ma_hoa_lai";

  /** Kết quả một lần chạy. */
  public record Result(
      UUID dekId, int rows, long remaining, boolean retired, Map<String, Integer> perTable) {}

  private final JdbcTemplate keyring;
  private final JdbcTemplate data;
  private final FieldEncryptor encryptor;
  private final DekService dekService;
  private final RotationReporter reporter;
  private final Clock clock;
  private final Map<String, String> aadRowColumn;
  private final Map<String, String> aadCoordinate;
  private final int batchSize;

  /**
   * @param keyring kết nối {@code svc_keyring} (đổi trạng thái DEK)
   * @param data kết nối có quyền UPDATE các cột {@code _enc}, {@code dek_id}, {@code row_version}
   * @param aadRowColumn cột mã dòng của AAD theo bảng {@code schema.bảng}, mặc định {@code id}
   * @param aadCoordinate toạ độ cột AAD khác tên thật, ví dụ {@code
   *     evidence.upload_session.file_name_enc -> evidence.object_meta.file_name_enc}
   */
  public DekReencryptJob(
      JdbcTemplate keyring,
      JdbcTemplate data,
      FieldEncryptor encryptor,
      DekService dekService,
      RotationReporter reporter,
      Clock clock,
      Map<String, String> aadRowColumn,
      Map<String, String> aadCoordinate,
      int batchSize) {
    this.keyring = Objects.requireNonNull(keyring, "keyring");
    this.data = Objects.requireNonNull(data, "data");
    this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
    this.dekService = Objects.requireNonNull(dekService, "dekService");
    this.reporter = Objects.requireNonNull(reporter, "reporter");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.aadRowColumn = Map.copyOf(aadRowColumn);
    this.aadCoordinate = Map.copyOf(aadCoordinate);
    if (batchSize < 1 || batchSize > 5000) {
      throw new IllegalArgumentException("batchSize phải trong khoảng 1..5000");
    }
    this.batchSize = batchSize;
  }

  /**
   * Mã hoá lại mọi dòng của DEK nghi lộ. {@code stop} được hỏi giữa các lô (ví dụ hết khung giờ
   * thấp điểm); trả {@code remaining > 0} khi dừng sớm.
   */
  public Result run(UUID tenant, UUID dekId, Approval approval, BooleanSupplier stop) {
    Objects.requireNonNull(approval, "approval");
    DekRecord dek = load(tenant, dekId);
    if (dek.purpose() == DekPurpose.BLIND_INDEX) {
      throw new IllegalStateException("Khoá gốc chỉ mục mù không mã hoá lại bằng job này");
    }
    if (dek.status() == DekStatus.DESTROYED) {
      throw new IllegalStateException("DEK đã huỷ, không còn dữ liệu đọc được để mã hoá lại");
    }
    if (dek.status() == DekStatus.ACTIVE) {
      if (!YearMonth.from(dek.validMonth()).isBefore(YearMonth.now(clock))) {
        throw new IllegalStateException(
            "DEK nghi lộ là DEK ACTIVE của tháng hiện hành: ràng buộc UQ không cho sinh DEK thay"
                + " thế trong tháng (xem runbook xoay-vong-khoa-du-lieu.md)");
      }
      setStatus(tenant, dekId, DekStatus.DECRYPT_ONLY);
    }
    Map<String, Integer> perTable = new LinkedHashMap<>();
    int rows = 0;
    boolean stopped = false;
    List<EncryptedColumnCatalog.Table> tables = EncryptedColumnCatalog.load(data);
    outer:
    for (EncryptedColumnCatalog.Table t : tables) {
      while (true) {
        if (stop.getAsBoolean()) {
          stopped = true;
          break outer;
        }
        int n = TenantTx.run(data, tenant, conn -> batch(conn, tenant, dek, t));
        if (n == 0) {
          break;
        }
        rows += n;
        perTable.merge(t.qualified(), n, Integer::sum);
      }
    }
    long remaining = stopped ? remaining(tenant, dekId, tables) : 0;
    boolean retired = false;
    if (!stopped || remaining == 0) {
      remaining = remaining(tenant, dekId, tables);
      if (remaining == 0) {
        setStatus(tenant, dekId, DekStatus.RETIRED);
        retired = true;
      }
    }
    reporter.items(JOB, "dong", rows);
    reporter.remaining(JOB, remaining);
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("so_dong", Integer.toString(rows));
    attrs.put("con_lai", Long.toString(remaining));
    attrs.put("da_nghi_huu", Boolean.toString(retired));
    attrs.put("phieu", approval.reference());
    attrs.put("nguoi_duyet", approval.approvedBy().toString());
    reporter.audit("KEY_ROTATION_DEK_REENCRYPT", tenant, approval.requestedBy(), dekId, attrs);
    return new Result(dekId, rows, remaining, retired, perTable);
  }

  private int batch(Connection conn, UUID tenant, DekRecord old, EncryptedColumnCatalog.Table t)
      throws SQLException {
    String rowCol = aadRowColumn.getOrDefault(t.qualified(), "id");
    List<String> select = new ArrayList<>(t.primaryKey());
    if (!select.contains(rowCol)) {
      select.add(rowCol);
    }
    select.addAll(t.encColumns());
    String table =
        EncryptedColumnCatalog.quote(t.schema()) + "." + EncryptedColumnCatalog.quote(t.name());
    String sql =
        "SELECT "
            + String.join(", ", select.stream().map(EncryptedColumnCatalog::quote).toList())
            + " FROM "
            + table
            + " WHERE tenant_id = ? AND dek_id = ? LIMIT "
            + batchSize
            + " FOR UPDATE SKIP LOCKED";
    StringBuilder upd = new StringBuilder("UPDATE " + table + " SET ");
    for (String c : t.encColumns()) {
      upd.append(EncryptedColumnCatalog.quote(c)).append(" = ?, ");
    }
    upd.append("dek_id = ?");
    if (t.hasRowVersion()) {
      upd.append(", row_version = row_version + 1");
    }
    upd.append(" WHERE tenant_id = ? AND dek_id = ?");
    for (String k : t.primaryKey()) {
      upd.append(" AND ").append(EncryptedColumnCatalog.quote(k)).append(" = ?");
    }
    UUID target = targetDek(tenant, old);
    int n = 0;
    try (PreparedStatement ps = conn.prepareStatement(sql);
        PreparedStatement up = conn.prepareStatement(upd.toString())) {
      ps.setObject(1, tenant);
      ps.setObject(2, old.id());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String rowId = rs.getString(rowCol);
          UUID newDek = null;
          int i = 1;
          for (String c : t.encColumns()) {
            byte[] env = rs.getBytes(c);
            byte[] fresh = null;
            if (env != null) {
              List<String> aad =
                  List.of(
                      aadCoordinate.getOrDefault(t.qualified() + "." + c, t.qualified() + "." + c),
                      rowId);
              byte[] plain = encryptor.decrypt(tenant, aad, env);
              try {
                EncryptedField f = encryptor.encryptField(tenant, old.purpose(), aad, plain);
                if (!f.dekId().equals(target)) {
                  throw new IllegalStateException("DEK đích không hợp lệ khi mã hoá lại");
                }
                newDek = f.dekId();
                fresh = f.ciphertext();
              } finally {
                Arrays.fill(plain, (byte) 0);
              }
            }
            up.setBytes(i++, fresh);
          }
          if (newDek == null) {
            // Dòng trỏ DEK cũ nhưng mọi cột _enc rỗng: chuyển sang DEK hiện hành để DEK cũ nghỉ
            // hưu.
            newDek = target;
          }
          up.setObject(i++, newDek);
          up.setObject(i++, tenant);
          up.setObject(i++, old.id());
          for (String k : t.primaryKey()) {
            up.setObject(i++, rs.getObject(k));
          }
          n += up.executeUpdate();
        }
      }
    }
    return n;
  }

  private UUID targetDek(UUID tenant, DekRecord old) {
    DekCache.CachedDek t = dekService.getOrCreateActiveDek(tenant, old.purpose());
    if (t.dekId().equals(old.id())) {
      throw new IllegalStateException("DEK hiện hành trùng DEK nghi lộ");
    }
    return t.dekId();
  }

  private long remaining(UUID tenant, UUID dekId, List<EncryptedColumnCatalog.Table> tables) {
    long total = 0;
    for (EncryptedColumnCatalog.Table t : tables) {
      String table =
          EncryptedColumnCatalog.quote(t.schema()) + "." + EncryptedColumnCatalog.quote(t.name());
      total +=
          TenantTx.run(
              data,
              tenant,
              conn -> {
                try (PreparedStatement ps =
                    conn.prepareStatement(
                        "SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND dek_id = ?")) {
                  ps.setObject(1, tenant);
                  ps.setObject(2, dekId);
                  try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                  }
                }
              });
    }
    return total;
  }

  private DekRecord load(UUID tenant, UUID dekId) {
    DekRecord r =
        TenantTx.run(
            keyring,
            tenant,
            conn -> {
              try (PreparedStatement ps =
                  conn.prepareStatement(
                      "SELECT "
                          + DekRecord.COLUMNS
                          + " FROM keyring.dek WHERE tenant_id = ? AND id = ?")) {
                ps.setObject(1, tenant);
                ps.setObject(2, dekId);
                try (ResultSet rs = ps.executeQuery()) {
                  return rs.next() ? DekRecord.map(rs) : null;
                }
              }
            });
    if (r == null) {
      throw new IllegalArgumentException("Không có DEK " + dekId + " của tenant " + tenant);
    }
    return r;
  }

  private void setStatus(UUID tenant, UUID dekId, DekStatus status) {
    TenantTx.run(
        keyring,
        tenant,
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE keyring.dek SET status = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, status.name());
            ps.setObject(2, tenant);
            ps.setObject(3, dekId);
            return ps.executeUpdate();
          }
        });
  }
}
