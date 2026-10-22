package vn.viettel.okrkpi.product.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.product.application.evidence.port.ReminderRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho của tác vụ nhắc quá hạn nghiệm thu (CORE-03-08). Hàng chờ đọc {@code
 * product.evidence_version} nối {@code product.evidence.reviewer_id}. Sổ chống nhắc trùng dùng lại
 * bảng {@code product.idempotency_key} (phân vùng theo ngày, giữ hai ngày): mỗi lần nhắc là một
 * khoá {@code <loại>:<ngày>} của người nhận, nên không cần bảng hay cột mới (wiki 14).
 */
public class JdbcReminderRepository implements ReminderRepository {

  private final JdbcClient jdbc;

  public JdbcReminderRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<PendingReview> pendingReviews(TenantId tenant) {
    return jdbc.sql(
            """
            SELECT v.evidence_id, v.version_no, v.period_id, e.reviewer_id, v.submitted_at
              FROM product.evidence_version v
              JOIN product.evidence e
                ON e.period_bucket = v.period_bucket AND e.tenant_id = v.tenant_id
               AND e.id = v.evidence_id
             WHERE v.tenant_id = ? AND v.status IN ('DA_NOP', 'DANG_NGHIEM_THU')
               AND v.submitted_at IS NOT NULL AND e.reviewer_id IS NOT NULL
             ORDER BY e.reviewer_id, v.submitted_at
            """)
        .param(tenant.value())
        .query(
            (rs, i) -> {
              Timestamp at = rs.getTimestamp("submitted_at");
              return new PendingReview(
                  rs.getObject("evidence_id", UUID.class),
                  rs.getInt("version_no"),
                  rs.getObject("period_id", UUID.class),
                  rs.getObject("reviewer_id", UUID.class),
                  at == null ? null : at.toInstant());
            })
        .list();
  }

  @Override
  public boolean claim(TenantId tenant, UUID recipient, String kind, LocalDate day) {
    String key = kind + ":" + day;
    return jdbc.sql(
                """
                INSERT INTO product.idempotency_key
                  (tenant_id, actor_id, idem_key, created_day, request_hash, status, resource_ref)
                VALUES (?, ?, ?, ?, ?, 'XONG', ?)
                ON CONFLICT (tenant_id, actor_id, idem_key, created_day) DO NOTHING
                """)
            .param(tenant.value())
            .param(recipient)
            .param(key)
            .param(Date.valueOf(day))
            .param(sha256(key))
            .param("nhac-qua-han-nghiem-thu")
            .update()
        == 1;
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
