package vn.viettel.okrkpi.workflow.infrastructure.security;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.authz.OpaClient;
import vn.viettel.okrkpi.shared.authz.OpaDecision;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.workflow.application.port.AssigneeSeparation;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.monitor.MonitoredTask;

/**
 * {@link AssigneeSeparation} hỏi OPA với chủ thể là <b>người nhận mới</b>: chức năng {@code
 * SCR-0203.duyet} (người xử lý bước duyệt), tài nguyên là đối tượng nghiệp vụ kèm người khởi tạo ở
 * {@code participants.initiator}. Ràng buộc tách vai trò của ma trận (4.1, BR-02-08, PL 4 V.1) nằm
 * trong OPA nên mã nguồn không tự liệt kê ai được nhận việc.
 *
 * <p>Chỉ lý do {@code separation_of_duties}, {@code four_eyes_required} được xét: vai trò, phạm vi
 * và AAL2 của người nhận được kiểm khi chính người đó thao tác bằng token của mình. OPA không trả
 * lời được thì từ chối (đóng khi lỗi).
 */
@Component
public class OpaAssigneeSeparation implements AssigneeSeparation {

  /** Chức năng mà người nhận sẽ thực hiện trên bước (ma trận SCR-0203). */
  static final String ACTION = "SCR-0203.duyet";

  static final String SELF_APPROVAL = "WORKFLOW-403-SELF-APPROVAL";
  static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";

  private static final Set<String> SEPARATION =
      Set.of("separation_of_duties", "four_eyes_required");

  private final OpaClient opa;
  private final Clock clock;

  public OpaAssigneeSeparation(OpaClient opa, Clock clock) {
    this.opa = opa;
    this.clock = clock;
  }

  @Override
  public void check(MonitoredTask task, UUID newAssigneeId) {
    OpaDecision decision = opa.decide(input(task, newAssigneeId, clock.instant().getEpochSecond()));
    if (decision.deny().stream().anyMatch(SEPARATION::contains)) {
      throw new BusinessException(
          SELF_APPROVAL,
          "Người nhận mới vi phạm nguyên tắc tách vai trò với hồ sơ này (người khởi tạo không tự"
              + " xử lý bước duyệt).");
    }
    if (decision.deny().stream().anyMatch(d -> d.startsWith("opa_"))) {
      throw new BusinessException(OUT_OF_SCOPE, "Chưa kiểm được tách vai trò, vui lòng thử lại.");
    }
  }

  /** Đầu vào OPA đặt người nhận mới làm chủ thể (công khai để kiểm thử với chính sách thật). */
  public static OpaInput input(MonitoredTask task, UUID newAssigneeId, long nowEpochSeconds) {
    CandidateGroup candidate = task.primaryCandidate();
    String unit = candidate == null ? null : candidate.unitId().toString();
    List<String> roles = candidate == null ? List.of() : List.of(candidate.role());
    OpaInput.Subject subject =
        OpaInput.Subject.user(
            newAssigneeId.toString(),
            task.tenantId().toString(),
            unit,
            roles,
            null,
            null,
            List.of());
    Map<String, List<String>> participants =
        task.initiatorId() == null
            ? Map.of()
            : Map.of("initiator", List.of(task.initiatorId().toString()));
    OpaInput.Resource resource =
        new OpaInput.Resource(
            task.businessKey().type().toLowerCase(Locale.ROOT),
            task.businessKey().id().toString(),
            task.tenantId().toString(),
            unit,
            null,
            List.of(newAssigneeId.toString()),
            null,
            null,
            null,
            "C1",
            null,
            participants,
            null,
            null,
            null);
    return new OpaInput(subject, ACTION, null, resource, new OpaInput.Context(nowEpochSeconds));
  }
}
