package vn.viettel.okrkpi.docgen.application.render;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;

/**
 * Mẫu 3 (DV-11-03): danh sách quyết định xếp loại của một đơn vị hoặc của các hồ sơ đang chọn
 * (SCR-0904 "Xuất Mẫu 3"). Bộ ánh xạ sắp dòng theo đơn vị (thứ tự {@code thu_tu_don_vi}, rồi tên
 * đơn vị), theo mức quyết định (thứ tự mức trong bảng nhãn {@code muc_xep_loai} của mẫu, chưa có
 * mức xếp cuối), rồi theo họ tên theo thứ tự chữ cái tiếng Việt; đánh lại số thứ tự sau khi sắp;
 * suy ra cờ "Không tự đánh giá" khi không có mức tự đề xuất (QT-09 ngoại lệ thứ ba, hệ thống không
 * tự điền mức).
 *
 * <p>Tổng hợp số lượng theo mức là bảng {@code tong_hop_theo_muc} (cột {@code muc}, {@code
 * so_luong}) và trường {@code tong_so}; mẫu giả lập DL-05 chưa có hai vùng trộn này nên bộ trộn bỏ
 * qua cho tới khi mẫu bổ sung (wiki trang 14).
 */
public final class Mau3Renderer extends ManifestFormRenderer {

  public static final String CODE = "MAU_3";
  public static final String LIST_TAG = "danh_sach_xep_loai";
  public static final String SUMMARY_TAG = "tong_hop_theo_muc";
  public static final String TOTAL_TAG = "tong_so";
  static final String UNIT_ORDER = "thu_tu_don_vi";
  static final String UNIT_NAME = "ten_don_vi";
  static final String NAME = "ho_ten";
  static final String SELF = "muc_tu_de_xuat";
  static final String NO_SELF = "khong_tu_danh_gia";
  static final String DECIDED = "muc_quyet_dinh";

  public Mau3Renderer() {
    super(CODE);
  }

  @Override
  public Map<String, Object> prepare(
      Map<String, Object> data, TemplateManifest manifest, RenderContext ctx) {
    Object raw = data.get(LIST_TAG);
    if (raw == null) {
      return data;
    }
    if (!(raw instanceof List<?> list)) {
      throw new RejectedDocument("Danh sách xếp loại không phải danh sách.");
    }
    List<String> levels = new ArrayList<>(manifest.labelTable("muc_xep_loai").keySet());
    Collator collator = Collator.getInstance(Locale.forLanguageTag("vi"));
    List<Map<String, Object>> rows = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> m)) {
        throw new RejectedDocument("Dòng của danh sách xếp loại không phải đối tượng.");
      }
      Map<String, Object> row = new HashMap<>();
      m.forEach((k, v) -> row.put(String.valueOf(k), v));
      row.putIfAbsent(NO_SELF, row.get(SELF) == null);
      rows.add(row);
    }
    Comparator<Map<String, Object>> order =
        Comparator.<Map<String, Object>>comparingLong(r -> unitOrder(r.get(UNIT_ORDER)))
            .thenComparing(r -> text(r.get(UNIT_NAME)), collator)
            .thenComparingInt(r -> rank(levels, r.get(DECIDED)))
            .thenComparing(r -> text(r.get(NAME)), collator);
    rows.sort(order);
    for (int i = 0; i < rows.size(); i++) {
      rows.get(i).put(FormModelBuilder.ROW_NUMBER_TAG, i + 1);
    }
    Map<String, Object> out = new LinkedHashMap<>(data);
    out.put(LIST_TAG, rows);
    return out;
  }

  @Override
  public MergeModel finish(
      MergeModel model, Map<String, Object> data, TemplateManifest manifest, RenderContext ctx) {
    Map<String, Long> counts = new LinkedHashMap<>();
    manifest.labelTable("muc_xep_loai").keySet().forEach(code -> counts.put(code, 0L));
    long total = 0;
    if (data.get(LIST_TAG) instanceof List<?> rows) {
      for (Object row : rows) {
        Object level = ((Map<?, ?>) row).get(DECIDED);
        if (level != null) {
          counts.merge(level.toString(), 1L, Long::sum);
        }
        total++;
      }
    }
    Map<String, String> labels = manifest.labelTable("muc_xep_loai");
    List<Map<String, String>> summary = new ArrayList<>();
    counts.forEach(
        (code, n) ->
            summary.add(
                Map.of(
                    "muc", labels.getOrDefault(code, code),
                    "so_luong", ValueFormats.number("so_luong", 0, n))));
    Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>(model.tables());
    tables.put(SUMMARY_TAG, summary);
    Map<String, String> values = new LinkedHashMap<>(model.values());
    values.put(TOTAL_TAG, ValueFormats.number(TOTAL_TAG, 0, total));
    Set<String> locked = new HashSet<>(model.locked());
    locked.add(SUMMARY_TAG);
    locked.add(TOTAL_TAG);
    return new MergeModel(values, tables, locked, model.watermark());
  }

  private static long unitOrder(Object value) {
    if (value == null) {
      return Long.MAX_VALUE;
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      throw new RejectedDocument("Thứ tự đơn vị trong danh sách xếp loại không phải số.");
    }
  }

  private static int rank(List<String> levels, Object level) {
    int i = level == null ? -1 : levels.indexOf(level.toString());
    return i < 0 ? levels.size() : i;
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString();
  }
}
