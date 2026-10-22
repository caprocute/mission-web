package vn.viettel.okrkpi.workflow.application.approval;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.workflow.application.port.OrgDirectory;
import vn.viettel.okrkpi.workflow.application.port.WorkingCalendar;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalLevel;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalRule;
import vn.viettel.okrkpi.workflow.domain.approval.OrgUnit;
import vn.viettel.okrkpi.workflow.domain.approval.WorkingDays;

/**
 * Phân giải cấp duyệt (TL-01 G2 "Ví dụ DMN chọn cấp duyệt"): sắp các dòng theo {@code thuTu}, phân
 * giải {@code scope} thành đơn vị cụ thể bằng {@code org_replica} tại ngày khởi tạo, đổi {@code
 * hanNgayLamViec} thành hạn theo lịch nghỉ của tenant. Hạn các cấp cộng dồn vì các cấp chạy tuần tự
 * (suy luận); mốc hạn là 17 giờ giờ Việt Nam của ngày làm việc cuối (theo ví dụ hợp đồng).
 */
@Service
public class ApprovalLevelResolver {

  /** Giờ Việt Nam: lịch làm việc và hạn tính theo múi giờ này. */
  public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  /** Giờ kết thúc ngày làm việc dùng làm mốc hạn. */
  static final LocalTime DUE_TIME = LocalTime.of(17, 0);

  /** Số bước đi lên tối đa khi tìm cấp uỷ có thẩm quyền. */
  static final int MAX_ANCESTOR_HOPS = 12;

  private final OrgDirectory directory;
  private final WorkingCalendar calendar;
  private final Clock clock;

  public ApprovalLevelResolver(OrgDirectory directory, WorkingCalendar calendar, Clock clock) {
    this.directory = directory;
    this.calendar = calendar;
    this.clock = clock;
  }

  /**
   * Danh sách cấp đã phân giải theo thứ tự.
   *
   * @throws ApprovalSetupException khi không có cấp, thứ tự trùng, hoặc không tìm được đơn vị
   */
  public List<ApprovalLevel> resolve(Subject subject, List<ApprovalRule> rules) {
    Objects.requireNonNull(subject, "subject");
    if (rules == null || rules.isEmpty()) {
      throw new ApprovalSetupException("Không có cấp duyệt cho hồ sơ này");
    }
    if (rules.size() > ApprovalParams.MAX_LEVELS) {
      throw new ApprovalSetupException("Luồng duyệt vượt số cấp tối đa");
    }
    List<ApprovalRule> sorted = new ArrayList<>(rules);
    sorted.sort(Comparator.comparingInt(ApprovalRule::order));
    Set<Integer> orders = new HashSet<>();
    int totalDays = 0;
    for (ApprovalRule rule : sorted) {
      if (!orders.add(rule.order())) {
        throw new ApprovalSetupException("Thứ tự cấp duyệt bị trùng");
      }
      totalDays += rule.workingDays();
    }
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    OrgUnit unit = unit(subject.tenantId(), subject.unitId(), today);
    // Đủ rộng cho mọi ngày nghỉ dài (Tết) trong khoảng hạn.
    Map<LocalDate, Boolean> overrides =
        calendar.overrides(subject.tenantId(), today, today.plusDays(totalDays * 3L + 31));
    List<ApprovalLevel> levels = new ArrayList<>(sorted.size());
    int cumulative = 0;
    for (ApprovalRule rule : sorted) {
      cumulative += rule.workingDays();
      UUID target = target(subject, unit, rule, today);
      LocalDate dueDay = WorkingDays.plus(today, cumulative, overrides);
      levels.add(
          new ApprovalLevel(
              rule.order(),
              rule.role(),
              rule.scope(),
              target,
              dueDay.atTime(DUE_TIME).atZone(ZONE).toInstant(),
              rule.workingDays()));
    }
    return levels;
  }

  private UUID target(Subject subject, OrgUnit unit, ApprovalRule rule, LocalDate today) {
    return switch (rule.scope()) {
      case DON_VI_TRUC_TIEP -> unit.id();
      case CAP_TREN_TRUC_TIEP -> required(unit.parentOn(subject.flow()), "cấp trên trực tiếp");
      case QUAN_LY_TRUC_TIEP ->
          subject.headOfUnit()
              ? required(unit.adminParentId(), "đơn vị quản lý trực tiếp")
              : unit.id();
      case CAP_UY_CO_THAM_QUYEN -> partyCommittee(subject.tenantId(), unit, today);
    };
  }

  private UUID partyCommittee(UUID tenantId, OrgUnit start, LocalDate today) {
    UUID current = start.partyParentId();
    for (int hop = 0; hop < MAX_ANCESTOR_HOPS && current != null; hop++) {
      OrgUnit ancestor = unit(tenantId, current, today);
      if (ancestor.isPartyOrganization()) {
        return ancestor.id();
      }
      current = ancestor.partyParentId();
    }
    throw new ApprovalSetupException("Không tìm được cấp uỷ có thẩm quyền trên cây tổ chức");
  }

  private OrgUnit unit(UUID tenantId, UUID unitId, LocalDate today) {
    return directory
        .unit(tenantId, unitId, today)
        .orElseThrow(
            () -> new ApprovalSetupException("Không tìm thấy đơn vị trong bản sao tổ chức"));
  }

  private static UUID required(UUID unitId, String what) {
    if (unitId == null) {
      throw new ApprovalSetupException("Không tìm được " + what + " trên cây tổ chức");
    }
    return unitId;
  }

  /**
   * Đối tượng được duyệt.
   *
   * @param tenantId tenant
   * @param flow luồng Đảng hoặc Nhà nước
   * @param unitId đơn vị của đối tượng
   * @param headOfUnit người trình là người đứng đầu đơn vị
   */
  public record Subject(UUID tenantId, ApprovalFlow flow, UUID unitId, boolean headOfUnit) {

    public Subject {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(flow, "flow");
      Objects.requireNonNull(unitId, "unitId");
    }
  }
}
