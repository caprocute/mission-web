package vn.viettel.okrkpi.evidence.application;

import java.util.UUID;

/** Mã hoá phong bì tên tệp gốc (C2, {@code file_name_enc}); hiện thực bằng FieldEncryptor. */
public interface FileNameCipher {

  /** Tên tệp đã mã hoá cùng khoá dữ liệu đã dùng. */
  record Sealed(byte[] ciphertext, UUID dekId) {}

  Sealed seal(UUID tenantId, UUID objectId, String fileName);
}
