package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.RoleHolders;
import vn.viettel.okrkpi.config.application.port.UiPackChecker;
import vn.viettel.okrkpi.config.application.port.UiPackJobs;
import vn.viettel.okrkpi.config.application.port.UiPackNotices;
import vn.viettel.okrkpi.config.application.port.UiPackRepository;
import vn.viettel.okrkpi.config.domain.IllegalUiPackTransitionException;
import vn.viettel.okrkpi.config.domain.UiPack;
import vn.viettel.okrkpi.config.domain.UiPackContent;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.config.domain.UiPackVersionStatus;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * UI Pack và vòng đời phiên bản của MOD-08 (CORE-08-08, QT-18, SCR-1801, ADR-05): soạn nháp có kiểm
 * lược đồ UI Pack 1.0, xem trước, gửi duyệt chạy kiểm tra tự động trong tác vụ {@code TEST_RUN}, từ
 * chối có tách vai trò. Biên dịch, ký, phát hành (CORE-08-11) và quay lui (CORE-08-12) gắn vào sau.
 */
public class UiPackService {

  static final String TX = "configTransactionManager";
  static final String OBJECT_TYPE = "config.ui_pack_version";
  static final String REVIEWER_ROLE = "tenant_admin";
  static final String DEFAULT_PACK_CODE = "mac-dinh";
  static final String DEFAULT_PACK_NAME = "Giao diện của đơn vị";
  static final String DEFAULT_CONTENT = "/config/ui-pack/default-version.json";

  /** Trang chủ tối đa 12 widget (mô tả {@code updateUiPackVersion}). */
  public static final int HOME_WIDGETS_MAX = 12;

  public static final int COMMENT_MAX = 1000;
  static final Duration STEP_UP_WINDOW = Duration.ofMinutes(15);

  private static final Logger log = LoggerFactory.getLogger(UiPackService.class);

  private final UiPackRepository repo;
  private final UiPackNotices notices;
  private final UiPackChecker checker;
  private final UiPackJobs jobs;
  private final RoleHolders roles;
  private final ConfigAudit audit;
  private final ObjectMapper json;
  private final UiPackSchema schema;
  private final Clock clock;
  private final Executor checks;
  private final TransactionOperations writes;

  public UiPackService(
      UiPackRepository repo,
      UiPackNotices notices,
      UiPackChecker checker,
      UiPackJobs jobs,
      RoleHolders roles,
      ConfigAudit audit,
      ObjectMapper json,
      Clock clock,
      Executor checks,
      TransactionOperations writes) {
    this.repo = Objects.requireNonNull(repo, "repo");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.checker = checker == null ? UiPackChecker.NONE : checker;
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.roles = Objects.requireNonNull(roles, "roles");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.schema = UiPackSchema.load(json);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.checks = Objects.requireNonNull(checks, "checks");
    this.writes = Objects.requireNonNull(writes, "writes");
  }

  /** UI Pack kèm phiên bản đang phát hành và phiên bản đang soạn. */
  public record PackSummary(UiPack pack, String currentVersion, String draftVersion) {}

  /** Kết quả xem trước. */
  public record Preview(
      String version,
      String mode,
      Map<String, String> cssVariables,
      JsonNode layouts,
      List<UiPackChecker.Result> checks,
      int bundleSizeGzipBytes) {}

  /** Phiên bản vừa bị từ chối kèm ý kiến. */
  public record Rejected(UiPackVersion version, String comment) {}

