package vn.viettel.okrkpi.esign.infrastructure.sign;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.esign.domain.sign.ProviderCode;
import vn.viettel.okrkpi.esign.domain.sign.SignItem;
import vn.viettel.okrkpi.esign.domain.sign.SignPurpose;
import vn.viettel.okrkpi.esign.domain.sign.SignRequest;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestRepository;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestStatus;
import vn.viettel.okrkpi.esign.domain.sign.SignatureFormat;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * {@link SignRequestRepository} trên JDBC ({@code esign.sign_request}, {@code esign.sign_item}).
 */
public class JdbcSignRequestRepository implements SignRequestRepository {

  private static final String REQUEST_PENDING = "ESIGN-409-REQUEST-PENDING";

  private final JdbcClient jdbc;

  public JdbcSignRequestRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SignRequest> findById(UUID id) {
    return jdbc.sql("SELECT * FROM esign.sign_request WHERE id = ?")
        .param(id)
        .query((rs, i) -> request(rs))
        .optional();
  }

  @Override
  public Optional<SignRequest> lockForProcessing(UUID id) {
    return jdbc.sql("SELECT * FROM esign.sign_request WHERE id = ? FOR UPDATE SKIP LOCKED")
        .param(id)
        .query((rs, i) -> request(rs))
        .optional();
  }

  @Override
  public boolean existsPending(UUID signerPersonId, String sourceModule, UUID sourceRefId) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM esign.sign_request
             WHERE signer_person_id = ? AND source_module = ? AND source_ref_id = ?
               AND status IN ('TAO', 'CHO_XAC_NHAN'))
            """)
        .param(signerPersonId)
        .param(sourceModule)
        .param(sourceRefId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public List<SignRequest> listBySigner(
      UUID signerPersonId, SignRequestStatus status, SignPurpose purpose, UUID afterId, int limit) {
    return jdbc.sql(
            """
            SELECT * FROM esign.sign_request
             WHERE signer_person_id = :signer
               AND (CAST(:status AS varchar) IS NULL OR status = :status)
               AND (CAST(:purpose AS varchar) IS NULL OR purpose = :purpose)
               AND (CAST(:after AS uuid) IS NULL OR id < :after)
             ORDER BY id DESC
             LIMIT :limit
            """)
        .param("signer", signerPersonId)
        .param("status", status == null ? null : status.name())
        .param("purpose", purpose == null ? null : purpose.name())
        .param("after", afterId)
        .param("limit", limit)
        .query((rs, i) -> request(rs))
        .list();
  }

  @Override
  public void insert(SignRequest r, UUID actor) {
    try {
      jdbc.sql(
              """
              INSERT INTO esign.sign_request
                (id, tenant_id, signer_person_id, purpose, source_module, source_ref_id, provider,
                 is_batch, provider_txn_id, status, expires_at, completed_at, created_at, created_by,
                 updated_at, updated_by, row_version)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
              """)
          .param(r.id())
          .param(r.tenantId())
          .param(r.signerPersonId())
          .param(r.purpose().name())
          .param(r.sourceModule())
          .param(r.sourceRefId())
          .param(r.provider().name())
          .param(r.batch())
          .param(r.providerTxnId())
          .param(r.status().name())
          .param(ts(r.expiresAt()))
          .param(ts(r.completedAt()))
          .param(ts(r.createdAt()))
          .param(actor)
          .param(ts(r.createdAt()))
          .param(actor)
          .update();
    } catch (DuplicateKeyException e) {
      throw new BusinessException(REQUEST_PENDING, "Hồ sơ này đang có yêu cầu ký chờ xác nhận.");
    }
    for (SignItem item : r.items()) {
      jdbc.sql(
              """
              INSERT INTO esign.sign_item
                (id, tenant_id, sign_request_id, document_kind, document_ref, document_sha256,
                 signature_format)
              VALUES (?, ?, ?, ?, ?, ?, ?)
              """)
          .param(item.id())
          .param(r.tenantId())
          .param(r.id())
          .param(item.documentKind())
          .param(item.documentRef())
          .param(item.documentSha256())
          .param(item.format().name())
          .update();
    }
  }

  @Override
  public void update(SignRequest r, UUID actor) {
    int rows =
        jdbc.sql(
                """
                UPDATE esign.sign_request
                   SET provider_txn_id = ?, status = ?, expires_at = ?, completed_at = ?,
                       updated_at = now(), updated_by = ?, row_version = row_version + 1
                 WHERE id = ? AND row_version = ?
                """)
            .param(r.providerTxnId())
            .param(r.status().name())
            .param(ts(r.expiresAt()))
            .param(ts(r.completedAt()))
            .param(actor)
            .param(r.id())
            .param(r.rowVersion())
            .update();
    if (rows != 1) {
      throw new ConcurrentModification(r.id());
    }
    for (SignItem item : r.items()) {
      if (item.signed()) {
        jdbc.sql(
                """
                UPDATE esign.sign_item
                   SET signature_value = ?, signed_object_key = ?, certificate_ref_id = ?,
                       signed_at = ?
                 WHERE id = ? AND signature_value IS NULL
                """)
            .param(item.signatureValue())
            .param(item.signedObjectKey())
            .param(item.certificateRefId())
            .param(ts(item.signedAt()))
            .param(item.id())
            .update();
      }
    }
  }

  private SignRequest request(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    List<SignItem> items =
        jdbc.sql("SELECT * FROM esign.sign_item WHERE sign_request_id = ? ORDER BY id")
            .param(id)
            .query((r, i) -> item(r))
            .list();
    UUID certificate =
        items.stream()
            .map(SignItem::certificateRefId)
            .filter(c -> c != null)
            .findFirst()
            .orElse(null);
    return new SignRequest(
        id,
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("signer_person_id", UUID.class),
        SignPurpose.valueOf(rs.getString("purpose")),
        rs.getString("source_module"),
        rs.getObject("source_ref_id", UUID.class),
        ProviderCode.valueOf(rs.getString("provider")),
        items,
        rs.getString("provider_txn_id"),
        SignRequestStatus.valueOf(rs.getString("status")),
        instant(rs, "expires_at"),
        instant(rs, "completed_at"),
        certificate,
        instant(rs, "created_at"),
        rs.getLong("row_version"));
  }

  private static SignItem item(ResultSet rs) throws SQLException {
    return new SignItem(
        rs.getObject("id", UUID.class),
        rs.getString("document_kind"),
        rs.getString("document_ref"),
        rs.getBytes("document_sha256"),
        SignatureFormat.valueOf(rs.getString("signature_format")),
        rs.getBytes("signature_value"),
        rs.getString("signed_object_key"),
        rs.getObject("certificate_ref_id", UUID.class),
        instant(rs, "signed_at"));
  }

  static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
