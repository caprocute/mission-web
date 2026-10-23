package vn.viettel.okrkpi.workflow.application.template;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.application.port.RolloutCalendar;
import vn.viettel.okrkpi.workflow.application.port.TemplateAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.TemplateBindingUsage;
import vn.viettel.okrkpi.workflow.application.port.TemplateBpmnLinter;
import vn.viettel.okrkpi.workflow.application.port.TemplateCodeHost;
import vn.viettel.okrkpi.workflow.application.port.TemplateReleaseStore;
import vn.viettel.okrkpi.workflow.application.port.TemplateRolloutStore;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestEngine;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestRunStore;
import vn.viettel.okrkpi.workflow.application.port.WorkflowTemplateStore;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;
import vn.viettel.okrkpi.workflow.domain.template.CellRollout;
import vn.viettel.okrkpi.workflow.domain.template.Release;
import vn.viettel.okrkpi.workflow.domain.template.ReleaseStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateLintFinding;
import vn.viettel.okrkpi.workflow.domain.template.TemplateRuleViolation;
import vn.viettel.okrkpi.workflow.domain.template.TemplateStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;

/**
 * Thư viện và vòng đời mẫu luồng nội bộ (BS-01 SCR-1505, QT-15; DV-06-09): xem thư viện, soạn và
 * sửa phiên bản nháp (linter máy chủ quyết định), đề nghị phát hành bằng merge request vào repo mẫu
 * luồng, phê duyệt bốn mắt khởi động saga phát hành, theo dõi triển khai từng cell. Phiên bản từ
 * {@code HIEU_LUC} không sửa được; mọi thay đổi tạo phiên bản mới.
 */
public class WorkflowTemplateService {

  private static final Pattern CODE = Pattern.compile("^[a-z0-9-]{1,64}$");

  private final WorkflowTemplateStore store;
  private final TemplateBpmnLinter linter;
  private final TemplateBindingUsage usage;
  private final TemplateTestRunStore runs;
  private final TemplateReleaseStore releases;
  private final TemplateRolloutStore rollouts;
  private final TemplateCodeHost codeHost;
  private final TemplateAccessPolicy access;
  private final TemplateTestEngine engine;
  private final RolloutCalendar calendar;
  private final SagaService sagas;
  private final ReleaseSettings settings;
  private final Clock clock;

  /** Các cổng mà dịch vụ dùng. */
  public record Ports(
      WorkflowTemplateStore store,
      TemplateBpmnLinter linter,
      TemplateBindingUsage usage,
      TemplateTestRunStore runs,
      TemplateReleaseStore releases,
      TemplateRolloutStore rollouts,
      TemplateCodeHost codeHost,
      TemplateAccessPolicy access,
      TemplateTestEngine engine,
      RolloutCalendar calendar) {}

