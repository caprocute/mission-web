package vn.viettel.okrkpi.docgen.domain;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.Ids;

/** Mã định danh kiểu mạnh của thực thể mẫu, sinh bằng UUIDv7 (TL-03 "Kiểu dữ liệu chuẩn"). */
public record TemplateItemId(UUID value) {

  public TemplateItemId {
    Objects.requireNonNull(value, "value");
  }

  public static TemplateItemId newId() {
    return new TemplateItemId(Ids.newUuidV7());
  }

  public static TemplateItemId of(UUID value) {
    return new TemplateItemId(value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
