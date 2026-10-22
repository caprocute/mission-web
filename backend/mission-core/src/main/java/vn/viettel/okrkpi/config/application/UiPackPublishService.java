package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelExecutionException;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.config.api.PolicySchemaPort;
import vn.viettel.okrkpi.config.api.UiPackCompatibilityApi;
import vn.viettel.okrkpi.config.application.port.CdnInvalidator;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.UiPackBundleStore;
import vn.viettel.okrkpi.config.application.port.UiPackEvents;
import vn.viettel.okrkpi.config.application.port.UiPackJobs;
import vn.viettel.okrkpi.config.application.port.UiPackRepository;
import vn.viettel.okrkpi.config.application.port.UiPackSigner;
import vn.viettel.okrkpi.config.domain.IllegalUiPackTransitionException;
import vn.viettel.okrkpi.config.domain.UiPack;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.config.domain.UiPackVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Phát hành UI Pack và phục vụ bản hiện hành (CORE-08-11, QT-18, SCR-1801, SCR-0002, ADR-05):
 * {@code publishUiPackVersion} nhận job {@code RENDER}; job biên dịch token, đóng gói {@code
 * pack.json} theo {@code UiPackRuntime}, băm SHA-256 dạng chuẩn tắc, ký bằng khoá nền tảng, đẩy kho
 * đối tượng theo đường dẫn bất biến, rồi trong một giao dịch chuyển {@code DA_PHAT_HANH}, đưa bản
 * cũ sang {@code THAY_THE}, gắn {@code ui_pack.current_version_id} và ghi EVT-23 vào outbox. Con
 * trỏ {@code current.json} do consumer EVT-23 ghi rồi vô hiệu CDN.
 */
public class UiPackPublishService implements UiPackCompatibilityApi {

  private static final Logger log = LoggerFactory.getLogger(UiPackPublishService.class);

  /** Các trường toàn vẹn không nằm trong phần được băm và ký. */
  public static final List<String> INTEGRITY_FIELDS =
      List.of("contentHash", "signature", "publishedAt");

  private final UiPackService packs;
  private final UiPackRepository repo;
  private final UiPackCompiler compiler;
  private final UiPackSigner signer;
  private final UiPackBundleStore store;
  private final UiPackEvents events;
  private final CdnInvalidator cdn;
  private final UiPackJobs jobs;
  private final ConfigAudit audit;
  private final ObjectMapper json;
  private final Clock clock;
  private final Executor renders;
  private final TransactionOperations writes;
  private PolicySchemaPort schemas;
  private final CelEngine cel =
      new CelEngine(StaticWorkingCalendar.weekendsOnly(), LayoutValidator.VISIBLE_WHEN_LIMITS);

