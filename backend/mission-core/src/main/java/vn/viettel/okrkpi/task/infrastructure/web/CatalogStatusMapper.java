package vn.viettel.okrkpi.task.infrastructure.web;

import vn.viettel.okrkpi.contract.tasks.model.WorkCatalogStatus;
import vn.viettel.okrkpi.task.domain.catalog.CatalogStatus;

/**
 * Lớp ánh xạ duy nhất giữa trạng thái phiên bản danh mục của CSDL (TL-03: {@code NHAP}, {@code
 * HIEU_LUC}, {@code NGUNG}) và của hợp đồng ({@code WorkCatalogStatus}: {@code NHAP}, {@code
 * AP_DUNG}, {@code HET_HIEU_LUC}), theo quyết định của CORE-02-01.
 */
public final class CatalogStatusMapper {

  private CatalogStatusMapper() {}

  public static WorkCatalogStatus toContract(CatalogStatus status) {
    return switch (status) {
      case NHAP -> WorkCatalogStatus.NHAP;
      case HIEU_LUC -> WorkCatalogStatus.AP_DUNG;
      case NGUNG -> WorkCatalogStatus.HET_HIEU_LUC;
    };
  }

  public static CatalogStatus toDomain(WorkCatalogStatus status) {
    return switch (status) {
      case NHAP -> CatalogStatus.NHAP;
      case AP_DUNG -> CatalogStatus.HIEU_LUC;
      case HET_HIEU_LUC -> CatalogStatus.NGUNG;
    };
  }
}
