package vn.viettel.okrkpi.config.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
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
import vn.viettel.okrkpi.config.application.UiPackService;
import vn.viettel.okrkpi.config.application.port.UiPackChecker;
import vn.viettel.okrkpi.config.domain.UiPackContent;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.config.domain.UiPackVersionStatus;
import vn.viettel.okrkpi.contract.config.model.PolicyPackVersionRef;
import vn.viettel.okrkpi.contract.config.model.SduiNode;
import vn.viettel.okrkpi.contract.config.model.UiPackAsset;
import vn.viettel.okrkpi.contract.config.model.UiPackCheck;
import vn.viettel.okrkpi.contract.config.model.UiPackPage;
import vn.viettel.okrkpi.contract.config.model.UiPackPreview;
import vn.viettel.okrkpi.contract.config.model.UiPackRejectInput;
import vn.viettel.okrkpi.contract.config.model.UiPackSummary;
import vn.viettel.okrkpi.contract.config.model.UiPackVersionCreate;
import vn.viettel.okrkpi.contract.config.model.UiPackVersionPage;
import vn.viettel.okrkpi.contract.config.model.UiPackVersionUpdate;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * UI Pack và phiên bản của {@code config.yaml} (CORE-08-08, QT-18, SCR-1801): {@code listUiPacks},
 * {@code listUiPackVersions}, {@code createUiPackVersion}, {@code getUiPackVersion}, {@code
 * updateUiPackVersion}, {@code previewUiPackVersion}, {@code submitUiPackVersion}, {@code
 * rejectUiPackVersion}, {@code activateUiPackVersion} (CORE-08-12).
 */
@RestController
@RequestMapping("/api/v1/ui/packs")
public class UiPackController {

  private static final String BASE = "/api/v1/ui/packs";

  private final UiPackService packs;
  private final vn.viettel.okrkpi.config.application.UiPackPublishService publish;
  private final CursorCodec cursors;
  private final Callers callers;
  private final ConfigIdempotency idempotency;
  private final ObjectMapper json;

