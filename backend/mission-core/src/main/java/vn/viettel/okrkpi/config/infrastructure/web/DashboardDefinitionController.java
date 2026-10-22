package vn.viettel.okrkpi.config.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.api.DashboardDefinitionApi.DashboardDefinitionView;
import vn.viettel.okrkpi.config.application.DashboardDefinitionService;
import vn.viettel.okrkpi.config.domain.DashboardDefinition;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Thao tác dịch vụ định nghĩa dashboard của {@code config.yaml} (CORE-08-17): {@code
 * listDashboardDefs}, {@code createDashboardDef}, {@code getDashboardDef}, {@code
 * replaceDashboardDef}. Chỉ client dịch vụ analytics-api (SVC-15) gọi, cùng cách {@code
 * resolvePolicyPack}: tenant ở tham số {@code tenant} (OPA kiểm khớp token), người thực hiện ở
 * header {@code X-Acting-User}. Thân yêu cầu đọc dạng cây JSON để lỗi trả 422 theo trường; phản hồi
 * dựng dạng cây vì bộ sinh mã không giữ được giá trị lọc kiểu chuỗi hoặc số của widget.
 */
@RestController
@RequestMapping("/api/v1/config/dashboard-defs")
public class DashboardDefinitionController {

  private static final String BASE = "/api/v1/config/dashboard-defs";
  private static final String SCOPE = "dashboard-defs";
  private static final int MAX_VIEWER_UNITS = 64;

  private final DashboardDefinitionService dashboards;
  private final ConfigIdempotency idempotency;
  private final CursorCodec cursors;
  private final ObjectMapper json;

  public DashboardDefinitionController(
      DashboardDefinitionService dashboards,
      ConfigIdempotency idempotency,
      CursorCodec cursors,
      ObjectProvider<ObjectMapper> json) {
    this.dashboards = Objects.requireNonNull(dashboards, "dashboards");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.json = json.getIfAvailable(ObjectMapper::new);
  }

  @GetMapping
  @RequiresPermission(operationId = "listDashboardDefs", module = "config")
  public JsonNode listDashboardDefs(
      @RequestParam("tenant") UUID tenant,
      @RequestHeader("X-Acting-User") UUID actingUser,
      @RequestParam(value = "viewerUnit", required = false) List<UUID> viewerUnit,
      @RequestParam(value = "owner", required = false) String owner,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    if (owner != null && !owner.equals("me")) {
      throw invalid("owner", "Chỉ nhận giá trị me.");
    }
    List<UUID> units = units(viewerUnit);
    PageRequest page = cursors.pageRequest(limit, cursor, SCOPE);
    TenantId tenantId = TenantId.of(tenant);
    List<DashboardDefinition> fetched =
        inTenant(
            tenantId,
            () ->
                dashboards.visible(
                    tenantId,
                    actingUser,
                    units,
                    owner != null,
                    page.afterKey().map(k -> k.getUuid(0)).orElse(null),
                    page.fetchSize()));
    CursorPage<DashboardDefinition> result = cursors.page(fetched, page, d -> CursorKey.of(d.id()));
    ObjectNode body = json.createObjectNode();
    ArrayNode items = body.putArray("items");
    for (DashboardDefinition d : result.items()) {
      ObjectNode s = items.addObject();
      s.put("id", d.id().toString());
      s.put("title", d.title());
      s.put("ownerUserId", d.ownerUserId().toString());
      s.put("shareScope", d.shareScope().name());
      JsonNode widgets = dashboards.layout(d).path("widgets");
      s.put("widgetCount", widgets.size());
      s.put("updatedAt", time(d.updatedAt()));
    }
    if (result.nextCursor() == null) {
      body.putNull("nextCursor");
    } else {
      body.put("nextCursor", result.nextCursor());
    }
    return body;
  }

