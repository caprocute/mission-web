package vn.viettel.okrkpi.docgen.infrastructure.render;

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
 * /internal/v1/dossier-form-instances/{id}/render-data} trả tài liệu JSON chuẩn tắc đã băm.
 */
public class HttpRenderInputClient implements RenderInputPort {

  static final String FORMS =
      "/internal/v1/dossier-render-inputs/{packageId}?actor={actor}&formCodes={codes}";
  static final String DATA = "/internal/v1/dossier-form-instances/{id}/render-data";
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
    return Optional.of(
        body.forms().stream()
            .map(
                f ->
                    new FormRef(
                        f.formInstanceId(),
                        f.formCode(),
                        f.subjectGroupCode(),
                        HexFormat.of().parseHex(f.dataSnapshotHash()),
                        f.templateVersion(),
                        f.policyPackCode(),
                        f.policyPackVersion()))
            .toList());
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
      String policyPackVersion) {}
}
