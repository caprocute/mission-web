package vn.viettel.okrkpi.appraisal.infrastructure.web;

import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.transfer.RatingTransferService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-13 thu thập kết quả xếp loại các kỳ của cán bộ được điều động liên cell
 * (DV-13-10, cấu hình {@code okrkpi.integ.cell-transfer.parts[KET_QUA_XEP_LOAI].collect-url} của
 * gateway): {@code GET /internal/v1/appraisal/transfer-parts/{personId}/ratings}, 404 khi chưa có
 * kết quả đã chốt. Tenant lấy từ token dịch vụ uỷ quyền ({@code X-Tenant-Id}); không nằm trong hợp
 * đồng công khai, bảo vệ bằng xác thực dịch vụ của mesh.
 */
@RestController
public class RatingTransferController {

  private final RatingTransferService service;

  public RatingTransferController(RatingTransferService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @GetMapping("/internal/v1/appraisal/transfer-parts/{personId}/ratings")
  public RatingTransferService.Ratings ratings(@PathVariable("personId") UUID personId) {
    return service
        .ratings(TenantContext.require(), personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
