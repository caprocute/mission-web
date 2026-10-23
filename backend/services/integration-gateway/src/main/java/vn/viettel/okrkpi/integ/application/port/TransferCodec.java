package vn.viettel.okrkpi.integ.application.port;

import java.util.List;
import java.util.UUID;

/**
 * Định dạng gói chuyển liên cell: danh mục phần, phần đầu đã ký, khối phần (trước khi mã hoá) và
 * kết quả nhập do cell đích ký. Phần đầu và kết quả giữ nguyên byte để kiểm chữ ký.
 */
public interface TransferCodec {

  /** Một phần hồ sơ; {@code kind} là loại phần cấu hình (ví dụ KET_QUA_XEP_LOAI). */
  record Part(String name, String kind, String mediaType, byte[] content) {
    @Override
    public String toString() {
      return "Part[" + name + ", " + kind + ", " + content.length + " byte]";
    }
  }

  /** Phần đầu đã ký; {@code manifest} là JSON danh mục (tên, loại, cỡ, băm từng phần). */
  record Header(
      int version,
      UUID packageId,
      String sourceCell,
      UUID sourceTenantId,
      String sourceTenantCode,
      String targetCell,
      UUID targetTenantId,
      String targetTenantCode,
      UUID personId,
      String manifest,
      String packageSha256,
      String wrappedKeySha256) {}

  /** Kết quả nhập do cell đích ký gửi về cell nguồn. */
  record Outcome(UUID packageId, UUID sourceTenantId, String cell, String status, String reason) {}

  /** Khối phần hoặc phần đầu không đọc được. */
  final class MalformedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MalformedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  String manifest(List<Part> parts);

  /** Băm hex SHA-256 từng phần theo danh mục, theo tên phần. */
  java.util.Map<String, String> manifestDigests(String manifest);

  byte[] header(Header header);

  Header parseHeader(byte[] header);

  byte[] bundle(List<Part> parts);

  List<Part> unbundle(byte[] bundle);

  byte[] outcome(Outcome outcome);

  Outcome parseOutcome(byte[] outcome);
}