  /**
   * UI Pack của tenant. Tenant chưa có UI Pack nào được tạo sẵn một UI Pack "mac-dinh" ở trang đầu
   * (suy luận: hợp đồng không có thao tác tạo UI Pack, khởi tạo tenant chưa làm).
   */
  @Transactional(TX)
  public List<PackSummary> listPacks(TenantId tenantId, UUID actorId, UUID afterId, int limit) {
    List<UiPack> packs = repo.listPacks(tenantId, afterId, limit);
    if (packs.isEmpty() && afterId == null) {
      UiPack created = new UiPack(Ids.newUuidV7(), DEFAULT_PACK_CODE, DEFAULT_PACK_NAME, null, 0);
      repo.insertPack(tenantId, created, actorId);
      packs = List.of(created);
    }
    List<PackSummary> result = new ArrayList<>();
    for (UiPack p : packs) {
      String current =
          p.currentVersionId() == null
              ? null
              : repo.findVersionById(tenantId, p.currentVersionId())
                  .map(UiPackVersion::version)
                  .orElse(null);
      result.add(
          new PackSummary(p, current, repo.findInProgressVersion(tenantId, p.id()).orElse(null)));
    }
    return result;
  }

  /**
   * @throws NoSuchElementException khi UI Pack không có
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<UiPackVersion> listVersions(
      TenantId tenantId, UUID packId, UiPackVersionStatus status, UUID beforeId, int limit) {
    pack(tenantId, packId);
    return repo.listVersions(tenantId, packId, status, beforeId, limit);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public UiPackVersion get(TenantId tenantId, UUID packId, String version) {
    return version(tenantId, packId, version);
  }

  /**
   * Tạo phiên bản nháp, nhân bản từ {@code cloneFrom} hoặc từ nội dung khởi đầu khi {@code null}.
   */
  @Transactional(TX)
  public UiPackVersion create(
      TenantId tenantId, UUID actorId, UUID packId, String version, String cloneFrom) {
    pack(tenantId, packId);
    UiPackContent content =
        cloneFrom == null
            ? defaultContent()
            : repo.findVersion(tenantId, packId, cloneFrom)
                .map(UiPackVersion::content)
                .orElseThrow(
                    () ->
                        invalid(
                            "cloneFromVersion",
                            "Không có phiên bản " + cloneFrom + " để nhân bản"));
    UiPackVersion draft;
    try {
      draft =
          UiPackVersion.draft(Ids.newUuidV7(), packId, version, content, actorId, clock.instant());
    } catch (IllegalArgumentException e) {
      throw invalid("version", e.getMessage());
    }
    if (!repo.insertVersion(tenantId, draft)) {
      throw invalid("version", "Phiên bản " + version + " đã có trong UI Pack này");
    }
    audit.changed(tenantId, actorId, "TAO_PHIEN_BAN_UI_PACK", OBJECT_TYPE, version, draft.id(), 0);
    return draft;
  }

