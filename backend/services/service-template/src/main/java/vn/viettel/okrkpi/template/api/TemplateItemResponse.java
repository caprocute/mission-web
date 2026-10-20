package vn.viettel.okrkpi.template.api;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.template.domain.TemplateItem;

/** DTO phản hồi mục mẫu ở bộ điều hợp web ({@code api}). */
public record TemplateItemResponse(
    UUID id, String name, String status, Instant createdAt, long version) {

  public static TemplateItemResponse from(TemplateItem item) {
    return new TemplateItemResponse(
        item.id().value(), item.name(), item.status().name(), item.createdAt(), item.version());
  }
}
