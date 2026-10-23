package vn.viettel.okrkpi.docgen.application.render;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.TemplateManifest.Field;

/**
 * Dựng {@link MergeModel} từ dữ liệu biểu mẫu theo tệp kê khai của mẫu (DV-11-02, DV-11-03): mỗi
 * trường kê khai lấy giá trị cùng tên trong tài liệu JSON của MOD-06 (khoá trùng {@code tag}, suy
 * luận về hình dạng dữ liệu), định dạng theo kiểu và tra nhãn theo bảng nhãn đi kèm mẫu (BR-11-04).
 *
 * <ul>
 *   <li>Trường bảng nhận một danh sách đối tượng, mỗi đối tượng khoá theo tag cột; cột {@code stt}
 *       thiếu giá trị được đánh số từ 1.
 *   <li>Giá trị C2 mã hoá ({@code {"c2": "<base64>"}}) giải mã bằng {@link SealedValueOpener} với
 *       AAD {@code [formInstanceId, tag]}; giá trị rõ chỉ nằm trong mô hình trả về.
 *   <li>Mã không có trong bảng nhãn làm tác vụ lỗi thay vì in mã thô lên biểu mẫu.
 *   <li>Hai tem chân trang ({@code tem_phien_ban_quy_tac}, {@code tem_phien_ban_mau}) lấy từ bối
 *       cảnh lệnh sinh (BR-11-03), không lấy từ dữ liệu.
 * </ul>
 */
public final class FormModelBuilder {

  public static final String POLICY_STAMP_TAG = "tem_phien_ban_quy_tac";
  public static final String TEMPLATE_STAMP_TAG = "tem_phien_ban_mau";
  public static final String ROW_NUMBER_TAG = "stt";
  static final String SEALED_KEY = "c2";
  static final String DEFAULT_YES = "Có";

  private final SealedValueOpener opener;

  /**
   * Bộ ánh xạ đặt vào dữ liệu để in một câu cố định của mẫu: khoá trong bảng {@code cau_ghi_chu}.
   */
  public record SentenceRef(String key) {}

  public FormModelBuilder(SealedValueOpener opener) {
    this.opener = opener == null ? SealedValueOpener.UNAVAILABLE : opener;
  }

  /** AAD của một trường C2 trong một biểu mẫu nguồn. */
  public static List<String> aad(RenderContext ctx, String tag) {
    return List.of(ctx.formInstanceId().toString(), tag);
  }

  public MergeModel build(TemplateManifest manifest, Map<String, Object> data, RenderContext ctx) {
    Map<String, String> values = new LinkedHashMap<>();
    Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
    Set<String> locked = new HashSet<>();
    for (Field field : manifest.fields()) {
      if (field.locked()) {
        locked.add(field.tag());
      }
      if (field.kind() == TemplateManifest.Kind.BANG && field.columns().isEmpty()) {
        values.put(field.tag(), freeText(field, data.get(field.tag()), ctx));
      } else if (field.kind() == TemplateManifest.Kind.BANG) {
        field.columns().stream()
            .filter(Field::locked)
            .forEach(c -> locked.add(field.tag() + "/" + c.tag()));
        tables.put(field.tag(), rows(manifest, field, data.get(field.tag()), ctx));
      } else {
        values.put(field.tag(), format(manifest, field, data.get(field.tag()), ctx));
      }
    }
    if (values.containsKey(POLICY_STAMP_TAG)) {
      values.put(POLICY_STAMP_TAG, policyStamp(ctx));
    }
    if (values.containsKey(TEMPLATE_STAMP_TAG)) {
      values.put(TEMPLATE_STAMP_TAG, templateStamp(manifest, ctx));
    }
    return new MergeModel(values, tables, locked, null);
  }

