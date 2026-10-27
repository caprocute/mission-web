package vn.viettel.okrkpi.workflow.infrastructure.periodclose;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseRequest;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway;

/**
 * MOD-08 của mission-core là bên tham gia thật của saga chốt kỳ (QA-06): kiểm tiền điều kiện, lệnh
 * {@code KHOA_KY}, {@code HOAN_TAT_KY} và bù trừ qua cổng nội bộ {@code
 * /internal/v1/config/periods/{periodId}/close/...} (đường dẫn suy luận, wiki trang 14). Token dịch
 * vụ của client {@code workflow-service}, tenant đích ở {@code X-Tenant-Id}. Lỗi mạng, 5xx và 409
 * ném ra để khung saga thử lại theo chính sách của bước.
 */
public class HttpMod08PeriodCloseGateway implements PeriodCloseGateway {

  static final String TENANT_HEADER = "X-Tenant-Id";

  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  private final RestClient http;
  private final Supplier<String> token;

  public HttpMod08PeriodCloseGateway(RestClient http, Supplier<String> token) {
    this.http = Objects.requireNonNull(http, "http");
    this.token = Objects.requireNonNull(token, "token");
  }

  @Override
  public PreconditionReport checkPreconditions(PeriodCloseRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sagaId", request.sagaId());
    body.put("lockOrderId", request.lockOrderId());
    Map<String, Object> out =
        http.post()
            .uri(base(request) + "/preconditions")
            .headers(h -> headers(h, request))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(MAP);
    return new PreconditionReport(strings(out, "violations"), strings(out, "warnings"));
  }

  @Override
  public void submit(Command command, PeriodCloseRequest request, UUID jobId) {
    requireMod08(command);
    http.put()
        .uri(base(request) + "/jobs/{jobId}", jobId)
        .headers(h -> headers(h, request))
        .contentType(MediaType.APPLICATION_JSON)
        .body(command(command, request, null))
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public JobState state(Command command, PeriodCloseRequest request, UUID jobId) {
    requireMod08(command);
    Map<String, Object> out;
    try {
      out =
          http.get()
              .uri(base(request) + "/jobs/{jobId}?command={command}", jobId, command.name())
              .headers(h -> headers(h, request))
              .retrieve()
              .body(MAP);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return JobState.missing();
      }
      throw e;
    }
    Object status = out == null ? null : out.get("status");
    if ("HOAN_TAT".equals(status)) {
      return JobState.done();
    }
    if ("THAT_BAI".equals(status)) {
      return JobState.failed(String.valueOf(out.get("error")));
    }
    if ("DANG_CHAY".equals(status)) {
      return JobState.running(out.get("progress") instanceof Number n ? n.intValue() : null);
    }
    return JobState.missing();
  }

  @Override
  public void undo(Command command, PeriodCloseRequest request, String undoKey) {
    requireMod08(command);
    http.post()
        .uri(base(request) + "/undo")
        .headers(h -> headers(h, request))
        .contentType(MediaType.APPLICATION_JSON)
        .body(command(command, request, undoKey))
        .retrieve()
        .toBodilessEntity();
  }

  private static void requireMod08(Command command) {
    if (command.participant() != Participant.MOD_08) {
      throw new IllegalArgumentException("Lệnh " + command + " không thuộc MOD-08");
    }
  }

  private static String base(PeriodCloseRequest request) {
    return "/internal/v1/config/periods/" + request.periodId() + "/close";
  }

  private void headers(HttpHeaders h, PeriodCloseRequest request) {
    h.setBearerAuth(token.get());
    h.set(TENANT_HEADER, request.tenantId().toString());
  }

  private static Map<String, Object> command(
      Command command, PeriodCloseRequest request, String undoKey) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("command", command.name());
    body.put("sagaId", request.sagaId());
    body.put("lockOrderId", request.lockOrderId());
    if (undoKey != null) {
      body.put("undoKey", undoKey);
      body.put("reason", "Bù trừ tiến trình khoá kỳ");
    }
    return body;
  }

  private static List<String> strings(Map<String, Object> out, String key) {
    if (out == null || !(out.get(key) instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().map(String::valueOf).toList();
  }
}
