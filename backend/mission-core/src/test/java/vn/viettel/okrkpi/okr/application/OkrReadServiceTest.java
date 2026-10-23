package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.okr.api.OkrApi.KeyResultSnapshot;
import vn.viettel.okrkpi.okr.api.OkrApi.KeyResultSummary;
import vn.viettel.okrkpi.okr.application.OkrReadService.AlignmentView;
import vn.viettel.okrkpi.okr.application.OkrReadService.TreeView;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Phía đọc của MOD-01 trên kho dữ liệu trong bộ nhớ: cây, bản đồ liên kết, cổng {@code OkrApi}. */
class OkrReadServiceTest {

  private static final TenantId T = TenantId.of(UUID.randomUUID());
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID PEER = UUID.randomUUID();
  private static final UUID CHILD = UUID.randomUUID();
  private static final PeriodSnapshot Q1 =
      new PeriodSnapshot(
          PeriodId.of(UUID.randomUUID()),
          T,
          "Q1-2027",
          "Quý I năm 2027",
          LocalDate.of(2027, 1, 1),
          LocalDate.of(2027, 3, 31),
          PeriodStatus.MO);

  private final FakeRepository repo = new FakeRepository();
  private final StubPeriods periods = new StubPeriods();
  private final OkrReadService service =
      new OkrReadService(repo, periods, () -> LocalDate.of(2027, 1, 15));

  @Test
  void kyVaPhanVung() {
    assertThat(OkrReadService.buckets(Q1)).containsExactly(20271, 20270);
    assertThat(service.period(T, null)).isEqualTo(Q1);
    assertThat(service.period(T, Q1.id().value())).isEqualTo(Q1);
    assertThatThrownBy(() -> service.period(T, UUID.randomUUID()))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(service.optionalPeriod(T, UUID.randomUUID())).isEmpty();
    OkrReadService.PeriodView v = service.periodView(Q1);
    assertThat(v.weekNo()).isEqualTo(3);
    assertThat(v.weekCount()).isEqualTo(13);
    assertThat(
            new OkrReadService(repo, periods, () -> LocalDate.of(2027, 5, 1))
                .periodView(Q1)
                .weekNo())
        .isNull();
  }

  @Test
  void cayCoTienDoCapTrenVaLienKetNgang() {
    Seed s = seed();
    TreeView tree = service.tree(T, Q1, CHILD, false);
    assertThat(tree.parent().id()).isEqualTo(s.o1);
    tree = service.tree(T, Q1, UNIT, true);
    assertThat(tree.objectives()).hasSize(1);
    assertThat(tree.objectives().getFirst().progress()).isEqualByComparingTo("0.5");
    assertThat(tree.bases()).hasSize(1);
    assertThat(tree.recommendedMax()).isEqualTo(3);
    // Một liên kết ngang từ KR của đơn vị, một liên kết ngang từ mục tiêu của đơn vị bạn tới KR
    // mình.
    assertThat(tree.peerLinks()).hasSize(2);
    assertThat(tree.peerLinks())
        .anySatisfy(p -> assertThat(p.localKeyResult().id()).isEqualTo(s.kr1))
        .allSatisfy(p -> assertThat(p.peer().id()).isEqualTo(s.o2));
    assertThat(service.countUnitObjectives(T, Q1, UNIT)).isEqualTo(1);
  }

  @Test
  void cayDocQuaNhieuTrang() {
    for (int i = 0; i < 450; i++) {
      repo.objectives.add(objective(UUID.randomUUID(), PEER, String.format("MT-%03d", i), null));
    }
    assertThat(service.tree(T, Q1, PEER, false).objectives()).hasSize(450);
    assertThat(service.tree(T, Q1, UNIT, false).peerLinks()).isEmpty();
  }

