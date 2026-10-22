package vn.viettel.okrkpi.task.domain.catalog;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Mục công việc trong một phiên bản danh mục ({@code task.catalog_item}, PL 1 Bảng 3). */
public record CatalogItem(
    UUID id,
    UUID catalogId,
    String code,
    String name,
    String productName,
    String unit,
    int difficulty,
    BigDecimal baseScore,
    String qualityCriteria,
    String evidenceRequired,
    String group,
    UUID positionId,
    Integer standardDays,
    String status,
    int rowVersion) {

  public CatalogItem {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(catalogId, "catalogId");
    Objects.requireNonNull(code, "code");
  }

  /** Mục mới từ dữ liệu đã kiểm, mã đã chốt. */
  public static CatalogItem create(UUID id, UUID catalogId, String code, CatalogItemDraft d) {
    return new CatalogItem(
        id,
        catalogId,
        code,
        d.name(),
        d.productName(),
        d.unit(),
        d.difficulty(),
        d.baseScore(),
        d.qualityCriteria(),
        d.evidenceRequired(),
        d.group(),
        d.positionId(),
        d.standardDays(),
        d.status(),
        0);
  }

  /** Nội dung mới của mục, giữ định danh và phiên bản dòng. */
  public CatalogItem revise(String newCode, CatalogItemDraft d) {
    return new CatalogItem(
        id,
        catalogId,
        newCode,
        d.name(),
        d.productName(),
        d.unit(),
        d.difficulty(),
        d.baseScore(),
        d.qualityCriteria(),
        d.evidenceRequired(),
        d.group(),
        d.positionId(),
        d.standardDays(),
        d.status(),
        rowVersion);
  }
}
