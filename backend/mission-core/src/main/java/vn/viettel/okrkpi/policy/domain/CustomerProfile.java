package vn.viettel.okrkpi.policy.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Hồ sơ tập khách hàng ({@code policy.customer_profile}, BS-03 ADR-33): bản kê có phiên bản trỏ tới
 * phiên bản các thành phần cấu hình. Lõi không biết hồ sơ cụ thể nào; mọi giá trị riêng của khách
 * hàng nằm trong dữ liệu.
 */
public record CustomerProfile(
    UUID id,
    String code,
    String name,
    boolean simulated,
    List<String> legalBasis,
    List<String> tenantTags,
    Instant createdAt,
    UUID createdBy,
    long rowVersion) {

  /** Dạng mã của hợp đồng ({@code CustomerProfileCreate.code}). */
  public static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9/_-]*$");

  public CustomerProfile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    legalBasis = legalBasis == null ? List.of() : List.copyOf(legalBasis);
    tenantTags = tenantTags == null ? List.of() : List.copyOf(tenantTags);
  }
}