  public UiPackController(
      UiPackService packs,
      vn.viettel.okrkpi.config.application.UiPackPublishService publish,
      CursorCodec cursors,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ConfigIdempotency idempotency,
      ObjectProvider<ObjectMapper> json) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.publish = Objects.requireNonNull(publish, "publish");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.callers = new Callers(subjects, periodCallers);
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.json = json.getIfAvailable(ObjectMapper::new);
  }

  @GetMapping
  @RequiresPermission(operationId = "listUiPacks", module = "config")
  public UiPackPage listUiPacks(
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    PageRequest page = cursors.pageRequest(limit, cursor, "ui-packs");
    List<UiPackService.PackSummary> fetched =
        packs.listPacks(
            TenantContext.require(),
            callers.current(),
            page.afterKey().map(k -> k.getUuid(0)).orElse(null),
            page.fetchSize());
    CursorPage<UiPackService.PackSummary> result =
        cursors.page(fetched, page, p -> CursorKey.of(p.pack().id()));
    return new UiPackPage(
        result.items().stream().map(UiPackController::toDto).toList(), result.nextCursor());
  }

  @GetMapping("/{id}/versions")
  @RequiresPermission(operationId = "listUiPackVersions", module = "config")
  public UiPackVersionPage listUiPackVersions(
      @PathVariable("id") UUID id,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    PageRequest page = cursors.pageRequest(limit, cursor, "ui-pack-versions:" + id);
    UiPackVersionStatus s = status == null ? null : statusOf(status);
    List<UiPackVersion> fetched =
        notFound(
            () ->
                packs.listVersions(
                    TenantContext.require(),
                    id,
                    s,
                    page.afterKey().map(k -> k.getUuid(0)).orElse(null),
                    page.fetchSize()));
    CursorPage<UiPackVersion> result = cursors.page(fetched, page, v -> CursorKey.of(v.id()));
    return new UiPackVersionPage(
        result.items().stream().map(v -> toDto(v, null)).toList(), result.nextCursor());
  }

  @PostMapping("/{id}/versions")
  @RequiresPermission(
      action = "SCR-1801.soan",
      operationId = "createUiPackVersion",
      module = "config")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.UiPackVersion> createUiPackVersion(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody UiPackVersionCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    String path = BASE + "/" + id + "/versions";
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        path,
        body,
        vn.viettel.okrkpi.contract.config.model.UiPackVersion.class,
        vn.viettel.okrkpi.contract.config.model.UiPackVersion::getRowVersion,
        () -> {
          UiPackVersion v =
              notFound(
                  () ->
                      packs.create(
                          tenant, actor, id, body.getVersion(), body.getCloneFromVersion()));
          return ResponseEntity.created(URI.create(path + "/" + v.version()))
              .eTag(ETags.of(v.rowVersion()))
              .body(toDto(v, null));
        });
  }

  @GetMapping("/{id}/versions/{v}")
  @RequiresPermission(operationId = "getUiPackVersion", module = "config")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.UiPackVersion> getUiPackVersion(
      @PathVariable("id") UUID id, @PathVariable("v") String version) {
    UiPackVersion v = notFound(() -> packs.get(TenantContext.require(), id, version));
    return ETags.ok(toDto(v, null), v.rowVersion());
  }

  @PutMapping("/{id}/versions/{v}")
  @RequiresPermission(
      action = "SCR-1801.soan",
      operationId = "updateUiPackVersion",
      module = "config")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.UiPackVersion> updateUiPackVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody UiPackVersionUpdate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        BASE + "/" + id + "/versions/" + version,
        body,
        vn.viettel.okrkpi.contract.config.model.UiPackVersion.class,
        vn.viettel.okrkpi.contract.config.model.UiPackVersion::getRowVersion,
        () -> {
          UiPackContent content =
              packs.content(
                  json.valueToTree(body.getTokens()),
                  withoutNulls(json.valueToTree(body.getLayouts())),
                  (ArrayNode)
                      withoutNulls(
                          json.valueToTree(
                              body.getAssets() == null ? List.of() : body.getAssets())),
                  body.getComponentRegistryVersion(),
                  body.getPolicyPackVersionId(),
                  Boolean.TRUE.equals(body.getElderMode()),
                  Boolean.TRUE.equals(body.getFewSteps()));
          UiPackVersion v =
              notFound(() -> packs.update(tenant, actor, id, version, expected, content));
          return ETags.ok(toDto(v, null), v.rowVersion());
        });
  }

  @GetMapping("/{id}/versions/{v}/preview")
  @RequiresPermission(operationId = "previewUiPackVersion", module = "config")
  public UiPackPreview previewUiPackVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestParam(value = "mode", required = false, defaultValue = "default") String mode) {
    if (!mode.equals("default") && !mode.equals("elderly")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode");
    }
    UiPackService.Preview p =
        notFound(() -> packs.preview(TenantContext.require(), id, version, mode.equals("elderly")));
    UiPackPreview dto =
        new UiPackPreview(
            p.version(),
            UiPackPreview.ModeEnum.fromValue(p.mode()),
            new HashMap<>(p.cssVariables()),
            layouts(p.layouts()),
            p.checks().stream().map(UiPackController::toDto).toList());
    dto.setBundleSizeGzipBytes(p.bundleSizeGzipBytes());
    return dto;
  }

  @PostMapping("/{id}/versions/{v}/submit")
  @RequiresPermission(
      action = "SCR-1801.gui-duyet",
      operationId = "submitUiPackVersion",
      module = "config")
  public ResponseEntity<Job> submitUiPackVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + id + "/versions/" + version + "/submit",
        null,
        Job.class,
        j -> 0,
        () -> {
          String jobId = notFound(() -> packs.submit(tenant, actor, id, version, expected));
          return Jobs.accepted(
              Job.accepted(jobId, "TEST_RUN", java.time.OffsetDateTime.now(ZoneOffset.UTC)));
        });
  }

  @PostMapping("/{id}/versions/{v}/reject")
  @RequiresPermission(
      action = "SCR-1801.duyet",
      operationId = "rejectUiPackVersion",
      module = "config")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.UiPackVersion> rejectUiPackVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody UiPackRejectInput body) {
    TenantId tenant = TenantContext.require();
    vn.viettel.okrkpi.config.application.Actor actor = callers.actor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        BASE + "/" + id + "/versions/" + version + "/reject",
        body,
        vn.viettel.okrkpi.contract.config.model.UiPackVersion.class,
        vn.viettel.okrkpi.contract.config.model.UiPackVersion::getRowVersion,
        () -> {
          UiPackService.Rejected r =
              notFound(() -> packs.reject(tenant, actor, id, version, expected, body.getComment()));
          return ETags.ok(toDto(r.version(), r.comment()), r.version().rowVersion());
        });
  }

  /** Quay lui về phiên bản {@code THAY_THE} (CORE-08-12, BR-18-10, MSG-18-04). */
  @PostMapping("/{id}/versions/{v}/activate")
  @RequiresPermission(
      action = "SCR-1801.quay-lui",
      operationId = "activateUiPackVersion",
      module = "config")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.UiPackVersion>
      activateUiPackVersion(
          @PathVariable("id") UUID id,
          @PathVariable("v") String version,
          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
          @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        BASE + "/" + id + "/versions/" + version + "/activate",
        null,
        vn.viettel.okrkpi.contract.config.model.UiPackVersion.class,
        vn.viettel.okrkpi.contract.config.model.UiPackVersion::getRowVersion,
        () -> {
          UiPackVersion v = notFound(() -> publish.activate(tenant, actor, id, version, expected));
          return ETags.ok(toDto(v, null), v.rowVersion());
        });
  }

  /**
   * Bỏ các trường {@code null} mà DTO sinh từ hợp đồng thêm vào (ví dụ {@code visibleWhen} của nút
   * SDUI không có điều kiện), để nội dung lưu chỉ có các trường người soạn gửi.
   */
  static JsonNode withoutNulls(JsonNode node) {
    if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode o) {
      List<String> nulls = new java.util.ArrayList<>();
      o.fields()
          .forEachRemaining(
              e -> {
                if (e.getValue().isNull()) {
                  nulls.add(e.getKey());
                } else {
                  withoutNulls(e.getValue());
                }
              });
      o.remove(nulls);
    } else if (node.isArray()) {
      node.forEach(UiPackController::withoutNulls);
    }
    return node;
  }

  private static UiPackVersionStatus statusOf(String s) {
    try {
      return UiPackVersionStatus.valueOf(s);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status");
    }
  }

  private static <T> T notFound(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static UiPackSummary toDto(UiPackService.PackSummary s) {
    UiPackSummary dto = new UiPackSummary(s.pack().id(), s.pack().code(), s.pack().name());
    dto.setCurrentVersionId(s.pack().currentVersionId());
    dto.setCurrentVersion(s.currentVersion());
    dto.setDraftVersion(s.draftVersion());
    return dto;
  }

  private vn.viettel.okrkpi.contract.config.model.UiPackVersion toDto(
      UiPackVersion v, String reviewComment) {
    UiPackContent c = v.content();
    vn.viettel.okrkpi.contract.config.model.UiPackVersion dto =
        new vn.viettel.okrkpi.contract.config.model.UiPackVersion(
            v.id(),
            v.uiPackId(),
            v.version(),
            vn.viettel.okrkpi.contract.config.model.UiPackVersionStatus.fromValue(
                v.status().name()),
            map(packs.read(c.tokensJson())),
            layouts(packs.read(c.layoutsJson())),
            c.componentRegistryVersion(),
            c.elderMode(),
            c.fewSteps(),
            v.rowVersion());
    dto.setAssets(assets(packs.read(c.assetsJson())));
    dto.setForms(List.of());
    if (c.policyPackVersionId() != null) {
      dto.setPolicyPack(new PolicyPackVersionRef(c.policyPackVersionId()));
    }
    dto.setBundleSha256(v.bundleSha256());
    dto.setPublishedAt(v.publishedAt() == null ? null : v.publishedAt().atOffset(ZoneOffset.UTC));
    dto.setReviewComment(reviewComment);
    dto.setUpdatedAt(v.updatedAt() == null ? null : v.updatedAt().atOffset(ZoneOffset.UTC));
    return dto;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(JsonNode node) {
    return json.convertValue(node, LinkedHashMap.class);
  }

  private Map<String, SduiNode> layouts(JsonNode node) {
    Map<String, SduiNode> out = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            e -> out.put(e.getKey(), json.convertValue(e.getValue(), SduiNode.class)));
    return out;
  }

  private List<UiPackAsset> assets(JsonNode node) {
    return node.isArray()
        ? json.convertValue(
            node, json.getTypeFactory().constructCollectionType(List.class, UiPackAsset.class))
        : List.of();
  }

  private static UiPackCheck toDto(UiPackChecker.Result r) {
    UiPackCheck dto = new UiPackCheck(UiPackCheck.CodeEnum.fromValue(r.code().name()), r.passed());
    dto.setMessage(r.message());
    dto.setMsg(r.msg());
    return dto;
  }
}
