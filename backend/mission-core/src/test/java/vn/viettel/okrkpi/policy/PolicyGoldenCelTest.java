package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static vn.viettel.okrkpi.policy.PolicyPackFixtures.GOI_DANG;
import static vn.viettel.okrkpi.policy.PolicyPackFixtures.GOI_NHA_NUOC;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelRounding;
import vn.viettel.okrkpi.cel.domain.CelSandboxOptions;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;

/**
 * Chạy biểu thức CEL của Policy Pack giả lập bằng {@code vt-cel} trên bộ ca mẫu ở {@code
 * tests/bo-ca-mau-gia-lap.yaml} (DL-01, DL-03; BR-09-01..BR-09-13, BR-10-02..BR-10-04, BR-15-07).
 *
 * <p>Kiểm thử mô phỏng thứ tự phạm vi của gói: {@code nhiem_vu → nhom → ho_so → xep_loai} và
 * {@code nam}. Bảng DMN không chạy ở đây (Flowable DMN thuộc CORE-05-05; tập lệnh {@code
 * data/golden/chay_ca_mau.py} đã kiểm DMN): mức tiêu chí chung được quy đổi lại theo ngưỡng của
 * {@code dmn/tieu-chi-chung.dmn}, còn mức theo kết quả nhiệm vụ lấy từ {@code mong_doi.xep_loai}.
 */
@SuppressWarnings("unchecked")
class PolicyGoldenCelTest {

  /** Thời hạn rộng cho kiểm thử: lần chạy đầu khi JVM lạnh có thể vượt 50 ms mặc định. */
  private final CelEngine cel =
      new CelEngine(
          StaticWorkingCalendar.weekendsOnly(),
          new CelSandboxOptions(
              CelSandboxOptions.DEFAULT_MAX_EXPRESSION_LENGTH,
              CelSandboxOptions.DEFAULT_MAX_AST_DEPTH,
              CelSandboxOptions.DEFAULT_MAX_AST_NODE_COUNT,
              CelSandboxOptions.DEFAULT_MAX_COMPREHENSION_ITERATIONS,
              java.time.Duration.ofSeconds(5),
              CelSandboxOptions.DEFAULT_PROGRAM_CACHE_MAX_SIZE));

  static Stream<Arguments> goi() {
    return Stream.of(Arguments.of(GOI_DANG), Arguments.of(GOI_NHA_NUOC));
  }

  @ParameterizedTest
  @MethodSource("goi")
  void moiBieuThucBienDichDuoc(String id) {
    Goi g = new Goi(id);
    SoftAssertions soft = new SoftAssertions();
    g.dinhNghia().forEach(
        (ten, d) ->
            soft.assertThatCode(() -> cel.compile((String) d.get("expression"), g.kieu, kieuKetQua(d)))
                .as("%s: %s", id, ten)
                .doesNotThrowAnyException());
    soft.assertAll();
  }

