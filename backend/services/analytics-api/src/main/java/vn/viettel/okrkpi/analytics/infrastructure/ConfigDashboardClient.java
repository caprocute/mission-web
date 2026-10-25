package vn.viettel.okrkpi.analytics.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.analytics.application.AnalyticsErrors;
import vn.viettel.okrkpi.analytics.application.DashboardStorePort;
import vn.viettel.okrkpi.analytics.domain.Dashboard;
import vn.viettel.okrkpi.analytics.domain.DashboardDraft;
import vn.viettel.okrkpi.analytics.domain.DashboardPage;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Cổng dịch vụ {@code /api/v1/config/dashboard-defs} của MOD-08 (CORE-08-17, {@code config.yaml}):
 * token client dịch vụ, tenant ở tham số {@code tenant}, người thực hiện ở {@code X-Acting-User},
 * đơn vị của người xem ở {@code viewerUnit}; {@code Idempotency-Key} và {@code If-Match} chuyển
 * nguyên. Lỗi của MOD-08 đổi sang mã của SVC-15: trùng tên thành {@code
 * ANALYTICS-409-DUPLICATE-TITLE}, lệch phiên bản thành 409, thiếu {@code If-Match} thành 428, lỗi
 * trường giữ nguyên danh sách {@code errors}.
 */
final class ConfigDashboardClient implements DashboardStorePort {

  static final String DUPLICATE_TITLE = "CONFIG-409-DUPLICATE-TITLE";
  private static final String PATH = "/api/v1/config/dashboard-defs";

  private final ResilientClient http;
  private final ServiceToken token;
  private final ObjectMapper json;

  ConfigDashboardClient(ResilientClient http, ServiceToken token, ObjectMapper json) {
    this.http = http;
    this.token = token;
    this.json = json;
  }

  @Override
  public DashboardPage list(
      Viewer viewer, List<UUID> viewerUnits, boolean mine, int limit, String cursor) {
    List<Object> vars = new ArrayList<>();
    StringBuilder uri = new StringBuilder(PATH).append("?tenant={tenant}&limit={limit}");
    vars.add(viewer.tenant());
    vars.add(limit);
    if (mine) {
      uri.append("&owner=me");
    }
    if (cursor != null) {
      uri.append("&cursor={cursor}");
      vars.add(cursor);
    }
    units(uri, vars, viewerUnits);
    return read(
        call(HttpMethod.GET, uri.toString(), null, headers(viewer, null, null), vars),
        DashboardPage.class);
  }

  @Override
  public Dashboard create(Viewer viewer, DashboardDraft draft, String idempotencyKey) {
    return read(
        call(
            HttpMethod.POST,
            PATH + "?tenant={tenant}",
            draft,
            headers(viewer, idempotencyKey, null),
            List.of(viewer.tenant())),
        Dashboard.class);
  }

  @Override
  public Dashboard get(Viewer viewer, List<UUID> viewerUnits, UUID id) {
    List<Object> vars = new ArrayList<>(List.of(id, viewer.tenant()));
    StringBuilder uri = new StringBuilder(PATH).append("/{id}?tenant={tenant}");
    units(uri, vars, viewerUnits);
    return read(
        call(HttpMethod.GET, uri.toString(), null, headers(viewer, null, null), vars),
        Dashboard.class);
  }

  @Override
  public Dashboard replace(
      Viewer viewer, UUID id, DashboardDraft draft, String idempotencyKey, String ifMatch) {
    return read(
        call(
            HttpMethod.PUT,
            PATH + "/{id}?tenant={tenant}",
            draft,
            headers(viewer, idempotencyKey, ifMatch),
            List.of(id, viewer.tenant())),
        Dashboard.class);
  }

  private static void units(StringBuilder uri, List<Object> vars, List<UUID> units) {
    for (int i = 0; i < units.size(); i++) {
      uri.append("&viewerUnit={u").append(i).append('}');
      vars.add(units.get(i));
    }
  }

  private HttpHeaders headers(Viewer viewer, String idempotencyKey, String ifMatch) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token.bearer());
    h.setAccept(List.of(MediaType.APPLICATION_JSON));
    h.set("X-Acting-User", viewer.userId().toString());
    // Token client credentials không mang claim tenant: mission-core đọc tenant đích ở X-Tenant-Id
    // khi analytics-api có trong okrkpi.tenant.delegating-clients.
    h.set("X-Tenant-Id", viewer.tenant().toString());
    if (idempotencyKey != null) {
      h.set(ResilientClient.IDEMPOTENCY_KEY, idempotencyKey);
    }
    if (ifMatch != null) {
      h.set(HttpHeaders.IF_MATCH, ifMatch);
    }
    return h;
  }

  private JsonNode call(
      HttpMethod method, String uri, Object body, HttpHeaders headers, List<Object> vars) {
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    try {
      return http.exchange(method, uri, body, headers, JsonNode.class, vars.toArray());
    } catch (RestClientResponseException e) {
      throw translate(e);
    }
  }

  private RuntimeException translate(RestClientResponseException e) {
    JsonNode problem;
    try {
      problem = json.readTree(e.getResponseBodyAsByteArray());
    } catch (IOException ex) {
      problem = json.createObjectNode();
    }
    String code = problem == null ? null : problem.path("code").asText(null);
    String detail = problem == null ? null : problem.path("detail").asText(null);
    return switch (e.getStatusCode().value()) {
      case 403 -> AnalyticsErrors.outOfScope(detail);
      case 404 -> new ErrorResponseException(HttpStatus.NOT_FOUND);
      case 409 ->
          DUPLICATE_TITLE.equals(code)
              ? new BusinessException(AnalyticsErrors.DUPLICATE_TITLE, detail)
              : new VersionConflictException("ANALYTICS", null, null);
      case 428 -> new PreconditionRequiredException();
      case 400, 422 ->
          new BusinessException(AnalyticsErrors.VALIDATION, detail, fieldErrors(problem));
      default -> e;
    };
  }

  private static List<ProblemFieldError> fieldErrors(JsonNode problem) {
    List<ProblemFieldError> out = new ArrayList<>();
    if (problem != null) {
      for (JsonNode f : problem.path("errors")) {
        out.add(ProblemFieldError.of(f.path("field").asText("body"), f.path("message").asText("")));
      }
    }
    return out;
  }

  private <T> T read(JsonNode node, Class<T> type) {
    try {
      return json.treeToValue(node, type);
    } catch (IOException e) {
      throw new IllegalStateException("Phản hồi MOD-08 không đúng hợp đồng", e);
    }
  }
}
