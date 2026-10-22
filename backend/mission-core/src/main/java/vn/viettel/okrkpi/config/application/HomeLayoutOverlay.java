package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Áp lớp phủ cá nhân {@code ui.home.layout} lên bố cục trang chủ đã lọc theo vai trò (CNH-02, đề
 * xuất CNH-01 mục 4.3). Widget là các nút con trực tiếp của gốc bố cục; mã widget là {@code id} của
 * nút, thiếu thì lấy {@code component}. Mã không còn trong bố cục (UI Pack mới, đổi vai trò) bị bỏ
 * qua; widget {@code mandatory} không bao giờ bị ẩn. Thứ tự: widget ghim, rồi theo {@code
 * widgetOrder}, rồi các widget còn lại theo thứ tự của UI Pack. Ghim thắng ẩn khi trùng (suy luận).
 */
public final class HomeLayoutOverlay {

  private HomeLayoutOverlay() {}

  /**
   * @param layout gốc bố cục đã lọc theo vai trò (không bị sửa)
   * @param overlay giá trị khoá {@code ui.home.layout}, {@code null} khi người dùng chưa lưu
   * @param overlayVersion phiên bản lớp phủ, {@code null} khi chưa lưu
   */
  public static Applied apply(JsonNode layout, JsonNode overlay, Integer overlayVersion) {
    ObjectNode out = ((ObjectNode) layout).deepCopy();
    JsonNode children = out.path("children");
    Map<String, ObjectNode> widgets = new LinkedHashMap<>();
    List<String> mandatory = new ArrayList<>();
    if (children.isArray()) {
      for (JsonNode child : children) {
        if (child.isObject()) {
          String id = widgetId(child);
          widgets.putIfAbsent(id, (ObjectNode) child);
          if (child.path("mandatory").asBoolean(false)) {
            mandatory.add(id);
          }
        }
      }
    }
    if (overlay == null || !overlay.isObject() || !children.isArray()) {
      return new Applied(out, false, null, List.copyOf(mandatory));
    }
    Set<String> pinned = known(overlay.path("pinnedWidgets"), widgets);
    Set<String> hidden = known(overlay.path("hiddenWidgets"), widgets);
    hidden.removeAll(mandatory);
    hidden.removeAll(pinned);
    Set<String> collapsed = known(overlay.path("collapsedSections"), widgets);

    List<JsonNode> placements = new ArrayList<>();
    overlay.path("widgetOrder").forEach(placements::add);
    placements.sort(Comparator.comparingInt(p -> p.path("order").asInt(Integer.MAX_VALUE)));
    Set<String> ordered = new LinkedHashSet<>(pinned);
    Map<String, Integer> spans = new LinkedHashMap<>();
    for (JsonNode p : placements) {
      String id = p.path("id").asText(null);
      if (id != null && widgets.containsKey(id)) {
        ordered.add(id);
        if (p.path("colSpan").isIntegralNumber()) {
          spans.put(id, p.path("colSpan").asInt());
        }
      }
    }
    ordered.addAll(widgets.keySet());

    ArrayNode kept = out.putArray("children");
    for (String id : ordered) {
      if (hidden.contains(id)) {
        continue;
      }
      ObjectNode widget = widgets.get(id);
      Integer span = spans.get(id);
      if (span != null && widget.path("props").path("resizable").asBoolean(false)) {
        widget.with("props").put("colSpan", span);
      }
      if (collapsed.contains(id)) {
        widget.with("props").put("collapsed", true);
      }
      kept.add(widget);
    }
    // Nút con không phải đối tượng (không hợp lệ theo SduiNode) được giữ nguyên ở cuối.
    for (JsonNode child : children) {
      if (!child.isObject()) {
        kept.add(child);
      }
    }
    return new Applied(out, true, overlayVersion, List.copyOf(mandatory));
  }

  static String widgetId(JsonNode node) {
    JsonNode id = node.get("id");
    return id != null && id.isTextual() ? id.asText() : node.path("component").asText("");
  }

  private static Set<String> known(JsonNode ids, Map<String, ObjectNode> widgets) {
    Set<String> out = new LinkedHashSet<>();
    if (ids.isArray()) {
      for (JsonNode id : ids) {
        if (id.isTextual() && widgets.containsKey(id.asText())) {
          out.add(id.asText());
        }
      }
    }
    return out;
  }

  /** Bố cục sau khi áp lớp phủ và thông tin cho {@code personalizationMeta}. */
  public record Applied(
      JsonNode layout,
      boolean hasOverlay,
      Integer overlayVersion,
      List<String> mandatoryWidgetIds) {}
}
