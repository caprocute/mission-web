package vn.viettel.okrkpi.appraisal.application.scoring;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository.IncidentRow;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ProfileKey;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ScoringInput;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.okr.api.OkrApi.KeyResultSnapshot;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.InputDeclaration;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskApi.ResponsibleItem;
import vn.viettel.okrkpi.task.api.TaskApi.WorkItemSnapshot;

/**
 * Trường mở rộng của đầu vào tính điểm theo BS-03 (CORE-04-13): {@code workItems} (DC-01), {@code
 * responsibilityScope} (DC-03) và {@code incidents} (DC-05). Mỗi trường chỉ được đọc khi phiên bản
 * gói ghim của hồ sơ khai báo đầu vào tương ứng trong {@code inputs}, nên gói không khai báo không
 * phát sinh truy vấn sang MOD-01, MOD-02 hay bảng sự việc. Việc lá đọc một lần cho cả trang hồ sơ.
 *
 * <p>Không giao dịch riêng: mỗi cổng của mô-đun khác chạy trong giao dịch chỉ đọc của mô-đun đó
 * (mỗi mô-đun một {@code DataSource}, wiki 21).
 */
public class ScoringExtensionService {

  /** Số việc lá tối đa mỗi hồ sơ trong trang đầu vào lô (BS-03 DC-01 "Hiệu năng"). */
  public static final int WORK_ITEM_LIMIT = 200;

  static final String WORK_ITEMS_SOURCE = "task.aggregate.work_items";

  private final ScoringInputRepository profiles;
  private final PolicyQueryApi policies;
  private final TaskApi tasks;
  private final OkrApi okr;
  private final IncidentService incidents;

