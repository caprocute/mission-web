package vn.viettel.okrkpi.shared.idempotency;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;

/**
 * Một dòng {@code <schema>.idempotency_key} (TL-03 "Bảng idempotency_key").
 *
 * @param key giá trị header {@code Idempotency-Key}
 * @param createdDay khoá phân vùng ngày
 * @param requestHash SHA-256 của phương thức, đường dẫn, thân chuẩn hoá
 * @param status {@link Status#DANG_XU_LY} hoặc {@link Status#XONG}
 * @param responseCode mã HTTP đã trả, {@code null} khi chưa xong
 * @param responseBody JSON đã trả, {@code null} khi chưa xong hoặc thân có trường C2
 * @param resourceRef tài nguyên đã tạo (header {@code Location}), có thể {@code null}
 * @param createdAt thời điểm tạo
 */
public record IdempotencyRecord(
    String key,
    LocalDate createdDay,
    byte[] requestHash,
    Status status,
    Integer responseCode,
    String responseBody,
    String resourceRef,
    Instant createdAt) {

  /** Giá trị CHECK của cột {@code status}. */
  public enum Status {
    DANG_XU_LY,
    XONG
  }

  public IdempotencyRecord {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(requestHash, "requestHash");
    Objects.requireNonNull(status, "status");
    requestHash = requestHash.clone();
  }

  @Override
  public byte[] requestHash() {
    return requestHash.clone();
  }

  /** So băm theo thời gian hằng (không lộ độ dài tiền tố khớp). */
  public boolean sameRequest(byte[] otherHash) {
    return java.security.MessageDigest.isEqual(requestHash, otherHash);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof IdempotencyRecord r
        && key.equals(r.key)
        && Objects.equals(createdDay, r.createdDay)
        && Arrays.equals(requestHash, r.requestHash)
        && status == r.status
        && Objects.equals(responseCode, r.responseCode)
        && Objects.equals(responseBody, r.responseBody)
        && Objects.equals(resourceRef, r.resourceRef)
        && Objects.equals(createdAt, r.createdAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, createdDay, Arrays.hashCode(requestHash), status, responseCode);
  }

  /** Không in thân phản hồi (có thể chứa dữ liệu người dùng) ra nhật ký. */
  @Override
  public String toString() {
    return "IdempotencyRecord[key="
        + key
        + ", createdDay="
        + createdDay
        + ", status="
        + status
        + ", responseCode="
        + responseCode
        + "]";
  }
}
