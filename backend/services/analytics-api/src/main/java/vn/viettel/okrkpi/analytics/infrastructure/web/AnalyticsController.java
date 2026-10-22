package vn.viettel.okrkpi.analytics.infrastructure.web;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import vn.viettel.okrkpi.analytics.application.DashboardService;
import vn.viettel.okrkpi.analytics.application.EmbedService;
import vn.viettel.okrkpi.analytics.application.QueryService;
import vn.viettel.okrkpi.analytics.application.ScopeService;
import vn.viettel.okrkpi.analytics.application.SemanticModelService;
import vn.viettel.okrkpi.analytics.domain.Dashboard;
import vn.viettel.okrkpi.analytics.domain.EmbedToken;
import vn.viettel.okrkpi.analytics.domain.QueryResult;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.analytics.infrastructure.web.WebDtos.DashboardBody;
import vn.viettel.okrkpi.analytics.infrastructure.web.WebDtos.DashboardInputBody;
import vn.viettel.okrkpi.analytics.infrastructure.web.WebDtos.EmbedTokenRequestBody;
import vn.viettel.okrkpi.analytics.infrastructure.web.WebDtos.ModelBody;
import vn.viettel.okrkpi.analytics.infrastructure.web.WebDtos.PageBody;
import vn.viettel.okrkpi.analytics.infrastructure.web.WebDtos.QueryBody;
import vn.viettel.okrkpi.analytics.infrastructure.web.WebDtos.ShareBody;
import vn.viettel.okrkpi.shared.etag.ETags;

/**
 * DV-15-04 · QT-16 → SCR-1601, SCR-1602 → {@code /api/v1/analytics/*} ({@code platform.yaml}) →
 * SVC-15. Mỗi thao tác kiểm vai trò theo hợp đồng rồi mới gọi tầng ứng dụng; tenant, người xem và
 * đơn vị công tác chỉ lấy từ token.
 */
@RestController
@Validated
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

  private final QueryService queries;
  private final SemanticModelService models;
  private final ScopeService scopes;
  private final DashboardService dashboards;
  private final EmbedService embeds;

  public AnalyticsController(
      QueryService queries,
      SemanticModelService models,
      ScopeService scopes,
      DashboardService dashboards,
      EmbedService embeds) {
    this.queries = queries;
    this.models = models;
    this.scopes = scopes;
    this.dashboards = dashboards;
    this.embeds = embeds;
  }

  /**
   * {@code queryAnalytics}: hợp đồng bắt buộc {@code Idempotency-Key} như mọi POST; truy vấn chỉ
   * đọc nên khoá không dùng để chống lặp, kết quả lặp lại đã có bộ đệm.
   */
  @PostMapping("/query")
  public QueryResult query(
      @Valid @RequestBody QueryBody body, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    RoleGate.require("queryAnalytics");
    return queries.query(Viewers.current(), body.toDomain());
  }

  @GetMapping("/semantic-model")
  public ModelBody semanticModel() {
    RoleGate.require("getSemanticModel");
    return ModelBody.of(models.current(scopes.resolve(Viewers.current(), null)));
  }

  @GetMapping("/dashboards")
  public PageBody listDashboards(
      @RequestParam(required = false) String owner,
      @RequestParam(defaultValue = "20")
          @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(200) int limit,
      @RequestParam(required = false) String cursor) {
    RoleGate.require("listDashboards");
    if (owner != null && !"me".equals(owner)) {
      throw new org.springframework.web.ErrorResponseException(HttpStatus.BAD_REQUEST);
    }
    return PageBody.of(dashboards.list(Viewers.current(), owner != null, limit, cursor));
  }

  @PostMapping("/dashboards")
  public ResponseEntity<DashboardBody> createDashboard(
      @Valid @RequestBody DashboardInputBody body,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    RoleGate.require("createDashboard");
    Dashboard d =
        dashboards.create(Viewers.current(), body.title(), body.widgets(), idempotencyKey);
    return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(d.id())
                .toUri())
        .eTag(ETags.of(d.rowVersion()))
        .body(DashboardBody.of(d, null));
  }

  @GetMapping("/dashboards/{id}")
  public ResponseEntity<DashboardBody> getDashboard(
      @PathVariable UUID id,
      @RequestParam(name = "period", required = false) UUID periodId,
      @RequestParam(name = "unit", required = false) UUID unitId,
      @RequestParam(defaultValue = "true") boolean includeData) {
    RoleGate.require("getDashboard");
    Viewer viewer = Viewers.current();
    DashboardService.View v = dashboards.get(viewer, id, periodId, unitId, includeData);
    return ResponseEntity.ok()
        .eTag(ETags.of(v.dashboard().rowVersion()))
        .body(DashboardBody.of(v.dashboard(), v.data()));
  }

  @PutMapping("/dashboards/{id}")
  public ResponseEntity<DashboardBody> updateDashboard(
      @PathVariable UUID id,
      @Valid @RequestBody DashboardInputBody body,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
    RoleGate.require("updateDashboard");
    Dashboard d =
        dashboards.update(
            Viewers.current(), id, body.title(), body.widgets(), idempotencyKey, ifMatch);
    return ResponseEntity.ok().eTag(ETags.of(d.rowVersion())).body(DashboardBody.of(d, null));
  }

  @PostMapping("/dashboards/{id}/shares")
  public ResponseEntity<DashboardBody> shareDashboard(
      @PathVariable UUID id,
      @Valid @RequestBody ShareBody body,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
    RoleGate.require("shareDashboard");
    Dashboard d =
        dashboards.share(
            Viewers.current(),
            id,
            body.shareScope(),
            body.sharedUnitIds(),
            idempotencyKey,
            ifMatch);
    return ResponseEntity.ok().eTag(ETags.of(d.rowVersion())).body(DashboardBody.of(d, null));
  }

  /**
   * DV-15-05 {@code createAnalyticsEmbedToken}: guest token Superset sống tối đa 5 phút, phạm vi
   * khoá theo nhánh tổ chức của người xem; mã dashboard chưa đăng ký nhúng trả 404. Mỗi lần gọi cấp
   * token mới nên khoá {@code Idempotency-Key} không dùng để trả lại token cũ.
   */
  @PostMapping("/embed-tokens")
  public ResponseEntity<EmbedToken> createEmbedToken(
      @Valid @RequestBody EmbedTokenRequestBody body,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    RoleGate.require("createAnalyticsEmbedToken");
    EmbedToken token =
        embeds
            .issue(Viewers.current(), body.dashboardCode(), body.unitId())
            .orElseThrow(
                () -> new org.springframework.web.ErrorResponseException(HttpStatus.NOT_FOUND));
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(token);
  }
}
