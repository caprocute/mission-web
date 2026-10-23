package vn.viettel.okrkpi.workflow.application.stage;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.workflow.application.port.AssigneeWorkload;
import vn.viettel.okrkpi.workflow.application.port.ComplaintHandlerPolicy;
import vn.viettel.okrkpi.workflow.application.port.ExternalJobQueue;
import vn.viettel.okrkpi.workflow.application.port.ExternalJobQueue.CallbackJob;
import vn.viettel.okrkpi.workflow.application.port.OrgDirectory;
import vn.viettel.okrkpi.workflow.application.port.RoleHolderDirectory;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;
import vn.viettel.okrkpi.workflow.domain.approval.OrgUnit;
import vn.viettel.okrkpi.workflow.domain.stage.CandidatePicker;
import vn.viettel.okrkpi.workflow.domain.stage.Participants;
import vn.viettel.okrkpi.workflow.domain.stage.StageDeadlines;
import vn.viettel.okrkpi.workflow.domain.stage.StagePlan;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;
import vn.viettel.okrkpi.workflow.domain.stage.StageVariables;

/**
 * External worker chọn người (chủ đề {@value #TOPIC}) trước các bước giao đích danh của mẫu phần 2,
 * chạy ngoài giao dịch của engine như worker gọi lại lõi:
 *
 * <ul>
 *   <li>Khiếu nại (BR-12-04): hỏi OPA gói {@code okrkpi.complaint} ứng viên {@code appraiser} hợp
 *       lệ trên chuỗi đơn vị xử lý và cấp trên, loại người khiếu nại, chọn người ít việc nhất.
 *   <li>Mở lại kỳ (BR-13-02, BR-13-03): hai người duyệt khác nhau, khác người tạo yêu cầu và chưa
 *       tham gia hồ sơ, ở cùng một đơn vị; đơn vị thiếu người thứ hai thì lên cấp trên, không bao
 *       giờ hạ xuống một người.
 *   <li>Phát hành Policy Pack (BR-15-09): người phê duyệt khác người soạn.
 * </ul>
 *
 * <p>Quá hạn lần {@code n} chọn lại từ đơn vị cấp trên thứ {@code n}. Không còn ai hợp lệ trên cả
 * chuỗi thì giao cho nhóm vai trò tại đơn vị gốc và ghi log; lõi vẫn kiểm tách vai trò khi nhận
 * lệnh ghi.
 */
@Service
public class AssigneeSelectionService {

  /** Chủ đề external worker. */
  public static final String TOPIC = "wf-chon-nguoi";

  static final int BATCH = 20;
  static final Duration LOCK = Duration.ofMinutes(2);
  static final Duration RETRY_AFTER = Duration.ofMinutes(1);
  static final int MAX_HOPS = 12;

  private static final Logger log = LoggerFactory.getLogger(AssigneeSelectionService.class);

  private final ExternalJobQueue queue;
  private final OrgDirectory directory;
  private final RoleHolderDirectory holders;
  private final AssigneeWorkload workload;
  private final ComplaintHandlerPolicy complaintPolicy;
  private final Clock clock;

  public AssigneeSelectionService(
      ExternalJobQueue queue,
      OrgDirectory directory,
      RoleHolderDirectory holders,
      AssigneeWorkload workload,
      ComplaintHandlerPolicy complaintPolicy,
      Clock clock) {
    this.queue = queue;
    this.directory = directory;
    this.holders = holders;
    this.workload = workload;
    this.complaintPolicy = complaintPolicy;
    this.clock = clock;
  }

  /** Một lượt; trả số việc đã chọn xong. */
  public int runOnce(String workerId) {
    int done = 0;
    for (CallbackJob job : queue.acquire(Set.of(TOPIC), BATCH, LOCK, workerId)) {
      try {
        queue.complete(job, workerId, choose(job));
        done++;
      } catch (IllegalArgumentException e) {
        queue.fail(
            job, workerId, "Biến tiến trình thiếu hoặc sai: " + e.getMessage(), 0, RETRY_AFTER);
      } catch (RuntimeException e) {
        log.warn("Chọn người cho tiến trình {} lỗi, thử lại sau", job.processInstanceId(), e);
        queue.fail(
            job,
            workerId,
            "Chưa chọn được người làm bước",
            Math.max(0, job.retries() - 1),
            RETRY_AFTER);
      }
    }
    return done;
  }

