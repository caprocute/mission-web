package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.dossier.application.transfer.port.DossierHistory;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Hồ sơ cá nhân đã ký và kết luận kiểm điểm của một cán bộ cho gói chuyển liên cell (DV-13-10). */
public class JdbcDossierHistory implements DossierHistory {

  private final JdbcClient jdbc;

  public JdbcDossierHistory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<SignedPackage> signedPackages(TenantId tenant, UUID personId) {
    return jdbc.sql(
            "SELECT period_id, code, package_kind, version_no, status, manifest_hash,"
                + " package_sha256, submitted_at FROM dossier.dossier_package"
                + " WHERE tenant_id = ? AND person_id = ?"
                + " AND status IN ('DA_KY', 'DA_NOP', 'DA_LUU_TRU')"
                + " ORDER BY period_bucket, package_kind, version_no")
        .params(tenant.value(), personId)
        .query(
            (rs, i) -> {
              Timestamp t = rs.getTimestamp("submitted_at");
              return new SignedPackage(
                  rs.getObject("period_id", UUID.class),
                  rs.getString("code"),
                  rs.getString("package_kind"),
                  rs.getInt("version_no"),
                  rs.getString("status"),
                  rs.getBytes("manifest_hash"),
                  rs.getBytes("package_sha256"),
                  t == null ? null : t.toInstant());
            })
        .list();
  }

  @Override
  public List<Conclusion> conclusions(TenantId tenant, UUID personId) {
    return jdbc.sql(
            "SELECT id, period_id, kind, phase, comment_enc, remediation_due, created_at"
                + " FROM dossier.review_comment WHERE tenant_id = ? AND subject_person_id = ?"
                + " AND comment_kind = 'KET_LUAN' ORDER BY period_bucket, created_at, id")
        .params(tenant.value(), personId)
        .query(
            (rs, i) -> {
              Date due = rs.getDate("remediation_due");
              return new Conclusion(
                  rs.getObject("id", UUID.class),
                  rs.getObject("period_id", UUID.class),
                  rs.getString("kind"),
                  rs.getString("phase"),
                  rs.getBytes("comment_enc"),
                  due == null ? null : due.toLocalDate(),
                  rs.getTimestamp("created_at").toInstant());
            })
        .list();
  }
}
