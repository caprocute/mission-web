package vn.viettel.okrkpi.workflow.application.stage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.port.ApprovalProcesses;
import vn.viettel.okrkpi.workflow.application.port.BindingReader;
import vn.viettel.okrkpi.workflow.application.port.StageProcesses;
import vn.viettel.okrkpi.workflow.application.port.StageProcesses.Snapshot;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;
import vn.viettel.okrkpi.workflow.domain.binding.SkipConditionChecker;
import vn.viettel.okrkpi.workflow.domain.stage.Participants;
import vn.viettel.okrkpi.workflow.domain.stage.StageDeadlines;
import vn.viettel.okrkpi.workflow.domain.stage.StageStep;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;
import vn.viettel.okrkpi.workflow.domain.stage.StageVariables;

/**
 * Điều khiển bốn mẫu phần 2 (DV-06-04) theo tín hiệu của lõi: khởi tạo, báo bước đã xong (cổng
 * {@code advance}, EVT-11), chữ ký đã hoàn tất (EVT-18), khiếu nại đã gửi (EVT-25) và Policy Pack
 * đã phát hành (EVT-13).
 *
 * <p>Tín hiệu cho bước đang mở thì hoàn thành bước; tín hiệu cho bước sau (lõi đã đi trước, ví dụ
 * sự kiện về sau lệnh) thì chuyển tiến trình tới bước đó rồi hoàn thành; tín hiệu cho bước đã qua
 * (trùng hoặc đến muộn) thì bỏ qua. Mọi thao tác idempotent nên consumer giao lại không sinh việc
 * mới.
 */
@Service
public class StageProcessService {

  /** Tiêu đề việc tối đa (cột {@code wf_task_projection.title}). */
  static final int MAX_TITLE = 255;

  private static final Logger log = LoggerFactory.getLogger(StageProcessService.class);

  private final ApprovalProcesses starter;
  private final StageProcesses processes;
  private final BindingReader bindings;
  private final StagePlanner planner;
  private final SkipConditionChecker skips;

  public StageProcessService(
      ApprovalProcesses starter,
      StageProcesses processes,
      BindingReader bindings,
      StagePlanner planner,
      SkipConditionChecker skips) {
    this.starter = starter;
    this.processes = processes;
    this.bindings = bindings;
    this.planner = planner;
    this.skips = skips;
  }

  /** Kết quả xử lý một tín hiệu. */
  public enum Outcome {
    /** Đã khởi tạo tiến trình mới. */
    STARTED,
    /** Tiến trình cho cùng đối tượng và phiên bản đã có. */
    EXISTING,
    /** Đã hoàn thành bước đang mở. */
    ADVANCED,
    /** Lõi đã đi trước: đã chuyển tới bước của tín hiệu rồi hoàn thành. */
    SHORTCUT,
    /** Đã giao tín hiệu ký hoặc phát hành, hoặc ghi cờ chờ. */
    SIGNALLED,
    /** Tín hiệu cho bước đã qua hoặc không thuộc mẫu nào. */
    IGNORED,
    /** Không có tiến trình đang chạy cho đối tượng. */
    NO_PROCESS
  }

