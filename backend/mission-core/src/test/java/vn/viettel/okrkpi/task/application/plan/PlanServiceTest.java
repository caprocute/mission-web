package vn.viettel.okrkpi.task.application.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/** Dịch vụ kế hoạch quý trên kho giả lập trong bộ nhớ (CORE-02-05). */
class PlanServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private final UUID actor = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final PeriodSnapshot q4 =
      period("2026-Q4", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31));
  private final PeriodSnapshot q1 =
      period("2027-Q1", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31));
  private final FakeRepo repo = new FakeRepo();
  private final Set<UUID> locked = new HashSet<>();
  private final List<OkrApi.KeyResultSnapshot> krs = new ArrayList<>();

  private final PeriodApi periods =
      new PeriodApi() {
        @Override
        public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
          return Optional.of(q1);
        }

        @Override
        public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId id) {
          return List.of(q4, q1).stream().filter(p -> p.id().equals(id)).findFirst();
        }

        @Override
        public void assertOpen(TenantId tenantId, PeriodId id) {
          if (locked.contains(id.value())) {
            throw new PeriodLockedException(id);
          }
        }
      };

  private final OkrApi okr =
      new OkrApi() {
        @Override
        public Optional<KeyResultSummary> findKeyResultById(TenantId t, UUID id) {
          return Optional.empty();
        }

        @Override
        public List<KeyResultSnapshot> listApprovedKeyResults(TenantId t, PeriodId p, UUID u) {
          return krs;
        }
      };

  private final OrgDirectoryApi org =
      new OrgDirectoryApi() {
        @Override
        public List<AssignmentView> assignmentsOf(TenantId t, UUID person, LocalDate on) {
          return person.equals(actor)
              ? List.of(
                  new AssignmentView(
                      UUID.randomUUID(), person, unit, null, null, "CHINH", null, true, on, null))
              : List.of();
        }

        @Override
        public List<UUID> headsOf(TenantId t, UUID o, LocalDate on) {
          return List.of();
        }

        @Override
        public Optional<UUID> parentOf(TenantId t, OrgTree tree, UUID o) {
          return Optional.empty();
        }

        @Override
        public List<UUID> descendantsOf(TenantId t, OrgTree tree, UUID o, int d) {
          return List.of();
        }

        @Override
        public List<UUID> ancestorsOf(TenantId t, OrgTree tree, UUID o) {
          return List.of();
        }

        @Override
        public boolean isDescendant(TenantId t, OrgTree tree, UUID a, UUID c) {
          return false;
        }
      };

  private final PlanService service =
      new PlanService(repo, periods, okr, org, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

  @Test
  void taoKeHoachVaTrungTra409() {
    PlanService.PlanView v = service.createPlan(TENANT, q1.id().value(), null, actor);
    assertThat(v.plan().ownerUnitId()).isEqualTo(unit);
    assertThat(v.plan().periodBucket()).isEqualTo(20271);
    assertThat(v.currentVersion().status()).isEqualTo(PlanVersionStatus.NHAP);
    assertThat(v.currentVersion().versionNo()).isEqualTo(1);
    assertThat(v.effectiveVersionNo()).isNull();
    assertThat(code(() -> service.createPlan(TENANT, q1.id().value(), unit, actor)))
        .isEqualTo(PlanService.PLAN_EXISTS);
    assertThat(code(() -> service.createPlan(TENANT, q1.id().value(), null, UUID.randomUUID())))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(service.listPlans(TENANT, null, unit, PlanVersionStatus.NHAP, null, 10)).hasSize(1);
    assertThat(
            service.listPlans(TENANT, q1.id().value(), null, PlanVersionStatus.DA_DUYET, null, 10))
        .isEmpty();
    locked.add(q1.id().value());
    assertThatThrownBy(() -> service.createPlan(TENANT, q1.id().value(), UUID.randomUUID(), actor))
        .isInstanceOf(PeriodLockedException.class);
  }

  @Test
  void luuNhapLayDiemTuDanhMucVaChanPhienBanDaDuyet() {
    UUID plan = service.createPlan(TENANT, q1.id().value(), unit, actor).plan().id();
    UUID catalogItem = UUID.randomUUID();
    repo.catalog.put(
        catalogItem,
        new PlanRepository.CatalogRow(
            catalogItem, "TC-01", "Báo cáo quý", "báo cáo", 3, BigDecimal.TEN, "Đúng hạn", null));
    PlanRepository.ItemRow fromCatalog = draft("Tổng hợp báo cáo", "THUONG_XUYEN", catalogItem);
    PlanRepository.ItemRow free = draft("Khảo sát cơ sở", "THEN_CHOT", null);
    PlanService.VersionView saved =
        service.saveVersion(TENANT, plan, 1, 0, List.of(fromCatalog, free), actor);
    assertThat(saved.version().rowVersion()).isEqualTo(1);
    assertThat(saved.items()).extracting(PlanRepository.ItemRow::itemNo).containsExactly(1, 2);
    assertThat(saved.items().get(0).standardScore()).isEqualTo(BigDecimal.TEN);
    assertThat(saved.items().get(0).unit()).isEqualTo("báo cáo");
    assertThat(saved.items().get(0).difficulty()).isEqualTo(3);
    assertThat(saved.items().get(1).standardScore()).isNull();

    assertThatThrownBy(() -> service.saveVersion(TENANT, plan, 1, 0, List.of(free), actor))
        .isInstanceOf(VersionConflictException.class);
    assertThat(
            code(
                () ->
                    service.saveVersion(
                        TENANT,
                        plan,
                        1,
                        1,
                        List.of(draft("x", "THEN_CHOT", UUID.randomUUID())),
                        actor)))
        .isEqualTo("COMMON-422-VALIDATION");
    PlanRepository.ItemRow zero = withQuantity(free, BigDecimal.ZERO);
    assertThat(code(() -> service.saveVersion(TENANT, plan, 1, 1, List.of(zero), actor)))
        .isEqualTo("COMMON-422-VALIDATION");

    repo.setStatus(plan, 1, PlanVersionStatus.DA_DUYET);
    assertThat(code(() -> service.saveVersion(TENANT, plan, 1, 1, List.of(free), actor)))
        .isEqualTo(PlanService.INVALID_TRANSITION);
    assertThatThrownBy(() -> service.saveVersion(TENANT, plan, 9, 0, List.of(), actor))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(service.versions(TENANT, plan).rows()).hasSize(1);
    assertThat(service.approvedItems(TENANT, q1.id().value(), null, null, 0, 10)).hasSize(2);
  }

  @Test
  void saoChepQuyTruocChiLayThuongXuyenVaChuaXong() {
    UUID old = service.createPlan(TENANT, q4.id().value(), unit, actor).plan().id();
    PlanRepository.ItemRow regular = draft("Giao ban tuần", "THUONG_XUYEN", null);
    PlanRepository.ItemRow open = draft("Đề án A", "THEN_CHOT", null);
    PlanRepository.ItemRow done = draft("Đề án B", "THEN_CHOT", null);
    List<PlanRepository.ItemRow> oldItems =
        service.saveVersion(TENANT, old, 1, 0, List.of(regular, open, done), actor).items();
    repo.setStatus(old, 1, PlanVersionStatus.DA_DUYET);
    repo.closed.add(oldItems.get(2).id());

    UUID plan = service.createPlan(TENANT, q1.id().value(), unit, actor).plan().id();
    assertThat(
            service
                .listPlans(TENANT, q1.id().value(), unit, null, null, 10)
                .get(0)
                .previousPeriod())
        .isEqualTo(q4);
    PlanService.VersionView copied =
        service.copyFromPreviousPeriod(TENANT, plan, 1, 0, null, actor);
    assertThat(copied.items())
        .extracting(PlanRepository.ItemRow::title)
        .containsExactly("Giao ban tuần", "Đề án A");
    assertThat(copied.items().get(0).originItemId()).isEqualTo(oldItems.get(0).id());
    assertThat(copied.items().get(0).dueDate()).isEqualTo(LocalDate.of(2027, 2, 15));
    // Phiên bản đã có dòng thì không sao chép đè.
    assertThat(
            code(() -> service.copyFromPreviousPeriod(TENANT, plan, 1, 1, q4.id().value(), actor)))
        .isEqualTo(PlanService.INVALID_TRANSITION);

    UUID other = service.createPlan(TENANT, q1.id().value(), UUID.randomUUID(), actor).plan().id();
    assertThat(code(() -> service.copyFromPreviousPeriod(TENANT, other, 1, 0, null, actor)))
        .isEqualTo(PlanService.INVALID_TRANSITION);
  }

  @Test
  void goiYMoiKrChuaCoDong() {
    UUID plan = service.createPlan(TENANT, q1.id().value(), unit, actor).plan().id();
    OkrApi.KeyResultSnapshot a = kr("KR-1", null);
    OkrApi.KeyResultSnapshot b = kr("KR-2", LocalDate.of(2027, 2, 1));
    OkrApi.KeyResultSnapshot c = kr("KR-3", LocalDate.of(2027, 6, 1));
    krs.addAll(List.of(a, b, c));
    repo.linked.add(b.id());
    List<PlanRepository.ItemRow> s = service.suggestions(TENANT, plan);
    assertThat(s).extracting(PlanRepository.ItemRow::keyResultId).containsExactly(a.id(), c.id());
    assertThat(s.get(0).dueDate()).isEqualTo(q1.endDate());
    assertThat(s.get(1).dueDate()).isEqualTo(q1.endDate());
    assertThat(s.get(0).lead().id()).isEqualTo(unit);
    assertThat(s.get(0).taskGroup()).isEqualTo("THEN_CHOT");
  }

  private OkrApi.KeyResultSnapshot kr(String code, LocalDate due) {
    return new OkrApi.KeyResultSnapshot(
        UUID.randomUUID(),
        TENANT,
        q1.id(),
        UUID.randomUUID(),
        "DA_DUYET",
        "DON_VI",
        unit,
        null,
        code,
        "Kết quả " + code,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        due,
        null);
  }

  private PlanRepository.ItemRow draft(String title, String group, UUID catalogItem) {
    return new PlanRepository.ItemRow(
        null,
        0,
        catalogItem,
        null,
        null,
        title,
        group,
        new PlanRepository.Assignee("UNIT", unit, ""),
        null,
        title,
        BigDecimal.ONE,
        "báo cáo",
        null,
        null,
        LocalDate.of(2026, 11, 15),
        null,
        null,
        null);
  }

  private static PlanRepository.ItemRow withQuantity(PlanRepository.ItemRow r, BigDecimal q) {
    return new PlanRepository.ItemRow(
        r.id(),
        r.itemNo(),
        r.catalogItemId(),
        r.catalogCode(),
        r.keyResultId(),
        r.title(),
        r.taskGroup(),
        r.lead(),
        r.coAssignees(),
        r.productName(),
        q,
        r.unit(),
        r.difficulty(),
        r.standardScore(),
        r.dueDate(),
        r.originItemId(),
        r.changeReason(),
        r.recurrence());
  }

  private static PeriodSnapshot period(String code, LocalDate start, LocalDate end) {
    return new PeriodSnapshot(
        PeriodId.of(UUID.randomUUID()), TENANT, code, "Quý " + code, start, end, PeriodStatus.MO);
  }

  private static String code(Runnable r) {
    try {
      r.run();
    } catch (BusinessException e) {
      return e.errorCode().code();
    }
    throw new AssertionError("không ném BusinessException");
  }

  /** Kho kế hoạch trong bộ nhớ. */
  static final class FakeRepo implements PlanRepository {
    final Map<UUID, PlanRow> plans = new LinkedHashMap<>();
    final Map<UUID, List<VersionRow>> versions = new HashMap<>();
    final Map<UUID, List<ItemRow>> items = new HashMap<>();
    final Map<UUID, CatalogRow> catalog = new HashMap<>();
    final Set<UUID> closed = new HashSet<>();
    final Set<UUID> linked = new HashSet<>();

    void setStatus(UUID plan, int no, PlanVersionStatus status) {
      List<VersionRow> vs = versions.get(plan);
      for (int i = 0; i < vs.size(); i++) {
        VersionRow v = vs.get(i);
        if (v.versionNo() == no) {
          vs.set(
              i,
              new VersionRow(
                  v.id(),
                  v.planId(),
                  no,
                  status,
                  status == PlanVersionStatus.DA_DUYET,
                  v.submittedAt(),
                  Instant.EPOCH,
                  v.contentHash(),
                  v.reviewComment(),
                  v.changeReason(),
                  v.itemCount(),
                  v.rowVersion()));
        }
      }
    }

    @Override
    public boolean insertPlan(TenantId tenant, PlanRow plan, UUID versionId, UUID actor) {
      boolean dup =
          plans.values().stream()
              .anyMatch(
                  p ->
                      p.periodId().equals(plan.periodId())
                          && p.ownerUnitId().equals(plan.ownerUnitId()));
      if (dup) {
        return false;
      }
      plans.put(
          plan.id(),
          new PlanRow(
              plan.id(),
              plan.periodId(),
              plan.periodBucket(),
              plan.planType(),
              plan.ownerUnitId(),
              plan.ownerPersonId(),
              versionId,
              0));
      versions.put(
          plan.id(),
          new ArrayList<>(
              List.of(
                  new VersionRow(
                      versionId,
                      plan.id(),
                      1,
                      PlanVersionStatus.NHAP,
                      false,
                      null,
                      null,
                      null,
                      null,
                      null,
                      0,
                      0))));
      return true;
    }

    @Override
    public Optional<PlanRow> findPlan(TenantId tenant, UUID planId) {
      return Optional.ofNullable(plans.get(planId));
    }

    @Override
    public List<PlanRow> listPlans(
        TenantId tenant,
        int bucket,
        UUID periodId,
        UUID unitId,
        PlanVersionStatus status,
        UUID afterId,
        int limit) {
      return plans.values().stream()
          .filter(p -> p.periodId().equals(periodId))
          .filter(p -> unitId == null || p.ownerUnitId().equals(unitId))
          .filter(p -> status == null || versions.get(p.id()).get(0).status() == status)
          .limit(limit)
          .toList();
    }

    @Override
    public List<VersionRow> versions(TenantId tenant, int bucket, UUID planId) {
      return versions.get(planId).stream()
          .sorted(Comparator.comparingInt(VersionRow::versionNo).reversed())
          .map(
              v ->
                  new VersionRow(
                      v.id(),
                      v.planId(),
                      v.versionNo(),
                      v.status(),
                      v.current(),
                      v.submittedAt(),
                      v.approvedAt(),
                      v.contentHash(),
                      v.reviewComment(),
                      v.changeReason(),
                      items.getOrDefault(v.id(), List.of()).size(),
                      v.rowVersion()))
          .toList();
    }

    @Override
    public Optional<VersionRow> lockVersion(TenantId tenant, int bucket, UUID planId, int no) {
      return versions(tenant, bucket, planId).stream().filter(v -> v.versionNo() == no).findFirst();
    }

    @Override
    public List<ItemRow> items(TenantId tenant, int bucket, UUID versionId) {
      return items.getOrDefault(versionId, List.of());
    }

    @Override
    public long replaceItems(
        TenantId tenant, PlanRow plan, VersionRow version, List<ItemRow> rows, UUID actor) {
      items.put(version.id(), List.copyOf(rows));
      List<VersionRow> vs = versions.get(plan.id());
      for (int i = 0; i < vs.size(); i++) {
        VersionRow v = vs.get(i);
        if (v.id().equals(version.id())) {
          vs.set(
              i,
              new VersionRow(
                  v.id(),
                  v.planId(),
                  v.versionNo(),
                  v.status(),
                  v.current(),
                  v.submittedAt(),
                  v.approvedAt(),
                  v.contentHash(),
                  v.reviewComment(),
                  v.changeReason(),
                  rows.size(),
                  v.rowVersion() + 1));
          return v.rowVersion() + 1;
        }
      }
      throw new NoSuchElementException();
    }

    @Override
    public Map<UUID, CatalogRow> catalogItems(TenantId tenant, Collection<UUID> ids) {
      Map<UUID, CatalogRow> out = new HashMap<>();
      ids.forEach(id -> Optional.ofNullable(catalog.get(id)).ifPresent(c -> out.put(id, c)));
      return out;
    }

    @Override
    public Optional<Map.Entry<PlanRow, VersionRow>> effectiveOf(
        TenantId tenant, int bucket, UUID periodId, UUID ownerUnitId, UUID ownerPersonId) {
      for (PlanRow p : plans.values()) {
        if (p.periodBucket() == bucket
            && (periodId == null || p.periodId().equals(periodId))
            && p.ownerUnitId().equals(ownerUnitId)) {
          Optional<VersionRow> v =
              versions(tenant, bucket, p.id()).stream()
                  .filter(x -> x.status() == PlanVersionStatus.DA_DUYET)
                  .findFirst();
          if (v.isPresent()) {
            return Optional.of(new AbstractMap.SimpleImmutableEntry<>(p, v.get()));
          }
        }
      }
      return Optional.empty();
    }

    @Override
    public Set<UUID> closedItems(TenantId tenant, int bucket, Collection<UUID> itemIds) {
      Set<UUID> out = new HashSet<>(itemIds);
      out.retainAll(closed);
      return out;
    }

    @Override
    public Set<UUID> linkedKeyResults(TenantId tenant, int bucket, UUID periodId, UUID planId) {
      return linked;
    }

    @Override
    public List<ApprovedItemRow> approvedItems(
        TenantId tenant,
        int bucket,
        UUID periodId,
        PlanVersionStatus status,
        UUID afterPlanId,
        int afterItemNo,
        int limit) {
      List<ApprovedItemRow> out = new ArrayList<>();
      for (PlanRow p : plans.values()) {
        if (!p.periodId().equals(periodId)) {
          continue;
        }
        versions(tenant, bucket, p.id()).stream()
            .filter(v -> v.status() == status)
            .findFirst()
            .ifPresent(
                v ->
                    items(tenant, bucket, v.id())
                        .forEach(
                            i ->
                                out.add(
                                    new ApprovedItemRow(
                                        p.id(), periodId, v.versionNo(), i, "", null))));
      }
      return out;
    }
  }
}
