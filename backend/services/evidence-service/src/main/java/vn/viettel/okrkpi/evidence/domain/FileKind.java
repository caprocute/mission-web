package vn.viettel.okrkpi.evidence.domain;

import java.util.Arrays;
import java.util.Optional;

/** Kiểu tệp được phép (SEC-48, hợp đồng {@code FileUploadCreate.mediaType}). */
public enum FileKind {
  PDF("application/pdf"),
  DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
  XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
  JPEG("image/jpeg"),
  PNG("image/png"),
  /** Chỉ cho bản mã đầu cuối C3 (ADR-06). */
  CIPHERTEXT("application/octet-stream");

  private final String mediaType;

  FileKind(String mediaType) {
    this.mediaType = mediaType;
  }

  public String mediaType() {
    return mediaType;
  }

  public static Optional<FileKind> ofMediaType(String mediaType) {
    return Arrays.stream(values()).filter(k -> k.mediaType.equals(mediaType)).findFirst();
  }
}
