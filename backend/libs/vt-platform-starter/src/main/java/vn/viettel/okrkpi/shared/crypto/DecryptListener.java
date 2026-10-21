package vn.viettel.okrkpi.shared.crypto;

import java.util.List;
import java.util.UUID;

/**
 * CORE-00-05 · ADR-06 · Điểm móc của {@link FieldEncryptor} sau mỗi lần giải mã C2 thành công. Chỉ
 * nhận siêu dữ liệu (tenant, mục đích DEK, phần AAD {@code [schema.bảng.cột, mã dòng]}), không bao
 * giờ nhận bản rõ hay bản mã.
 */
@FunctionalInterface
public interface DecryptListener {

  /**
   * @throws RuntimeException khi không ghi được vết; {@link FieldEncryptor} sẽ không trả bản rõ
   */
  void onDecrypt(UUID tenantId, DekPurpose purpose, List<String> aadParts);
}
