package vn.viettel.okrkpi.contract.test.domain;

import java.util.Objects;

/**
 * Một sai lệch phát hiện khi đối chiếu tài liệu OpenAPI thực tế ({@code /v3/api-docs}) hoặc phản
 * hồi JSON thực tế với hợp đồng chuẩn {@code contracts/openapi/modules/*.yaml} (ADR-32, NEN-006).
 *
 * @param category nhóm sai lệch (MISSING_ENDPOINT, EXTRA_ENDPOINT, INCOMPATIBLE_CHANGE,
 *     BASELINE_CONVENTION, RESPONSE_SCHEMA)
 * @param location vị trí sai lệch (ví dụ {@code GET /jobs/{id}} hoặc {@code GET /jobs/{id} ->
 *     200.progress})
 * @param message thông điệp tiếng Việt mô tả chi tiết sai lệch
 */
public record ContractViolation(String category, String location, String message) {

  public ContractViolation {
    Objects.requireNonNull(category, "category must not be null");
    Objects.requireNonNull(location, "location must not be null");
    Objects.requireNonNull(message, "message must not be null");
    if (category.isBlank() || location.isBlank() || message.isBlank()) {
      throw new IllegalArgumentException("category, location, message must not be blank");
    }
  }

  public static ContractViolation missingEndpoint(String method, String path) {
    String loc = method + " " + path;
    return new ContractViolation(
        "MISSING_ENDPOINT", loc, "Thiếu endpoint bắt buộc trong hợp đồng: " + loc);
  }

  public static ContractViolation extraEndpoint(String method, String path) {
    String loc = method + " " + path;
    return new ContractViolation(
        "EXTRA_ENDPOINT", loc, "Phát hiện endpoint ngoài hợp đồng: " + loc);
  }

  public static ContractViolation incompatibleChange(String location, String detail) {
    return new ContractViolation(
        "INCOMPATIBLE_CHANGE", location, "Thay đổi không tương thích hợp đồng: " + detail);
  }

  public static ContractViolation baselineConvention(String location, String detail) {
    return new ContractViolation(
        "BASELINE_CONVENTION", location, "Vi phạm quy ước mục 13 baseline: " + detail);
  }

  public static ContractViolation responseSchema(String location, String detail) {
    return new ContractViolation(
        "RESPONSE_SCHEMA", location, "Phản hồi JSON lệch schema hợp đồng: " + detail);
  }

  public String format() {
    return "[" + category + "] " + location + " — " + message;
  }
}
