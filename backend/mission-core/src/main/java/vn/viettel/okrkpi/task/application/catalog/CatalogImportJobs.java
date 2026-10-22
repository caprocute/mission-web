package vn.viettel.okrkpi.task.application.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob;

/** Cổng lưu lần nhập danh mục ({@code task.catalog_import_job}, CORE-02-04). */
public interface CatalogImportJobs {

  void insert(TenantId tenant, UUID actor, CatalogImportJob job);

  /** Ghi kết quả cuối (trạng thái, số dòng, nhóm lỗi, dòng lỗi) của một lần nhập đang xử lý. */
  void finish(
      TenantId tenant,
      UUID actor,
      CatalogImportJob result,
      List<CatalogImportJob.ErrorRow> errorRows);

  Optional<CatalogImportJob> find(TenantId tenant, UUID id);

  /** Lần nhập mới nhất của một phiên bản danh mục. */
  Optional<CatalogImportJob> latest(TenantId tenant, UUID catalogId);

  List<CatalogImportJob.ErrorRow> errorRows(TenantId tenant, UUID id);
}
