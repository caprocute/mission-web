package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Thuật toán ghép lớp phủ trang chủ của CNH-02 (đề xuất CNH-01 mục 4.3). */
class HomeLayoutOverlayTest {

  private final ObjectMapper json = new ObjectMapper();

  private final JsonNode layout =
      tree(
          """
          {"component":"vt.layout.grid","children":[
            {"component":"vt.widget.a","mandatory":true},
            {"component":"vt.widget.b","props":{"resizable":true}},
            {"component":"vt.widget.c"},
            {"component":"vt.widget.d","id":"tin"}]}
          """);

  private JsonNode tree(String s) {
    try {
      return json.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> ids(JsonNode layout) {
    List<String> out = new ArrayList<>();
    layout.path("children").forEach(c -> out.add(HomeLayoutOverlay.widgetId(c)));
    return out;
  }

  @Test
  void withoutOverlayKeepsPackOrderAndReportsMandatory() {
    HomeLayoutOverlay.Applied a = HomeLayoutOverlay.apply(layout, null, null);
    assertThat(ids(a.layout())).containsExactly("vt.widget.a", "vt.widget.b", "vt.widget.c", "tin");
    assertThat(a.hasOverlay()).isFalse();
    assertThat(a.mandatoryWidgetIds()).containsExactly("vt.widget.a");
  }

  @Test
  void pinnedThenOrderedThenRestAndHiddenSkipsMandatoryAndUnknown() {
    JsonNode overlay =
        tree(
            """
            {"pinnedWidgets":["tin","khong.co"],
             "hiddenWidgets":["vt.widget.a","vt.widget.c","tin"],
             "widgetOrder":[{"id":"vt.widget.b","order":1,"colSpan":8},
                            {"id":"vt.widget.a","order":0,"colSpan":6}],
             "collapsedSections":["vt.widget.b"]}
            """);
    HomeLayoutOverlay.Applied a = HomeLayoutOverlay.apply(layout, overlay, 3);
    assertThat(ids(a.layout())).containsExactly("tin", "vt.widget.a", "vt.widget.b");
    assertThat(a.overlayVersion()).isEqualTo(3);
    JsonNode b = a.layout().path("children").get(2);
    assertThat(b.at("/props/colSpan").asInt()).isEqualTo(8);
    assertThat(b.at("/props/collapsed").asBoolean()).isTrue();
    // Widget không cho đổi kích thước giữ nguyên.
    assertThat(a.layout().path("children").get(1).at("/props/colSpan").isMissingNode()).isTrue();
    // Bố cục gốc không bị sửa.
    assertThat(ids(layout)).hasSize(4);
  }
}
