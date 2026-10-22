package vn.viettel.okrkpi.task.application.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;
import vn.viettel.okrkpi.task.domain.catalog.CatalogStatus;
import vn.viettel.okrkpi.task.domain.catalog.WorkCatalog;

/** Cổng lưu trữ {@code task.work_catalog}, {@code task.catalog_item} (CORE-02-03). */
public interface CatalogRepository {

  /** Mã mục đã có trong danh mục (unique {@code uq_catalog_item__code}). */
  final class DuplicateCodeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DuplicateCodeException(Throwable cause) {
      super("Mã công việc đã có trong danh mục", cause);
    }
  }

  /** Hai lệnh cùng tạo một số phiên bản ({@code uq_work_catalog__code_version}). */
  final class DuplicateVersionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DuplicateVersionException(Throwable cause) {
      super("Phiên bản danh mục đã tồn tại", cause);
    }
  }

  /** Khoá keyset của danh sách phiên bản: hạng trạng thái, tên, phiên bản giảm dần, id. */
  record CatalogKey(int rank, String name, int version, UUID id) {}

  /** Mục nhìn thấy từ một phiên bản: mục riêng hoặc mục kế thừa (chỉ đọc) của danh mục cấp trên. */
  record VisibleItem(CatalogItem item, UUID rootCatalogId, UUID effectivePositionId) {
    public boolean inherited() {
      return !item.catalogId().equals(rootCatalogId);
    }
  }

  /** Số nhiệm vụ và dòng kế hoạch dùng một mục trong một kỳ. */
  record PeriodUse(UUID itemId, UUID periodId, int count) {}

  Optional<WorkCatalog> findCatalog(TenantId tenant, UUID id);

  /** Trang phiên bản, đang áp dụng trước, rồi nháp, rồi đã ngừng. */
  List<WorkCatalog> listCatalogs(TenantId tenant, UUID orgUnit, CatalogKey after, int fetch);

  /** Hạng sắp xếp của trạng thái trong {@link #listCatalogs}. */
  static int rank(CatalogStatus status) {
    return switch (status) {
      case HIEU_LUC -> 0;
      case NHAP -> 1;
      case NGUNG -> 2;
    };
  }

  /** Phiên bản mới nhất của dòng danh mục {@code code} ở trạng thái cho trước. */
  Optional<WorkCatalog> findLatest(TenantId tenant, String code, CatalogStatus status);

  int maxVersion(TenantId tenant, String code);

  void insertCatalog(TenantId tenant, UUID actor, WorkCatalog catalog);

  /** Đổi trạng thái khi {@code row_version} khớp; trả {@code false} khi lệch. */
  boolean changeStatus(
      TenantId tenant, UUID actor, UUID id, CatalogStatus status, int expectedRowVersion);

  /** Chuyển mọi phiên bản {@code from} của dòng danh mục sang {@code to}. */
  int replaceStatus(TenantId tenant, UUID actor, String code, CatalogStatus from, CatalogStatus to);

  void changeParent(TenantId tenant, UUID actor, UUID id, UUID parentId);

  Optional<UUID> parentOf(TenantId tenant, UUID id);

  /** Tên các phiên bản theo id. */
  Map<UUID, String> catalogNames(TenantId tenant, Collection<UUID> ids);

  /** Nhãn vị trí việc làm: tên danh mục gắn vị trí đó (bản sao tổ chức chưa có tên vị trí). */
  Map<UUID, String> positionLabels(TenantId tenant, Collection<UUID> positionIds);

  /**
   * Mục nhìn thấy từ một phiên bản: mục riêng cộng mục của chuỗi danh mục cấp trên; mục riêng che
   * mục kế thừa cùng mã. Sắp theo mã, id.
   */
  List<VisibleItem> visibleItems(TenantId tenant, UUID catalogId);

  /**
   * Mục {@code AP_DUNG} nhìn thấy từ các phiên bản đang áp dụng, keyset theo (mã, id).
   *
   * @param positions vị trí của người gọi khi {@code applicable=me}; {@code null} là mọi vị trí
   * @param foldedQuery từ khoá đã bỏ dấu, hoặc {@code null}
   */
  List<VisibleItem> applicableItems(
      TenantId tenant,
      UUID orgUnit,
      Set<UUID> positions,
      String foldedQuery,
      String afterCode,
      UUID afterId,
      int fetch);

  Optional<CatalogItem> findItem(TenantId tenant, UUID id);

  Optional<CatalogItem> findItemByCode(TenantId tenant, UUID catalogId, String code);

  List<String> itemCodes(TenantId tenant, UUID catalogId);

  void insertItem(TenantId tenant, UUID actor, CatalogItem item);

  /** Ghi nội dung mới khi {@code row_version} khớp; trả {@code false} khi lệch. */
  boolean reviseItem(TenantId tenant, UUID actor, CatalogItem item, int expectedRowVersion);

  /** Sao chép mọi mục của {@code from} sang {@code to} với id mới, giữ mã. */
  int copyItems(TenantId tenant, UUID actor, UUID from, UUID to);

  /** Nhiệm vụ ({@code task.task}) và dòng kế hoạch ({@code task.plan_item}) dùng các mục. */
  List<PeriodUse> usage(TenantId tenant, Collection<UUID> itemIds);
}