  private List<Map<String, String>> rows(
      TemplateManifest manifest, Field table, Object raw, RenderContext ctx) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new RejectedDocument("Trường bảng " + table.tag() + " không phải danh sách.");
    }
    List<Map<String, String>> out = new ArrayList<>(list.size());
    int index = 0;
    for (Object item : list) {
      index++;
      if (!(item instanceof Map<?, ?> row)) {
        throw new RejectedDocument("Dòng của bảng " + table.tag() + " không phải đối tượng.");
      }
      Map<String, String> cells = new LinkedHashMap<>();
      for (Field column : table.columns()) {
        Object value = row.get(column.tag());
        if (value == null && ROW_NUMBER_TAG.equals(column.tag())) {
          value = index;
        }
        cells.put(column.tag(), format(manifest, column, value, ctx));
      }
      out.add(cells);
    }
    return out;
  }

  /**
   * Bảng chưa khai cột (Mẫu 1A {@code ket_qua_tap_the} trong mẫu giả lập): in như đoạn văn, danh
   * sách thì mỗi phần tử một dòng.
   */
  private String freeText(Field field, Object raw, RenderContext ctx) {
    Object value = unseal(field, raw, ctx);
    if (value instanceof List<?> list) {
      return String.join("\n", list.stream().map(String::valueOf).toList());
    }
    return value == null ? "" : value.toString();
  }

  /** Chữ in của một giá trị theo kiểu trường; giá trị vắng là chuỗi rỗng. */
  String format(TemplateManifest manifest, Field field, Object raw, RenderContext ctx) {
    Object value = unseal(field, raw, ctx);
    if (value == null) {
      return "";
    }
    if (value instanceof SentenceRef ref) {
      String text = manifest.labelTable("cau_ghi_chu").get(ref.key());
      if (text == null) {
        throw new RejectedDocument("Bảng nhãn của mẫu không có câu ghi chú " + ref.key() + ".");
      }
      return text;
    }
    return switch (field.kind()) {
      case VAN_BAN, VAN_BAN_DAI -> value.toString();
      case SO -> ValueFormats.number(field.tag(), field.decimals(), value);
      case NGAY -> ValueFormats.date(field.tag(), value);
      case NHAN_MUC -> label(manifest, "muc_xep_loai", field, value);
      case NHAN_TIEU_CHI -> label(manifest, "nhan_tieu_chi_chung", field, value);
      case NHAN_TRANG_THAI -> label(manifest, "tinh_trang_khac_phuc", field, value);
      case NHAN_NHOM -> label(manifest, "nhom_trien_vong", field, value);
      case CO ->
          Boolean.parseBoolean(value.toString())
              ? manifest.labelTable("cau_ghi_chu").getOrDefault(field.tag(), DEFAULT_YES)
              : "";
      case BANG -> throw new RejectedDocument("Bảng " + field.tag() + " lồng trong bảng.");
    };
  }

  private Object unseal(Field field, Object raw, RenderContext ctx) {
    if (!(raw instanceof Map<?, ?> map) || !map.containsKey(SEALED_KEY)) {
      return raw;
    }
    byte[] envelope;
    try {
      envelope = Base64.getDecoder().decode(String.valueOf(map.get(SEALED_KEY)));
    } catch (IllegalArgumentException e) {
      throw new RejectedDocument("Trường " + field.tag() + " có phong bì mã hoá hỏng.");
    }
    return opener.open(ctx.tenantId(), aad(ctx, field.tag()), envelope);
  }

  private static String label(TemplateManifest manifest, String table, Field field, Object value) {
    String text = manifest.labelTable(table).get(value.toString());
    if (text == null) {
      throw new RejectedDocument(
          "Bảng nhãn của mẫu không có nhãn cho giá trị " + value + " của trường " + field.tag());
    }
    return text;
  }

  static String policyStamp(RenderContext ctx) {
    if (ctx.policyPackCode() == null || ctx.policyPackCode().isBlank()) {
      return "";
    }
    return ctx.policyPackVersion() == null || ctx.policyPackVersion().isBlank()
        ? ctx.policyPackCode()
        : ctx.policyPackCode() + " phiên bản " + ctx.policyPackVersion();
  }

  static String templateStamp(TemplateManifest manifest, RenderContext ctx) {
    String text = Integer.toString(ctx.templateVersion());
    return manifest.templateVersion() == null
        ? text
        : text + " (" + manifest.templateVersion() + ")";
  }
}
