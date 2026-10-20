package vn.viettel.okrkpi.org.infrastructure.crypto;

import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;

/**
 * Hiện thực mặc định khi chưa có thư viện mã hoá trường NEN-015 (OpenBao Transit, {@code keyring.dek}): từ chối mọi
 * thao tác để không bao giờ ghi cột C2 dạng rõ hay bằng khoá tự đặt trong mã.
 */
@Component
public class UnavailableFieldEncryptor implements FieldEncryptor {

  private static final String REASON = "Chưa cấu hình mã hoá trường C2 (chờ NEN-015)";

  @Override
  public Encrypted encrypt(UUID tenantId, String column, UUID rowId, byte[] plaintext) {
    throw new UnsupportedOperationException(REASON);
  }

  @Override
  public byte[] decrypt(UUID tenantId, String column, UUID rowId, UUID dekId, byte[] ciphertext) {
    throw new UnsupportedOperationException(REASON);
  }

  @Override
  public byte[] blindIndex(UUID tenantId, String column, String normalizedValue) {
    throw new UnsupportedOperationException(REASON);
  }
}