  /** Khởi tạo một mẫu; gửi lại cùng đối tượng và phiên bản trả tiến trình đã có. */
  @Transactional(timeout = 10)
  public Outcome start(StartStageCommand c) {
    StageTemplate template = c.template();
    if (c.title().isBlank() || c.title().length() > MAX_TITLE) {
      throw new ApprovalSetupException("Tiêu đề việc phải có và tối đa 255 ký tự");
    }
    if (template == StageTemplate.DUYET_HO_SO_DANH_GIA && c.subjectPersonId() == null) {
      throw new ApprovalSetupException("Thiếu người được đánh giá");
    }
    if (template == StageTemplate.XU_LY_KHIEU_NAI && c.overallDue() == null) {
      throw new ApprovalSetupException("Thiếu hạn trả lời khiếu nại");
    }
    BusinessKey key = new BusinessKey(template.businessType(), c.businessId());
    starter.lockStart(c.tenantId(), template.code(), key);
    if (starter.findStarted(c.tenantId(), template.code(), key, c.objectVersion()).isPresent()) {
      return Outcome.EXISTING;
    }
    Optional<String> raw = bindings.rawParams(c.tenantId(), template.processKind(), c.flow());
    StagePlanner.Planned planned = planner.plan(template, raw);
    Map<String, Object> v = new LinkedHashMap<>();
    v.put(ApprovalVariables.TENANT_ID, c.tenantId().toString());
    v.put(ApprovalVariables.KHOI, c.flow().name());
    v.put(ApprovalVariables.LOAI_HO_SO, template.processKind());
    v.put(ApprovalVariables.LA_NGUOI_DUNG_DAU, c.headOfUnit());
    v.put(ApprovalVariables.DON_VI_ID, c.unitId().toString());
    v.put(ApprovalVariables.TIEU_DE, c.title().strip());
    v.put(ApprovalVariables.PHIEN_BAN_DOI_TUONG, c.objectVersion());
    v.put(StageVariables.KE_HOACH_BUOC, planned.plan().encode());
    v.put(StageVariables.SO_LAN_QUA_HAN, "");
    v.put(StageVariables.KET_QUA, "");
    if (c.creatorId() != null) {
      v.put(StageVariables.NGUOI_TAO, c.creatorId().toString());
    }
    Map<String, List<UUID>> participants = new HashMap<>(c.participants());
    if (c.profileId() != null) {
      v.put(StageVariables.HO_SO_ID, c.profileId().toString());
      processes
          .appraisalHistory(c.tenantId(), c.profileId())
          .ifPresent(h -> h.participants().forEach((g, p) -> merge(participants, g, p)));
    }
    if (!participants.isEmpty()) {
      v.put(StageVariables.THAM_GIA, Participants.encode(participants));
    }
    switch (template) {
      case DUYET_HO_SO_DANH_GIA -> {
        v.put(StageVariables.NGUOI_DUOC_DANH_GIA, c.subjectPersonId().toString());
        v.put(StageVariables.CHE_DO, c.periodMode() == null ? "QUY" : c.periodMode());
        v.put(StageVariables.SO_LAN_TRA_LAI, 0);
        v.put(StageVariables.MAX_TRA_LAI, planned.maxReturns());
        v.put(StageVariables.BO_QUA_DE_XUAT, false);
        for (StageStep step : template.steps()) {
          if (step.signs()) {
            v.put(StageVariables.signedFlag(step.elementId()), false);
          }
        }
      }
      case XU_LY_KHIEU_NAI -> v.put(StageVariables.HAN_TONG_THE, c.overallDue().toString());
      case DUYET_PHAT_HANH_POLICY_PACK -> v.put(StageVariables.DA_PHAT_HANH, false);
      case MO_LAI_KY_HAI_NGUOI -> {
        // Không có biến riêng: hai người duyệt do worker chọn người ghi.
      }
    }
    if (ApprovalVariables.sizeOf(v) > ApprovalVariables.MAX_START_BYTES) {
      throw new ApprovalSetupException("Biến khởi tạo tiến trình vượt ngân sách 1,5 KB");
    }
    starter.start(c.tenantId(), template.code(), key, c.creatorId(), v);
    return Outcome.STARTED;
  }

  /** Lõi báo bước đã xong. */
  @Transactional(timeout = 10)
  public Outcome advance(AdvanceStageCommand c) {
    StageStep step =
        c.template()
            .step(c.step())
            .orElseThrow(() -> new ApprovalSetupException("Bước không thuộc mẫu"));
    if (!StageTemplate.outcomes(step.elementId()).contains(c.outcome())) {
      throw new ApprovalSetupException("Kết quả không hợp lệ cho bước này");
    }
    Optional<Snapshot> found = processes.active(c.tenantId(), c.template(), c.businessId());
    if (found.isEmpty()) {
      log.info(
          "Không có tiến trình {} đang chạy cho đối tượng {}", c.template().code(), c.businessId());
      return Outcome.NO_PROCESS;
    }
    Snapshot s = found.get();
    if (step.elementId().equals(s.openStep())) {
      complete(c, s, step);
      return Outcome.ADVANCED;
    }
    if (2 * c.template().indexOf(step.elementId()) <= position(c.template(), s)) {
      return Outcome.IGNORED;
    }
    processes.moveTo(s.processInstanceId(), main(c.template(), s), step.elementId());
    Snapshot moved = processes.active(c.tenantId(), c.template(), c.businessId()).orElseThrow();
    if (!step.elementId().equals(moved.openStep())) {
      return Outcome.IGNORED;
    }
    complete(c, moved, step);
    return Outcome.SHORTCUT;
  }

