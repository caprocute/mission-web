package vn.viettel.okrkpi.evidence.application;

import java.util.List;
import java.util.Map;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.AuthzResourceProvider;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;

/**
 * Kiểm quyền mở nội dung tệp qua OPA (NEN-014) với tài nguyên là tệp đã nạp: bản ghi nghiệp vụ sở
 * hữu ({@code ownerModule:ownerRefId}), người tải lên và phân loại. Quan hệ người xem với minh
 * chứng (người nộp, người nghiệm thu, phạm vi đơn vị) do chính sách OPA tra theo bản ghi sở hữu
 * (suy luận, wiki trang 14 mục 47).
 */
public class FileAccessGuard {

  /** Tài nguyên OPA của một tệp. */
  public record FileAccess(StoredFile file) implements AuthzResourceProvider {
    @Override
    public AuthzResource toAuthzResource() {
      return AuthzResource.builder()
          .type("file")
          .id(file.objectId().toString())
          .tenantId(file.tenantId().toString())
          .ownerIds(List.of(file.createdBy().toString()))
          .classification(file.classification())
          .participants(
              file.ownerRefId() == null
                  ? Map.of()
                  : Map.of("owner_ref", List.of(file.ownerModule() + ":" + file.ownerRefId())))
          .build();
    }
  }

  @RequiresPermission(operationId = "getFileDownloadUrl", resourceType = "file")
  public void checkDownload(FileAccess access) {
    // Chỉ để chặn trước bởi PermissionInterceptor.
  }

  @RequiresPermission(operationId = "getFilePreview", resourceType = "file")
  public void checkPreview(FileAccess access) {
    // Chỉ để chặn trước bởi PermissionInterceptor.
  }

  @RequiresPermission(operationId = "getFileScanResult", resourceType = "file")
  public void checkScanResult(FileAccess access) {
    // Chỉ để chặn trước bởi PermissionInterceptor.
  }
}
