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
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-10 hỏi tài liệu cần ký của khiếu nại ({@code GET
 * /internal/v1/esign-sources/oversight/{id}?purpose=KHIEU_NAI}, cùng đường với cổng mở lại kỳ, tách
 * theo tham số {@code purpose}). Tài liệu là băm các trường và bản mã nội dung của khiếu nại đang
 * chờ ký, dạng {@code JWS}; người ký duy nhất là người khiếu nại. Không nằm trong hợp đồng công
 * khai.
 */
@RestController
@RequestMapping("/internal/v1/esign-sources/oversight")
public class ComplaintEsignSourceController {

  static final String KIND = "KHIEU_NAI";
  static final String FORMAT = "JWS";

  private final ComplaintService service;

  public ComplaintEsignSourceController(ComplaintService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @GetMapping(value = "/{id}", params = "purpose=KHIEU_NAI")
  public ResponseEntity<ReopenEsignSourceController.Source> source(@PathVariable("id") UUID id) {
    ComplaintService.SignSource s =
        service
            .signSource(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok(
        new ReopenEsignSourceController.Source(
            true,
            Set.of(s.signer()),
            List.of(
                new ReopenEsignSourceController.Doc(
                    KIND, s.ref(), HexFormat.of().formatHex(s.sha256()), FORMAT))));
  }
}
