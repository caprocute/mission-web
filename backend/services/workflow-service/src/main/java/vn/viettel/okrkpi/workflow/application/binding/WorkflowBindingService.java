package vn.viettel.okrkpi.workflow.application.binding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.workflow.application.port.BindingDraftStore;
import vn.viettel.okrkpi.workflow.application.port.BindingSimulator;
import vn.viettel.okrkpi.workflow.application.port.TemplateDirectory;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.binding.BindingDraft;
import vn.viettel.okrkpi.workflow.domain.binding.BindingScope;
import vn.viettel.okrkpi.workflow.domain.binding.BindingStatus;
import vn.viettel.okrkpi.workflow.domain.binding.LintIssue;
import vn.viettel.okrkpi.workflow.domain.binding.LintResult;
import vn.viettel.okrkpi.workflow.domain.binding.ParamsLinter;
import vn.viettel.okrkpi.workflow.domain.binding.SampleRun;
import vn.viettel.okrkpi.workflow.domain.binding.SimulationSample;
import vn.viettel.okrkpi.workflow.domain.binding.SkipConditionChecker;
import vn.viettel.okrkpi.workflow.domain.binding.StepPreview;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateInfo;
import vn.viettel.okrkpi.workflow.domain.binding.TestSummary;

/**
 * Tham số luồng duyệt theo mẫu (DV-06-08, QT-15, SCR-1504; BR-15-11, BR-15-12): đọc và lưu tham số
 * của một phiên bản Policy Pack, chạy linter và bộ hồ sơ mẫu trên engine thử khi lưu, mô phỏng
 * không ghi gì, và chép tham số vào {@code wf_binding} khi saga phát hành gói của MOD-05 kích hoạt
 * (CORE-05-10 bước 4). Người gọi là MOD-05 qua API nội bộ; kiểm phiên bản gói, khoá kỳ do MOD-05
 * làm trước (CORE-05-11).
 */
@Service
public class WorkflowBindingService {

  /** Mã lỗi của hợp đồng {@code saveWorkflowBinding}, giữ nguyên khi MOD-05 chuyển tiếp. */
  public static final String WF_PARAMS = "POLICY-422-WF-PARAMS";

  public static final String CEL_TYPE = "POLICY-422-CEL-TYPE";
  public static final String TEMPLATE_NOT_ACTIVE = "POLICY-422-TEMPLATE-NOT-ACTIVE";
  static final String FROZEN = "POLICY-409-VERSION-FROZEN";
  static final String MSG = "MSG-15-09";

  private final BindingDraftStore drafts;
  private final TemplateDirectory templates;
  private final BindingSimulator simulator;
  private final ParamsLinter linter;

  public WorkflowBindingService(
      BindingDraftStore drafts,
      TemplateDirectory templates,
      BindingSimulator simulator,
      SkipConditionChecker cel) {
    this.drafts = drafts;
    this.templates = templates;
    this.simulator = simulator;
    this.linter = new ParamsLinter(cel);
  }

  /**
   * Nội dung lưu ({@code WorkflowBindingUpdate}).
   *
   * @param tenantId tenant
   * @param packVersionId phiên bản Policy Pack
   * @param processKind loại quy trình
   * @param templateKey mã mẫu
   * @param templateVersionNo phiên bản mẫu
   * @param scope phạm vi
   * @param params tham số
   * @param expectedRowVersion phiên bản trong {@code If-Match}; 0 khi lưu lần đầu
   * @param actor người gọi
   */
  public record SaveCommand(
      UUID tenantId,
      UUID packVersionId,
      String processKind,
      String templateKey,
      int templateVersionNo,
      BindingScope scope,
      Map<String, Object> params,
      long expectedRowVersion,
      UUID actor) {

    public SaveCommand {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(packVersionId, "packVersionId");
      Objects.requireNonNull(processKind, "processKind");
      Objects.requireNonNull(templateKey, "templateKey");
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(actor, "actor");
    }
  }

  /**
   * Kết quả mô phỏng ({@code WorkflowSimulateResult}).
   *
   * @param steps các bước sinh ra
   * @param lint kết quả linter
   * @param templateTests bộ hồ sơ mẫu mặc định
   * @param sampleRuns các ca đã chạy
   */
  public record Simulation(
      List<StepPreview> steps,
      LintResult lint,
      TestSummary templateTests,
      List<SampleRun> sampleRuns) {}

