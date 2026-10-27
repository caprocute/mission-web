package vn.viettel.okrkpi.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.viettel.okrkpi.analytics.application.Fakes.KY_Q3;
import static vn.viettel.okrkpi.analytics.application.Fakes.KY_Q4;
import static vn.viettel.okrkpi.analytics.application.Fakes.TENANT;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_HUYEN;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_KHAC;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_TINH;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.analytics.domain.AnalyticsRequest;
import vn.viettel.okrkpi.analytics.domain.Column;
import vn.viettel.okrkpi.analytics.domain.Filter;
import vn.viettel.okrkpi.analytics.domain.QueryResult;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.shared.error.BusinessException;

/** DV-15-04: kiểm lớp ngữ nghĩa, phạm vi người xem, bộ đệm, so sánh kỳ trước, độ mới dữ liệu. */
class QueryServiceTest {

  static final Instant NOW = Instant.parse("2026-11-16T02:00:00Z");
  final Fakes.Cube cube = new Fakes.Cube();
  final Fakes.Org org = new Fakes.Org();
  final Fakes.Cache cache = new Fakes.Cache();
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final ScopeService scopes = new ScopeService(org);
  final QueryService service =
      new QueryService(
          scopes,
          new SemanticModelService(cube, Duration.ofMinutes(5), clock),
          cube,
          org,
          cache,
          Duration.ofSeconds(60),
          Duration.ofMinutes(5),
          clock);

  {
    cube.freshnessMs = NOW.minusSeconds(30).toEpochMilli();
  }

  static Viewer viewer(UUID unit) {
    return new Viewer(TENANT, UUID.randomUUID(), unit);
  }

  static AnalyticsRequest req(List<String> ms, List<String> ds) {
    return new AnalyticsRequest(ms, ds, List.of(), null, null, null, false, 50);
  }

  @Test
  void unknownMeasureIsInvalidCombination() {
    assertThatThrownBy(
            () -> service.query(viewer(UNIT_TINH), req(List.of("okr.khong_co"), List.of())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo(AnalyticsErrors.INVALID_COMBINATION);
              assertThat(e.errors()).extracting("field").containsExactly("measures[0]");
            });
    assertThat(cube.queries).isEmpty();
  }

  @Test
  void dimensionNotReachableFromMeasureIsRejected() {
    assertThatThrownBy(
            () ->
                service.query(
                    viewer(UNIT_TINH), req(List.of("task.so_nhiem_vu"), List.of("okr.chu_the"))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(AnalyticsErrors.INVALID_COMBINATION));
    AnalyticsRequest periodOnTask =
        new AnalyticsRequest(
            List.of("task.so_nhiem_vu"), List.of(), List.of(), KY_Q4, null, null, false, 50);
    assertThatThrownBy(() -> service.query(viewer(UNIT_TINH), periodOnTask))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errors()).extracting("field").containsExactly("periodId"));
    AnalyticsRequest privateFilter =
        new AnalyticsRequest(
            List.of("okr.tien_do_o"),
            List.of(),
            List.of(new Filter("okr.tenant_id", "equals", List.of("x"))),
            null,
            null,
            null,
            false,
            50);
    assertThatThrownBy(() -> service.query(viewer(UNIT_TINH), privateFilter))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void viewerWithoutUnitOrOutsideBranchIsOutOfScope() {
    assertThatThrownBy(() -> service.query(viewer(null), req(List.of("okr.tien_do_o"), List.of())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(AnalyticsErrors.OUT_OF_SCOPE));
    AnalyticsRequest other =
        new AnalyticsRequest(
            List.of("okr.tien_do_o"), List.of(), List.of(), null, UNIT_KHAC, null, false, 50);
    assertThatThrownBy(() -> service.query(viewer(UNIT_TINH), other))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(AnalyticsErrors.OUT_OF_SCOPE));
    // Cấp dưới không mở rộng lên cấp trên.
    AnalyticsRequest up =
        new AnalyticsRequest(
            List.of("okr.tien_do_o"), List.of(), List.of(), null, UNIT_TINH, null, false, 50);
    assertThatThrownBy(() -> service.query(viewer(UNIT_HUYEN), up))
        .isInstanceOf(BusinessException.class);
    assertThat(cube.queries).isEmpty();
  }

