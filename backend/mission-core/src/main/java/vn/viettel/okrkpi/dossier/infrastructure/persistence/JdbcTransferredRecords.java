package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.dossier.application.transfer.port.TransferredRecords;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@code dossier.transferred_record} (DV-13-10). */
public class JdbcTransferredRecords implements TransferredRecords {

  private final JdbcClient jdbc;

  public JdbcTransferredRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insert(TenantId tenant, NewRecord r, UUID actor) {
    return jdbc.sql(
                "INSERT INTO dossier.transferred_record (id, tenant_id, created_by, updated_by,"
                    + " package_id, part_name, part_kind, source_tenant_code, source_person_id,"
                    + " media_type, content_sha256, content_enc, dek_id) VALUES (?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
            .params(
                r.id(),
                tenant.value(),
                actor,
                actor,
                r.packageId(),
                r.partName(),
                r.partKind(),
                r.sourceTenantCode(),
                r.sourcePersonId(),
                r.mediaType(),
                r.contentSha256(),
                r.contentEnc(),
                r.dekId())
            .update()
        == 1;
  }

  @Override
  public Optional<byte[]> contentSha256(TenantId tenant, UUID packageId, String partName) {
    return jdbc.sql(
            "SELECT content_sha256 FROM dossier.transferred_record WHERE tenant_id = ?"
                + " AND package_id = ? AND part_name = ?")
        .params(tenant.value(), packageId, partName)
        .query((rs, i) -> rs.getBytes(1))
        .optional();
  }
}
