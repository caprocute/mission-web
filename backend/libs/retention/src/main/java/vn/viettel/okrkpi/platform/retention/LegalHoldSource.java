package vn.viettel.okrkpi.platform.retention;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Nguồn cờ giữ pháp lý ({@code legal_hold}) cho job xoá bằng mật mã: tệp minh chứng ở {@code
 * evidence.object_meta} (kèm các phiên bản đối tượng để gia hạn Object Lock) và các bảng hồ sơ khác
 * có cột {@code dek_id}, {@code legal_hold} (khai báo trong cấu hình job). Đọc bằng vai trò chỉ đọc
 * {@code ro_<schema>} trong ngữ cảnh RLS của tenant.
 */
public final class LegalHoldSource {

  /** Một phiên bản đối tượng trên kho đang bị giữ pháp lý. */
  public record HeldObject(String bucket, String key, String versionId) {}

  private final JdbcTemplate evidence;
  private final JdbcTemplate other;
  private final List<String> otherTables;

  /**
   * @param evidence kết nối tới CSDL có schema {@code evidence}; {@code null} khi CSDL đang xử lý
   *     không có tệp minh chứng
   * @param otherTables bảng {@code schema.bảng} khác có {@code dek_id} và {@code legal_hold}
   */
  public LegalHoldSource(JdbcTemplate evidence, JdbcTemplate other, List<String> otherTables) {
    this.evidence = evidence;
    this.other = other;
    this.otherTables = otherTables.stream().map(LegalHoldSource::qualified).toList();
  }

  /** Bảng khác đang giữ pháp lý dữ liệu mã hoá bằng DEK này. */
  boolean heldElsewhere(UUID tenant, UUID dekId) {
    if (other == null) {
      return false;
    }
    for (String t : otherTables) {
      boolean held =
          TenantTx.run(
              other,
              tenant,
              conn -> {
                try (PreparedStatement ps =
                    conn.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM "
                            + t
                            + " WHERE dek_id = ? AND legal_hold)")) {
                  ps.setObject(1, dekId);
                  try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                  }
                }
              });
      if (held) {
        return true;
      }
    }
    return false;
  }

  /** Phiên bản tệp minh chứng có {@code legal_hold} mà tên tệp hoặc nội dung dùng DEK này. */
  List<HeldObject> heldObjects(UUID tenant, UUID dekId) {
    if (evidence == null) {
      return List.of();
    }
    return TenantTx.run(
        evidence,
        tenant,
        conn -> {
          List<HeldObject> out = new ArrayList<>();
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT v.bucket, v.object_key, v.ceph_version_id FROM evidence.object_meta m"
                      + " JOIN evidence.object_version v ON v.tenant_id = m.tenant_id AND"
                      + " v.object_meta_id = m.id WHERE m.tenant_id = ? AND m.dek_id = ? AND"
                      + " m.legal_hold ORDER BY v.object_key, v.version_no")) {
            ps.setObject(1, tenant);
            ps.setObject(2, dekId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(new HeldObject(rs.getString(1), rs.getString(2), rs.getString(3)));
              }
            }
          }
          return out;
        });
  }

  private static String qualified(String t) {
    String[] p = t.split("\\.");
    if (p.length != 2) {
      throw new IllegalArgumentException("Bảng giữ pháp lý phải có dạng schema.bảng: " + t);
    }
    return ParquetCodec.quote(p[0]) + "." + ParquetCodec.quote(p[1]);
  }
}
