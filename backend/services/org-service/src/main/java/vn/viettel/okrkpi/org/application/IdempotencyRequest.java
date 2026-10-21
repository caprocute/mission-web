package vn.viettel.okrkpi.org.application;

/** Khoá {@code Idempotency-Key} cùng băm SHA-256 của phương thức, đường dẫn và thân yêu cầu chuẩn hoá. */
public record IdempotencyRequest(String key, byte[] requestHash) {
}
