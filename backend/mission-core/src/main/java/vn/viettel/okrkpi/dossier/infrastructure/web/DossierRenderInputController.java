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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService.PreviewInput;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService.RenderInput;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-11 hỏi dữ liệu sinh biểu mẫu của MOD-06 (CORE-06-06, suy luận ở wiki trang 14;
 * khớp {@code HttpRenderInputClient} của document-service): {@code GET
 * /internal/v1/dossier-render-inputs/{packageId}?actor=&formCodes=} trả biểu mẫu {@code CHO_SINH}
 * của gói đang sinh kèm băm hex, phiên bản mẫu và Policy Pack; {@code GET
 * /internal/v1/dossier-form-instances/{id}/render-data} trả tài liệu JSON chuẩn tắc mà băm của nó
 * là {@code data_snapshot_hash}. DV-11-02, DV-11-03 thêm {@code POST
 * /internal/v1/dossier-draft-forms} (tạo biểu mẫu nháp ngoài gói, trả như danh sách biểu mẫu) và
 * {@code POST /internal/v1/dossier-form-previews} (dữ liệu hiện tại để xem trước, không ghi gì).
 * Không nằm trong hợp đồng công khai; bảo vệ bằng xác thực dịch vụ của mesh.
 */
@RestController
public class DossierRenderInputController {

  private final DossierPackageService service;

  public DossierRenderInputController(DossierPackageService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /** Thân phản hồi danh sách biểu mẫu. */
  public record Inputs(List<RenderInput> forms) {}

  /** Thân yêu cầu tạo biểu mẫu nháp ({@code HttpRenderInputClient.DraftRequest}). */
  public record DraftRequest(UUID actor, String formCode, UUID periodId, List<UUID> profileIds) {}

  /** Thân yêu cầu dữ liệu xem trước ({@code HttpRenderInputClient.PreviewRequest}). */
  public record PreviewRequest(
      UUID actor, String formCode, String sourceModule, UUID sourceRefId) {}

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

  @PostMapping("/internal/v1/dossier-draft-forms")
  public ResponseEntity<Inputs> drafts(@RequestBody DraftRequest request) {
    List<RenderInput> forms =
        service
            .draftForms(
                TenantContext.require(),
                request.actor(),
                request.formCode(),
                request.periodId(),
                request.profileIds())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok(new Inputs(forms));
  }

  @PostMapping("/internal/v1/dossier-form-previews")
  public ResponseEntity<PreviewInput> preview(@RequestBody PreviewRequest request) {
    PreviewInput body =
        service
            .preview(
                TenantContext.require(),
                request.actor(),
                request.formCode(),
                request.sourceModule(),
                request.sourceRefId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok(body);
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