  /** EVT-11: ánh xạ bước, trạng thái đích của hồ sơ sang bước và kết quả của mẫu hồ sơ đánh giá. */
  @Transactional(timeout = 10)
  public Outcome onAppraisalStep(
      UUID tenantId, UUID profileId, String stage, String toStatus, UUID actorId) {
    if ("DA_KY_SO".equals(toStatus)) {
      return onSigned(tenantId, "appraisal", "QUYET_DINH", profileId, actorId);
    }
    Optional<String[]> mapped = appraisalStep(stage, toStatus);
    if (mapped.isEmpty()) {
      return Outcome.IGNORED;
    }
    return advance(
        new AdvanceStageCommand(
            tenantId,
            StageTemplate.DUYET_HO_SO_DANH_GIA,
            profileId,
            mapped.get()[0],
            mapped.get()[1],
            actorId,
            Map.of()));
  }

  /** Bước và kết quả của mẫu hồ sơ đánh giá theo EVT-11. */
  static Optional<String[]> appraisalStep(String stage, String toStatus) {
    String key = stage + "/" + toStatus;
    return Optional.ofNullable(
        switch (key) {
          case "TU_DE_XUAT/CHO_THAM_DINH" -> new String[] {"tuDanhGia", "NOP"};
          case "THAM_DINH/DA_THAM_DINH" -> new String[] {"thamDinh", "DUYET"};
          case "THAM_DINH/TU_DANH_GIA" -> new String[] {"thamDinh", "TRA_LAI"};
          case "DE_XUAT/CHO_QUYET_DINH" -> new String[] {"deXuat", "DUYET"};
          case "QUYET_DINH/DA_QUYET_DINH" -> new String[] {"quyetDinh", "DUYET"};
          case "QUYET_DINH/DA_THAM_DINH" -> new String[] {"quyetDinh", "XEM_LAI"};
          default -> null;
        });
  }

  /**
   * EVT-18: chữ ký của một mốc đã hoàn tất. Với mẫu có chữ ký là tín hiệu xong bước, hoàn thành
   * bước đang mở có mốc đó; với hồ sơ đánh giá, mở chốt chờ ký (hoặc ghi cờ khi chữ ký về trước
   * EVT-11).
   */
  @Transactional(timeout = 10)
  public Outcome onSigned(
      UUID tenantId, String sourceModule, String purpose, UUID sourceRefId, UUID actorId) {
    Optional<StageTemplate> template = StageTemplate.bySignature(sourceModule, purpose);
    if (template.isEmpty()) {
      return Outcome.IGNORED;
    }
    StageTemplate t = template.get();
    Optional<Snapshot> found = processes.active(tenantId, t, sourceRefId);
    if (found.isEmpty()) {
      return Outcome.NO_PROCESS;
    }
    Snapshot s = found.get();
    if (t.signCompletesStep()) {
      Optional<StageStep> open = Optional.ofNullable(s.openStep()).flatMap(t::step);
      if (open.isEmpty() || !purpose.equals(open.get().signPurpose())) {
        return Outcome.IGNORED;
      }
      String id = open.get().elementId();
      return advance(
          new AdvanceStageCommand(
              tenantId, t, sourceRefId, id, StageTemplate.signedOutcome(id), actorId, Map.of()));
    }
    for (StageStep step : t.steps()) {
      if (!purpose.equals(step.signPurpose())) {
        continue;
      }
      Map<String, Object> flag = Map.of(StageVariables.signedFlag(step.elementId()), true);
      if (s.activeElementIds().contains(StageVariables.signWait(step.elementId()))) {
        processes.deliverSigned(s.processInstanceId(), flag);
        return Outcome.SIGNALLED;
      }
      if (step.elementId().equals(s.openStep())) {
        processes.setVariables(s.processInstanceId(), flag);
        return Outcome.SIGNALLED;
      }
    }
    return Outcome.IGNORED;
  }