  /** Biến kết quả (người được chọn, đơn vị nơi chọn) cho việc chọn người {@code job}. */
  Map<String, Object> choose(CallbackJob job) {
    StageTemplate template =
        StageTemplate.byBusinessType(job.businessKey().type())
            .orElseThrow(() -> new IllegalArgumentException("loại đối tượng không thuộc phần 2"));
    if (!job.elementId().startsWith(StageVariables.CHOOSER_PREFIX)) {
      throw new IllegalArgumentException("phần tử chọn người phải là chon_<bước>");
    }
    String stepId = job.elementId().substring(StageVariables.CHOOSER_PREFIX.length());
    Map<String, Object> v = job.variables();
    UUID tenant = job.tenantId();
    ApprovalFlow flow =
        ApprovalFlow.parse(String.valueOf(v.get(ApprovalVariables.KHOI)))
            .orElseThrow(() -> new IllegalArgumentException("luồng"));
    StagePlan.Planned planned =
        StagePlan.decode(String.valueOf(v.get(StageVariables.KE_HOACH_BUOC)))
            .step(stepId)
            .orElseThrow(() -> new IllegalArgumentException("kế hoạch bước"));
    UUID base = UUID.fromString(String.valueOf(v.get(ApprovalVariables.DON_VI_ID)));
    LocalDate today = LocalDate.ofInstant(clock.instant(), StageDeadlines.ZONE);
    int level = OverdueCounts.of(v.get(StageVariables.SO_LAN_QUA_HAN), stepId);
    List<UUID> chain = chain(tenant, base, flow, level, today);
    Map<UUID, List<UUID>> holding = holders.holders(tenant, planned.role(), chain, today);
    Map<String, List<UUID>> participants = Participants.decode(v.get(StageVariables.THAM_GIA));
    Set<UUID> excluded = new HashSet<>(Participants.all(participants));
    uuid(v.get(StageVariables.NGUOI_TAO)).ifPresent(excluded::add);
    Map<String, Object> out = new HashMap<>();
    String assigneeVar =
        StageTemplate.assigneeVariable(stepId)
            .orElseThrow(() -> new IllegalArgumentException("bước không giao đích danh"));
    if (template == StageTemplate.XU_LY_KHIEU_NAI) {
      Optional<ComplaintHandlerPolicy.Decision> decision =
          complaintPolicy.decide(chain, holding, participants);
      List<UUID> candidates = new ArrayList<>();
      decision.ifPresent(
          d -> d.candidates().stream().filter(p -> !excluded.contains(p)).forEach(candidates::add));
      if (decision.isEmpty() || candidates.isEmpty()) {
        return fallback(job, out, assigneeVar, chain.get(0));
      }
      out.put(assigneeVar, leastBusy(tenant, candidates).toString());
      out.put(StageVariables.DON_VI_CHON, decision.get().unitId().toString());
      if (decision.get().escalated()) {
        log.info(
            "Khiếu nại {} chuyển cấp trên do đơn vị xử lý không có ứng viên", job.businessKey());
      }
      return out;
    }
    int needed = "duyetLan1".equals(stepId) ? 2 : 1;
    if ("duyetLan2".equals(stepId)) {
      uuid(v.get(StageVariables.NGUOI_DUYET_1)).ifPresent(excluded::add);
    }
    Set<UUID> everyone = new HashSet<>();
    holding.values().forEach(everyone::addAll);
    Optional<CandidatePicker.Pick> pick =
        CandidatePicker.pick(
            chain, holding, excluded, workload.openTasks(tenant, everyone), needed);
    if (pick.isEmpty()) {
      return fallback(job, out, assigneeVar, chain.get(0));
    }
    List<UUID> assignees = pick.get().assignees();
    out.put(assigneeVar, assignees.get(0).toString());
    if (needed == 2) {
      out.put(StageVariables.NGUOI_DUYET_2, assignees.get(1).toString());
    }
    out.put(StageVariables.DON_VI_CHON, pick.get().unitId().toString());
    return out;
  }

  private Map<String, Object> fallback(
      CallbackJob job, Map<String, Object> out, String assigneeVar, UUID unit) {
    log.warn(
        "Không còn người hợp lệ cho {} của {}, giao cho nhóm vai trò tại đơn vị gốc",
        job.elementId(),
        job.businessKey());
    out.put(assigneeVar, null);
    if ("chon_duyetLan1".equals(job.elementId())) {
      out.put(StageVariables.NGUOI_DUYET_2, null);
    }
    out.put(StageVariables.DON_VI_CHON, unit.toString());
    return out;
  }

  private UUID leastBusy(UUID tenant, List<UUID> candidates) {
    Map<UUID, Long> load = workload.openTasks(tenant, candidates);
    return candidates.stream()
        .min(
            Comparator.<UUID>comparingLong(p -> load.getOrDefault(p, 0L))
                .thenComparing(UUID::toString))
        .orElseThrow();
  }

  /** Đơn vị từ cấp trên thứ {@code from} của {@code base} trở lên, gần trước. */
  List<UUID> chain(UUID tenant, UUID base, ApprovalFlow flow, int from, LocalDate today) {
    List<UUID> units = new ArrayList<>();
    UUID current = base;
    for (int hop = 0; hop < MAX_HOPS && current != null; hop++) {
      if (hop >= from) {
        units.add(current);
      }
      Optional<OrgUnit> unit = directory.unit(tenant, current, today);
      current = unit.map(u -> u.parentOn(flow)).orElse(null);
    }
    if (units.isEmpty()) {
      // Quá hạn vượt gốc cây: dừng ở đơn vị cao nhất.
      units.add(top(tenant, base, flow, today));
    }
    return units;
  }

  private UUID top(UUID tenant, UUID base, ApprovalFlow flow, LocalDate today) {
    UUID current = base;
    for (int hop = 0; hop < MAX_HOPS; hop++) {
      UUID parent = directory.unit(tenant, current, today).map(u -> u.parentOn(flow)).orElse(null);
      if (parent == null) {
        return current;
      }
      current = parent;
    }
    return current;
  }

  private static Optional<UUID> uuid(Object value) {
    if (value == null || value.toString().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(UUID.fromString(value.toString()));
  }
}
