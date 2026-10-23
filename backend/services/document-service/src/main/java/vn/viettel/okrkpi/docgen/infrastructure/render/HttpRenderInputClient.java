package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.TransientFailure;
import vn.viettel.okrkpi.docgen.application.render.RenderInputPort;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Cổng nội bộ của MOD-06 trong mission-core (suy luận, wiki trang 14): {@code GET
 * /internal/v1/dossier-render-inputs/{packageId}} trả biểu mẫu cần sinh kèm băm dữ liệu, {@code GET
 * /internal/v1/dossier-form-instances/{id}/render-data} trả tài liệu JSON chuẩn tắc đã băm; {@code
 * POST /internal/v1/dossier-draft-forms} tạo biểu mẫu nháp không gắn gói (DV-11-03), {@code POST
 * /internal/v1/dossier-form-previews} trả dữ liệu hiện tại để xem trước (DV-11-02).
 */
public class HttpRenderInputClient implements RenderInputPort {

  static final String FORMS =
      "/internal/v1/dossier-render-inputs/{packageId}?actor={actor}&formCodes={codes}";
  static final String DATA = "/internal/v1/dossier-form-instances/{id}/render-data";
  static final String DRAFTS = "/internal/v1/dossier-draft-forms";
  static final String PREVIEWS = "/internal/v1/dossier-form-previews";
  static final String INVALID_STATE = "DOSSIER-409-INVALID-STATE";

  private final ResilientClient client;

  public HttpRenderInputClient(ResilientClient client) {
    this.client = client;
  }

  @Override
  public Optional<List<FormRef>> forms(
      UUID tenantId, UUID actor, UUID packageId, List<String> formCodes) {
    Response body;
    try {
      body = client.get(FORMS, Response.class, packageId, actor, String.join(",", formCodes));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (HttpClientErrorException.Conflict e) {
      throw new BusinessException(INVALID_STATE, "Gói hồ sơ không ở trạng thái lập hồ sơ.");
    }
    if (body == null || body.forms() == null) {
      return Optional.of(List.of());
    }
    return Optional.of(refs(body));
  }

  private static List<FormRef> refs(Response body) {
    return body.forms().stream()
        .map(
            f ->
                new FormRef(
                    f.formInstanceId(),
                    f.formCode(),
                    f.subjectGroupCode(),
                    HexFormat.of().parseHex(f.dataSnapshotHash()),
                    f.templateVersion(),
                    f.policyPackCode(),
                    f.policyPackVersion(),
                    f.reopenRequestId()))
        .toList();
  }

  @Override
  public Optional<List<FormRef>> draftForms(
      UUID tenantId, UUID actor, String formCode, UUID periodId, List<UUID> profileIds) {
    Response body;
    try {
      body =
          client.post(
              DRAFTS,
              new DraftRequest(actor, formCode, periodId, profileIds),
              null,
              Response.class);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (HttpClientErrorException.Conflict e) {
      throw new BusinessException(INVALID_STATE, "Kỳ đánh giá không ở trạng thái cho phép.");
    }
    return Optional.of(body == null || body.forms() == null ? List.of() : refs(body));
  }

  @Override
  public Optional<PreviewData> preview(
      UUID tenantId, UUID actor, String formCode, String sourceModule, UUID sourceRefId) {
    Preview body;
    try {
      body =
          client.post(
              PREVIEWS,
              new PreviewRequest(actor, formCode, sourceModule, sourceRefId),
              null,
              Preview.class);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    if (body == null || body.data() == null) {
      return Optional.empty();
    }
    return Optional.of(
        new PreviewData(
            formCode,
            body.subjectGroupCode(),
            body.templateVersion(),
            body.policyPackCode(),
            body.policyPackVersion(),
            body.data().getBytes(StandardCharsets.UTF_8)));
  }

  @Override
  public Optional<FormData> data(UUID tenantId, UUID formInstanceId) {
    try {
      byte[] body = client.get(DATA, byte[].class, formInstanceId);
      return body == null
          ? Optional.empty()
          : Optional.of(new FormData(formInstanceId, null, body));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (RestClientException e) {
      throw new TransientFailure("Không lấy được dữ liệu biểu mẫu: " + e.getMessage(), e);
    }
  }

  /** Thân phản hồi danh sách biểu mẫu (suy luận); băm hex chữ thường như {@code DossierSha256}. */
  record Response(List<Form> forms) {}

  /**
   * Một biểu mẫu; {@code templateVersion}, {@code policyPackCode}, {@code policyPackVersion} là
   * phiên bản mẫu và Policy Pack gắn với kỳ của gói (DV-11-04, suy luận).
   */
  record Form(
      UUID formInstanceId,
      String formCode,
      String subjectGroupCode,
      String dataSnapshotHash,
      Integer templateVersion,
      String policyPackCode,
      String policyPackVersion,
      UUID reopenRequestId) {}

  /** Thân yêu cầu tạo biểu mẫu nháp (suy luận). */
  record DraftRequest(UUID actor, String formCode, UUID periodId, List<UUID> profileIds) {}

  /** Thân yêu cầu dữ liệu xem trước (suy luận). */
  record PreviewRequest(UUID actor, String formCode, String sourceModule, UUID sourceRefId) {}

  /**
   * Dữ liệu xem trước (suy luận): {@code data} là tài liệu JSON chuẩn tắc dạng chuỗi, cùng hình
   * dạng với dữ liệu biểu mẫu chính thức.
   */
  record Preview(
      String subjectGroupCode,
      Integer templateVersion,
      String policyPackCode,
      String policyPackVersion,
      String data) {}
}
