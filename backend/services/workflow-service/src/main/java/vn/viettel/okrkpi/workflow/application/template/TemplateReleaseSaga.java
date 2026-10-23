package vn.viettel.okrkpi.workflow.application.template;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.application.port.CellEngineGateway;
import vn.viettel.okrkpi.workflow.application.port.RolloutCalendar;
import vn.viettel.okrkpi.workflow.application.port.TemplateCodeHost;
import vn.viettel.okrkpi.workflow.application.port.TemplateEventPublisher;
import vn.viettel.okrkpi.workflow.application.port.TemplateReleaseStore;
import vn.viettel.okrkpi.workflow.application.port.TemplateRolloutStore;
import vn.viettel.okrkpi.workflow.application.port.WorkflowTemplateStore;
import vn.viettel.okrkpi.workflow.application.saga.SagaBlueprint;
import vn.viettel.okrkpi.workflow.application.saga.SagaStepHandler;
import vn.viettel.okrkpi.workflow.application.saga.SagaStepHandlerSet;
import vn.viettel.okrkpi.workflow.application.saga.StepInvocation;
import vn.viettel.okrkpi.workflow.application.saga.StepResult;
import vn.viettel.okrkpi.workflow.domain.saga.RetryPolicy;
import vn.viettel.okrkpi.workflow.domain.saga.SagaDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;
import vn.viettel.okrkpi.workflow.domain.template.CellRollout;
import vn.viettel.okrkpi.workflow.domain.template.Release;
import vn.viettel.okrkpi.workflow.domain.template.RolloutStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;

/**
 * Saga {@code PHAT_HANH_MAU_LUONG} trên khung DV-06-05 (BS-01 "Triển khai lên cell", hợp đồng
 * {@code approveWorkflowTemplate}): chờ pipeline ký gói mẫu (cosign), ghi {@code HIEU_LUC} cùng chữ
 * ký và người phê duyệt, phát sự kiện qua outbox, rồi triển khai cuốn chiếu từng cell theo thứ tự
 * cấu hình.
 *
 * <p>Mỗi bước cell: từ chối trong khung cấm (giờ cao điểm, ba ngày cuối kỳ), kiểm sức khoẻ engine
 * của cell trước và sau khi triển khai; engine không khoẻ sau triển khai thì đặt lại ngay phiên bản
 * trước. Hết số lần thử thì khung bù trừ ngược các cell đã triển khai bằng {@code DAT_LAI_MAC_DINH}
 * (đặt lại phiên bản trước làm mặc định) và saga dừng ở {@code DA_BU_TRU}. Bước {@code
 * CHUYEN_HIEU_LUC} không bù trừ vì phiên bản đã phát hành là bất biến.
 */
public final class TemplateReleaseSaga implements SagaBlueprint, SagaStepHandlerSet {

  public static final String P_TEMPLATE_ID = "templateId";
  public static final String P_TEMPLATE_CODE = "templateCode";
  public static final String P_VERSION_NO = "versionNo";
  public static final String P_APPROVER = "approvedBy";
  public static final String P_CELLS = "cells";

  public static final String STEP_SIGN = "CHO_KY_SO";
  public static final String STEP_ACTIVATE = "CHUYEN_HIEU_LUC";
  public static final String STEP_CELL_PREFIX = "TRIEN_KHAI_CELL_";
  public static final String COMPENSATION_CELL = "DAT_LAI_MAC_DINH";

  /** Sự kiện trả lời của pipeline ký, để truy vết (callback nội bộ gọi {@code onReply}). */
  public static final String REPLY_SIGNED = "ci.wftemplate.signed";

  private final Dependencies deps;
  private final ReleaseSettings settings;
  private final SagaDefinition definition;

  /** Các cổng saga dùng. */
  public record Dependencies(
      WorkflowTemplateStore store,
      TemplateReleaseStore releases,
      TemplateRolloutStore rollouts,
      TemplateCodeHost codeHost,
      CellEngineGateway cells,
      RolloutCalendar calendar,
      TemplateEventPublisher events,
      RolloutProgress progress,
      Clock clock) {
    public Dependencies {
      Objects.requireNonNull(store, "store");
      Objects.requireNonNull(releases, "releases");
      Objects.requireNonNull(rollouts, "rollouts");
      Objects.requireNonNull(codeHost, "codeHost");
      Objects.requireNonNull(cells, "cells");
      Objects.requireNonNull(calendar, "calendar");
      Objects.requireNonNull(events, "events");
      Objects.requireNonNull(progress, "progress");
      Objects.requireNonNull(clock, "clock");
    }
  }