  public ScoringExtensionService(
      ScoringInputRepository profiles,
      PolicyQueryApi policies,
      TaskApi tasks,
      OkrApi okr,
      IncidentService incidents) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.policies = policies;
    this.tasks = tasks;
    this.okr = okr;
    this.incidents = incidents;
  }

  /**
   * Đầu vào gói khai báo, nhận theo nguồn hoặc kiểu của hợp đồng ({@code PolicyInputDeclaration}),
   * hoặc theo tên quy ước khi gói chỉ ghi tên (suy luận).
   */
  public record Declared(boolean workItems, boolean responsibility, boolean incidents) {

    public static final Declared NONE = new Declared(false, false, false);

    public static Declared of(List<InputDeclaration> inputs) {
      boolean work = false;
      boolean scope = false;
      boolean incident = false;
      for (InputDeclaration d : inputs) {
        String type = d.type() == null ? "" : d.type().replace(" ", "");
        String source = d.source() == null ? "" : d.source();
        work |=
            WORK_ITEMS_SOURCE.equals(source)
                || "list<WorkItem>".equals(type)
                || "work_items".equals(d.name());
        scope |= "list<ResponsibilityItem>".equals(type) || "responsibility_scope".equals(d.name());
        incident |=
            source.startsWith("appraisal.incident")
                || "list<AppraisalIncidentInput>".equals(type)
                || "incidents".equals(d.name());
      }
      return new Declared(work, scope, incident);
    }

    boolean any() {
      return workItems || responsibility || incidents;
    }
  }

  /** Một mục phụ trách theo {@code ResponsibilityItem} của hợp đồng. */
  public record Responsibility(
      String itemType,
      UUID itemId,
      BigDecimal sumS,
      BigDecimal sumQ,
      BigDecimal sumD,
      BigDecimal targetAchievedRatio,
      BigDecimal confirmedLevel,
      Integer ownLateWorkdays) {}

  /**
   * Phần mở rộng của một hồ sơ; trường {@code null} là gói không khai báo.
   *
   * @param workItemsAfter id việc lá cuối của trang đầu khi còn việc lá, ngược lại {@code null}
   */
  public record Extensions(
      List<WorkItemSnapshot> workItems,
      UUID workItemsAfter,
      List<Responsibility> responsibility,
      List<IncidentRow> incidents) {

    public static final Extensions NONE = new Extensions(null, null, null, null);
  }

  /** Một trang việc lá của {@code listAppraisalWorkItems}. */
  public record WorkItemPage(List<WorkItemSnapshot> items, UUID nextAfter) {}

  /** Phần mở rộng cho cả trang hồ sơ, theo {@code profileId}. */
  public Map<UUID, Extensions> extend(TenantId tenant, UUID periodId, List<ScoringInput> page) {
    Map<UUID, Declared> byVersion = new HashMap<>();
    Map<UUID, Declared> declared = new LinkedHashMap<>();
    for (ScoringInput in : page) {
      declared.put(
          in.profileId(),
          in.policyPackVersionId() == null
              ? Declared.NONE
              : byVersion.computeIfAbsent(in.policyPackVersionId(), v -> declared(tenant, v)));
    }
    if (declared.values().stream().noneMatch(Declared::any)) {
      return Map.of();
    }
    PeriodId period = PeriodId.of(periodId);
    Set<UUID> workPersons = persons(page, declared, Declared::workItems);
    Map<UUID, List<WorkItemSnapshot>> work =
        workPersons.isEmpty() || tasks == null
            ? Map.of()
            : tasks.listWorkItems(tenant, period, workPersons, WORK_ITEM_LIMIT + 1);
    Set<UUID> incidentPersons = persons(page, declared, Declared::incidents);
    Map<UUID, List<IncidentRow>> facts =
        incidentPersons.isEmpty() || incidents == null
            ? Map.of()
            : incidents.forPersons(tenant, periodId, incidentPersons);
    Map<UUID, Extensions> out = new LinkedHashMap<>();
    for (ScoringInput in : page) {
      Declared d = declared.get(in.profileId());
      if (!d.any()) {
        continue;
      }
      List<WorkItemSnapshot> items = null;
      UUID after = null;
      if (d.workItems()) {
        List<WorkItemSnapshot> all = work.getOrDefault(in.personId(), List.of());
        boolean more = all.size() > WORK_ITEM_LIMIT;
        items = more ? List.copyOf(all.subList(0, WORK_ITEM_LIMIT)) : all;
        after = more ? items.getLast().taskId() : null;
      }
      List<Responsibility> scope = d.responsibility() ? responsibility(tenant, period, in) : null;
      List<IncidentRow> rows = d.incidents() ? facts.getOrDefault(in.personId(), List.of()) : null;
      out.put(in.profileId(), new Extensions(items, after, scope, rows));
    }
    return out;
  }

  /**
   * Trang việc lá tiếp theo của một hồ sơ; gói không khai báo {@code work_items} thì trang rỗng.
   *
   * @throws NoSuchElementException khi không có hồ sơ trong tenant
   */
  public WorkItemPage workItems(TenantId tenant, UUID profileId, UUID after, int limit) {
    ProfileKey key =
        profiles.profileKey(tenant, profileId).orElseThrow(NoSuchElementException::new);
    boolean declared =
        key.policyPackVersionId() != null
            && declared(tenant, key.policyPackVersionId()).workItems();
    if (!declared || tasks == null || key.personId() == null) {
      return new WorkItemPage(List.of(), null);
    }
    List<WorkItemSnapshot> rows =
        tasks.listWorkItems(tenant, PeriodId.of(key.periodId()), key.personId(), after, limit + 1);
    boolean more = rows.size() > limit;
    List<WorkItemSnapshot> items = more ? List.copyOf(rows.subList(0, limit)) : rows;
    return new WorkItemPage(items, more ? items.getLast().taskId() : null);
  }

  /**
   * Phạm vi phụ trách của người giữ chức vụ lãnh đạo, quản lý: nhiệm vụ, KPI (MOD-02), KR (MOD-01)
   * người đó phụ trách và đơn vị khi là người đứng đầu. Hồ sơ không lãnh đạo, quản lý nhận danh
   * sách rỗng.
   */
  private List<Responsibility> responsibility(TenantId tenant, PeriodId period, ScoringInput in) {
    if (!(in.head() || in.leadership()) || in.personId() == null) {
      return List.of();
    }
    List<Responsibility> out = new ArrayList<>();
    if (tasks != null) {
      for (ResponsibleItem t : tasks.listResponsibleItems(tenant, period, in.personId())) {
        out.add(
            new Responsibility(
                t.itemType(),
                t.itemId(),
                t.sumStandardScore(),
                null,
                t.sumCompletedScore(),
                t.targetAchievedRatio(),
                null,
                t.ownLateWorkdays()));
      }
    }
    if (okr != null) {
      for (KeyResultSnapshot k : okr.listResponsibleKeyResults(tenant, period, in.personId())) {
        out.add(new Responsibility("KR", k.id(), null, null, null, k.progress(), null, 0));
      }
    }
    if (in.head() && in.orgUnitId() != null) {
      out.add(new Responsibility("DON_VI", in.orgUnitId(), null, null, null, null, null, null));
    }
    return out;
  }

  private Declared declared(TenantId tenant, UUID versionId) {
    return policies == null ? Declared.NONE : Declared.of(policies.inputs(tenant, versionId));
  }

  private static Set<UUID> persons(
      List<ScoringInput> page,
      Map<UUID, Declared> declared,
      java.util.function.Predicate<Declared> wanted) {
    Set<UUID> out = new LinkedHashSet<>();
    for (ScoringInput in : page) {
      if (in.personId() != null && wanted.test(declared.get(in.profileId()))) {
        out.add(in.personId());
      }
    }
    return out;
  }
}
