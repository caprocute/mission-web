package vn.viettel.okrkpi.docgen.application.render;

import java.util.LinkedHashMap;
import java.util.Map;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;

/**
 * Bảng chấm điểm (DV-11-03): dựng từ vết tính đã chốt ({@code scoring.score_trace}, {@code
 * appraisal.score_snapshot}) do MOD-06 gửi trong dữ liệu biểu mẫu; document-service không tính lại
 * điểm (TL-01 "Tính lại tái lập được"). Dữ liệu phải mang {@code policy_version} của vết tính, và
 * khi lệnh sinh có phiên bản Policy Pack gắn với kỳ thì hai giá trị phải trùng, nếu không tác vụ
 * lỗi thay vì in một bảng điểm của bộ quy tắc khác. Băm đầu vào in rút gọn như SCR-0903.
 */
public final class BangChamDiemRenderer extends ManifestFormRenderer {

  public static final String CODE = "BANG_CHAM_DIEM";
  static final String POLICY_VERSION = "policy_version";
  static final String INPUT_HASH = "bam_dau_vao";
  static final int HASH_PREFIX = 12;

  public BangChamDiemRenderer() {
    super(CODE);
  }

  @Override
  public Map<String, Object> prepare(
      Map<String, Object> data, TemplateManifest manifest, RenderContext ctx) {
    Object version = data.get(POLICY_VERSION);
    if (version == null || version.toString().isBlank()) {
      throw new RejectedDocument("Vết tính điểm không ghi phiên bản bộ quy tắc đã dùng.");
    }
    if (ctx.policyPackVersion() != null
        && !ctx.policyPackVersion().isBlank()
        && !ctx.policyPackVersion().equals(version.toString())) {
      throw new RejectedDocument(
          "Vết tính điểm thuộc phiên bản bộ quy tắc "
              + version
              + ", khác phiên bản "
              + ctx.policyPackVersion()
              + " đã chốt cho kỳ.");
    }
    Map<String, Object> out = new LinkedHashMap<>(data);
    if (data.get(INPUT_HASH) instanceof String hash && hash.length() > HASH_PREFIX) {
      out.put(INPUT_HASH, hash.substring(0, HASH_PREFIX) + "…");
    }
    return out;
  }
}