  @Transactional(readOnly = true)
  public List<BindingDraft> list(UUID tenantId, UUID packVersionId) {
    return drafts.list(tenantId, packVersionId);
  }

  @Transactional(readOnly = true)
  public Optional<BindingDraft> find(UUID tenantId, UUID packVersionId, String processKind) {
    return drafts.find(tenantId, packVersionId, processKind);
  }

  /**
   * {@code saveWorkflowBinding}: mẫu phải đã phát hành và phục vụ đúng loại quy trình, tham số qua
   * linter, mọi hồ sơ mẫu đạt; lệch {@code If-Match} trả 409.
   */
  @Transactional
  public BindingDraft save(SaveCommand c) {
    Optional<BindingDraft> current = drafts.find(c.tenantId(), c.packVersionId(), c.processKind());
    long currentVersion = current.map(BindingDraft::rowVersion).orElse(0L);
    if (currentVersion != c.expectedRowVersion()) {
      throw VersionConflictException.of("POLICY", c.expectedRowVersion(), currentVersion);
    }
    if (current.isPresent() && current.get().status() != BindingStatus.NHAP) {
      throw new BusinessException(
          FROZEN, "Tham số luồng của phiên bản đã phát hành không sửa được");
    }
    TemplateInfo template = template(c.templateKey(), c.templateVersionNo());
    if (!template.processKind().equals(c.processKind())) {
      throw invalid(
          WF_PARAMS,
          "Mẫu luồng đã chọn không phục vụ loại quy trình này",
          List.of(
              ProblemFieldError.of(
                  "templateKey", "Mẫu không phục vụ loại quy trình", "loai-quy-trinh")));
    }
    checkScope(c.scope());
    LintResult lint = linter.lint(c.params(), template.rules());
    if (!lint.passed()) {
      throw lintFailed(lint);
    }
    List<SampleRun> runs = runAll(template, c.params(), List.of(SimulationSample.values()));
    TestSummary tests = summary(runs);
    if (!tests.allPassed()) {
      throw invalid(
          WF_PARAMS,
          "Mẫu luồng chưa chạy đúng với tham số này ("
              + tests.passed()
              + "/"
              + tests.total()
              + " hồ sơ mẫu đạt)",
          runs.stream()
              .filter(r -> !r.passed())
              .map(
                  r ->
                      ProblemFieldError.of(
                          "params", "Hồ sơ mẫu chưa đạt: " + r.sample().label(), "kiem-thu-mau"))
              .toList());
    }
    if (current.isEmpty()) {
      return drafts.insert(draft(UUID.randomUUID(), c, lint, tests, 0), c.actor());
    }
    return drafts
        .update(
            draft(current.get().id(), c, lint, tests, currentVersion), currentVersion, c.actor())
        .orElseThrow(() -> new VersionConflictException("POLICY", c.expectedRowVersion(), null));
  }

  /**
   * {@code simulateWorkflowBinding}: linter, các bước sinh ra và chạy các hồ sơ mẫu trên engine
   * thử; không ghi gì ở CSDL của cell.
   */
  public Simulation simulate(
      String templateKey, int templateVersionNo, Map<String, Object> params, List<String> samples) {
    TemplateInfo template = template(templateKey, templateVersionNo);
    List<SimulationSample> chosen = new ArrayList<>();
    List<ProblemFieldError> unknown = new ArrayList<>();
    for (String code : samples == null ? List.<String>of() : samples) {
      SimulationSample.parse(code)
          .ifPresentOrElse(
              chosen::add,
              () ->
                  unknown.add(
                      ProblemFieldError.of(
                          "samples", "Hồ sơ mẫu không có trong bộ mẫu", "ho-so-mau")));
    }
    if (!unknown.isEmpty()) {
      throw invalid(WF_PARAMS, "Hồ sơ mẫu đã chọn không có trong bộ mẫu", unknown);
    }
    LintResult lint = linter.lint(params, template.rules());
    List<StepPreview> steps = linter.steps(params, template.rules());
    if (!lint.passed()) {
      return new Simulation(steps, lint, null, List.of());
    }
    List<SampleRun> all = runAll(template, params, List.of(SimulationSample.values()));
    List<SampleRun> shown =
        chosen.isEmpty() ? all : all.stream().filter(r -> chosen.contains(r.sample())).toList();
    return new Simulation(steps, lint, summary(all), shown);
  }

