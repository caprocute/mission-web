package vn.viettel.okrkpi.shared.idempotency;

import java.util.Objects;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phạm vi của một khoá: khoá chỉ có nghĩa trong (tenant, người gọi) của một schema (TL-03 cột
 * {@code actor_id}: "khoá chỉ có nghĩa trong phạm vi một người").
 *
 * @param schema schema nghiệp vụ chứa {@code idempotency_key}, ví dụ {@code task}
 * @param tenant tenant đã kiểm
 * @param actor người gọi ({@code sub} của JWT)
 */
public record IdempotencyScope(String schema, TenantId tenant, ActorId actor) {

  /** Tên schema hợp lệ theo TL-03 "Quy ước đặt tên" (chữ thường, số, gạch dưới). */
  static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  public IdempotencyScope {
    Objects.requireNonNull(schema, "schema");
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(actor, "actor");
    if (!SCHEMA.matcher(schema).matches()) {
      throw new IllegalArgumentException("Tên schema không hợp lệ: " + schema);
    }
  }
}