  @Test
  void unitInsideBranchNarrowsScope() {
    AnalyticsRequest down =
        new AnalyticsRequest(
            List.of("okr.tien_do_o"), List.of(), List.of(), null, UNIT_HUYEN, null, false, 50);
    service.query(viewer(UNIT_TINH), down);
    assertThat(cube.paths).containsExactly("/t01/h01");
  }

  @Test
  void cacheIsSharedOnlyWithinTheSameScope() {
    AnalyticsRequest r = req(List.of("okr.tien_do_o"), List.of("org_unit.ten"));
    service.query(viewer(UNIT_TINH), r);
    service.query(viewer(UNIT_TINH), r);
    assertThat(cube.queries).hasSize(1);
    service.query(viewer(UNIT_HUYEN), r);
    assertThat(cube.queries).hasSize(2);
    assertThat(cube.paths).containsExactly("/t01", "/t01/h01");
    assertThat(cache.entries).hasSize(2);
  }

  @Test
  void resultColumnsCarryTitlesUnitsAndNumbers() {
    cube.rows = (s, q) -> List.of(row("org_unit.ten", "Huyện A", "okr.tien_do_o", "72.5"));
    QueryResult r =
        service.query(viewer(UNIT_TINH), req(List.of("okr.tien_do_o"), List.of("org_unit.ten")));
    assertThat(r.semanticModelVersion()).isEqualTo("2026.4");
    assertThat(r.columns())
        .containsExactly(
            new Column("org_unit.ten", "Tên đơn vị", "string", null),
            new Column("okr.tien_do_o", "Tiến độ mục tiêu O", "number", "%"));
    assertThat(r.rows()).hasSize(1);
    assertThat(r.rows().get(0).get(0)).isEqualTo("Huyện A");
    assertThat(r.rows().get(0).get(1)).isEqualTo(new java.math.BigDecimal("72.5"));
    assertThat(r.stale()).isFalse();
  }

  /**
   * HT-39: bốn chỉ số của SCR-1601 thuộc nhiều cube không nối nhau; mỗi cube chạy một truy vấn,
   * dòng ghép theo giá trị chiều.
   */
  @Test
  void measuresOfSeveralCubesRunOneCubeQueryEachAndMergeByDimension() {
    cube.rows =
        (s, q) ->
            ((List<?>) q.get("measures")).contains("okr.tien_do_o")
                ? List.of(
                    row("org_unit.ten", "Huyện A", "okr.tien_do_o", "80", "okr.so_muc_tieu", "3"),
                    row("org_unit.ten", "Huyện B", "okr.tien_do_o", "50", "okr.so_muc_tieu", "2"))
                : List.of(
                    row("org_unit.ten", "Huyện B", "task.so_nhiem_vu", "7"),
                    row("org_unit.ten", "Huyện C", "task.so_nhiem_vu", "1"));
    QueryResult res =
        service.query(
            viewer(UNIT_TINH),
            req(
                List.of("okr.tien_do_o", "task.so_nhiem_vu", "okr.so_muc_tieu"),
                List.of("org_unit.ten")));
    assertThat(cube.queries)
        .extracting(q -> q.get("measures"))
        .containsExactly(List.of("okr.tien_do_o", "okr.so_muc_tieu"), List.of("task.so_nhiem_vu"));
    assertThat(res.columns())
        .extracting(Column::name)
        .containsExactly("org_unit.ten", "okr.tien_do_o", "task.so_nhiem_vu", "okr.so_muc_tieu");
    java.util.function.Function<String, java.math.BigDecimal> n = java.math.BigDecimal::new;
    assertThat(res.rows())
        .containsExactly(
            java.util.Arrays.asList("Huyện A", n.apply("80"), null, n.apply("3")),
            java.util.Arrays.asList("Huyện B", n.apply("50"), n.apply("7"), n.apply("2")),
            java.util.Arrays.asList("Huyện C", null, n.apply("1"), null));
  }

