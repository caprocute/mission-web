package vn.viettel.okrkpi.docgen.domain;

import java.time.Instant;
import java.util.Objects;
import vn.viettel.okrkpi.shared.kernel.Result;

/**
 * Thực thể domain thuần của mẫu microservice (TL-01 "domainIsPure": chỉ phụ thuộc Java chuẩn và
 * {@code vt-kernel}).
 */
public record TemplateItem(
    TemplateItemId id, String name, TemplateItemStatus status, Instant createdAt, long version) {

  public static final int MAX_NAME_LENGTH = 200;

  public TemplateItem {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  /** Khởi tạo mục mới ở trạng thái {@link TemplateItemStatus#NHAP}. */
  public static Result<TemplateItem, TemplateItemError> create(String rawName, Instant now) {
    Objects.requireNonNull(now, "now");
    if (rawName == null || rawName.isBlank()) {
      return Result.err(TemplateItemError.TEN_TRONG);
    }
    String normalized = rawName.trim();
    if (normalized.length() > MAX_NAME_LENGTH) {
      return Result.err(TemplateItemError.TEN_QUA_DAI);
    }
    return Result.ok(
        new TemplateItem(TemplateItemId.newId(), normalized, TemplateItemStatus.NHAP, now, 0L));
  }

  /** Chuyển từ {@link TemplateItemStatus#NHAP} sang {@link TemplateItemStatus#KICH_HOAT}. */
  public Result<TemplateItem, TemplateItemError> activate() {
    if (status != TemplateItemStatus.NHAP) {
      return Result.err(TemplateItemError.TRANG_THAI_KHONG_HOP_LE);
    }
    return Result.ok(
        new TemplateItem(id, name, TemplateItemStatus.KICH_HOAT, createdAt, version + 1));
  }

  /** Chuyển từ {@link TemplateItemStatus#KICH_HOAT} sang {@link TemplateItemStatus#LUU_TRU}. */
  public Result<TemplateItem, TemplateItemError> archive() {
    if (status != TemplateItemStatus.KICH_HOAT) {
      return Result.err(TemplateItemError.TRANG_THAI_KHONG_HOP_LE);
    }
    return Result.ok(
        new TemplateItem(id, name, TemplateItemStatus.LUU_TRU, createdAt, version + 1));
  }
}
