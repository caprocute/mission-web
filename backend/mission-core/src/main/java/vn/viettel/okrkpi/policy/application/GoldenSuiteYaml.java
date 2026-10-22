package vn.viettel.okrkpi.policy.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Định dạng tệp bộ ca {@code bo_ca_mau} để trao đổi với khách hàng (TL-01 "Bộ ca mẫu vàng", bổ sung
 * của DL-03): mỗi ca có {@code id}, {@code mo_ta}, {@code dau_vao}, {@code mong_doi}, tuỳ chọn
 * {@code loai}, {@code tham_so}, {@code legal_ref}, {@code source}, {@code thay_the}; khoá {@code
 * mac_dinh} ghép giá trị mặc định theo {@code loai} và cho từng phần tử {@code nhiem_vu} (suy luận
 * từ tệp của DL-03).
 */
final class GoldenSuiteYaml {

  /** Một ca đọc từ tệp; {@code input} đã ghép mặc định. */
  record Entry(
      String caseCode,
      String description,
      Map<String, Object> input,
      Map<String, Object> expected,
      String legalRef,
      String source,
      String replaces) {}

  record Suite(String code, String packCode, List<Entry> entries) {}

  private GoldenSuiteYaml() {}

  @SuppressWarnings("unchecked")
  static Suite parse(String text) {
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(20);
    options.setCodePointLimit(10 * 1024 * 1024);
    Object root = new Yaml(new SafeConstructor(options)).load(text);
    if (!(root instanceof Map<?, ?> doc) || !(doc.get("ca") instanceof List<?> items)) {
      throw new IllegalArgumentException("Tệp bộ ca cần khoá bo_ca_mau và danh sách ca");
    }
    Map<String, Object> defaults =
        doc.get("mac_dinh") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    List<Entry> entries = new ArrayList<>();
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> ca)) {
        throw new IllegalArgumentException("Mỗi ca trong bộ ca là một đối tượng");
      }
      Map<String, Object> values = (Map<String, Object>) ca;
      entries.add(
          new Entry(
              text(values.get("id")),
              text(values.get("mo_ta")),
              input(values, defaults),
              values.get("mong_doi") instanceof Map<?, ?> e
                  ? new LinkedHashMap<>((Map<String, Object>) e)
                  : Map.of(),
              text(values.get("legal_ref")),
              text(values.get("source")),
              text(values.get("thay_the"))));
    }
    return new Suite(text(doc.get("bo_ca_mau")), text(doc.get("goi")), entries);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> input(Map<String, Object> ca, Map<String, Object> defaults) {
    Map<String, Object> dauVao = new LinkedHashMap<>();
    String loai = text(ca.get("loai"));
    if (loai != null && defaults.get(loai) instanceof Map<?, ?> base) {
      dauVao.putAll((Map<String, Object>) base);
    }
    if (ca.get("dau_vao") instanceof Map<?, ?> own) {
      dauVao.putAll((Map<String, Object>) own);
    }
    if (defaults.get("nhiem_vu") instanceof Map<?, ?> nvBase
        && dauVao.get("nhiem_vu") instanceof List<?> list) {
      List<Object> merged = new ArrayList<>();
      for (Object nv : list) {
        Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) nvBase);
        if (nv instanceof Map<?, ?> own) {
          m.putAll((Map<String, Object>) own);
        }
        merged.add(m);
      }
      dauVao.put("nhiem_vu", merged);
    }
    if (loai == null && !ca.containsKey("tham_so")) {
      return dauVao;
    }
    Map<String, Object> input = new LinkedHashMap<>();
    if (loai != null) {
      input.put("loai", loai);
    }
    if (ca.get("tham_so") instanceof Map<?, ?> thamSo) {
      input.put("tham_so", thamSo);
    }
    input.put("dau_vao", dauVao);
    return input;
  }

  /** Ghi bộ ca; ca có khoá bao {@code dau_vao} tách lại thành {@code loai}, {@code tham_so}. */
  static String write(String code, String packCode, List<Map<String, Object>> cases) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("bo_ca_mau", code);
    doc.put("goi", packCode);
    doc.put("ca", cases);
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setAllowUnicode(true);
    options.setWidth(120);
    return new Yaml(new Representer(options), options).dump(doc);
  }

  /** Một ca để ghi ra tệp. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> entry(
      String caseCode,
      String description,
      Map<String, Object> input,
      Map<String, Object> expected,
      String legalRef,
      String source,
      String status) {
    Map<String, Object> ca = new LinkedHashMap<>();
    ca.put("id", caseCode);
    ca.put("mo_ta", description);
    if (input.get("dau_vao") instanceof Map<?, ?> inner) {
      if (input.containsKey("loai")) {
        ca.put("loai", input.get("loai"));
      }
      if (input.containsKey("tham_so")) {
        ca.put("tham_so", input.get("tham_so"));
      }
      ca.put("dau_vao", (Map<String, Object>) inner);
    } else {
      ca.put("dau_vao", input);
    }
    ca.put("mong_doi", expected);
    if (legalRef != null) {
      ca.put("legal_ref", legalRef);
    }
    ca.put("source", source);
    ca.put("trang_thai", status);
    return ca;
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
