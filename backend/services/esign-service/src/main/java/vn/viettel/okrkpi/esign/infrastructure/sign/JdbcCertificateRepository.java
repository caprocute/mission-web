package vn.viettel.okrkpi.esign.infrastructure.sign;

import static vn.viettel.okrkpi.esign.infrastructure.sign.JdbcSignRequestRepository.instant;
import static vn.viettel.okrkpi.esign.infrastructure.sign.JdbcSignRequestRepository.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRef;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRepository;

/** {@link CertificateRepository} trên JDBC ({@code esign.certificate_ref}). */
public class JdbcCertificateRepository implements CertificateRepository {

  private final JdbcClient jdbc;

  public JdbcCertificateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CertificateRef> findById(UUID id) {
    return jdbc.sql("SELECT * FROM esign.certificate_ref WHERE id = ?")
        .param(id)
        .query((rs, i) -> certificate(rs))
        .optional();
  }

  @Override
  public List<CertificateRef> findByPerson(UUID personId) {
    return jdbc.sql(
            "SELECT * FROM esign.certificate_ref WHERE person_id = ? ORDER BY not_after DESC")
        .param(personId)
        .query((rs, i) -> certificate(rs))
        .list();
  }

  @Override
  public CertificateRef upsert(CertificateRef c) {
    // Chứng thư đã có (cùng dấu vân tay): chỉ cập nhật kết quả OCSP gần nhất.
    return jdbc.sql(
            """
            INSERT INTO esign.certificate_ref
              (id, tenant_id, person_id, cert_sha256, subject_dn, issuer_dn, serial_no, not_before,
               not_after, cert_der, last_ocsp_status, last_ocsp_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, cert_sha256) DO UPDATE
               SET last_ocsp_status = coalesce(EXCLUDED.last_ocsp_status,
                                               esign.certificate_ref.last_ocsp_status),
                   last_ocsp_at = coalesce(EXCLUDED.last_ocsp_at, esign.certificate_ref.last_ocsp_at)
            RETURNING *
            """)
        .param(c.id())
        .param(c.tenantId())
        .param(c.personId())
        .param(c.certSha256())
        .param(c.subjectDn())
        .param(c.issuerDn())
        .param(c.serialNo())
        .param(ts(c.notBefore()))
        .param(ts(c.notAfter()))
        .param(c.certDer())
        .param(c.lastOcspStatus())
        .param(ts(c.lastOcspAt()))
        .query((rs, i) -> certificate(rs))
        .single();
  }

  private static CertificateRef certificate(ResultSet rs) throws SQLException {
    return new CertificateRef(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("person_id", UUID.class),
        rs.getBytes("cert_sha256"),
        rs.getString("subject_dn"),
        rs.getString("issuer_dn"),
        rs.getString("serial_no"),
        instant(rs, "not_before"),
        instant(rs, "not_after"),
        rs.getBytes("cert_der"),
        rs.getString("last_ocsp_status"),
        instant(rs, "last_ocsp_at"));
  }
}
