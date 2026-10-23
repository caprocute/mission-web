package vn.viettel.okrkpi.docgen.application.render;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nội dung trộn vào content control của mẫu DOCX theo tag (DV-11-02, DV-11-03): giá trị đã định
 * dạng sẵn để in, các bảng lặp dòng mẫu, tập tag khoá chỉnh sửa (vùng số liệu, BR-11-02) và dấu bản
 * nháp. Giá trị C2 đã giải mã chỉ nằm trong đối tượng này ở bộ nhớ worker; {@link #toString()}
 * không in giá trị để không lọt vào nhật ký.
 *
 * @param values tag → chữ in; xuống dòng là ngắt dòng trong ô
 * @param tables tag bảng → các dòng, mỗi dòng là tag cột → chữ in
 * @param locked các tag (trường hay bảng) đặt khoá {@code sdtContentLocked}
 * @param watermark dấu in đầu tài liệu và chân mọi trang (ví dụ "BẢN NHÁP"); {@code null} là không
 *     có
 */
public record MergeModel(
    Map<String, String> values,
    Map<String, List<Map<String, String>>> tables,
    Set<String> locked,
    String watermark) {

  public MergeModel {
    values = Map.copyOf(values);
    tables = Map.copyOf(tables);
    locked = Set.copyOf(locked);
  }

  /** Cùng nội dung, thêm dấu. */
  public MergeModel withWatermark(String mark) {
    return new MergeModel(values, tables, locked, mark);
  }

  @Override
  public String toString() {
    return "MergeModel[" + values.size() + " trường, " + tables.size() + " bảng]";
  }
}
