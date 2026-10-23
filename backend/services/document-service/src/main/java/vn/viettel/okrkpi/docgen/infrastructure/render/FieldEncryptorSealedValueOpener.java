package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.SealedValueOpener;
import vn.viettel.okrkpi.shared.crypto.CryptoException;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;

/**
 * Giải mã giá trị C2 bằng {@link FieldEncryptor} của thư viện nền (DEK của tenant trong {@code
 * keyring.dek}, bọc bằng OpenBao Transit). Lỗi giải mã không mang giá trị hay phong bì vào thông
 * điệp, để không lọt dữ liệu vào nhật ký.
 */
public class FieldEncryptorSealedValueOpener implements SealedValueOpener {

  private final FieldEncryptor encryptor;

  public FieldEncryptorSealedValueOpener(FieldEncryptor encryptor) {
    this.encryptor = encryptor;
  }

  @Override
  public String open(UUID tenantId, List<String> aad, byte[] envelope) {
    try {
      return encryptor.decryptToString(tenantId, aad, envelope);
    } catch (CryptoException | IllegalArgumentException e) {
      throw new RejectedDocument("Không giải mã được một trường dữ liệu của biểu mẫu.");
    }
  }
}