  public TemplateReleaseSaga(Dependencies deps, ReleaseSettings settings) {
    this.deps = Objects.requireNonNull(deps, "deps");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.definition = buildDefinition(settings);
  }

  static SagaDefinition buildDefinition(ReleaseSettings settings) {
    List<SagaStepDefinition> steps = new ArrayList<>();
    steps.add(
        new SagaStepDefinition(
            STEP_SIGN,
            "code-host.merge-request.approve",
            REPLY_SIGNED,
            settings.signatureTimeout(),
            RetryPolicy.standard(),
            null,
            false));
    steps.add(
        new SagaStepDefinition(
            STEP_ACTIVATE,
            "config.wftemplate.published.v1",
            null,
            Duration.ofMinutes(1),
            RetryPolicy.standard(),
            null,
            false));
    for (int i = 1; i <= settings.cells().size(); i++) {
      steps.add(
          new SagaStepDefinition(
              cellStep(i),
              "cell.engine.deploy",
              null,
              Duration.ofMinutes(5),
              settings.cellRetry(),
              COMPENSATION_CELL,
              false));
    }
    return new SagaDefinition(SagaType.PHAT_HANH_MAU_LUONG, steps, true);
  }

  static String cellStep(int index) {
    return STEP_CELL_PREFIX + String.format("%02d", index);
  }

  @Override
  public SagaDefinition definition() {
    return definition;
  }

  /** Không cần khoá độc quyền: saga khởi động idempotent theo mã phiên bản mẫu. */
  @Override
  public String exclusiveKey(Map<String, Object> parameters) {
    return null;
  }

  @Override
  public List<SagaStepHandler> handlers() {
    List<SagaStepHandler> all = new ArrayList<>();
    all.add(new SignHandler());
    all.add(new ActivateHandler());
    for (int i = 1; i <= settings.cells().size(); i++) {
      all.add(new CellHandler(i));
    }
    return List.copyOf(all);
  }

  // --- Bước 1: chờ pipeline ký -------------------------------------------------------------

  final class SignHandler implements SagaStepHandler {
    @Override
    public SagaType sagaType() {
      return SagaType.PHAT_HANH_MAU_LUONG;
    }

    @Override
    public String stepCode() {
      return STEP_SIGN;
    }

    @Override
    public StepResult execute(StepInvocation inv) {
      UUID templateId = templateId(inv);
      Release release =
          deps.releases()
              .find(templateId)
              .orElseThrow(() -> new IllegalStateException("Thiếu đề nghị phát hành của mẫu"));
      if (release.signed()) {
        return StepResult.completed();
      }
      deps.codeHost().approve(templateId, release.mergeRequestRef(), approver(inv));
      return StepResult.awaiting(
          UUID.nameUUIDFromBytes(inv.idempotencyKey().getBytes(StandardCharsets.UTF_8)));
    }
  }

  // --- Bước 2: HIEU_LUC và sự kiện ---------------------------------------------------------

  final class ActivateHandler implements SagaStepHandler {
    @Override
    public SagaType sagaType() {
      return SagaType.PHAT_HANH_MAU_LUONG;
    }

    @Override
    public String stepCode() {
      return STEP_ACTIVATE;
    }

    @Override
    public StepResult execute(StepInvocation inv) {
      UUID templateId = templateId(inv);
      TemplateVersion current =
          deps.store()
              .find(templateId)
              .orElseThrow(() -> new IllegalStateException("Không còn phiên bản mẫu"));
      if (current.status() == TemplateStatus.HIEU_LUC) {
        return StepResult.completed();
      }
      Release release =
          deps.releases()
              .find(templateId)
              .orElseThrow(() -> new IllegalStateException("Thiếu đề nghị phát hành của mẫu"));
      if (!release.signed()) {
        throw new IllegalStateException("Pipeline chưa ký gói mẫu");
      }
      TemplateVersion published =
          current.publish(release.signature(), approver(inv), null, deps.clock().instant());
      TemplateVersion saved =
          deps.store()
              .update(published, current.rowVersion())
              .orElseThrow(() -> new IllegalStateException("Phiên bản mẫu vừa bị ghi khác"));
      deps.releases().applied(templateId);
      deps.events().published(saved, cells(inv));
      return StepResult.completed();
    }
  }

