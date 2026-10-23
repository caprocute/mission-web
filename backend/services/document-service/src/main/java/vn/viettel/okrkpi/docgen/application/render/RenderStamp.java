package vn.viettel.okrkpi.docgen.application.render;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tem phiên bản của một tệp chính thức (DV-11-04, BR-11-03): Policy Pack và phiên bản gắn với kỳ,
 * mẫu biểu và phiên bản mẫu đã dùng, gói hồ sơ, băm dữ liệu đầu vào. Tem được đóng hai nơi: chân
 * trang in trên mọi trang (người đọc thấy) và siêu dữ liệu XMP (máy đọc, đối chiếu khi ký gói).
 *
 * @param inputSha256 băm SHA-256 của dữ liệu đã chuẩn hoá ({@code render_job.input_sha256})
 */
public record RenderStamp(
    String policyPackCode,
    String policyPackVersion,
    String formCode,
    int templateVersion,
    UUID packageId,
    byte[] inputSha256) {

  /** Tên các thuộc tính XMP của tem, theo thứ tự ghi. */
  public static final String POLICY_PACK = "policyPack";

  public static final String POLICY_PACK_VERSION = "policyPackVersion";
  public static final String FORM_CODE = "formCode";
  public static final String TEMPLATE_VERSION = "templateVersion";
  public static final String PACKAGE_ID = "packageId";
  public static final String INPUT_SHA256 = "inputSha256";

  public RenderStamp {
    Objects.requireNonNull(formCode, "formCode");
    Objects.requireNonNull(inputSha256, "inputSha256");
    inputSha256 = inputSha256.clone();
  }

  @Override
  public byte[] inputSha256() {
    return inputSha256.clone();
  }

  /** Các thuộc tính theo thứ tự ghi; giá trị vắng ghi chuỗi rỗng để tem luôn đủ trường. */
  public Map<String, String> properties() {
    Map<String, String> out = new LinkedHashMap<>();
    out.put(POLICY_PACK, policyPackCode == null ? "" : policyPackCode);
    out.put(POLICY_PACK_VERSION, policyPackVersion == null ? "" : policyPackVersion);
    out.put(FORM_CODE, formCode);
    out.put(TEMPLATE_VERSION, Integer.toString(templateVersion));
    out.put(PACKAGE_ID, packageId == null ? "" : packageId.toString());
    out.put(INPUT_SHA256, HexFormat.of().formatHex(inputSha256));
    return out;
  }

  /**
   * Dòng chân trang in trên mọi trang: nhãn nghiệp vụ, không in băm hay mã gói (đã có trong siêu dữ
   * liệu). Ví dụ "Mẫu 1A, phiên bản mẫu 2 · Bộ quy tắc đánh giá PP-DANG phiên bản 1.3.0".
   */
  public String footerText() {
    StringBuilder text =
        new StringBuilder(formLabel(formCode)).append(", phiên bản mẫu ").append(templateVersion);
    if (policyPackCode != null && !policyPackCode.isBlank()) {
      text.append(" · Bộ quy tắc đánh giá ").append(policyPackCode);
      if (policyPackVersion != null && !policyPackVersion.isBlank()) {
        text.append(" phiên bản ").append(policyPackVersion);
      }
    }
    return text.toString();
  }

  /** Nhãn của mẫu: {@code MAU_1A} là "Mẫu 1A", {@code BANG_CHAM_DIEM} là "Bảng chấm điểm". */
  static String formLabel(String formCode) {
    if (formCode.startsWith("MAU_")) {
      return "Mẫu " + formCode.substring(4);
    }
    if ("BANG_CHAM_DIEM".equals(formCode)) {
      return "Bảng chấm điểm";
    }
    return formCode;
  }
}