  /** Lưu nháp: chỉ khi {@code NHAP}, kiểm lược đồ và các bộ kiểm tra ngay. */
  @Transactional(TX)
  public UiPackVersion update(
      TenantId tenantId,
      UUID actorId,
      UUID packId,
      String version,
      long expectedVersion,
      UiPackContent content) {
    UiPack pack = pack(tenantId, packId);
    UiPackVersion current = version(tenantId, packId, version);
    expect(current, expectedVersion);
    UiPackVersion next;
    try {
      // Biểu mẫu chỉ đổi qua attachUiPackForm (CORE-08-13), lưu nháp giữ nguyên.
      next =
          current.edit(content.withForms(current.content().formsJson()), actorId, clock.instant());
    } catch (IllegalUiPackTransitionException e) {
      throw notDraft(current);
    }
    JsonNode document = document(pack, next);
    List<ProblemFieldError> errors = structuralErrors(document);
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Nội dung giao diện chưa hợp lệ.", errors);
    }
    for (UiPackChecker.Result r : checker.check(tenantId, document)) {
      if (!r.passed()) {
        throw new BusinessException(errorCodeOf(r.code()), r.message(), r.errors(), r.msg());
      }
    }
    save(tenantId, next, current.rowVersion(), expectedVersion);
    audit.changed(
        tenantId, actorId, "LUU_NHAP_UI_PACK", OBJECT_TYPE, version, next.id(), next.rowVersion());
    return next;
  }

  /** Biên dịch thử: biến CSS từ token (bản thô, trước CORE-08-11), bố cục, kết quả kiểm tra. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Preview preview(TenantId tenantId, UUID packId, String version, boolean elderly) {
    UiPack pack = pack(tenantId, packId);
    UiPackVersion v = version(tenantId, packId, version);
    JsonNode document = document(pack, v);
    List<UiPackChecker.Result> results = runChecks(tenantId, document);
    JsonNode tokens = read(v.content().tokensJson());
    if (elderly) {
      tokens = merge(tokens.deepCopy(), tokens.path("$extensions").path("modes").path("elderly"));
    }
    Map<String, String> css = new LinkedHashMap<>();
    flatten(tokens, "--vt", css);
    return new Preview(
        v.version(),
        elderly ? "elderly" : "default",
        css,
        read(v.content().layoutsJson()),
        results,
        gzipSize(document));
  }

  /**
   * Gửi duyệt: {@code NHAP → DANG_KIEM_TRA}, nhận tác vụ {@code TEST_RUN}; kiểm tra chạy sau khi
   * giao dịch ghi xong.
   *
   * @return mã tác vụ
   */
  @Transactional(TX)
  public String submit(
      TenantId tenantId, UUID actorId, UUID packId, String version, long expectedVersion) {
    pack(tenantId, packId);
    UiPackVersion current = version(tenantId, packId, version);
    expect(current, expectedVersion);
    UiPackVersion next;
    try {
      next = current.submit(actorId, clock.instant());
    } catch (IllegalUiPackTransitionException e) {
      throw notDraft(current);
    }
    save(tenantId, next, current.rowVersion(), expectedVersion);
    audit.changed(
        tenantId, actorId, "GUI_DUYET_UI_PACK", OBJECT_TYPE, version, next.id(), next.rowVersion());
    String jobId = Ids.newUuidV7().toString();
    jobs.accepted(tenantId, jobId, actorId);
    afterCommit(
        () ->
            checks.execute(
                () -> TenantContext.run(tenantId, () -> check(tenantId, packId, version, jobId))));
    return jobId;
  }

  /** Bước kiểm tra tự động của tác vụ {@code TEST_RUN}: đạt thì {@code CHO_DUYET}, lỗi về nháp. */
  void check(TenantId tenantId, UUID packId, String version, String jobId) {
    jobs.running(tenantId, jobId);
    try {
      List<ProblemFieldError> failures = new ArrayList<>();
      Boolean passed =
          writes.execute(
              s -> {
                UiPack pack = pack(tenantId, packId);
                UiPackVersion current = version(tenantId, packId, version);
                List<UiPackChecker.Result> results = runChecks(tenantId, document(pack, current));
                boolean ok = results.stream().allMatch(UiPackChecker.Result::passed);
                results.stream()
                    .filter(r -> !r.passed())
                    .forEach(
                        r -> {
                          if (r.errors().isEmpty()) {
                            failures.add(
                                new ProblemFieldError(
                                    r.code().name(),
                                    r.message() == null ? "Chưa đạt" : r.message(),
                                    r.msg()));
                          }
                          failures.addAll(r.errors());
                        });
                UiPackVersion next = current.checked(ok, clock.instant());
                if (!repo.updateVersion(tenantId, next, current.rowVersion())) {
                  throw VersionConflictException.of("CONFIG", current.rowVersion(), -1);
                }
                audit.changed(
                    tenantId,
                    next.updatedBy(),
                    ok ? "KIEM_TRA_UI_PACK_DAT" : "KIEM_TRA_UI_PACK_KHONG_DAT",
                    OBJECT_TYPE,
                    version,
                    next.id(),
                    next.rowVersion());
                if (ok) {
                  List<UUID> reviewers =
                      roles.holders(tenantId, REVIEWER_ROLE, clock.instant()).stream()
                          .filter(r -> !next.authoredBy(r))
                          .toList();
                  notices.reviewRequested(tenantId, pack.name(), next, reviewers);
                }
                return ok;
              });
      if (Boolean.TRUE.equals(passed)) {
        jobs.passed(tenantId, jobId, location(packId, version));
      } else {
        jobs.failed(
            tenantId, jobId, "Kiểm tra tự động chưa đạt, phiên bản đã trở về nháp.", failures);
      }
    } catch (RuntimeException e) {
      log.error("Kiểm tra UI Pack {} phiên bản {} lỗi", packId, version, e);
      // Giao dịch kiểm tra đã hoàn tác nên phiên bản còn ở DANG_KIEM_TRA; trả về nháp để người soạn
      // gửi lại được, nếu không trang chỉ còn "Đang kiểm tra tự động" mà không có thao tác nào.
      returnToDraft(tenantId, packId, version);
      jobs.failed(
          tenantId,
          jobId,
          "Không chạy được kiểm tra tự động, phiên bản đã trở về nháp, vui lòng gửi lại.",
          List.of());
    }
  }

  private void returnToDraft(TenantId tenantId, UUID packId, String version) {
    try {
      writes.executeWithoutResult(
          s ->
              repo.findVersion(tenantId, packId, version)
                  .filter(v -> v.status() == UiPackVersionStatus.DANG_KIEM_TRA)
                  .ifPresent(
                      current -> {
                        UiPackVersion next = current.checked(false, clock.instant());
                        if (repo.updateVersion(tenantId, next, current.rowVersion())) {
                          audit.changed(
                              tenantId,
                              next.updatedBy(),
                              "KIEM_TRA_UI_PACK_KHONG_DAT",
                              OBJECT_TYPE,
                              version,
                              next.id(),
                              next.rowVersion());
                        }
                      }));
    } catch (RuntimeException e) {
      log.error("Không trả được UI Pack {} phiên bản {} về nháp", packId, version, e);
    }
  }

  /** Từ chối: người duyệt khác người soạn, có ý kiến, xác thực nhiều lớp; về {@code NHAP}. */
  @Transactional(TX)
  public Rejected reject(
      TenantId tenantId,
      Actor reviewer,
      UUID packId,
      String version,
      long expectedVersion,
      String comment) {
    requireStepUp(reviewer, "rejectUiPackVersion");
    String text = comment == null ? "" : comment.strip();
    if (text.isEmpty() || text.length() > COMMENT_MAX) {
      throw invalid("comment", "Ý kiến bắt buộc, tối đa " + COMMENT_MAX + " ký tự");
    }
    UiPack pack = pack(tenantId, packId);
    UiPackVersion current = version(tenantId, packId, version);
    expect(current, expectedVersion);
    UiPackVersion next;
    try {
      next = current.reject(reviewer.userId(), clock.instant());
    } catch (UiPackVersion.SameReviewerException e) {
      throw new BusinessException(
          "UI-403-SAME-PERSON", "Người duyệt phải khác người soạn và người gửi duyệt.");
    } catch (IllegalUiPackTransitionException e) {
      throw new BusinessException("UI-409-NOT-DRAFT", "Chỉ từ chối được phiên bản đang chờ duyệt.");
    }
    save(tenantId, next, current.rowVersion(), expectedVersion);
    audit.changed(
        tenantId,
        reviewer.userId(),
        "TU_CHOI_UI_PACK",
        OBJECT_TYPE,
        version,
        next.id(),
        next.rowVersion());
    List<UUID> to =
        new ArrayList<>(new LinkedHashSet<>(List.of(current.createdBy(), current.updatedBy())));
    notices.rejected(tenantId, pack.name(), next, text, to);
    return new Rejected(next, text);
  }

  /** Tài liệu UI Pack 1.0 dựng từ phiên bản, để kiểm lược đồ và chạy bộ kiểm tra. */
  JsonNode document(UiPack pack, UiPackVersion v) {
    UiPackContent c = v.content();
    ObjectNode doc = json.createObjectNode();
    doc.put("pack", pack.code());
    doc.put("version", v.version());
    ObjectNode requires = doc.putObject("requires");
    requires.put("registry", c.componentRegistryVersion());
    if (c.policyPackVersionId() != null) {
      requires.put("policyPack", c.policyPackVersionId().toString());
    }
    JsonNode tokens = read(c.tokensJson());
    doc.set("tokens", tokens);
    if (c.elderMode()) {
      ObjectNode elderly = doc.putObject("modes").putObject("elderly");
      JsonNode overrides = tokens.path("$extensions").path("modes").path("elderly");
      ObjectNode flags = json.createObjectNode().put("reducedSteps", c.fewSteps());
      if (overrides.isObject()) {
        // Cờ của chế độ cao tuổi (ví dụ minTouchTarget) không phải token (TL-01 ví dụ UI Pack).
        ObjectNode copy = ((ObjectNode) overrides).deepCopy();
        JsonNode declared = copy.remove("flags");
        if (declared != null && declared.has("minTouchTarget")) {
          flags.set("minTouchTarget", declared.get("minTouchTarget"));
        }
        elderly.set("tokens", copy);
      }
      elderly.set("flags", flags);
    }
    doc.set("layouts", read(c.layoutsJson()));
    doc.set("assets", read(c.assetsJson()));
    return doc;
  }

  private List<UiPackChecker.Result> runChecks(TenantId tenantId, JsonNode document) {
    List<ProblemFieldError> errors = structuralErrors(document);
    List<UiPackChecker.Result> results = new ArrayList<>();
    results.add(
        errors.isEmpty()
            ? UiPackChecker.Result.ok(UiPackChecker.Code.SCHEMA, "Nội dung đúng cấu trúc UI Pack.")
            : new UiPackChecker.Result(
                UiPackChecker.Code.SCHEMA,
                false,
                "Nội dung chưa đúng cấu trúc UI Pack.",
                null,
                errors));
    if (errors.isEmpty()) {
      results.addAll(checker.check(tenantId, document));
    }
    return results;
  }

  private List<ProblemFieldError> structuralErrors(JsonNode document) {
    List<ProblemFieldError> errors = new ArrayList<>(schema.validate(document));
    JsonNode home = document.path("layouts").path("home").path("children");
    if (home.isArray() && home.size() > HOME_WIDGETS_MAX) {
      errors.add(
          new ProblemFieldError(
              "layouts.home.children",
              "Trang chủ có tối đa " + HOME_WIDGETS_MAX + " thành phần",
              null));
    }
    return errors;
  }

  private static String errorCodeOf(UiPackChecker.Code code) {
    return switch (code) {
      case REGISTRY -> "UI-422-UNKNOWN-COMPONENT";
      case CONTRAST, ELDER_MODE -> "UI-422-CONTRAST";
      case CEL_PARITY -> "UI-422-CEL-INVALID";
      default -> "COMMON-422-VALIDATION";
    };
  }

  private UiPack pack(TenantId tenantId, UUID packId) {
    return repo.findPack(tenantId, packId).orElseThrow();
  }

  private UiPackVersion version(TenantId tenantId, UUID packId, String version) {
    return repo.findVersion(tenantId, packId, version).orElseThrow();
  }

  static void expect(UiPackVersion v, long expectedVersion) {
    if (expectedVersion != v.rowVersion()) {
      throw VersionConflictException.of("CONFIG", expectedVersion, v.rowVersion());
    }
  }

  private void save(TenantId tenantId, UiPackVersion next, int current, long expectedVersion) {
    if (!repo.updateVersion(tenantId, next, current)) {
      throw VersionConflictException.of("CONFIG", expectedVersion, current + 1);
    }
  }

  private static BusinessException notDraft(UiPackVersion v) {
    return new BusinessException(
        "UI-409-NOT-DRAFT", "Phiên bản " + v.version() + " không còn ở trạng thái nháp.");
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  static String location(UUID packId, String version) {
    return "/api/v1/ui/packs/" + packId + "/versions/" + version;
  }

  void requireStepUp(Actor actor, String operationId) {
    long maxAge = STEP_UP_WINDOW.toSeconds();
    if (!StepUpRequiredException.REQUIRED_ACR.equals(
        AuthzSubjectResolver.normalizeAcr(actor.acr()))) {
      throw new StepUpRequiredException(
          operationId, maxAge, "Thao tác cần xác thực nhiều lớp trong 15 phút gần nhất.");
    }
    long age = clock.instant().getEpochSecond() - (actor.authTime() == null ? 0 : actor.authTime());
    if (actor.authTime() == null || age > maxAge || age < -30) {
      throw new StepUpRequiredException(
          operationId, maxAge, "Phiên xác thực nhiều lớp đã quá 15 phút, cần xác thực lại.");
    }
  }

  static void afterCommit(Runnable work) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              work.run();
            }
          });
    } else {
      work.run();
    }
  }

  private UiPackContent defaultContent() {
    try (InputStream in = UiPackService.class.getResourceAsStream(DEFAULT_CONTENT)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu nội dung UI Pack khởi đầu " + DEFAULT_CONTENT);
      }
      JsonNode d = json.readTree(in);
      return new UiPackContent(
          write(d.get("tokens")),
          write(d.get("layouts")),
          write(d.path("assets")),
          d.get("componentRegistryVersion").asText(),
          null,
          true,
          false);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** JSON chuẩn hoá của một nút, để lưu vào {@link UiPackContent}. */
  public String write(JsonNode node) {
    try {
      return json.writeValueAsString(
          node == null || node.isMissingNode() ? json.createArrayNode() : node);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public JsonNode read(String text) {
    try {
      return json.readTree(text);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Nội dung UI Pack đã lưu không phải JSON", e);
    }
  }

  /**
   * Gộp ghi đè token (chế độ cao tuổi) vào bản token; dùng chung với {@link UiPackStaticChecks}.
   */
  static JsonNode merge(JsonNode base, JsonNode overrides) {
    if (!(base instanceof ObjectNode target) || !overrides.isObject()) {
      return base;
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = overrides.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode existing = target.get(e.getKey());
      if (existing != null
          && existing.isObject()
          && e.getValue().isObject()
          && !e.getValue().has("$value")) {
        merge(existing, e.getValue());
      } else {
        target.set(e.getKey(), e.getValue());
      }
    }
    return target;
  }

  /** Token lá có {@code $value} chuỗi hoặc số thành biến CSS {@code --vt-<đường-dẫn>}. */
  static void flatten(JsonNode group, String prefix, Map<String, String> out) {
    for (Iterator<Map.Entry<String, JsonNode>> it = group.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      if (e.getKey().startsWith("$") || !e.getValue().isObject()) {
        continue;
      }
      String name = prefix + "-" + e.getKey();
      JsonNode value = e.getValue().get("$value");
      if (value == null) {
        flatten(e.getValue(), name, out);
      } else if (value.isTextual() || value.isNumber()) {
        out.put(name, value.asText());
      }
    }
  }

  private int gzipSize(JsonNode document) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
      gz.write(json.writeValueAsBytes(document));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.size();
  }

  /** Nội dung soạn được dựng từ các phần JSON của thân yêu cầu. */
  public UiPackContent content(
      JsonNode tokens,
      JsonNode layouts,
      ArrayNode assets,
      String registryVersion,
      UUID policyPackVersionId,
      boolean elderMode,
      boolean fewSteps) {
    try {
      return new UiPackContent(
          write(tokens == null ? json.createObjectNode() : tokens),
          write(layouts == null ? json.createObjectNode() : layouts),
          write(assets == null ? json.createArrayNode() : assets),
          registryVersion == null ? "" : registryVersion,
          policyPackVersionId,
          elderMode,
          fewSteps);
    } catch (IllegalArgumentException e) {
      throw invalid("componentRegistryVersion", e.getMessage());
    }
  }
}
