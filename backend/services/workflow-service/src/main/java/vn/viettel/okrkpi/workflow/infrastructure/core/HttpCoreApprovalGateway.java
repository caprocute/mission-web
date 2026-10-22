package vn.viettel.okrkpi.workflow.infrastructure.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway;
import vn.viettel.okrkpi.workflow.domain.Decision;

/**
 * {@link CoreApprovalGateway} gọi API công bố của mission-core theo hợp đồng ({@code okr.yaml},
 * {@code tasks.yaml}): {@code approveOkrObjective}, {@code returnOkrObjective}, {@code
 * approvePlanVersion}, {@code returnPlanVersion}, {@code decideTaskChangeRequest}. Mọi lời gọi kèm
 * {@code Idempotency-Key}; thao tác có {@code If-Match} trong hợp đồng gửi phiên bản đối tượng lúc
 * trình (đối tượng bị khoá ở {@code CHO_DUYET} trong suốt luồng duyệt).
 *
 * <p>Token dịch vụ đọc từ tệp do ESO/CSI gắn (không đặt bí mật trong cấu hình); thiếu tệp thì gọi
 * không kèm token (chỉ đúng ở môi trường dev).
 */
@Component
public class HttpCoreApprovalGateway implements CoreApprovalGateway {

  private static final Logger log = LoggerFactory.getLogger(HttpCoreApprovalGateway.class);

  private final RestClient http;
  private final String tokenFile;

  public HttpCoreApprovalGateway(
      RestClient.Builder builder,
      @Value("${mission.workflow.core.base-url:http://127.0.0.1:8080/api/v1}") String baseUrl,
      @Value("${mission.workflow.core.token-file:}") String tokenFile) {
    this.http = builder.baseUrl(baseUrl).build();
    this.tokenFile = tokenFile;
  }

  @Override
  public Outcome apply(Request r) {
    Call call = call(r);
    try {
      RestClient.RequestBodySpec spec =
          http.post()
              .uri(call.path(), call.pathVariables().toArray())
              .contentType(MediaType.APPLICATION_JSON)
              .header("Idempotency-Key", r.idempotencyKey().toString());
      if (call.ifMatch()) {
        spec = spec.header(HttpHeaders.IF_MATCH, "\"" + r.objectVersion() + "\"");
      }
      String token = token();
      if (token != null) {
        spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
      }
      spec.body(call.body()).retrieve().toBodilessEntity();
      return Outcome.APPLIED;
    } catch (RestClientResponseException e) {
      return classify(e.getStatusCode(), r);
    } catch (RestClientException e) {
      log.warn("Không gọi được lõi để áp kết quả mẫu {}", r.template().code());
      return Outcome.RETRY;
    }
  }

  static Outcome classify(HttpStatusCode status, Request r) {
    if (status.is5xxServerError() || status.value() == 429 || status.value() == 408) {
      return Outcome.RETRY;
    }
    log.warn("Lõi từ chối áp kết quả mẫu {} với mã {}", r.template().code(), status.value());
    return Outcome.REJECTED;
  }

  /** Ánh xạ mẫu và quyết định sang thao tác của hợp đồng. */
  static Call call(Request r) {
    boolean approve = r.decision() == Decision.DUYET;
    Map<String, Object> body = new LinkedHashMap<>();
    return switch (r.template()) {
      case DUYET_OKR -> {
        if (r.comment() != null) {
          body.put("comment", r.comment());
        }
        yield new Call(
            approve ? "/okr/objectives/{id}/approve" : "/okr/objectives/{id}/return",
            List.<Object>of(r.businessId()),
            true,
            body);
      }
      case DUYET_KE_HOACH_QUY -> {
        if (approve) {
          body.put("signMethod", r.signMethod());
        }
        if (r.comment() != null) {
          body.put("comment", r.comment());
        }
        yield new Call(
            approve ? "/plans/{id}/versions/{v}/approve" : "/plans/{id}/versions/{v}/return",
            List.<Object>of(r.businessId(), r.planVersionNo()),
            true,
            body);
      }
      case DUYET_DE_NGHI -> {
        body.put("approve", approve);
        body.put("causeDecided", r.cause());
        if (r.comment() != null) {
          body.put("note", r.comment());
        }
        yield new Call(
            "/tasks/{id}/change-requests/{requestId}/decision",
            List.<Object>of(r.taskId(), r.businessId()),
            false,
            body);
      }
    };
  }

  private String token() {
    if (tokenFile == null || tokenFile.isBlank()) {
      return null;
    }
    try {
      return Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      log.warn("Không đọc được tệp token dịch vụ để gọi lõi");
      return null;
    }
  }

  /**
   * Thao tác HTTP đã ánh xạ.
   *
   * @param path đường dẫn tương đối của hợp đồng
   * @param pathVariables giá trị biến đường dẫn
   * @param ifMatch hợp đồng yêu cầu {@code If-Match}
   * @param body thân yêu cầu
   */
  record Call(String path, List<Object> pathVariables, boolean ifMatch, Map<String, Object> body) {}
}