  @Test
  void banDoLienKetTuGocVaTuDonVi() {
    Seed s = seed();
    AlignmentView root = service.alignment(T, s.o1, null, null, 9);
    assertThat(root.nodes())
        .extracting(OkrReadService.AlignmentNode::id)
        .contains(s.o1, s.o2, s.o3, s.kr1, s.kr2, s.kr3, s.basis);
    assertThat(root.edges()).hasSize(5);
    AlignmentView unit = service.alignment(T, null, Q1, UNIT, 1);
    assertThat(unit.rootId()).isNull();
    assertThat(unit.nodes()).extracting(OkrReadService.AlignmentNode::type).contains("OBJECTIVE");
    assertThat(service.alignment(T, null, null, null, 2).nodes()).isEmpty();
    assertThatThrownBy(() -> service.alignment(T, UUID.randomUUID(), null, null, 2))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void chiTietVaDanhSach() {
    Seed s = seed();
    OkrReadService.ObjectiveView o = service.objective(T, s.o1).orElseThrow();
    assertThat(o.keyResults()).hasSize(2);
    assertThat(service.linksOf(T, o)).hasSize(4);
    assertThat(service.objective(T, UUID.randomUUID())).isEmpty();
    assertThat(service.objectiveRow(T, s.o2)).isPresent();
    assertThat(
            service.objectives(
                T,
                new OkrReadRepository.ObjectiveFilter(
                    null, null, null, null, null, List.of(), null),
                null,
                null,
                10))
        .hasSize(3);
    // KR 1.2 chưa có giá trị hiện tại: lấy từ check-in đã gửi gần nhất.
    assertThat(service.keyResult(T, s.kr2).orElseThrow().progress()).isEqualByComparingTo("0.5");
    assertThat(
            service.keyResults(
                T,
                new OkrReadRepository.KeyResultFilter(
                    null, null, null, null, s.o1, null, false, false, null),
                null,
                null,
                10))
        .hasSize(2);
    assertThat(service.bases(T, null, null, null, null, null, 10)).hasSize(1);
    assertThat(service.links(T, null, s.o1, null, null, null, null, null, 10)).hasSize(1);
  }

  @Test
  void congOkrApiDocTuKhoVaBoNho() {
    Seed s = seed();
    OkrQueryService api = new OkrQueryService(periods, provider(service));
    KeyResultSnapshot snap = api.findKeyResult(T, s.kr1).orElseThrow();
    assertThat(snap.approved()).isTrue();
    assertThat(snap.ownerUnitId()).isEqualTo(UNIT);
    assertThat(api.findKeyResultById(T, s.kr1).orElseThrow().progressPercent()).isEqualTo(50.0);
    assertThat(api.findKeyResult(T, UUID.randomUUID())).isEmpty();
    assertThat(api.listApprovedKeyResults(T, Q1.id(), UNIT))
        .extracting(KeyResultSnapshot::id)
        .containsExactly(s.kr1, s.kr2);
    assertThat(api.listApprovedKeyResults(T, PeriodId.of(UUID.randomUUID()), UNIT)).isEmpty();
    // Mục tiêu tập thể (không có người sở hữu cá nhân) được giữ; bộ lọc người nằm ở kho đọc.
    assertThat(api.listResponsibleKeyResults(T, Q1.id(), UUID.randomUUID()))
        .extracting(KeyResultSnapshot::id)
        .contains(s.kr1, s.kr2);
    assertThat(api.listResponsibleKeyResults(T, PeriodId.of(UUID.randomUUID()), UUID.randomUUID()))
        .isEmpty();

    KeyResultSummary registered =
        new KeyResultSummary(UUID.randomUUID(), T, Q1.id(), "KR trong bộ nhớ", 0.5, 10);
    OkrQueryService memory = new OkrQueryService(periods);
    memory.registerKeyResult(registered);
    assertThat(memory.findKeyResultById(T, registered.id())).contains(registered);
    assertThat(memory.findKeyResultById(T, s.kr1)).isEmpty();
    assertThat(memory.findKeyResult(T, s.kr1)).isEmpty();
    assertThat(memory.listApprovedKeyResults(T, Q1.id(), UNIT)).isEmpty();
    KeyResultSnapshot noWeight =
        new KeyResultSnapshot(
            UUID.randomUUID(),
            T,
            Q1.id(),
            s.o1,
            "NHAP",
            "TAP_THE",
            UNIT,
            null,
            "KR",
            "KR",
            null,
            BigDecimal.ZERO,
            null,
            "BINH_THUONG");
    assertThat(noWeight.approved()).isFalse();
  }

  // ---------------------------------------------------------------- dữ liệu mẫu

  private record Seed(UUID o1, UUID o2, UUID o3, UUID kr1, UUID kr2, UUID kr3, UUID basis) {}

  private Seed seed() {
    UUID o1 = UUID.randomUUID();
    UUID o2 = UUID.randomUUID();
    UUID o3 = UUID.randomUUID();
    repo.objectives.add(objective(o1, UNIT, "MT-01", null));
    repo.objectives.add(objective(o2, PEER, "MT-01", null));
    repo.objectives.add(objective(o3, CHILD, "MT-01", o1));
    UUID kr1 = UUID.randomUUID();
    UUID kr2 = UUID.randomUUID();
    UUID kr3 = UUID.randomUUID();
    repo.keyResults.add(kr(kr1, o1, "KR 1.1", "TANG", "0", "100", "50", null));
    repo.keyResults.add(
        kr(
            kr2,
            o1,
            "KR 1.2",
            "GIAM",
            "10",
            "0",
            null,
            new OkrReadRepository.LastCheckin(new BigDecimal("5"), "CAO", Instant.now(), null)));
    repo.keyResults.add(kr(kr3, o2, "KR 1.1", "TANG", "0", "10", "10", null));
    UUID basis = UUID.randomUUID();
    OkrReadRepository.BasisRow b =
        new OkrReadRepository.BasisRow(
            basis, "NGHI_QUYET", "12-NQ/TU", "Nghị quyết", null, null, null, null, null, null);
    repo.bases.add(b);
    repo.objectiveBases.add(new OkrReadRepository.ObjectiveBasis(o1, b));
    repo.link("BASIS", basis, "OBJECTIVE", o1, "CAN_CU");
    repo.link("OBJECTIVE", o1, "OBJECTIVE", o3, "PHAN_RA");
    repo.link("KEY_RESULT", kr1, "OBJECTIVE", o2, "NGANG");
    repo.link("OBJECTIVE", o2, "KEY_RESULT", kr2, "NGANG");
    repo.link("KEY_RESULT", kr3, "TASK", UUID.randomUUID(), "DONG_GOP");
    return new Seed(o1, o2, o3, kr1, kr2, kr3, basis);
  }

  private static OkrReadRepository.ObjectiveRow objective(
      UUID id, UUID unit, String code, UUID parent) {
    return new OkrReadRepository.ObjectiveRow(
        id,
        Q1.id().value(),
        20271,
        "TAP_THE",
        unit,
        null,
        code,
        "Mục tiêu " + code,
        null,
        parent,
        parent == null ? 1 : 2,
        "DA_DUYET",
        null,
        1,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        0);
  }

  private static OkrReadRepository.KeyResultRow kr(
      UUID id,
      UUID objective,
      String code,
      String direction,
      String base,
      String target,
      String current,
      OkrReadRepository.LastCheckin last) {
    return new OkrReadRepository.KeyResultRow(
        id,
        Q1.id().value(),
        20271,
        objective,
        code,
        "Kết quả " + code,
        "SO_LUONG",
        "việc",
        new BigDecimal(base),
        new BigDecimal(target),
        current == null ? null : new BigDecimal(current),
        direction,
        new BigDecimal("0.5"),
        LocalDate.of(2027, 3, 31),
        null,
        null,
        null,
        "DA_DUYET",
        "BINH_THUONG",
        "TUAN",
        "HUMAN",
        false,
        null,
        null,
        0,
        last,
        null,
        null);
  }

  private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T bean) {
    StaticListableBeanFactory f = new StaticListableBeanFactory();
    f.addBean("bean", bean);
    @SuppressWarnings("unchecked")
    org.springframework.beans.factory.ObjectProvider<T> p =
        (org.springframework.beans.factory.ObjectProvider<T>) f.getBeanProvider(bean.getClass());
    return p;
  }

