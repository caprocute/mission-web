package vn.viettel.okrkpi.analytics.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.analytics.application.CoreDetailPort;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * API chi tiết của mission-core ({@code tasks.yaml}, {@code products.yaml}, {@code okr.yaml}) gọi
 * bằng token của người xem qua {@link ResilientClient} (GET được thử lại). 403 là mắt xích bị che,
 * 404 là không có; trường nào không cần cho chuỗi thì bỏ, kể cả trường C2 như ý kiến nghiệm thu.
 */
final class MissionCoreDetails implements CoreDetailPort {

  private static final Logger LOG = LoggerFactory.getLogger(MissionCoreDetails.class);

  private final ResilientClient http;

  MissionCoreDetails(ResilientClient http) {
    this.http = http;
  }

  @Override
  public Lookup<Task> task(String bearer, UUID id) {
    return get(bearer, "/api/v1/tasks/" + id, MissionCoreDetails::toTask);
  }

  @Override
  public Lookup<List<Deliverable>> deliverables(String bearer, UUID taskId) {
    return get(
        bearer,
        "/api/v1/products?task=" + taskId + "&limit=200",
        n -> items(n, MissionCoreDetails::toDeliverable));
  }

  @Override
  public Lookup<Deliverable> deliverable(String bearer, UUID id) {
    return get(bearer, "/api/v1/products/" + id, MissionCoreDetails::toDeliverable);
  }

  @Override
  public Lookup<Evidence> evidence(String bearer, UUID id) {
    return get(bearer, "/api/v1/evidences/" + id, MissionCoreDetails::toEvidence);
  }

  @Override
  public Lookup<List<Plan>> plans(String bearer, UUID periodId, UUID unitId) {
    return get(
        bearer,
        "/api/v1/plans?period=" + periodId + "&unit=" + unitId + "&limit=1",
        n -> items(n, MissionCoreDetails::toPlan));
  }

  @Override
  public Lookup<KeyResult> keyResult(String bearer, UUID id) {
    return get(
        bearer,
        "/api/v1/okr/key-results/" + id,
        n ->
            new KeyResult(
                uuid(n, "id"),
                uuid(n, "objectiveId"),
                text(n, "code"),
                text(n, "title"),
                text(n, "status")));
  }

  @Override
  public Lookup<Objective> objective(String bearer, UUID id) {
    return get(
        bearer,
        "/api/v1/okr/objectives/" + id,
        n ->
            new Objective(
                uuid(n, "id"),
                text(n, "code"),
                text(n, "title"),
                text(n, "status"),
                text(n.path("ownerUnit"), "name"),
                items(
                    n.path("bases"),
                    b -> new Basis(uuid(b, "id"), text(b, "docNumber"), text(b, "title")))));
  }

  private <T> Lookup<T> get(String bearer, String uri, Function<JsonNode, T> map) {
    HttpHeaders h = new HttpHeaders();
    h.set(HttpHeaders.AUTHORIZATION, bearer);
    JsonNode body;
    try {
      body = http.exchange(HttpMethod.GET, uri, null, h, JsonNode.class);
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 403) {
        return new Lookup.Denied<>();
      }
      if (status == 404) {
        return new Lookup.Missing<>();
      }
      LOG.warn("mission-core trả mã {} cho mắt xích truy vết", status);
      throw new DependencyUnavailableException(
          "mission-core", DependencyUnavailableException.Reason.IO_ERROR, null, e);
    }
    if (body == null) {
      return new Lookup.Missing<>();
    }
    return new Lookup.Found<>(map.apply(body));
  }

  private static Task toTask(JsonNode n) {
    return new Task(
        uuid(n, "id"),
        text(n, "code"),
        text(n, "title"),
        text(n, "status"),
        text(n, "unitName"),
        uuid(n, "periodId"),
        uuid(n, "keyResultId"),
        n.path("progress").isNumber() ? n.path("progress").asInt() : null);
  }

  private static Deliverable toDeliverable(JsonNode n) {
    return new Deliverable(
        uuid(n, "id"),
        uuid(n, "taskId"),
        text(n, "name"),
        text(n, "unit"),
        decimal(n, "quantityPlanned"),
        decimal(n, "quantityAccepted"),
        text(n, "status"));
  }

  private static Evidence toEvidence(JsonNode n) {
    return new Evidence(
        uuid(n, "id"),
        uuid(n, "deliverableId"),
        uuid(n.path("task"), "id"),
        text(n, "title"),
        text(n, "currentStatus"),
        n.path("currentVersionNo").isInt() ? n.path("currentVersionNo").asInt() : null,
        items(
            n.path("versions"), v -> new Version(v.path("versionNo").asInt(), text(v, "status"))));
  }

  private static Plan toPlan(JsonNode n) {
    JsonNode v = n.path("currentVersion");
    return new Plan(
        uuid(n, "id"),
        text(n, "code"),
        v.path("versionNo").asInt(1),
        text(v, "status"),
        text(n, "unitName"));
  }

  private static <T> List<T> items(JsonNode n, Function<JsonNode, T> map) {
    JsonNode arr = n.has("items") ? n.path("items") : n;
    List<T> out = new ArrayList<>();
    for (JsonNode item : arr) {
      out.add(map.apply(item));
    }
    return out;
  }

  private static String text(JsonNode n, String f) {
    JsonNode v = n.path(f);
    return v.isNull() || v.isMissingNode() ? null : v.asText();
  }

  private static UUID uuid(JsonNode n, String f) {
    String s = text(n, f);
    return s == null || s.isEmpty() ? null : UUID.fromString(s);
  }

  private static BigDecimal decimal(JsonNode n, String f) {
    JsonNode v = n.path(f);
    return v.isNumber() ? v.decimalValue() : null;
  }
}
