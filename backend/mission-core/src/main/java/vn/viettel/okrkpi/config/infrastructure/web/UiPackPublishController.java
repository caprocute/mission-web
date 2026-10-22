package vn.viettel.okrkpi.config.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.application.UiPackPublishService;
import vn.viettel.okrkpi.contract.config.model.UiPackReviewInput;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Phát hành và phục vụ UI Pack (CORE-08-11, QT-18, QT-00, SCR-1801, SCR-0002): {@code
 * publishUiPackVersion}, {@code getCurrentUiPack}, {@code getHomeLayout} của {@code config.yaml}.
 */
@RestController
@RequestMapping("/api/v1/ui")
public class UiPackPublishController {

  private final UiPackPublishService publish;
  private final Callers callers;
  private final ConfigIdempotency idempotency;
  private final ObjectMapper json;

  public UiPackPublishController(
      UiPackPublishService publish,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ConfigIdempotency idempotency,
      ObjectProvider<ObjectMapper> json) {
    this.publish = Objects.requireNonNull(publish, "publish");
    this.callers = new Callers(subjects, periodCallers);
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.json = json.getIfAvailable(ObjectMapper::new);
  }

  @PostMapping("/packs/{id}/versions/{v}/publish")
  @RequiresPermission(
      action = "SCR-1801.duyet",
      operationId = "publishUiPackVersion",
      module = "config")
  public ResponseEntity<Job> publishUiPackVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) UiPackReviewInput body) {
    TenantId tenant = TenantContext.require();
    vn.viettel.okrkpi.config.application.Actor actor = callers.actor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        "/api/v1/ui/packs/" + id + "/versions/" + version + "/publish",
        body,
        Job.class,
        j -> 0,
        () -> {
          String jobId = notFound(() -> publish.publish(tenant, actor, id, version, expected));
          return Jobs.accepted(Job.accepted(jobId, "RENDER", OffsetDateTime.now(ZoneOffset.UTC)));
        });
  }

  @GetMapping("/packs/current")
  @RequiresPermission(operationId = "getCurrentUiPack", module = "config")
  public ResponseEntity<JsonNode> getCurrentUiPack(
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    UiPackPublishService.Current current =
        publish
            .current(TenantContext.require())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return conditional(current.etag(), ifNoneMatch, current.body());
  }

  @GetMapping("/layouts/home")
  @RequiresPermission(operationId = "getHomeLayout", module = "config")
  public ResponseEntity<JsonNode> getHomeLayout(
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    UiPackPublishService.Home home =
        publish
            .home(TenantContext.require(), callers.viewer())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    var body = json.createObjectNode();
    body.put("uiPackVersion", home.uiPackVersion());
    body.put("registryVersion", home.registryVersion());
    body.put("mode", home.mode());
    body.put("reducedSteps", home.reducedSteps());
    body.set("layout", home.layout());
    return conditional(home.packEtag() + "-" + digest(body.toString()), ifNoneMatch, body);
  }

  /** ETag mạnh theo băm; {@code If-None-Match} khớp hoặc {@code *} thì 304. */
  static ResponseEntity<JsonNode> conditional(String tag, String ifNoneMatch, JsonNode body) {
    String etag = "\"" + tag + "\"";
    if (ifNoneMatch != null) {
      for (String candidate : ifNoneMatch.split(",")) {
        String c = candidate.strip();
        if (c.startsWith("W/")) {
          c = c.substring(2);
        }
        if (c.equals("*") || c.equals(etag)) {
          return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
              .header(HttpHeaders.ETAG, etag)
              .build();
        }
      }
    }
    return ResponseEntity.ok().header(HttpHeaders.ETAG, etag).body(body);
  }

  private static String digest(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> T notFound(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