  /**
   * EVT-25: khởi tạo {@code xu-ly-khieu-nai}. Đơn vị xử lý là đơn vị của bước thẩm định hồ sơ (suy
   * luận), người đã tham gia đọc từ tiến trình hồ sơ; không có lịch sử thì không khởi tạo và ghi
   * log để vận hành xử lý.
   */
  @Transactional(timeout = 10)
  public Outcome onComplaintFiled(
      UUID tenantId,
      UUID complaintId,
      UUID profileId,
      UUID complainantId,
      LocalDate responseDeadline,
      long version) {
    Optional<StageProcesses.ProfileHistory> history =
        processes.appraisalHistory(tenantId, profileId);
    if (history.isEmpty()) {
      log.warn(
          "Khiếu nại {} không có lịch sử luồng hồ sơ {}, chưa khởi tạo được luồng xử lý",
          complaintId,
          profileId);
      return Outcome.NO_PROCESS;
    }
    StageProcesses.ProfileHistory h = history.get();
    Instant due =
        responseDeadline.atTime(StageDeadlines.DUE_TIME).atZone(StageDeadlines.ZONE).toInstant();
    return start(
        new StartStageCommand(
            tenantId,
            StageTemplate.XU_LY_KHIEU_NAI,
            complaintId,
            version,
            h.flow(),
            h.appraisalUnitId() == null ? h.subjectUnitId() : h.appraisalUnitId(),
            "Xử lý khiếu nại kết quả đánh giá",
            complainantId,
            null,
            profileId,
            false,
            null,
            Map.of(),
            due));
  }

  /** EVT-13: phiên bản Policy Pack đã phát hành; tiến trình duyệt phát hành kết thúc. */
  @Transactional(timeout = 10)
  public Outcome onPolicyPublished(UUID tenantId, UUID versionId) {
    StageTemplate t = StageTemplate.DUYET_PHAT_HANH_POLICY_PACK;
    Optional<Snapshot> found = processes.active(tenantId, t, versionId);
    if (found.isEmpty()) {
      return Outcome.NO_PROCESS;
    }
    Snapshot s = found.get();
    if (processes.deliverPublished(s.processInstanceId())) {
      return Outcome.SIGNALLED;
    }
    processes.setVariables(s.processInstanceId(), Map.of(StageVariables.DA_PHAT_HANH, true));
    if ("pheDuyet".equals(s.openStep())) {
      return advance(
          new AdvanceStageCommand(tenantId, t, versionId, "pheDuyet", "DUYET", null, Map.of()));
    }
    return Outcome.SIGNALLED;
  }

  private void complete(AdvanceStageCommand c, Snapshot s, StageStep step) {
    Map<String, Object> v = new HashMap<>();
    v.put(StageVariables.KET_QUA, c.outcome());
    String id = step.elementId();
    if ("thamDinh".equals(id) && "TRA_LAI".equals(c.outcome())) {
      int used = number(s.variables().get(StageVariables.SO_LAN_TRA_LAI)) + 1;
      if (used > number(s.variables().get(StageVariables.MAX_TRA_LAI))) {
        throw new ApprovalSetupException("Hồ sơ đã được trả lại tối đa số lần cho phép");
      }
      v.put(StageVariables.SO_LAN_TRA_LAI, used);
    }
    if ("thamDinh".equals(id) && "DUYET".equals(c.outcome())) {
      Map<String, Object> facts = new HashMap<>(s.variables());
      putFact(c, facts, v, StageVariables.MUC_TU_DE_XUAT);
      putFact(c, facts, v, StageVariables.MUC_THAM_DINH);
      putFact(c, facts, v, StageVariables.CHUC_DANH);
      v.put(StageVariables.BO_QUA_DE_XUAT, skipProposal(c, facts));
    }
    processes.completeStep(s.openTaskId(), c.actorId(), v);
  }

