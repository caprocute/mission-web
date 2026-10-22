package vn.viettel.okrkpi.org.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.port.AssignmentStore;
import vn.viettel.okrkpi.org.application.port.AssignmentStore.PersonAssignmentRow;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.application.port.IdentityContextPort;
import vn.viettel.okrkpi.org.application.port.OutboxPort;
import vn.viettel.okrkpi.org.application.port.PersonDirectory;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.UuidV7;

/**
 * DV-02-02 · QT-00 bước 7 · {@code getMyOrgProfile}, {@code setMyContext}. Phân công lấy từ {@code org.assignment}
 * còn hiệu lực hôm nay theo hai trục thời gian (BR-00-07). Vai trò của phân công là vai trò realm của người dùng trong
 * token cho tới khi có {@code config.role_binding} (CORE-08-16); điểm chờ ghi ở wiki trang 14.
 *
 * <p>Chọn phân công có vai trò chỉ dùng ở AAL2 trong phiên AAL1 bị từ chối 401 bước lên (BR-00-01, MSG-00-05). Ghi
 * ngữ cảnh vào Keycloak trước, rồi trong cùng giao dịch với khoá idempotency ghi sự kiện sổ cái {@code sys.audit.v1}
 * (BR-00-08) vào outbox; ghi Keycloak lặp lại an toàn nên lỗi ở bước sau chỉ cần gọi lại.
 */
@Service
public class MyContextService {

  /** Vai trò chỉ dùng trong phiên AAL2, theo mô tả {@code setMyContext} của {@code org.yaml} và BR-00-01. */
  static final Set<String> AAL2_ROLES = Set.of("appraiser", "decider", "second_approver", "tenant_admin",
      "platform_admin");
  static final String AUDIT_TOPIC = "sys.audit.v1";
  static final String AUDIT_ACTION = "DOI_NGU_CANH";
  private static final long STEP_UP_SECONDS = 900;

  private final AssignmentStore assignments;
  private final PersonDirectory persons;
  private final IdentityContextPort identity;
  private final IdempotencyPort idempotency;
  private final OutboxPort outbox;
  private final PolicyPort policy;
  private final UnitSupport support;
  private final Clock clock;

  public MyContextService(AssignmentStore assignments, PersonDirectory persons, IdentityContextPort identity,
      IdempotencyPort idempotency, OutboxPort outbox, PolicyPort policy, UnitSupport support, Clock clock) {
    this.assignments = assignments;
    this.persons = persons;
    this.identity = identity;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.policy = policy;
    this.support = support;
    this.clock = clock;
  }

  /** Hồ sơ người dùng hiện tại và phân công còn hiệu lực. */
  public record Profile(UUID personId, String fullName, List<AssignmentView> assignments, UUID lastAssignmentId) {
  }

  /** Phân công kèm vai trò. */
  public record AssignmentView(PersonAssignmentRow row, List<String> roles) {
  }

  /** Ngữ cảnh đã ghi. */
  public record WorkContextView(UUID assignmentId, UUID orgUnitId, List<String> roles) {
  }

  @Transactional(readOnly = true)
  public Profile profile(Actor actor) {
    policy.authorize(actor, "getMyOrgProfile");
    List<String> roles = businessRoles(actor);
    List<AssignmentView> rows = current(actor).stream().map(r -> new AssignmentView(r, roles)).toList();
    String name = persons.currentNames(List.of(actor.actorId())).getOrDefault(actor.actorId(), "");
    UUID last = identity.lastAssignment(actor.actorId())
        .filter(id -> rows.stream().anyMatch(a -> a.row().id().equals(id))).orElse(null);
    return new Profile(actor.actorId(), name, rows, last);
  }

  @Transactional
  public WorkContextView setContext(Actor actor, IdempotencyRequest idem, String assignmentId) {
    policy.authorize(actor, "setMyContext");
    Optional<WorkContextView> replay = idempotency.begin(actor, idem, WorkContextView.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    UUID id = parse(assignmentId);
    PersonAssignmentRow row = current(actor).stream().filter(r -> r.id().equals(id)).findFirst()
        .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy phân công còn hiệu lực."));
    List<String> roles = businessRoles(actor);
    if (roles.stream().anyMatch(AAL2_ROLES::contains) && !"aal2".equals(actor.acr())) {
      throw new StepUpRequiredException(STEP_UP_SECONDS);
    }
    identity.write(actor.actorId(), new IdentityContextPort.StoredContext(row.id(), row.orgUnitId(), roles,
        List.of(flow(row.branch())), row.validTo()));
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("action", AUDIT_ACTION);
    audit.put("objectType", "ASSIGNMENT");
    audit.put("objectId", row.id().toString());
    audit.put("outcome", "THANH_CONG");
    audit.put("purpose", null);
    audit.put("actorAssignmentId", row.id().toString());
    audit.put("actorRole", roles.isEmpty() ? null : String.join(",", roles));
    audit.put("system", false);
    audit.put("targets", null);
    outbox.append(new OutboxEvent(UuidV7.next(clock), AUDIT_TOPIC, "AuditEvent", "Person", actor.actorId(), 0,
        actor.tenantId(), actor.actorId(), clock.instant(), actor.traceparent(), "C1", audit));
    WorkContextView view = new WorkContextView(row.id(), row.orgUnitId(), roles);
    idempotency.complete(actor, idem, 200, view, row.id().toString());
    return view;
  }

  private List<PersonAssignmentRow> current(Actor actor) {
    return assignments.findByPersonAt(actor.actorId(), support.resolve(null, null));
  }

  /** Vai trò nghiệp vụ trong token, bỏ vai trò kỹ thuật mặc định của Keycloak. */
  static List<String> businessRoles(Actor actor) {
    return actor.roles().stream()
        .filter(r -> !r.startsWith("default-roles") && !"offline_access".equals(r) && !"uma_authorization".equals(r))
        .sorted().toList();
  }

  /** Nhánh phân công sang luồng đánh giá: {@code DANG} → {@code DANG}, {@code CHINH_QUYEN} → {@code NHA_NUOC}. */
  static String flow(String branch) {
    return "DANG".equals(branch) ? "DANG" : "NHA_NUOC";
  }

  private static UUID parse(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new OrgNotFoundException("Không tìm thấy phân công còn hiệu lực.");
    }
  }
}