  public UiPackPublishService(
      UiPackService packs,
      UiPackRepository repo,
      UiPackCompiler compiler,
      UiPackSigner signer,
      UiPackBundleStore store,
      UiPackEvents events,
      CdnInvalidator cdn,
      UiPackJobs jobs,
      ConfigAudit audit,
      ObjectMapper json,
      Clock clock,
      Executor renders,
      TransactionOperations writes) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.repo = Objects.requireNonNull(repo, "repo");
    this.compiler = Objects.requireNonNull(compiler, "compiler");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.store = Objects.requireNonNull(store, "store");
    this.events = Objects.requireNonNull(events, "events");
    this.cdn = Objects.requireNonNull(cdn, "cdn");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.renders = Objects.requireNonNull(renders, "renders");
    this.writes = Objects.requireNonNull(writes, "writes");
  }

  /** Đường dẫn con trỏ phiên bản hiện hành của tenant trên CDN. */
  public static String pointerKey(TenantId tenantId) {
    return "ui-packs/" + tenantId.value() + "/current.json";
  }

  static String prefix(TenantId tenantId, String version) {
    return "ui-packs/" + tenantId.value() + "/" + version + "/";
  }

  /**
   * Duyệt và phát hành: người duyệt khác người soạn và người gửi, xác thực nhiều lớp, phiên bản
   * {@code CHO_DUYET}; nhận job {@code RENDER}, chạy sau khi giao dịch ghi xong.
   *
   * @return mã job
   */
  @Transactional(UiPackService.TX)
  public String publish(
      TenantId tenantId, Actor reviewer, UUID packId, String version, long expectedVersion) {
    packs.requireStepUp(reviewer, "publishUiPackVersion");
    repo.findPack(tenantId, packId).orElseThrow();
    UiPackVersion current = repo.findVersion(tenantId, packId, version).orElseThrow();
    UiPackService.expect(current, expectedVersion);
    if (current.status() != UiPackVersionStatus.CHO_DUYET) {
      throw new BusinessException(
          "UI-409-NOT-DRAFT", "Chỉ phát hành được phiên bản đang chờ duyệt.");
    }
    if (current.authoredBy(reviewer.userId())) {
      throw new BusinessException(
          "UI-403-SAME-PERSON", "Người duyệt phải khác người soạn và người gửi duyệt.");
    }
    String jobId = Ids.newUuidV7().toString();
    jobs.accepted(tenantId, jobId, reviewer.userId(), UiPackJobs.RENDER);
    UiPackService.afterCommit(
        () -> {
          try {
            renders.execute(
                () ->
                    TenantContext.run(
                        tenantId,
                        () ->
                            render(
                                tenantId,
                                packId,
                                version,
                                jobId,
                                reviewer.userId(),
                                expectedVersion)));
          } catch (RejectedExecutionException e) {
            jobs.failed(tenantId, jobId, "Hệ thống đang bận, vui lòng phát hành lại.", List.of());
          }
        });
    return jobId;
  }

  /** Bước nền của job {@code RENDER}. */
  void render(
      TenantId tenantId,
      UUID packId,
      String version,
      String jobId,
      UUID reviewer,
      long expectedVersion) {
    jobs.running(tenantId, jobId);
    try {
      // Đọc trong giao dịch để kết nối mang ngữ cảnh tenant (RLS).
      Object[] loaded =
          writes.execute(
              s ->
                  new Object[] {
                    repo.findPack(tenantId, packId).orElseThrow(),
                    repo.findVersion(tenantId, packId, version).orElseThrow()
                  });
      UiPack pack = (UiPack) loaded[0];
      UiPackVersion v = (UiPackVersion) loaded[1];
      UiPackService.expect(v, expectedVersion);
      Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
      ObjectNode body = runtimeBody(pack, v);
      byte[] canonical = compiler.canonical(body);
      String sha = HexFormat.of().formatHex(sha256(canonical));
      String signature = Base64.getEncoder().encodeToString(signer.sign(canonical));
      String prefix = prefix(tenantId, version);
      ObjectNode published = withIntegrity(body, sha, signature, at);
      JsonNode tokens = body.path("tokens");
      JsonNode elderly = body.path("modes").path("elderly").path("tokens");
      JsonNode overrides = elderly.isObject() ? elderly : null;
      store.put(
          prefix + "pack.json",
          json.writeValueAsBytes(published),
          "application/json",
          UiPackBundleStore.IMMUTABLE);
      store.put(
          prefix + "theme.css",
          compiler
              .css(tenantId.value().toString(), tokens, overrides)
              .getBytes(StandardCharsets.UTF_8),
          "text/css",
          UiPackBundleStore.IMMUTABLE);
      store.put(
          prefix + "preset.json",
          json.writeValueAsBytes(compiler.preset(tokens, overrides)),
          "application/json",
          UiPackBundleStore.IMMUTABLE);
      writes.executeWithoutResult(
          s ->
              commit(
                  tenantId, packId, version, reviewer, at, sha, prefix + "pack.json", signature));
      jobs.passed(
          tenantId,
          jobId,
          UiPackService.location(packId, version),
          "UI Pack v"
              + version
              + " đã phát hành. Người dùng sẽ nhận giao diện mới ở lần tải trang kế tiếp.");
    } catch (BusinessException e) {
      jobs.failed(tenantId, jobId, e.detail(), List.of());
    } catch (VersionConflictException e) {
      jobs.failed(
          tenantId, jobId, "Phiên bản vừa được người khác cập nhật, vui lòng tải lại.", List.of());
    } catch (RuntimeException | java.io.IOException e) {
      log.error("Phát hành UI Pack {} phiên bản {} lỗi", packId, version, e);
      jobs.failed(tenantId, jobId, "Không phát hành được, vui lòng thử lại.", List.of());
    }
  }

  private void commit(
      TenantId tenantId,
      UUID packId,
      String version,
      UUID reviewer,
      Instant at,
      String sha,
      String key,
      String signature) {
    UiPack pack = repo.findPack(tenantId, packId).orElseThrow();
    UiPackVersion current = repo.findVersion(tenantId, packId, version).orElseThrow();
    UiPackVersion next;
    try {
      next = current.publish(reviewer, at, sha, key, signature);
    } catch (UiPackVersion.SameReviewerException e) {
      throw new BusinessException(
          "UI-403-SAME-PERSON", "Người duyệt phải khác người soạn và người gửi duyệt.");
    } catch (IllegalUiPackTransitionException e) {
      throw new BusinessException(
          "UI-409-NOT-DRAFT", "Phiên bản không còn chờ duyệt, không phát hành được.");
    }
    if (!repo.updateVersion(tenantId, next, current.rowVersion())) {
      throw VersionConflictException.of("CONFIG", current.rowVersion(), -1);
    }
    if (pack.currentVersionId() != null && !pack.currentVersionId().equals(next.id())) {
      repo.findVersionById(tenantId, pack.currentVersionId())
          .filter(old -> old.status() == UiPackVersionStatus.DA_PHAT_HANH)
          .ifPresent(
              old -> {
                UiPackVersion replaced = old.supersede(reviewer, at);
                if (!repo.updateVersion(tenantId, replaced, old.rowVersion())) {
                  throw VersionConflictException.of("CONFIG", old.rowVersion(), -1);
                }
                audit.changed(
                    tenantId,
                    reviewer,
                    "THAY_THE_UI_PACK",
                    UiPackService.OBJECT_TYPE,
                    old.version(),
                    old.id(),
                    replaced.rowVersion());
              });
    }
    if (!repo.setCurrentVersion(tenantId, pack, next.id(), reviewer, at)) {
      throw VersionConflictException.of("CONFIG", pack.rowVersion(), -1);
    }
    events.published(tenantId, next, List.of("/" + pointerKey(tenantId)));
    audit.changed(
        tenantId,
        reviewer,
        "PHAT_HANH_UI_PACK",
        UiPackService.OBJECT_TYPE,
        version,
        next.id(),
        next.rowVersion());
  }

  /**
   * Consumer EVT-23: ghi con trỏ {@code current.json} khi phiên bản vẫn là bản hiện hành rồi vô
   * hiệu CDN; sự kiện của bản đã bị thay thế thì bỏ qua.
   */
  public void onPublished(TenantId tenantId, UUID versionId, List<String> cdnPaths) {
    Optional<UiPack> pack = repo.findCurrentPack(tenantId);
    if (pack.isEmpty() || !versionId.equals(pack.get().currentVersionId())) {
      log.info("Bỏ qua EVT-23 của phiên bản {} vì không còn hiện hành", versionId);
      return;
    }
    UiPackVersion v = repo.findVersionById(tenantId, versionId).orElseThrow();
    String base = "/" + prefix(tenantId, v.version());
    ObjectNode pointer = json.createObjectNode();
    pointer.put("version", v.version());
    pointer.put("pack", base + "pack.json");
    pointer.put("theme", base + "theme.css");
    pointer.put("preset", base + "preset.json");
    pointer.put("contentHash", v.bundleSha256());
    pointer.put("signatureAlgorithm", UiPackSigner.ALGORITHM);
    pointer.put("keyId", signer.keyId());
    pointer.put("publishedAt", DateTimeFormatter.ISO_INSTANT.format(v.publishedAt()));
    try {
      store.put(
          pointerKey(tenantId),
          json.writeValueAsBytes(pointer),
          "application/json",
          UiPackBundleStore.POINTER);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    cdn.invalidate(List.copyOf(cdnPaths));
  }

  /** Cổng tra Policy Pack của MOD-05; chưa có thì chỉ UI Pack không có biểu mẫu là tương thích. */
  public void setPolicySchemas(PolicySchemaPort schemas) {
    this.schemas = schemas;
  }

  /**
   * Quay lui về phiên bản {@code THAY_THE} ({@code activateUiPackVersion}, BR-18-10, ADR-25): kiểm
   * tương thích Policy Pack, đổi phiên bản hiện hành, phát EVT-23 để consumer trỏ {@code
   * current.json} về gói bất biến đã có.
   */
  @Transactional(UiPackService.TX)
  public UiPackVersion activate(
      TenantId tenantId, UUID actorId, UUID packId, String version, long expectedVersion) {
    UiPack pack = repo.findPack(tenantId, packId).orElseThrow();
    UiPackVersion target = repo.findVersion(tenantId, packId, version).orElseThrow();
    UiPackService.expect(target, expectedVersion);
    if (target.status() != UiPackVersionStatus.THAY_THE || target.bundleSha256() == null) {
      throw new BusinessException(
          "UI-409-NOT-DRAFT", "Chỉ quay lui được về phiên bản đã phát hành trước đây.");
    }
    JsonNode document = packs.document(pack, target);
    if (!compatible(
        tenantId, document, document.path("requires").path("policyPack").asText(null))) {
      throw new BusinessException(
          "UI-409-ROLLBACK-INCOMPATIBLE",
          "Không thể quay lui về v"
              + version
              + " vì biểu mẫu của phiên bản này tham chiếu Policy Pack đã ngừng.");
    }
    Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (pack.currentVersionId() != null) {
      repo.findVersionById(tenantId, pack.currentVersionId())
          .filter(old -> old.status() == UiPackVersionStatus.DA_PHAT_HANH)
          .ifPresent(
              old -> {
                if (!repo.updateVersion(tenantId, old.supersede(actorId, at), old.rowVersion())) {
                  throw VersionConflictException.of("CONFIG", old.rowVersion(), -1);
                }
              });
    }
    UiPackVersion next = target.reactivate(actorId, at);
    if (!repo.updateVersion(tenantId, next, target.rowVersion())) {
      throw VersionConflictException.of("CONFIG", target.rowVersion(), -1);
    }
    if (!repo.setCurrentVersion(tenantId, pack, next.id(), actorId, at)) {
      throw VersionConflictException.of("CONFIG", pack.rowVersion(), -1);
    }
    events.published(tenantId, next, List.of("/" + pointerKey(tenantId)));
    audit.changed(
        tenantId,
        actorId,
        "QUAY_LUI_UI_PACK",
        UiPackService.OBJECT_TYPE,
        version,
        next.id(),
        next.rowVersion());
    return next;
  }

  @Override
  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public boolean isCompatible(TenantId tenantId, String policyPackRef) {
    return repo.findCurrentPack(tenantId)
        .flatMap(
            pack ->
                repo.findVersionById(tenantId, pack.currentVersionId())
                    .map(v -> compatible(tenantId, packs.document(pack, v), policyPackRef)))
        .orElse(true);
  }

  /**
   * Policy Pack chưa ngừng và mọi biểu mẫu ({@code forms.*.schemaRef}) giải được trong {@code
   * policyPackRef}.
   */
  private boolean compatible(TenantId tenantId, JsonNode document, String policyPackRef) {
    JsonNode forms = document.path("forms");
    boolean hasForms = forms.isObject() && forms.size() > 0;
    if (schemas == null) {
      return !hasForms;
    }
    if (policyPackRef != null && schemas.retired(tenantId, policyPackRef)) {
      return false;
    }
    if (hasForms) {
      for (JsonNode form : forms) {
        String ref = form.path("schemaRef").asText("");
        if (ref.isEmpty() || schemas.fields(tenantId, ref, policyPackRef).isEmpty()) {
          return false;
        }
      }
    }
    return true;
  }

  /** UI Pack hiện hành của tenant ({@code getCurrentUiPack}). */
  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public Optional<Current> current(TenantId tenantId) {
    return repo.findCurrentPack(tenantId)
        .flatMap(
            pack ->
                repo.findVersionById(tenantId, pack.currentVersionId())
                    .filter(v -> v.bundleSha256() != null)
                    .map(
                        v ->
                            new Current(
                                v,
                                withIntegrity(
                                    runtimeBody(pack, v),
                                    v.bundleSha256(),
                                    v.signature(),
                                    v.publishedAt()))));
  }

  /** Bản hiện hành: phiên bản và thân {@code UiPackRuntime}. */
  public record Current(UiPackVersion version, ObjectNode body) {
    public String etag() {
      return version.bundleSha256();
    }
  }

  /**
   * Bố cục trang chủ đã lọc theo người xem ({@code getHomeLayout}): nút có {@code visibleWhen} sai
   * hoặc không đánh giá được bị bỏ; máy chủ là nguồn sự thật, trình duyệt chỉ đánh giá lại.
   */
  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public Optional<Home> home(TenantId tenantId, Viewer viewer) {
    return current(tenantId)
        .map(
            c -> {
              Map<String, Object> ctx =
                  Map.of(
                      "user",
                      Map.of(
                          "id", viewer.userId().toString(),
                          "roles", viewer.roles(),
                          "tenant", tenantId.value().toString(),
                          "unitId", viewer.unitId() == null ? "" : viewer.unitId()));
              JsonNode home = c.body().path("layouts").path("home");
              JsonNode layout = home.isObject() ? filter(home, ctx) : null;
              boolean reduced =
                  viewer.elderly()
                      && c.body()
                          .path("modes")
                          .path("elderly")
                          .path("flags")
                          .path("reducedSteps")
                          .asBoolean(false);
              return new Home(
                  viewer.elderly() ? "elderly" : "default",
                  c.version().version(),
                  c.body().path("requires").path("registry").asText(),
                  layout == null
                      ? json.createObjectNode().put("component", "vt.layout.grid")
                      : layout,
                  reduced,
                  c.etag());
            });
  }

  /**
   * Người xem trang chủ trong ngữ cảnh phân công đang chọn; {@code elderly} là chế độ cao tuổi
   * người dùng đã bật (CNH, mặc định tắt).
   */
  public record Viewer(UUID userId, List<String> roles, String unitId, boolean elderly) {
    public Viewer {
      Objects.requireNonNull(userId, "userId");
      roles = roles == null ? List.of() : List.copyOf(roles);
    }
  }

  /** Bố cục trang chủ đã phân giải. */
  public record Home(
      String mode,
      String uiPackVersion,
      String registryVersion,
      JsonNode layout,
      boolean reducedSteps,
      String packEtag) {}

  private JsonNode filter(JsonNode node, Map<String, Object> ctx) {
    if (node.hasNonNull("visibleWhen") && !visible(node.get("visibleWhen").asText(), ctx)) {
      return null;
    }
    ObjectNode out = ((ObjectNode) node).deepCopy();
    JsonNode children = node.path("children");
    if (children.isArray()) {
      ArrayNode kept = out.putArray("children");
      for (JsonNode child : children) {
        JsonNode f = filter(child, ctx);
        if (f != null) {
          kept.add(f);
        }
      }
    }
    return out;
  }

  private boolean visible(String expression, Map<String, Object> ctx) {
    try {
      return Boolean.TRUE.equals(
          cel.evaluate(cel.compile(expression, LayoutValidator.CONTEXT, CelValueType.BOOL), ctx));
    } catch (CelCompilationException | CelExecutionException e) {
      return false;
    }
  }

  /** Thân {@code UiPackRuntime} không gồm trường toàn vẹn: phần được băm và ký. */
  ObjectNode runtimeBody(UiPack pack, UiPackVersion v) {
    ObjectNode body = (ObjectNode) packs.document(pack, v).deepCopy();
    // UiPackRuntime không có trường tài nguyên; tài nguyên phục vụ riêng (wiki trang 14).
    body.remove("assets");
    if (!body.has("forms")) {
      body.putObject("forms");
    }
    if (!body.has("plugins")) {
      body.putArray("plugins");
    }
    if (!body.has("i18n")) {
      body.putObject("i18n");
    }
    return body;
  }

  private ObjectNode withIntegrity(ObjectNode body, String sha, String signature, Instant at) {
    ObjectNode out = body.deepCopy();
    out.put("contentHash", sha);
    out.put("signature", signature);
    out.put("publishedAt", at == null ? null : DateTimeFormatter.ISO_INSTANT.format(at));
    return out;
  }

  /** Phần được ký của một {@code pack.json}: bỏ các trường toàn vẹn, dạng chuẩn tắc. */
  public byte[] signedContent(JsonNode packJson) {
    ObjectNode copy = (ObjectNode) packJson.deepCopy();
    INTEGRITY_FIELDS.forEach(copy::remove);
    return compiler.canonical(copy);
  }

  static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Danh sách đường dẫn đối tượng của một phiên bản đã phát hành. */
  public static List<String> objects(TenantId tenantId, String version) {
    List<String> keys = new ArrayList<>();
    for (String f : List.of("pack.json", "theme.css", "preset.json")) {
      keys.add(prefix(tenantId, version) + f);
    }
    return keys;
  }
}