  public WorkflowTemplateService(
      Ports ports, SagaService sagas, ReleaseSettings settings, Clock clock) {
    this.store = ports.store();
    this.linter = ports.linter();
    this.usage = ports.usage();
    this.runs = ports.runs();
    this.releases = ports.releases();
    this.rollouts = ports.rollouts();
    this.codeHost = ports.codeHost();
    this.access = ports.access();
    this.engine = ports.engine();
    this.calendar = ports.calendar();
    this.sagas = sagas;
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Nội dung tạo mẫu ({@code WorkflowTemplateCreate}). */
  public record CreateCommand(
      UUID tenantId,
      String templateCode,
      UUID basedOnTemplateId,
      String bpmnXml,
      Map<String, Object> paramSchema,
      UUID actor) {}

  /** Nội dung sửa mẫu ({@code WorkflowTemplateUpdate}). */
  public record UpdateCommand(
      UUID id,
      String bpmnXml,
      Map<String, Object> paramSchema,
      long expectedRowVersion,
      UUID actor) {}

  // --- Đọc ---------------------------------------------------------------------------------

  /**
   * Một trang thư viện; trả tối đa {@code limit} dòng (người gọi xin thêm một để biết còn trang).
   */
  @Transactional(readOnly = true)
  public List<TemplateView> list(TemplateStatus status, String q, String afterKey, int limit) {
    access.checkLibrary(resource(null, List.of()));
    List<TemplateVersion> page = store.page(status, q, afterKey, limit);
    return views(page);
  }

  @Transactional(readOnly = true)
  public TemplateView get(UUID id) {
    access.checkLibrary(resource(id, List.of()));
    return views(List.of(require(id))).get(0);
  }

  @Transactional(readOnly = true)
  public List<CellRollout> rollouts(UUID id) {
    access.checkLibrary(resource(id, List.of()));
    require(id);
    return rollouts.list(id);
  }

  // --- Soạn --------------------------------------------------------------------------------

  /**
   * {@code createWorkflowTemplate}: lưu BPMN và khai báo tham số ở {@code NHAP} sau khi linter máy
   * chủ đạt; có {@code basedOnTemplateId} thì là phiên bản kế tiếp của cùng mã mẫu.
   */
  @Transactional
  public TemplateView create(CreateCommand c) {
    access.checkAuthor(resource(c.basedOnTemplateId(), List.of()));
    if (c.templateCode() == null || !CODE.matcher(c.templateCode()).matches()) {
      throw TemplateErrors.invalid(
          "templateCode", "Mã mẫu chỉ gồm chữ thường, chữ số và dấu gạch ngang.", "pattern");
    }
    if (c.basedOnTemplateId() != null) {
      TemplateVersion base = require(c.basedOnTemplateId());
      if (!base.templateCode().equals(c.templateCode())) {
        throw TemplateErrors.invalid(
            "basedOnTemplateId", "Phiên bản gốc thuộc mẫu luồng khác.", "cung-ma-mau");
      }
    }
    checkSchema(c.paramSchema());
    if (store.hasOpenDraft(c.templateCode())) {
      throw draftExists();
    }
    lintOrThrow(c.templateCode(), c.bpmnXml());
    int next = store.maxVersion(c.templateCode()) + 1;
    TemplateVersion draft =
        TemplateVersion.draft(
            Ids.newUuidV7(),
            c.tenantId(),
            c.templateCode(),
            next,
            c.bpmnXml(),
            sha256(c.bpmnXml()),
            c.paramSchema(),
            c.actor(),
            now());
    try {
      return views(List.of(store.insert(draft))).get(0);
    } catch (DuplicateKeyException e) {
      throw draftExists();
    }
  }

  /**
   * {@code updateWorkflowTemplate}: chỉ phiên bản {@code NHAP}, {@code DAT_KIEM_THU}; If-Match bắt
   * buộc.
   */
  @Transactional
  public TemplateView update(UpdateCommand c) {
    access.checkAuthor(resource(c.id(), List.of()));
    TemplateVersion current = require(c.id());
    requireVersion(current, c.expectedRowVersion());
    checkSchema(c.paramSchema());
    TemplateVersion edited;
    try {
      current.requireTestable();
      lintOrThrow(current.templateCode(), c.bpmnXml());
      edited = current.edit(c.bpmnXml(), sha256(c.bpmnXml()), c.paramSchema(), c.actor(), now());
    } catch (TemplateRuleViolation v) {
      throw TemplateErrors.of(v, current.status());
    }
    return views(List.of(save(edited, current.rowVersion()))).get(0);
  }

  // --- Đề nghị và phê duyệt phát hành ------------------------------------------------------------

  /**
   * {@code submitWorkflowTemplate}: chỉ khi {@code DAT_KIEM_THU}; mở merge request vào repo mẫu
   * luồng kèm BPMN, khai báo tham số và bộ test, rồi chuyển {@code CHO_PHE_DUYET}.
   */
  @Transactional
  public TemplateView submit(UUID id, long expectedRowVersion, UUID actor) {
    access.checkSubmit(resource(id, List.of()));
    TemplateVersion current = require(id);
    requireVersion(current, expectedRowVersion);
    String suiteRef = runs.latestFinished(id).map(r -> r.id().toString()).orElse(null);
    TemplateVersion submitted;
    try {
      submitted = current.submit(suiteRef, actor, now());
    } catch (TemplateRuleViolation v) {
      throw TemplateErrors.of(v, current.status());
    }
    TemplateCodeHost.MergeRequest mr = codeHost.open(releasePackage(submitted), actor);
    if (releases.find(id).isEmpty()) {
      releases.insert(
          new Release(id, mr.ref(), mr.url(), ReleaseStatus.MO, actor, null, null, null, null),
          actor);
    }
    return views(List.of(save(submitted, current.rowVersion()))).get(0);
  }

  /**
   * {@code approveWorkflowTemplate}: người phê duyệt khác người soạn (bốn mắt, kiểm ở miền trước
   * rồi ở OPA), không trong khung cấm triển khai; khởi động saga {@code PHAT_HANH_MAU_LUONG} chờ
   * pipeline ký rồi lên {@code HIEU_LUC} và triển khai cuốn chiếu. Gọi lại trả saga đã có.
   */
  @Transactional
  public SagaInstance approve(UUID id, long expectedRowVersion, UUID approver, UUID tenantId) {
    TemplateVersion current = require(id);
    requireVersion(current, expectedRowVersion);
    Optional<Release> release = releases.find(id);
    if (release.isPresent() && release.get().sagaId() != null && sagas != null) {
      Optional<SagaInstance> existing = sagas.find(release.get().sagaId());
      if (existing.isPresent()) {
        if (!approver.equals(release.get().approvedBy())) {
          throw new BusinessException(
              TemplateErrors.IMMUTABLE, "Mẫu luồng đã được phê duyệt phát hành.");
        }
        return existing.get();
      }
    }
    try {
      current.requireApprover(approver);
    } catch (TemplateRuleViolation v) {
      throw TemplateErrors.of(v, current.status());
    }
    access.checkApprove(
        resource(id, List.of(current.createdBy().toString(), current.updatedBy().toString())));
    calendar
        .window()
        .blockedAt(now())
        .ifPresent(
            reason -> {
              throw new BusinessException(TemplateErrors.ROLLOUT_WINDOW, reason);
            });
    if (release.isEmpty()) {
      throw new BusinessException(
          TemplateErrors.NOT_TESTED, "Mẫu luồng chưa có đề nghị phát hành.");
    }
    if (sagas == null) {
      throw new IllegalStateException("Khung saga chưa bật nên chưa phát hành được mẫu luồng");
    }
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put(TemplateReleaseSaga.P_TEMPLATE_ID, id.toString());
    parameters.put(TemplateReleaseSaga.P_TEMPLATE_CODE, current.templateCode());
    parameters.put(TemplateReleaseSaga.P_VERSION_NO, current.versionNo());
    parameters.put(TemplateReleaseSaga.P_APPROVER, approver.toString());
    parameters.put(TemplateReleaseSaga.P_CELLS, new ArrayList<>(settings.cells()));
    SagaInstance saga =
        sagas
            .start(
                new SagaService.StartCommand(
                    tenantId, SagaType.PHAT_HANH_MAU_LUONG, id, parameters, approver))
            .saga();
    releases.approved(id, approver, saga.id());
    rollouts.plan(id, settings.cells(), saga.id(), approver);
    return saga;
  }

  /**
   * Pipeline của repo mẫu luồng báo đã ký gói mẫu (cosign): ghi chữ ký vào đề nghị phát hành rồi
   * trả lời bước {@code CHO_KY_SO} của saga. Gọi trùng không lỗi.
   *
   * @return {@code false} khi mẫu chưa được phê duyệt (chưa có saga để trả lời)
   */
  @Transactional
  public boolean recordSignature(UUID id, byte[] signature, String pipelineRef) {
    if (signature == null || signature.length == 0) {
      throw TemplateErrors.invalid("signature", "Thiếu chữ ký của gói mẫu.", "required");
    }
    require(id);
    Release release =
        releases
            .find(id)
            .orElseThrow(
                () ->
                    new BusinessException(
                        TemplateErrors.NOT_TESTED, "Mẫu luồng chưa có đề nghị phát hành."));
    if (!release.signed()) {
      releases.signed(id, signature, pipelineRef);
    }
    if (release.sagaId() == null || sagas == null) {
      return false;
    }
    sagas.onReply(release.sagaId(), 1, Ids.newUuidV7(), true, null);
    return true;
  }

  /** Số kịch bản của bộ test mặc định. */
  public int scenarioCount() {
    return engine.scenarioCount();
  }

  // --- Nội bộ ------------------------------------------------------------------------------

  private List<TemplateView> views(List<TemplateVersion> items) {
    List<UUID> ids = items.stream().map(TemplateVersion::id).toList();
    Map<UUID, Integer> tenants = ids.isEmpty() ? Map.of() : usage.tenantCounts(ids);
    Map<UUID, Integer> tests = ids.isEmpty() ? Map.of() : store.testCounts(ids);
    Map<UUID, String> urls = ids.isEmpty() ? Map.of() : store.mergeRequestUrls(ids);
    int defaultTests = engine.scenarioCount();
    return items.stream()
        .map(
            t ->
                new TemplateView(
                    t,
                    tenants.getOrDefault(t.id(), 0),
                    tests.getOrDefault(t.id(), defaultTests),
                    urls.get(t.id())))
        .toList();
  }

  private TemplateVersion save(TemplateVersion next, long expected) {
    return store
        .update(next, expected)
        .orElseThrow(() -> new VersionConflictException(TemplateErrors.MODULE, expected, null));
  }

  private TemplateVersion require(UUID id) {
    return store.find(id).orElseThrow(WorkflowNotFoundException::new);
  }

  private static void requireVersion(TemplateVersion t, long expected) {
    if (t.rowVersion() != expected) {
      throw VersionConflictException.of(TemplateErrors.MODULE, expected, t.rowVersion());
    }
  }

  private void lintOrThrow(String code, String xml) {
    List<TemplateLintFinding> findings = linter.lint(code, xml);
    if (!findings.isEmpty()) {
      throw TemplateErrors.lint(findings);
    }
  }

  private static void checkSchema(Map<String, Object> schema) {
    if (schema == null) {
      throw TemplateErrors.invalid("paramSchema", "Cần khai báo tham số của mẫu.", "required");
    }
    Object type = schema.get("type");
    if (type != null && !"object".equals(type)) {
      throw TemplateErrors.invalid(
          "paramSchema", "Khai báo tham số phải là một đối tượng JSON Schema.", "type");
    }
  }

  private TemplateCodeHost.ReleasePackage releasePackage(TemplateVersion t) {
    String dir = "mau/" + t.templateCode() + "/v" + t.versionNo() + "/";
    Map<String, String> files = new LinkedHashMap<>();
    files.put(dir + t.templateCode() + ".bpmn20.xml", t.bpmnXml());
    files.put(dir + "tham-so.schema.json", Json.write(t.paramSchema()));
    files.put(
        dir + "bo-test.json",
        Json.write(
            Map.of(
                "kichBan",
                engine.scenarioCount(),
                "nganSachSqlMoiLanHoanThanh",
                settings.sqlBudget(),
                "lanChay",
                t.testSuiteRef() == null ? "" : t.testSuiteRef())));
    return new TemplateCodeHost.ReleasePackage(
        t.id(),
        t.templateCode(),
        t.versionNo(),
        files,
        "Phát hành mẫu luồng " + t.templateCode() + " phiên bản " + t.versionNo());
  }

  private static AuthzResource resource(UUID id, List<String> authors) {
    AuthzResource.Builder b = AuthzResource.builder().type("wf_template").classification("C0");
    if (id != null) {
      b.id(id.toString());
    }
    if (!authors.isEmpty()) {
      b.participants(Map.of("initiator", List.copyOf(authors)));
    }
    return b.build();
  }

  private static BusinessException draftExists() {
    return new BusinessException(
        TemplateErrors.DRAFT_EXISTS, "Mẫu luồng này đang có một phiên bản nháp khác.");
  }

  static byte[] sha256(String xml) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(xml.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM thiếu SHA-256", e);
    }
  }

  private Instant now() {
    return clock.instant();
  }
}
