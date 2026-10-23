package vn.viettel.okrkpi.product.infrastructure.web;

import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.product.application.e2ee.EvidenceKeyWrapService;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.FileAccess;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-08 hỏi trước khi cấp URL tải bản mã C3 ({@code GET
 * /internal/v1/e2ee-access/evidence-files/{objectId}?personId=}, CORE-07-06, suy luận ở wiki trang
 * 14): {@code wrapped} là tệp đã có bản bọc nào, {@code active} là người đó đang giữ bản bọc còn
 * hiệu lực. Không nằm trong hợp đồng công khai; bảo vệ bằng xác thực dịch vụ của mesh như cổng
 * {@code esign-sources}, tenant đích theo token dịch vụ và {@code X-Tenant-Id}.
 */
@RestController
@RequestMapping("/internal/v1/e2ee-access/evidence-files")
public class E2eeFileAccessController {

  private final EvidenceKeyWrapService keyWraps;

  public E2eeFileAccessController(EvidenceKeyWrapService keyWraps) {
    this.keyWraps = Objects.requireNonNull(keyWraps, "keyWraps");
  }

  @GetMapping("/{objectId}")
  public FileAccess access(
      @PathVariable("objectId") UUID objectId, @RequestParam("personId") UUID personId) {
    return keyWraps.fileAccess(TenantContext.require(), objectId, personId);
  }
}
