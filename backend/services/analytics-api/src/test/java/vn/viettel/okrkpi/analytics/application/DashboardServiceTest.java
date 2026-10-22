package vn.viettel.okrkpi.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.viettel.okrkpi.analytics.application.Fakes.TENANT;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_HUYEN;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_TINH;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.analytics.domain.Dashboard;
import vn.viettel.okrkpi.analytics.domain.DashboardDraft;
import vn.viettel.okrkpi.analytics.domain.DashboardPage;
import vn.viettel.okrkpi.analytics.domain.DashboardWidget;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;

/** DV-15-04: kiểm widget theo lớp ngữ nghĩa, giới hạn 20 widget, khoá lạc quan, chia sẻ. */
class DashboardServiceTest {

  final Fakes.Cube cube = new Fakes.Cube();
  final Fakes.Org org = new Fakes.Org();
  final Store store = new Store();
  final List<Dashboard> notified = new ArrayList<>();
  final Clock clock = Clock.systemUTC();
  final ScopeService scopes = new ScopeService(org);
  final SemanticModelService models = new SemanticModelService(cube, Duration.ofMinutes(5), clock);
  final QueryService queries =
      new QueryService(
          scopes,
          models,
          cube,
          org,
          new Fakes.Cache(),
          Duration.ofSeconds(60),
          Duration.ofMinutes(5),
          clock);
  final DashboardService service =
      new DashboardService(store, scopes, models, queries, (v, d) -> notified.add(d));
  final Viewer owner = new Viewer(TENANT, UUID.randomUUID(), UNIT_HUYEN);

  static DashboardWidget widget(String id, String measure, List<String> dims) {
    return new DashboardWidget(id, 0, 0, 3, 2, measure, dims, "COT", List.of(), true);
  }

  @Test
  void createValidatesWidgetsAndSavesPrivateWithModelVersion() {
    Dashboard d =
        service.create(
            owner, "Bảng của Bí thư", List.of(widget("w1", "okr.tien_do_o", List.of())), "k1");
    assertThat(store.drafts.get(0).shareScope()).isEqualTo(DashboardDraft.RIENG);
    assertThat(store.drafts.get(0).semanticModelVersion()).isEqualTo("2026.4");
    assertThat(d.rowVersion()).isZero();
    assertThat(store.idempotencyKeys).containsExactly("k1");
  }

