package vn.viettel.okrkpi.docgen.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.docgen.application.render.FormPreviewService;
import vn.viettel.okrkpi.docgen.infrastructure.web.model.ListDossierMeetings401Response;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code POST /api/v1/dossiers/forms/preview} ({@code previewDossierForm}, SCR-0901 "Xem trước Mẫu
 * 1B", DV-11-02): trả ngay tệp PDF có dấu "Bản nháp" từ dữ liệu hiện tại.
 *
 * <p>Viết tay thay vì hiện thực giao diện sinh từ hợp đồng vì bộ sinh ánh xạ thân {@code
 * application/pdf} thành {@code String}, không trả được byte nhị phân. Thao tác chỉ đọc và không
 * lưu gì, nên {@code Idempotency-Key} được nhận theo hợp đồng nhưng không cần lưu phản hồi để phát
 * lại (suy luận, wiki trang 14). Không cho trình duyệt hay proxy lưu đệm tệp.
 */
@RestController
@RequestMapping("/api/v1")
public class DossierFormPreviewController {

  private final FormPreviewService previews;

  public DossierFormPreviewController(FormPreviewService previews) {
    this.previews = previews;
  }

  /** Thân yêu cầu theo lược đồ {@code DossierFormPreviewRequest}. */
  public record DossierFormPreviewRequest(
      @NotBlank @Size(max = 16) String formCode,
      @NotNull @Pattern(regexp = "appraisal|oversight|dossier") String sourceModule,
      @NotNull UUID sourceRefId) {}

  @Operation(
      operationId = "previewDossierForm",
      summary = "Xem trước biểu mẫu từ dữ liệu hiện tại",
      tags = {"Biểu mẫu"},
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Tệp PDF xem trước.",
            content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string"))),
        @ApiResponse(
            responseCode = "401",
            description = "Chưa xác thực.",
            content = {
              @Content(
                  mediaType = "application/problem+json",
                  schema = @Schema(implementation = ListDossierMeetings401Response.class))
            }),
        @ApiResponse(
            responseCode = "403",
            description = "Không đủ quyền.",
            content = {
              @Content(
                  mediaType = "application/problem+json",
                  schema = @Schema(implementation = ListDossierMeetings401Response.class))
            }),
        @ApiResponse(
            responseCode = "404",
            description = "Không tìm thấy dữ liệu nguồn.",
            content = {
              @Content(
                  mediaType = "application/problem+json",
                  schema = @Schema(implementation = ListDossierMeetings401Response.class))
            }),
        @ApiResponse(
            responseCode = "422",
            description = "Dữ liệu không hợp lệ.",
            content = {
              @Content(
                  mediaType = "application/problem+json",
                  schema = @Schema(implementation = ListDossierMeetings401Response.class))
            }),
        @ApiResponse(
            responseCode = "default",
            description = "Lỗi khác theo RFC 9457.",
            content = {
              @Content(
                  mediaType = "application/problem+json",
                  schema = @Schema(implementation = ListDossierMeetings401Response.class))
            })
      },
      security = {@SecurityRequirement(name = "keycloak")})
  @PostMapping(
      value = "/dossiers/forms/preview",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> previewDossierForm(
      @RequestHeader(value = "Idempotency-Key") @Size(max = 64) String idempotencyKey,
      @Valid @RequestBody DossierFormPreviewRequest body) {
    byte[] pdf =
        previews.preview(
            new FormPreviewService.PreviewCommand(
                TenantContext.require().value(),
                CurrentCaller.actor(),
                body.formCode(),
                body.sourceModule(),
                body.sourceRefId()));
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .cacheControl(CacheControl.noStore())
        // ASVS 14.4.2: trình duyệt không hiển thị trực tiếp phản hồi API; web đọc thân qua
        // api-client.
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("xem-truoc.pdf").build().toString())
        .body(pdf);
  }
}
