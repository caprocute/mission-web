package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Băm SHA-256 tính lại từ tệp đang lưu trên kho đối tượng, để đối chiếu với băm đã ghi lúc sinh
 * trước khi ký gói (CORE-06-07). Rỗng khi kho chưa cấu hình hoặc không đọc được; khi đó danh sách
 * kiểm tra dựa vào băm đã ghi và manifest đã chốt.
 */
public interface FileHashes {

  Optional<byte[]> sha256(TenantId tenant, String objectKey);

  /** Không có kho đối tượng. */
  FileHashes NONE = (tenant, key) -> Optional.empty();
}
