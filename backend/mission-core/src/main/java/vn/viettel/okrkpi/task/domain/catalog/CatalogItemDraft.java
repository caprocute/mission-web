package vn.viettel.okrkpi.task.domain.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dữ liệu người dùng gửi khi thêm hoặc sửa mục ({@code CatalogItemInput}); {@code code} rỗng thì
 * máy chủ tự sinh, {@code status} rỗng thì {@code AP_DUNG}. Giá trị đã được {@link
 * CatalogRules#normalize} cắt khoảng trắng.
 */
public record CatalogItemDraft(
    String code,
    UUID positionId,
    String name,
    String productName,
    String unit,
    Integer difficulty,
    BigDecimal baseScore,
    String qualityCriteria,
    String evidenceRequired,
    String group,
    Integer standardDays,
    String status) {}
