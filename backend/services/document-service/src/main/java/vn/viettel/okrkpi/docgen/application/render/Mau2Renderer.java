package vn.viettel.okrkpi.docgen.application.render;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mẫu 2 (DV-11-03): kết quả sau thẩm định và mức quyết định của một người, theo kê khai DL-05; nhãn
 * tiêu chí chung lấy bảng {@code MAU_2} ("Không vi phạm", "Vi phạm") của mẫu (BR-11-04).
 *
 * <p>Hai dòng ghi chú cố định nhận cờ đúng/sai từ dữ liệu và in câu tương ứng trong bảng {@code
 * cau_ghi_chu} của mẫu: {@code ghi_chu_khong_xep_loai} khi có trường hợp đặc thù không xếp loại,
 * {@code ghi_chu_so_quy_thuc_co} khi điểm năm tính trên số quý thực có (QT-10). Dữ liệu có thể gửi
 * thẳng câu in (chuỗi), khi đó giữ nguyên. Câu giải thích hạ mức in nguyên văn (BR-09-10).
 */
public final class Mau2Renderer extends ManifestFormRenderer {

  public static final String CODE = "MAU_2";

  /** Tag ghi chú → khoá câu trong {@code cau_ghi_chu}. */
  static final Map<String, String> NOTES =
      Map.of(
          "ghi_chu_khong_xep_loai", "khong_xep_loai_ky_quy",
          "ghi_chu_so_quy_thuc_co", "diem_nam_theo_so_quy");

  public Mau2Renderer() {
    super(CODE);
  }

  /** Đổi cờ ghi chú thành khoá câu để bước dựng tra trong bảng nhãn của mẫu. */
  @Override
  public Map<String, Object> prepare(
      Map<String, Object> data, TemplateManifest manifest, RenderContext ctx) {
    Map<String, Object> out = new LinkedHashMap<>(data);
    NOTES.forEach(
        (tag, sentence) -> {
          Object flag = data.get(tag);
          if (flag instanceof Boolean b) {
            out.put(tag, b ? new FormModelBuilder.SentenceRef(sentence) : null);
          }
        });
    return out;
  }
}
