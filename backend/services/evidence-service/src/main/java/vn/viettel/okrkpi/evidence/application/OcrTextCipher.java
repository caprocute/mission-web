package vn.viettel.okrkpi.evidence.application;

import java.util.UUID;

/** Mã hoá phong bì văn bản OCR trước khi lưu {@code ocr_text.text_enc}. */
public interface OcrTextCipher {

  record Sealed(byte[] ciphertext, UUID dekId) {}

  Sealed seal(UUID tenantId, UUID versionId, String text);
}
