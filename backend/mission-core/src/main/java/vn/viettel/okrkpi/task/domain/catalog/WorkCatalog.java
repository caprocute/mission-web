package vn.viettel.okrkpi.task.domain.catalog;

import java.util.Objects;
import java.util.UUID;

/**
 * Một phiên bản danh mục ({@code task.work_catalog}). Các phiên bản của cùng danh mục chung {@code
 * code} và khác {@code version}.
 */
public record WorkCatalog(
    UUID id,
    String code,
    String name,
    String scope,
    UUID positionId,
    UUID orgUnitId,
    int version,
    String source,
    CatalogStatus status,
    UUID parentId,
    int rowVersion) {

  public WorkCatalog {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(status, "status");
  }

  /** Phiên bản n+1 ở {@link CatalogStatus#NHAP} nhân bản từ phiên bản này. */
  public WorkCatalog nextDraft(UUID newId, int newVersion) {
    return new WorkCatalog(
        newId,
        code,
        name,
        scope,
        positionId,
        orgUnitId,
        newVersion,
        source,
        CatalogStatus.NHAP,
        parentId,
        0);
  }
}
