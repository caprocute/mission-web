package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService.RenderInput;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-11 hỏi dữ liệu sinh biểu mẫu của MOD-06 (CORE-06-06, suy luận ở wiki trang 14;
 * khớp {@code HttpRenderInputClient} của document-service): {@code GET
 * /internal/v1/dossier-render-inputs/{packageId}?actor=&formCodes=} trả biểu mẫu {@code CHO_SINH}
 * của gói đang sinh kèm băm hex, phiên bản mẫu và Policy Pack; {@code GET
 * /internal/v1/dossier-form-instances/{id}/render-data} trả tài liệu JSON chuẩn tắc mà băm của nó
 * là {@code data_snapshot_hash}. Không nằm trong hợp đồng công khai; bảo vệ bằng xác thực dịch vụ
 * của mesh.
 */
@RestController
public class DossierRenderInputController {

  private final DossierPackageService service;

  public DossierRenderInputController(DossierPackageService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /** Thân phản hồi danh sách biểu mẫu. */
  public record Inputs(List<RenderInput> forms) {}

  @GetMapping("/internal/v1/dossier-render-inputs/{packageId}")
  public ResponseEntity<Inputs> inputs(
      @PathVariable("packageId") UUID packageId,
      @RequestParam(value = "actor", required = false) UUID actor,
      @RequestParam(value = "formCodes", required = false) String formCodes) {
    List<String> codes =
        formCodes == null || formCodes.isBlank()
            ? List.of()
            : Arrays.stream(formCodes.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    List<RenderInput> forms =
        service
            .renderInputs(TenantContext.require(), actor, packageId, codes)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok(new Inputs(forms));
  }

  @GetMapping("/internal/v1/dossier-form-instances/{id}/render-data")
  public ResponseEntity<byte[]> data(@PathVariable("id") UUID id) {
    byte[] body =
        service
            .renderData(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
  }
}