  private static final class StubPeriods implements PeriodApi {
    @Override
    public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
      return Optional.of(Q1);
    }

    @Override
    public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId periodId) {
      return Q1.id().equals(periodId) ? Optional.of(Q1) : Optional.empty();
    }

    @Override
    public void assertOpen(TenantId tenantId, PeriodId periodId) {}
  }

  /** Kho trong bộ nhớ, lọc đủ cho các đường đi của dịch vụ. */
  private static final class FakeRepository implements OkrReadRepository {

    @Override
    public long countKeyResults(TenantId tenant, OkrReadRepository.KeyResultFilter filter) {
      return 0;
    }

    final List<ObjectiveRow> objectives = new ArrayList<>();
    final List<KeyResultRow> keyResults = new ArrayList<>();
    final List<BasisRow> bases = new ArrayList<>();
    final List<ObjectiveBasis> objectiveBases = new ArrayList<>();
    final List<LinkRow> links = new ArrayList<>();

    void link(String fromType, UUID fromId, String toType, UUID toId, String kind) {
      links.add(
          new LinkRow(
              UUID.randomUUID(), Q1.id().value(), fromType, fromId, toType, toId, kind, null, 0));
    }

    private static <R> List<R> page(Stream<R> s, Comparator<R> order, int limit) {
      return s.sorted(order).limit(limit).toList();
    }

    @Override
    public List<ObjectiveRow> objectives(
        TenantId tenant, ObjectiveFilter f, String afterCode, UUID afterId, int limit) {
      Comparator<ObjectiveRow> order =
          Comparator.comparing(ObjectiveRow::code).thenComparing(ObjectiveRow::id);
      return page(
          objectives.stream()
              .filter(o -> f.unitIds() == null || f.unitIds().contains(o.ownerUnitId()))
              .filter(o -> f.ownerType() == null || f.ownerType().equals(o.ownerType()))
              .filter(
                  o ->
                      afterCode == null
                          || order.compare(
                                  o, OkrReadServiceTest.objective(afterId, null, afterCode, null))
                              > 0),
          order,
          limit);
    }

    @Override
    public long countObjectives(
        TenantId tenant, UUID periodId, List<Integer> buckets, UUID unitId) {
      return objectives.stream().filter(o -> o.ownerUnitId().equals(unitId)).count();
    }

    @Override
    public Optional<ObjectiveRow> objective(TenantId tenant, UUID id) {
      return objectives.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    @Override
    public List<ObjectiveRow> objectivesByIds(TenantId tenant, Collection<UUID> ids) {
      return objectives.stream().filter(o -> ids.contains(o.id())).toList();
    }

    @Override
    public List<KeyResultRow> keyResultsOf(
        TenantId tenant, List<Integer> buckets, Collection<UUID> objectiveIds) {
      return keyResults.stream().filter(k -> objectiveIds.contains(k.objectiveId())).toList();
    }

    @Override
    public List<KeyResultRow> keyResults(
        TenantId tenant, KeyResultFilter f, String afterCode, UUID afterId, int limit) {
      return page(
          keyResults.stream()
              .filter(k -> f.objectiveId() == null || f.objectiveId().equals(k.objectiveId()))
              .filter(
                  k ->
                      f.unitIds() == null
                          || objective(tenant, k.objectiveId())
                              .map(o -> f.unitIds().contains(o.ownerUnitId()))
                              .orElse(false))
              .filter(k -> afterCode == null || !k.id().equals(afterId)),
          Comparator.comparing(KeyResultRow::code).thenComparing(KeyResultRow::id),
          limit);
    }

    @Override
    public Optional<KeyResultRow> keyResult(TenantId tenant, UUID id) {
      return keyResults.stream().filter(k -> k.id().equals(id)).findFirst();
    }

    @Override
    public List<KeyResultRow> keyResultsByIds(TenantId tenant, Collection<UUID> ids) {
      return keyResults.stream().filter(k -> ids.contains(k.id())).toList();
    }

    @Override
    public List<ObjectiveBasis> basesOf(
        TenantId tenant, List<Integer> buckets, Collection<UUID> objectiveIds) {
      return objectiveBases.stream().filter(b -> objectiveIds.contains(b.objectiveId())).toList();
    }

    @Override
    public List<BasisRow> basesByIds(TenantId tenant, Collection<UUID> ids) {
      return bases.stream().filter(b -> ids.contains(b.id())).toList();
    }

    @Override
    public List<BasisRow> bases(
        TenantId tenant,
        String kind,
        LocalDate validOn,
        String query,
        String afterTitle,
        UUID afterId,
        int limit) {
      return bases.stream().limit(limit).toList();
    }

    @Override
    public List<LinkRow> linksTouching(TenantId tenant, Collection<UUID> ids) {
      return links.stream()
          .filter(l -> ids.contains(l.fromId()) || ids.contains(l.toId()))
          .toList();
    }

    @Override
    public List<LinkRow> links(
        TenantId tenant,
        String fromType,
        UUID fromId,
        String toType,
        UUID toId,
        String linkKind,
        UUID periodId,
        UUID afterId,
        int limit) {
      return links.stream().filter(l -> Objects.equals(fromId, l.fromId())).limit(limit).toList();
    }
  }
}