  @PostMapping
  @RequiresPermission(operationId = "createDashboardDef", module = "config")
  public ResponseEntity<JsonNode> createDashboardDef(
      @RequestParam("tenant") UUID tenant,
      @RequestHeader("X-Acting-User") UUID actingUser,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    TenantId tenantId = TenantId.of(tenant);
    return inTenant(
        tenantId,
        () ->
            idempotency.run(
                tenantId,
                actingUser,
                idempotencyKey,
                "POST",
                BASE,
                body,
                JsonNode.class,
                DashboardDefinitionController::rowVersion,
                () -> {
                  DashboardDefinition created = dashboards.create(tenantId, actingUser, body);
                  return ResponseEntity.created(URI.create(BASE + "/" + created.id()))
                      .eTag(ETags.of(created.rowVersion()))
                      .body(toDto(dashboards.view(created)));
                }));
  }

  @GetMapping("/{id}")
  @RequiresPermission(operationId = "getDashboardDef", module = "config")
  public ResponseEntity<JsonNode> getDashboardDef(
      @PathVariable("id") UUID id,
      @RequestParam("tenant") UUID tenant,
      @RequestHeader("X-Acting-User") UUID actingUser,
      @RequestParam(value = "viewerUnit", required = false) List<UUID> viewerUnit) {
    List<UUID> units = units(viewerUnit);
    TenantId tenantId = TenantId.of(tenant);
    DashboardDefinitionView view =
        inTenant(tenantId, () -> dashboards.find(tenantId, actingUser, units, id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ETags.ok(toDto(view), view.rowVersion());
  }

  @PutMapping("/{id}")
  @RequiresPermission(operationId = "replaceDashboardDef", module = "config")
  public ResponseEntity<JsonNode> replaceDashboardDef(
      @PathVariable("id") UUID id,
      @RequestParam("tenant") UUID tenant,
      @RequestHeader("X-Acting-User") UUID actingUser,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body) {
    TenantId tenantId = TenantId.of(tenant);
    Long expected =
        ifMatch == null || ifMatch.isBlank() ? null : ETags.expectedVersion(ifMatch, "CONFIG");
    return inTenant(
        tenantId,
        () ->
            idempotency.run(
                tenantId,
                actingUser,
                idempotencyKey,
                "PUT",
                BASE + "/" + id,
                body,
                JsonNode.class,
                DashboardDefinitionController::rowVersion,
                () -> {
                  try {
                    DashboardDefinition saved =
                        dashboards.replace(tenantId, actingUser, id, expected, body);
                    return ETags.ok(toDto(dashboards.view(saved)), saved.rowVersion());
                  } catch (NoSuchElementException e) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                  }
                }));
  }

  private JsonNode toDto(DashboardDefinitionView v) {
    ObjectNode o = json.createObjectNode();
    o.put("id", v.id().toString());
    o.put("title", v.title());
    o.put("ownerUserId", v.ownerUserId().toString());
    ArrayNode measures = o.putArray("measures");
    v.measures().forEach(measures::add);
    ArrayNode dimensions = o.putArray("dimensions");
    v.dimensions().forEach(dimensions::add);
    JsonNode widgets = v.layout().path("widgets");
    o.set("layout", widgets.isArray() ? widgets : json.createArrayNode());
    o.put("shareScope", v.shareScope());
    ArrayNode units = o.putArray("sharedUnitIds");
    v.sharedUnitIds().forEach(u -> units.add(u.toString()));
    JsonNode semantic = v.layout().get("semanticModelVersion");
    if (semantic != null && semantic.isTextual()) {
      o.put("semanticModelVersion", semantic.asText());
    }
    o.put("rowVersion", v.rowVersion());
    o.put("updatedAt", time(v.updatedAt()));
    return o;
  }

  private static long rowVersion(JsonNode body) {
    return body.path("rowVersion").asLong();
  }

  private static List<UUID> units(List<UUID> viewerUnit) {
    List<UUID> units = viewerUnit == null ? List.of() : List.copyOf(viewerUnit);
    if (units.size() > MAX_VIEWER_UNITS) {
      throw invalid("viewerUnit", "Tối đa " + MAX_VIEWER_UNITS + " đơn vị.");
    }
    return units;
  }

  private static String time(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC).toString();
  }

  private static <T> T inTenant(TenantId tenantId, Callable<T> work) {
    try {
      return TenantContext.call(tenantId, work);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