  /** Kích hoạt khi phát hành gói (saga CORE-05-10 bước 4). */
  @Transactional
  public List<BindingDraft> activate(UUID tenantId, UUID packVersionId, UUID actor) {
    return drafts.activate(tenantId, packVersionId, actor);
  }

  /** Gỡ kích hoạt khi bù trừ hoặc thu hồi gói. */
  @Transactional
  public List<BindingDraft> deactivate(UUID tenantId, UUID packVersionId, UUID actor) {
    return drafts.deactivate(tenantId, packVersionId, actor);
  }

  /** Danh mục mẫu đã phát hành, lọc theo loại quy trình và từ khoá. */
  public List<TemplateInfo> templates(String processKind, String q) {
    String needle = q == null ? null : q.strip().toLowerCase(java.util.Locale.ROOT);
    return templates.published().stream()
        .filter(t -> processKind == null || processKind.equals(t.processKind()))
        .filter(
            t ->
                needle == null
                    || needle.isEmpty()
                    || t.key().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || t.name().toLowerCase(java.util.Locale.ROOT).contains(needle))
        .toList();
  }

  public Optional<TemplateInfo> findTemplate(String key, int versionNo) {
    return templates.find(key, versionNo);
  }

  /** Các bước mặc định và khai báo tham số của mẫu. */
  public static List<StepPreview> previewSteps(TemplateInfo template) {
    return ParamsLinter.defaultSteps(template.rules());
  }

  /** Số hồ sơ mẫu của bộ kiểm thử mặc định ({@code testCount}). */
  public static int testCount() {
    return SimulationSample.values().length;
  }

  private TemplateInfo template(String key, int versionNo) {
    return templates
        .find(key, versionNo)
        .orElseThrow(
            () ->
                invalid(
                    TEMPLATE_NOT_ACTIVE,
                    "Mẫu luồng đã chọn không có hoặc chưa được phát hành",
                    List.of(
                        ProblemFieldError.of(
                            "templateKey", "Mẫu luồng chưa được phát hành", "BR-15-11"))));
  }

  private List<SampleRun> runAll(
      TemplateInfo template, Map<String, Object> params, List<SimulationSample> samples) {
    List<SampleRun> runs = new ArrayList<>(samples.size());
    for (SimulationSample sample : samples) {
      runs.add(
          simulator.run(
              template, params, sample, linter.skippedOrders(params, sample.variables())));
    }
    return runs;
  }

  private static TestSummary summary(List<SampleRun> runs) {
    return new TestSummary((int) runs.stream().filter(SampleRun::passed).count(), runs.size());
  }

  private static void checkScope(BindingScope scope) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (scope.flow() != null && ApprovalFlow.parse(scope.flow()).isEmpty()) {
      errors.add(
          ProblemFieldError.of(
              "scope.flow", "Luồng phải là Đảng hoặc Nhà nước", "pham-vi-ap-dung"));
    }
    if (scope.periodType() != null
        && (scope.periodType().isBlank() || scope.periodType().length() > 8)) {
      errors.add(
          ProblemFieldError.of("scope.periodType", "Loại kỳ không hợp lệ", "pham-vi-ap-dung"));
    }
    if (!errors.isEmpty()) {
      throw invalid(WF_PARAMS, "Phạm vi áp dụng chưa hợp lệ", errors);
    }
  }

  private static BindingDraft draft(
      UUID id, SaveCommand c, LintResult lint, TestSummary tests, long rowVersion) {
    return new BindingDraft(
        id,
        c.tenantId(),
        c.packVersionId(),
        c.processKind(),
        c.templateKey(),
        c.templateVersionNo(),
        c.scope(),
        c.params(),
        lint,
        tests,
        BindingStatus.NHAP,
        rowVersion);
  }

  private static BusinessException lintFailed(LintResult lint) {
    List<ProblemFieldError> errors =
        lint.issues().stream()
            .filter(LintIssue::isError)
            .map(i -> ProblemFieldError.of(i.elementId(), i.message(), i.rule()))
            .toList();
    return invalid(
        lint.onlyCelType() ? CEL_TYPE : WF_PARAMS, "Tham số luồng duyệt chưa hợp lệ", errors);
  }

  private static BusinessException invalid(
      String code, String detail, List<ProblemFieldError> errors) {
    return new BusinessException(code, detail, errors, MSG);
  }
}