  @ParameterizedTest
  @MethodSource("goi")
  void boCaMauDatTrenVtCel(String id) {
    Goi g = new Goi(id);
    Map<String, Object> bo = PolicyPackFixtures.yaml(PolicyPackFixtures.pack(id).resolve("tests/bo-ca-mau-gia-lap.yaml"));
    Map<String, Object> macDinh = (Map<String, Object>) bo.get("mac_dinh");
    SoftAssertions soft = new SoftAssertions();
    int daChay = 0;
    for (Map<String, Object> ca : (List<Map<String, Object>>) bo.get("ca")) {
      String loai = (String) ca.get("loai");
      if ("DAC_THU".equals(loai)) {
        continue;
      }
      Map<String, Object> dv = new HashMap<>((Map<String, Object>) macDinh.getOrDefault(loai, Map.of()));
      dv.putAll((Map<String, Object>) ca.get("dau_vao"));
      if (dv.containsKey("nhiem_vu")) {
        List<Map<String, Object>> ds = new ArrayList<>();
        for (Map<String, Object> nv : (List<Map<String, Object>>) dv.get("nhiem_vu")) {
          Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) macDinh.get("nhiem_vu"));
          m.putAll(nv);
          m.putIfAbsent("ma", "NV-" + ds.size());
          ds.add(m);
        }
        dv.put("nhiem_vu", ds);
      }
      Map<String, Object> thuc = "NAM".equals(loai) ? g.chayNam(dv, ca) : g.chayQuy(dv, ca);
      Map<String, Object> mong = (Map<String, Object>) ca.get("mong_doi");
      for (String k : List.of("diem", "ty_le_vuot", "kpi_nhiem_vu", "dat_ty_le_vuot", "nguoi_dung_dau", "muc_cuoi",
          "diem_nam", "so_quy_khtnv", "co_bo_tri_lai")) {
        if (mong.containsKey(k) && thuc.containsKey(k)) {
          soSanh(soft, ca.get("id") + "." + k, mong.get(k), thuc.get(k));
        }
      }
      daChay++;
    }
    assertThat(daChay).isPositive();
    soft.assertAll();
  }

  private static void soSanh(SoftAssertions soft, String nhan, Object mong, Object thuc) {
    if (mong instanceof List<?> lm) {
      List<?> lt = (List<?>) thuc;
      soft.assertThat(lt).as(nhan).hasSameSizeAs(lm);
      for (int i = 0; i < Math.min(lm.size(), lt.size()); i++) {
        soSanh(soft, nhan + "[" + i + "]", lm.get(i), lt.get(i));
      }
    } else if (mong instanceof Number n) {
      soft.assertThat(((Number) thuc).doubleValue()).as(nhan).isCloseTo(n.doubleValue(), within(1e-9));
    } else {
      soft.assertThat(thuc).as(nhan).isEqualTo(mong);
    }
  }

  private static CelValueType kieuKetQua(Map<String, Object> d) {
    return switch (String.valueOf(d.get("result_type"))) {
      case "BOOLEAN" -> CelValueType.BOOL;
      case "STRING" -> CelValueType.STRING;
      default -> CelValueType.DOUBLE;
    };
  }

  private static CelValueType kieuKhaiBao(Object raw) {
    String t = String.valueOf(raw).toLowerCase(Locale.ROOT);
    if (t.startsWith("list")) {
      return CelValueType.LIST_DYN;
    }
    return switch (t) {
      case "double" -> CelValueType.DOUBLE;
      case "int" -> CelValueType.INT;
      case "bool" -> CelValueType.BOOL;
      case "string" -> CelValueType.STRING;
      default -> CelValueType.MAP_STRING_DYN; // kiểu cấu trúc (NhiemVu, KetQuaNhomDauVao...) và map
    };
  }

  /** Một gói đã nạp: tham số, kiểu biến, định nghĩa CEL theo phạm vi. */
  private final class Goi {
    final Map<String, Object> policy;
    final Map<String, CelValueType> kieu = new HashMap<>();
    final boolean dang;

    Goi(String id) {
      policy = PolicyPackFixtures.yaml(PolicyPackFixtures.pack(id).resolve("policy.yaml"));
      dang = "DANG".equals(((Map<String, Object>) policy.get("metadata")).get("luong"));
      kieu.put("tham_so", CelValueType.MAP_STRING_DYN);
      for (String k : List.of("inputs", "inputs_nam", "ngu_canh")) {
        ((Map<String, Map<String, Object>>) policy.getOrDefault(k, Map.of()))
            .forEach((ten, v) -> kieu.put(ten, kieuKhaiBao(v.get("type"))));
      }
      dinhNghia().forEach((ten, d) -> kieu.put(ten, kieuKetQua(d)));
    }

    /** Mọi định nghĩa CEL của gói theo tên, kể cả phạm vi năm. */
    Map<String, Map<String, Object>> dinhNghia() {
      Map<String, Map<String, Object>> ra = new LinkedHashMap<>();
      for (String k : List.of("definitions", "definitions_nam")) {
        ((Map<String, Map<String, Map<String, Object>>>) policy.getOrDefault(k, Map.of()))
            .values()
            .forEach(ra::putAll);
      }
      return ra;
    }

    Map<String, Object> thamSo(Map<String, Object> ghiDe) {
      Map<String, Object> phang = new LinkedHashMap<>();
      ((Map<String, Map<String, Object>>) policy.get("params")).forEach((k, v) -> phang.put(k, v.get("mac_dinh")));
      if (ghiDe != null) {
        phang.putAll(ghiDe);
      }
      Map<String, Object> long_ = new LinkedHashMap<>();
      phang.forEach(
          (k, v) -> {
            String[] p = k.split("\\.");
            Map<String, Object> m = long_;
            for (int i = 0; i < p.length - 1; i++) {
              m = (Map<String, Object>) m.computeIfAbsent(p[i], x -> new LinkedHashMap<>());
            }
            m.put(p[p.length - 1], v);
          });
      return long_;
    }

    /** Chạy các định nghĩa của một phạm vi theo eval_order, ghi kết quả vào vars. */
    void chayPhamVi(String khoi, String phamVi, Map<String, Object> vars) {
      Map<String, Map<String, Object>> defs =
          ((Map<String, Map<String, Map<String, Object>>>) policy.get(khoi)).get(phamVi);
      defs.entrySet().stream()
          .sorted(Comparator.comparingInt(e -> ((Number) e.getValue().get("eval_order")).intValue()))
          .forEach(
              e -> {
                Map<String, Object> d = e.getValue();
                Object v = cel.evaluate((String) d.get("expression"), kieu, kieuKetQua(d), vars);
                Map<String, Object> r = (Map<String, Object>) d.get("rounding");
                if (r != null) {
                  v = lamTron((Number) v, ((Number) r.get("digits")).intValue(), (String) r.get("mode"));
                }
                vars.put(e.getKey(), v);
              });
    }

    Map<String, Object> chayQuy(Map<String, Object> dv, Map<String, Object> ca) {
      Map<String, Object> ts = thamSo((Map<String, Object>) ca.get("tham_so"));
      List<Map<String, Object>> kqNv = new ArrayList<>();
      List<Object> kpi = new ArrayList<>();
      for (Map<String, Object> nv : (List<Map<String, Object>>) dv.get("nhiem_vu")) {
        Map<String, Object> v = new HashMap<>(Map.of("nv", nv, "tham_so", ts));
        chayPhamVi("definitions", "nhiem_vu", v);
        kqNv.add(Map.of("ma", nv.get("ma"), "nhom", nv.get("nhom"), "diem_chuan", nv.get("diem_chuan"),
            "kpi_nhiem_vu", v.get("kpi_nhiem_vu"), "vuot_muc_tieu", v.get("vuot_muc_tieu")));
        kpi.add(lamTron((Number) v.get("kpi_nhiem_vu"), 4, "NUA_LEN"));
      }
      List<Map<String, Object>> kqNhom = new ArrayList<>();
      for (Map<String, Object> nhom : (List<Map<String, Object>>) ts.get("nhom")) {
        List<Object> ds = kqNv.stream()
            .filter(k -> ((List<?>) nhom.get("nhom_nhiem_vu")).contains(k.get("nhom")))
            .map(k -> k.get("kpi_nhiem_vu"))
            .toList();
        Map<String, Object> g = Map.of("ma", nhom.get("ma"), "diem_toi_da", nhom.get("diem_toi_da"), "ds_kpi", ds);
        Map<String, Object> v = new HashMap<>(Map.of("g", g, "tham_so", ts));
        chayPhamVi("definitions", "nhom", v);
        kqNhom.add(Map.of("ma", nhom.get("ma"), "diem_toi_da", nhom.get("diem_toi_da"),
            "kpi_nhom", v.get("kpi_nhom"), "diem_nhom", v.get("diem_nhom")));
      }
      Map<String, Object> vars = new HashMap<>(dv);
      vars.put("tham_so", ts);
      vars.put("ket_qua_nhiem_vu", kqNv);
      vars.put("ket_qua_nhom", kqNhom);
      chayPhamVi("definitions", "ho_so", vars);
      Map<String, Object> mong = (Map<String, Object>) ca.get("mong_doi");
      if (dang && mong.containsKey("xep_loai")) {
        vars.put("muc_tieu_chi_chung", mucTieuChiChung(((Number) dv.get("ty_le_dap_ung_tieu_chi_chung")).doubleValue()));
        vars.put("muc_ket_qua_nhiem_vu", mong.get("xep_loai"));
        chayPhamVi("definitions", "xep_loai", vars);
      }
      Map<String, Object> ra = new HashMap<>(vars);
      ra.put("kpi_nhiem_vu", kpi);
      return ra;
    }

    Map<String, Object> chayNam(Map<String, Object> dv, Map<String, Object> ca) {
      Map<String, Object> vars = new HashMap<>(dv);
      List<Map<String, Object>> quy = new ArrayList<>();
      for (Map<String, Object> q : (List<Map<String, Object>>) dv.get("ket_qua_quy")) {
        Map<String, Object> m = new LinkedHashMap<>(q);
        m.putIfAbsent("co_xep_loai", true);
        quy.add(m);
      }
      vars.put("ket_qua_quy", quy);
      vars.put("tham_so", thamSo((Map<String, Object>) ca.get("tham_so")));
      chayPhamVi("definitions_nam", "nam", vars);
      return vars;
    }
  }

  /**
   * Làm tròn hai bước: khử sai số dấu phẩy động nhị phân ở 10 chữ số rồi mới làm tròn theo gói.
   * Làm tròn thẳng một số double như 69,95 (thực tế 69,9499999...) cho ra 69,9 thay vì 70,0; đây
   * là quy tắc đề xuất cho scoring-service (CORE-05-04), ghi ở wiki trang 14 (suy luận).
   */
  static double lamTron(Number v, int chuSo, String cheDo) {
    return CelRounding.round(CelRounding.round(v, 10, "NUA_LEN"), chuSo, cheDo);
  }

  /** Quy đổi theo ngưỡng của dmn/tieu-chi-chung.dmn (BR-09-05). */
  private static String mucTieuChiChung(double tyLe) {
    if (tyLe >= 1) {
      return "THUC_HIEN_TOT";
    }
    if (tyLe >= 0.9) {
      return "DAM_BAO";
    }
    return tyLe >= 0.6 ? "CO_BAN_DAM_BAO" : "KHONG_DAM_BAO";
  }
}
