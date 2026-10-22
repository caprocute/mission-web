package vn.viettel.okrkpi.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bất biến của tầng miền: phạm vi theo nhánh, kiểm lớp ngữ nghĩa, giá trị mặc định. */
class DomainTest {

  static final UUID T = UUID.randomUUID();

  @Test
  void scopeCoversOnlyItsBranch() {
    Scope s = new Scope(T, "/t01", null);
    assertThat(s.covers("/t01")).isTrue();
    assertThat(s.covers("/t01/h01")).isTrue();
    assertThat(s.covers("/t011")).isFalse();
    assertThat(s.covers(null)).isFalse();
    assertThat(s.viewerUnits()).isEmpty();
    assertThat(s.narrowTo("/t01/h01").orgPath()).isEqualTo("/t01/h01");
    assertThatThrownBy(() -> new Scope(T, "/T01", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Scope(T, "/t01/'; DROP", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Scope(T, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Viewer(null, UUID.randomUUID(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void catalogChecksFiltersAndDimensions() {
    SemanticCatalog c =
        new SemanticCatalog(
            "2026.4",
            Map.of(
                "okr.tien_do_o",
                new SemanticCatalog.Measure("okr.tien_do_o", "Tiến độ", "number", "%", false),
                "task.so",
                new SemanticCatalog.Measure("task.so", "Số", "count", null, false)),
            Map.of(
                "okr.chu_the",
                new SemanticCatalog.Dimension(
                    "okr.chu_the", "Chủ thể", "string", Set.of("okr.tien_do_o"))));
    assertThat(
            c.check(
                "",
                List.of("okr.tien_do_o"),
                List.of("okr.chu_the"),
                List.of(new Filter("okr.chu_the", "equals", List.of("DON_VI")))))
        .isEmpty();
    List<SemanticCatalog.Violation> v =
        c.check(
            "w.",
            List.of("task.so", "khong.co"),
            List.of("khong.co", "okr.chu_the"),
            List.of(
                new Filter("okr.chu_the", "equals", null),
                new Filter("khong.co", "in", List.of(1))));
    assertThat(v)
        .extracting(SemanticCatalog.Violation::field)
        .containsExactly(
            "w.measures[1]",
            "w.dimensions[0]",
            "w.dimensions[1]",
            "w.filters[0].member",
            "w.filters[0].values",
            "w.filters[1].member");
    assertThat(v.get(4).combination()).isFalse();
  }

  @Test
  void recordsDefaultEmptyCollectionsAndLimit() {
    AnalyticsRequest r = new AnalyticsRequest(null, null, null, null, null, null, false, 0);
    assertThat(r.limit()).isEqualTo(AnalyticsRequest.DEFAULT_LIMIT);
    assertThat(r.measures()).isEmpty();
    assertThat(r.withPeriod(T).periodId()).isEqualTo(T);
    assertThat(new DashboardWidget("w", 0, 0, 1, 1, "m", null, "COT", null, null).filters())
        .isEmpty();
    assertThat(new Dashboard(T, "t", T, null, null, null, "RIENG", null, null, 0, null).layout())
        .isEmpty();
    assertThat(new DashboardDraft("t", null, "RIENG", null, null).sharedUnitIds()).isEmpty();
    assertThat(new DashboardPage(null, null).items()).isEmpty();
  }
}