  // --- Bước 3..n: từng cell ------------------------------------------------------------------

  final class CellHandler implements SagaStepHandler {
    private final int index;

    CellHandler(int index) {
      this.index = index;
    }

    @Override
    public SagaType sagaType() {
      return SagaType.PHAT_HANH_MAU_LUONG;
    }

    @Override
    public String stepCode() {
      return cellStep(index);
    }

    @Override
    public StepResult execute(StepInvocation inv) {
      UUID templateId = templateId(inv);
      String cell = cell(inv);
      Optional<CellRollout> row = deps.rollouts().find(templateId, cell);
      if (row.isPresent() && row.get().status() == RolloutStatus.DA_TRIEN_KHAI) {
        return StepResult.completed();
      }
      Optional<String> blocked = deps.calendar().window().blockedAt(deps.clock().instant());
      if (blocked.isPresent()) {
        fail(templateId, cell, blocked.get());
      }
      if (!deps.cells().healthy(cell)) {
        fail(templateId, cell, "Engine của cell " + cell + " không khoẻ trước khi triển khai");
      }
      String code = (String) inv.parameters().get(P_TEMPLATE_CODE);
      int versionNo = ((Number) inv.parameters().get(P_VERSION_NO)).intValue();
      TemplateVersion template =
          deps.store()
              .find(templateId)
              .orElseThrow(() -> new IllegalStateException("Không còn phiên bản mẫu"));
      Integer previous = deps.cells().currentVersion(cell, code);
      if (previous != null && previous == versionNo) {
        previous = row.map(CellRollout::previousVersionNo).orElse(null);
      }
      String deploymentId = deps.cells().deploy(cell, code, versionNo, template.bpmnXml());
      if (!deps.cells().healthy(cell)) {
        deps.cells().restorePrevious(cell, code, previous, previousXml(code, previous));
        fail(templateId, cell, "Engine của cell " + cell + " không khoẻ sau khi triển khai");
      }
      deps.rollouts().deployed(templateId, cell, deploymentId, previous, deps.clock().instant());
      return StepResult.completed();
    }

    @Override
    public void compensate(StepInvocation inv) {
      UUID templateId = templateId(inv);
      String cell = cell(inv);
      Optional<CellRollout> row = deps.rollouts().find(templateId, cell);
      if (row.isEmpty() || row.get().status() == RolloutStatus.DA_BU_TRU) {
        return;
      }
      String code = (String) inv.parameters().get(P_TEMPLATE_CODE);
      Integer previous = row.get().previousVersionNo();
      deps.cells().restorePrevious(cell, code, previous, previousXml(code, previous));
      deps.rollouts().mark(templateId, cell, RolloutStatus.DA_BU_TRU, null);
    }

    private String cell(StepInvocation inv) {
      List<String> cells = cells(inv);
      if (index > cells.size()) {
        throw new IllegalStateException("Saga không có cell thứ " + index);
      }
      return cells.get(index - 1);
    }
  }

  private String previousXml(String code, Integer previous) {
    if (previous == null) {
      return null;
    }
    return deps.store().findReleased(code, previous).map(TemplateVersion::bpmnXml).orElse(null);
  }

  /**
   * Ghi lỗi của cell trong giao dịch riêng (giao dịch của bước sẽ bị huỷ) rồi ném để khung thử lại.
   */
  private void fail(UUID templateId, String cell, String reason) {
    deps.progress().failed(templateId, cell, reason);
    throw new CellRolloutException(reason);
  }

  private static UUID templateId(StepInvocation inv) {
    return UUID.fromString((String) inv.parameters().get(P_TEMPLATE_ID));
  }

  private static UUID approver(StepInvocation inv) {
    return UUID.fromString((String) inv.parameters().get(P_APPROVER));
  }

  @SuppressWarnings("unchecked")
  static List<String> cells(StepInvocation inv) {
    Object raw = inv.parameters().get(P_CELLS);
    if (raw instanceof List<?> list) {
      return list.stream().map(String::valueOf).toList();
    }
    return List.of();
  }

  /** Một lần triển khai lên cell không thành; khung saga tính là một lần thử thất bại. */
  public static final class CellRolloutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CellRolloutException(String message) {
      super(message);
    }
  }
}
