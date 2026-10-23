package vn.viettel.okrkpi.dossier.application.transfer.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Phần hồ sơ nhận từ cell khác ({@code dossier.transferred_record}). */
public interface TransferredRecords {

  /** Một dòng cần ghi; {@code contentEnc} đã mã hoá phong bì. */
  record NewRecord(
      UUID id,
      UUID packageId,
      String partName,
      String partKind,
      String sourceTenantCode,
      UUID sourcePersonId,
      String mediaType,
      byte[] contentSha256,
      byte[] contentEnc,
      UUID dekId) {}

  /** Ghi một dòng; {@code false} khi phần này của gói đã có. */
  boolean insert(TenantId tenant, NewRecord r, UUID actor);

  /** Băm nội dung của phần đã nhập, nếu có. */
  Optional<byte[]> contentSha256(TenantId tenant, UUID packageId, String partName);
}