  private static void putFact(
      AdvanceStageCommand c, Map<String, Object> facts, Map<String, Object> v, String name) {
    String value = c.facts().get(name);
    if (value != null && value.length() <= 64) {
      facts.put(name, value);
      v.put(name, value);
    }
  }

  /** Điều kiện CEL {@code bo_qua_khi} của bước đề xuất; lỗi đánh giá thì không bỏ qua. */
  boolean skipProposal(AdvanceStageCommand c, Map<String, Object> facts) {
    Object flowCode = facts.get(ApprovalVariables.KHOI);
    Optional<String> raw =
        vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow.parse(String.valueOf(flowCode))
            .flatMap(f -> bindings.rawParams(c.tenantId(), c.template().processKind(), f));
    Optional<String> expression =
        raw.flatMap(r -> planner.skipCondition(c.template(), "DE_XUAT", r));
    if (expression.isEmpty()) {
      return false;
    }
    Map<String, Object> cel = new HashMap<>();
    Map<String, Object> subject = new HashMap<>();
    subject.put("chuc_danh", String.valueOf(facts.getOrDefault(StageVariables.CHUC_DANH, "")));
    cel.put("nguoi_duoc_danh_gia", subject);
    cel.put("muc_tham_dinh", String.valueOf(facts.getOrDefault(StageVariables.MUC_THAM_DINH, "")));
    cel.put(
        "muc_tu_de_xuat", String.valueOf(facts.getOrDefault(StageVariables.MUC_TU_DE_XUAT, "")));
    cel.put(
        "la_nguoi_dung_dau", Boolean.TRUE.equals(facts.get(ApprovalVariables.LA_NGUOI_DUNG_DAU)));
    cel.put("khoi", String.valueOf(flowCode));
    try {
      return skips.evaluate(expression.get(), cel);
    } catch (RuntimeException e) {
      log.warn("Điều kiện bỏ qua bước đề xuất không đánh giá được, giữ bước đề xuất", e);
      return false;
    }
  }

  /**
   * Vị trí xa nhất tiến trình đã tới: bước i là 2i, chờ ký sau bước i là 2i+1, chọn người là 2i-1.
   */
  static int position(StageTemplate t, Snapshot s) {
    int max = -1;
    for (String id : s.activeElementIds()) {
      max = Math.max(max, rank(t, id));
    }
    return max;
  }

  private static int rank(StageTemplate t, String id) {
    int i = t.indexOf(id);
    if (i >= 0) {
      return 2 * i;
    }
    if (id.startsWith("choKy_")) {
      return 2 * t.indexOf(id.substring("choKy_".length())) + 1;
    }
    if (id.startsWith(StageVariables.CHOOSER_PREFIX)) {
      return 2 * t.indexOf(id.substring(StageVariables.CHOOSER_PREFIX.length())) - 1;
    }
    if ("khoiChayPhatHanh".equals(id) || "choPhatHanh".equals(id)) {
      return 2 * t.steps().size() - 1;
    }
    return -1;
  }

  /** Phần tử chính đang chạy (bỏ nhánh nhắc việc và sự kiện biên). */
  static List<String> main(StageTemplate t, Snapshot s) {
    List<String> out = new ArrayList<>();
    for (String id : s.activeElementIds()) {
      if (t.indexOf(id) >= 0
          || id.startsWith("choKy_")
          || id.startsWith(StageVariables.CHOOSER_PREFIX)) {
        out.add(id);
      }
    }
    return out;
  }

  private static void merge(Map<String, List<UUID>> into, String group, List<UUID> people) {
    List<UUID> all = new ArrayList<>(into.getOrDefault(group, List.of()));
    people.stream().filter(p -> !all.contains(p)).forEach(all::add);
    into.put(group, all);
  }

  private static int number(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }
}