  @Test
  void moreThanTwentyWidgetsOrUnknownMeasureIsRejected() {
    List<DashboardWidget> many =
        IntStream.range(0, 21).mapToObj(i -> widget("w" + i, "okr.tien_do_o", List.of())).toList();
    assertThatThrownBy(() -> service.create(owner, "Nhiều", many, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(AnalyticsErrors.VALIDATION));
    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    "Sai",
                    List.of(
                        widget("w1", "okr.tien_do_o", List.of()),
                        widget("w2", "task.so_nhiem_vu", List.of("okr.chu_the"))),
                    null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo(AnalyticsErrors.INVALID_COMBINATION);
              assertThat(e.errors()).extracting("field").containsExactly("layout[1].dimensions[0]");
            });
    assertThat(store.drafts).isEmpty();
  }

  @Test
  void updateNeedsIfMatchAndStaleVersionConflicts() {
    Dashboard d =
        service.create(owner, "Bảng", List.of(widget("w1", "okr.tien_do_o", List.of())), null);
    List<DashboardWidget> layout =
        List.of(widget("w1", "okr.so_muc_tieu", List.of("org_unit.ten")));
    assertThatThrownBy(() -> service.update(owner, d.id(), "Bảng", layout, null, null))
        .isInstanceOf(PreconditionRequiredException.class);
    Dashboard saved = service.update(owner, d.id(), "Bảng mới", layout, null, "\"0\"");
    assertThat(saved.rowVersion()).isEqualTo(1);
    assertThatThrownBy(() -> service.update(owner, d.id(), "Bảng", layout, null, "\"0\""))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void shareKeepsLayoutAndRequiresUnitsForUnitScope() {
    Dashboard d =
        service.create(owner, "Bảng", List.of(widget("w1", "okr.tien_do_o", List.of())), null);
    assertThatThrownBy(
            () -> service.share(owner, d.id(), DashboardDraft.DON_VI, List.of(), null, "\"0\""))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errors()).extracting("field").containsExactly("sharedUnitIds"));
    Dashboard shared =
        service.share(owner, d.id(), DashboardDraft.DON_VI, List.of(UNIT_TINH), null, "\"0\"");
    assertThat(shared.shareScope()).isEqualTo(DashboardDraft.DON_VI);
    assertThat(shared.layout()).isEqualTo(d.layout());
    assertThat(notified).containsExactly(shared);
    Dashboard tenant = service.share(owner, d.id(), "TENANT", List.of(UNIT_TINH), null, "\"1\"");
    assertThat(tenant.sharedUnitIds()).isEmpty();
  }

  @Test
  void dataRunsInViewerScopeAndSkipsWidgetsOutsideTheModel() {
    Dashboard d =
        service.create(
            owner,
            "Bảng",
            List.of(
                widget("w1", "okr.tien_do_o", List.of()),
                widget("w2", "okr.so_muc_tieu", List.of())),
            null);
    // Lớp ngữ nghĩa đổi sau khi lưu: widget w2 trỏ chỉ số không còn.
    store.override(d.id(), w -> w.id().equals("w2") ? widget("w2", "okr.da_bo", List.of()) : w);
    Viewer other = new Viewer(TENANT, UUID.randomUUID(), UNIT_TINH);
    DashboardService.View v = service.get(other, d.id(), null, null, true);
    assertThat(cube.paths).containsExactly("/t01");
    assertThat(v.data().widgets()).hasSize(2);
    assertThat(v.data().widgets().get(1).columns()).isEmpty();
    assertThat(store.viewerUnits).contains(List.of(UNIT_TINH));
    assertThat(service.get(other, d.id(), null, null, false).data()).isNull();
  }

  /** Kho giả theo hành vi của MOD-08: khoá lạc quan theo rowVersion. */
  static final class Store implements DashboardStorePort {
    final Map<UUID, Dashboard> rows = new HashMap<>();
    final List<DashboardDraft> drafts = new ArrayList<>();
    final List<String> idempotencyKeys = new ArrayList<>();
    final List<List<UUID>> viewerUnits = new ArrayList<>();

    @Override
    public DashboardPage list(
        Viewer viewer, List<UUID> units, boolean mine, int limit, String cursor) {
      viewerUnits.add(units);
      return new DashboardPage(List.of(), null);
    }

    @Override
    public Dashboard create(Viewer viewer, DashboardDraft draft, String key) {
      drafts.add(draft);
      if (key != null) {
        idempotencyKeys.add(key);
      }
      Dashboard d = of(UUID.randomUUID(), viewer.userId(), draft, 0);
      rows.put(d.id(), d);
      return d;
    }

    @Override
    public Dashboard get(Viewer viewer, List<UUID> units, UUID id) {
      viewerUnits.add(units);
      return rows.get(id);
    }

    @Override
    public Dashboard replace(
        Viewer viewer, UUID id, DashboardDraft draft, String key, String ifMatch) {
      Dashboard cur = rows.get(id);
      long expected = Long.parseLong(ifMatch.replace("\"", ""));
      if (expected != cur.rowVersion()) {
        throw VersionConflictException.of("ANALYTICS", expected, cur.rowVersion());
      }
      drafts.add(draft);
      Dashboard d = of(id, cur.ownerUserId(), draft, cur.rowVersion() + 1);
      rows.put(id, d);
      return d;
    }

    void override(UUID id, java.util.function.UnaryOperator<DashboardWidget> f) {
      Dashboard c = rows.get(id);
      rows.put(
          id,
          new Dashboard(
              c.id(),
              c.title(),
              c.ownerUserId(),
              c.measures(),
              c.dimensions(),
              c.layout().stream().map(f).toList(),
              c.shareScope(),
              c.sharedUnitIds(),
              c.semanticModelVersion(),
              c.rowVersion(),
              c.updatedAt()));
    }

    static Dashboard of(UUID id, UUID owner, DashboardDraft d, long version) {
      return new Dashboard(
          id,
          d.title(),
          owner,
          List.of(),
          List.of(),
          d.layout(),
          d.shareScope(),
          d.sharedUnitIds(),
          d.semanticModelVersion(),
          version,
          Instant.now());
    }
  }
}