  @Test
  void periodFilterAndPreviousPeriodComparison() {
    cube.rows =
        (s, q) -> {
          String period = periodOf(q);
          return List.of(
              row(
                  "org_unit.ten",
                  "Huyện A",
                  "okr.tien_do_o",
                  KY_Q4.toString().equals(period) ? "80" : "60"),
              row(
                  "org_unit.ten",
                  "Huyện B",
                  "okr.tien_do_o",
                  KY_Q4.toString().equals(period) ? "50" : null));
        };
    AnalyticsRequest r =
        new AnalyticsRequest(
            List.of("okr.tien_do_o"),
            List.of("org_unit.ten"),
            List.of(),
            KY_Q4,
            null,
            null,
            true,
            50);
    QueryResult res = service.query(viewer(UNIT_TINH), r);
    assertThat(cube.queries)
        .extracting(QueryServiceTest::periodOf)
        .containsExactly(KY_Q4.toString(), KY_Q3.toString());
    assertThat(res.columns())
        .extracting(Column::name)
        .containsExactly("org_unit.ten", "okr.tien_do_o", "okr.tien_do_o.previous");
    assertThat(res.rows().get(0))
        .containsExactly("Huyện A", new java.math.BigDecimal("80"), new java.math.BigDecimal("60"));
    assertThat(res.rows().get(1)).containsExactly("Huyện B", new java.math.BigDecimal("50"), null);
  }

  @Test
  void compareNeedsPeriodAndGranularityNeedsTimeDimension() {
    AnalyticsRequest noPeriod =
        new AnalyticsRequest(
            List.of("okr.tien_do_o"), List.of(), List.of(), null, null, null, true, 50);
    assertThatThrownBy(() -> service.query(viewer(UNIT_TINH), noPeriod))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(AnalyticsErrors.VALIDATION));
    AnalyticsRequest noTime =
        new AnalyticsRequest(
            List.of("task.so_nhiem_vu"), List.of(), List.of(), null, null, "week", false, 50);
    assertThatThrownBy(() -> service.query(viewer(UNIT_TINH), noTime))
        .isInstanceOf(BusinessException.class);
    AnalyticsRequest week =
        new AnalyticsRequest(
            List.of("task.so_nhiem_vu"),
            List.of("task.han"),
            List.of(),
            null,
            null,
            "week",
            false,
            50);
    cube.rows =
        (s, q) -> List.of(row("task.han.week", "2026-11-09T00:00:00.000", "task.so_nhiem_vu", "4"));
    QueryResult res = service.query(viewer(UNIT_TINH), week);
    assertThat(cube.queries.get(0))
        .containsEntry(
            "timeDimensions", List.of(Map.of("dimension", "task.han", "granularity", "week")));
    assertThat(res.rows().get(0))
        .containsExactly("2026-11-09T00:00:00.000", new java.math.BigDecimal("4"));
  }

  @Test
  void staleWhenFreshnessOlderThanFiveMinutesOrMissing() {
    cube.freshnessMs = NOW.minusSeconds(301).toEpochMilli();
    QueryResult old = service.query(viewer(UNIT_TINH), req(List.of("okr.tien_do_o"), List.of()));
    assertThat(old.stale()).isTrue();
    assertThat(old.dataAsOf()).isEqualTo(NOW.minusSeconds(301));
    cube.freshnessMs = null;
    QueryResult none = service.query(viewer(UNIT_TINH), req(List.of("okr.so_muc_tieu"), List.of()));
    assertThat(none.stale()).isTrue();
  }

  @Test
  void inFilterBecomesCubeEqualsWithStringValues() {
    AnalyticsRequest r =
        new AnalyticsRequest(
            List.of("okr.tien_do_o"),
            List.of(),
            List.of(new Filter("okr.chu_the", "in", List.of("DON_VI", 2))),
            null,
            null,
            null,
            false,
            50);
    service.query(viewer(UNIT_TINH), r);
    assertThat(cube.queries.get(0).get("filters"))
        .isEqualTo(
            List.of(
                Map.of(
                    "member",
                    "okr.chu_the",
                    "operator",
                    "equals",
                    "values",
                    List.of("DON_VI", "2"))));
  }

  static String periodOf(Map<String, Object> q) {
    if (q.get("filters") instanceof List<?> fs) {
      for (Object f : fs) {
        if (f instanceof Map<?, ?> m && QueryService.PERIOD_MEMBER.equals(m.get("member"))) {
          return ((List<?>) m.get("values")).get(0).toString();
        }
      }
    }
    return null;
  }

  static Map<String, Object> row(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }
}
