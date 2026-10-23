package vn.viettel.okrkpi.oversight.infrastructure.web;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintResolutionService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-10 hỏi tài liệu cần ký của văn bản trả lời khiếu nại ({@code GET
 * /internal/v1/esign-sources/oversight/{id}?purpose=TRA_LOI_KHIEU_NAI}, CORE-07-04). Tài liệu là
 * băm mã khiếu nại, kết quả, điều chỉnh, số văn bản và bản mã văn bản trả lời; người ký duy nhất là
 * người có thẩm quyền đã yêu cầu ký. Không nằm trong hợp đồng công khai.
 */
@RestController
@RequestMapping("/internal/v1/esign-sources/oversight")
public class ComplaintResponseEsignSourceController {

  static final String KIND = "TRA_LOI_KHIEU_NAI";

  private final ComplaintResolutionService service;

  public ComplaintResponseEsignSourceController(ComplaintResolutionService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @GetMapping(value = "/{id}", params = "purpose=TRA_LOI_KHIEU_NAI")
  public ResponseEntity<ReopenEsignSourceController.Source> source(@PathVariable("id") UUID id) {
    ComplaintResolutionService.ResponseSource s =
        service
            .responseSource(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok(
        new ReopenEsignSourceController.Source(
            true,
            Set.of(s.signer()),
            List.of(
                new ReopenEsignSourceController.Doc(
                    KIND,
                    s.ref(),
                    HexFormat.of().formatHex(s.sha256()),
                    ComplaintEsignSourceController.FORMAT))));
  }
}
