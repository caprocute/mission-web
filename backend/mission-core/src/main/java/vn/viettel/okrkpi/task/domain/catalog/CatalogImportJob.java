package vn.viettel.okrkpi.task.domain.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Một lần nhập danh mục từ Excel ({@code task.catalog_import_job}; hợp đồng {@code
 * CatalogImportJob}).
 */
public record CatalogImportJob(
    UUID id,
    UUID catalogId,
    UUID targetCatalogId,
    String fileName,
    Status status,
    int totalRows,
    int validRows,
    List<ErrorGroup> errorGroups,
    String failureDetail,
    Instant createdAt,
    Instant finishedAt) {

  /** Trạng thái tác vụ theo hợp đồng. */
  public enum Status {
    DANG_XU_LY,
    XONG,
    LOI
  }

  /** Nhóm dòng lỗi cùng lý do (MSG-04-02). */
  public record ErrorGroup(String reason, int count, CatalogViolationException.Kind kind) {}

  /** Một dòng lỗi: số dòng trong tệp, giá trị gốc theo cột mẫu và lý do. */
  public record ErrorRow(int rowNo, List<String> values, String reason) {}

  public CatalogImportJob {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
    errorGroups = List.copyOf(errorGroups);
  }

  /** Lần nhập mới ở trạng thái đang xử lý. */
  public static CatalogImportJob started(
      UUID id, UUID catalogId, UUID targetCatalogId, String fileName, Instant now) {
    return new CatalogImportJob(
        id,
        catalogId,
        targetCatalogId,
        fileName,
        Status.DANG_XU_LY,
        0,
        0,
        List.of(),
        null,
        now,
        null);
  }
}
