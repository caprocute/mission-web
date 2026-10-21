package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.viettel.okrkpi.policy.PolicyPackFixtures.GOI_DANG;
import static vn.viettel.okrkpi.policy.PolicyPackFixtures.GOI_NHA_NUOC;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelValueType;

/**
 * Kiểm thử nạp bảng chuyển trạng thái nhiệm vụ và tham số luồng duyệt của Policy Pack giả lập
 * (DL-04; QT-05, QT-06, QT-07, QT-15; ADR-04). Máy trạng thái trong mã thuộc CORE-02-13.
 */
@SuppressWarnings("unchecked")
class TaskLifecycleLoadTest {

  /** Bộ trạng thái chuẩn của nhiệm vụ (baseline mục 9.1). */
  private static final Set<String> TRANG_THAI_CHUAN =
      Set.of("NHAP", "DA_GIAO", "DANG_THUC_HIEN", "CHO_NGHIEM_THU", "HOAN_THANH", "TAM_DUNG", "HUY");

  /** Lệnh của TL-01 (TaskCommand). */
  private static final Set<String> LENH =
      Set.of("ASSIGN", "ACCEPT", "SUBMIT", "APPROVE", "REWORK", "SUSPEND", "RESUME", "CANCEL");

  /** Kiểu biến ngữ cảnh guard (task.activation(actor, now) của TL-01). */
  private static final Map<String, CelValueType> NGU_CANH_GUARD =
      Map.of(
          "actor", CelValueType.MAP_STRING_DYN,
          "task", CelValueType.MAP_STRING_DYN,
          "deliverables", CelValueType.LIST_DYN,
          "change_request", CelValueType.MAP_STRING_DYN);

  private static final Pattern THOI_HAN = Pattern.compile("P(\\d+)D_LV");

  private final CelEngine cel = new CelEngine();
  private final Map<String, Object> lifecycle =
      PolicyPackFixtures.yaml(PolicyPackFixtures.pack(GOI_DANG).resolve("task_lifecycle.yaml"));
  private final List<Map<String, Object>> transitions =
      (List<Map<String, Object>>) lifecycle.get("transitions");

  @Test
  void trangThaiVaLenhThuocBoChuan() {
    assertThat((List<String>) lifecycle.get("trang_thai")).containsExactlyInAnyOrderElementsOf(TRANG_THAI_CHUAN);
    assertThat((List<String>) lifecycle.get("lenh")).containsExactlyInAnyOrderElementsOf(LENH);
    // qua_han là thuộc tính tính theo lịch ngày làm việc, không phải trạng thái (TL-01, QT-05).
    assertThat((Map<String, Object>) lifecycle.get("thuoc_tinh_tinh_toan")).containsKey("qua_han");
    for (Map<String, Object> t : transitions) {
      assertThat(TRANG_THAI_CHUAN).as("from của %s", t).contains((String) t.get("from"));
      assertThat(TRANG_THAI_CHUAN).as("to của %s", t).contains((String) t.get("to"));
      assertThat(LENH).as("command của %s", t).contains((String) t.get("command"));
      assertThat((List<String>) lifecycle.get("trang_thai_ket_thuc")).doesNotContain((String) t.get("from"));
      assertThat(t.get("reasonRequired")).isInstanceOf(Boolean.class);
    }
  }

  @Test
  void duChinDongCuaQt05VaKhongTrungCapFromCommand() {
    Set<String> cap = new HashSet<>();
    for (Map<String, Object> t : transitions) {
      assertThat(cap.add(t.get("from") + ":" + t.get("command"))).as("trùng %s", t).isTrue();
    }
    assertThat(cap)
        .contains(
            "NHAP:ASSIGN", "DA_GIAO:ACCEPT", "DANG_THUC_HIEN:SUBMIT", "CHO_NGHIEM_THU:REWORK",
            "CHO_NGHIEM_THU:APPROVE", "DANG_THUC_HIEN:SUSPEND", "TAM_DUNG:RESUME",
            "DA_GIAO:CANCEL", "DANG_THUC_HIEN:CANCEL", "TAM_DUNG:CANCEL");
  }

  @Test
  void vaiTroLayTuMaTranPhanQuyen() {
    Set<String> roles = PolicyPackFixtures.realmRoles();
    for (Map<String, Object> t : transitions) {
      assertThat((List<String>) t.get("allowedRoles")).isNotEmpty().allSatisfy(r -> assertThat(roles).contains(r));
    }
  }

  @Test
  void moiGuardBienDichDuocVaTraBool() {
    for (Map<String, Object> t : transitions) {
      String guard = (String) t.get("guardCel");
      if (guard != null) {
        assertThat(cel.compile(guard, NGU_CANH_GUARD, CelValueType.BOOL)).as(guard).isNotNull();
      }
    }
  }

  @Test
  void guardNghiemThuChiChoQuaKhiMoiMinhChungDaXacNhan() {
    String guard = guard("CHO_NGHIEM_THU", "APPROVE");
    assertThat(guard).isEqualTo("deliverables.all(d, d.status == 'DA_XAC_NHAN')");
    assertThat(eval(guard, List.of(Map.of("status", "DA_XAC_NHAN"), Map.of("status", "DA_XAC_NHAN")))).isTrue();
    assertThat(eval(guard, List.of(Map.of("status", "DA_XAC_NHAN"), Map.of("status", "CHO_XAC_NHAN")))).isFalse();
  }

  @Test
  void guardHuyChanKhiDaCoMinhChungXacNhan() {
    String guard = guard("DANG_THUC_HIEN", "CANCEL");
    assertThat(eval(guard, List.of(Map.of("status", "CHO_XAC_NHAN")))).isTrue();
    assertThat(eval(guard, List.of(Map.of("status", "DA_XAC_NHAN")))).isFalse();
  }

  static Stream<Arguments> goi() {
    return Stream.of(Arguments.of(GOI_DANG), Arguments.of(GOI_NHA_NUOC));
  }

  @ParameterizedTest
  @MethodSource("goi")
  void thamSoMacDinhNamTrongMien(String id) {
    Map<String, Object> policy = PolicyPackFixtures.yaml(PolicyPackFixtures.pack(id).resolve("policy.yaml"));
    Map<String, Map<String, Object>> params = (Map<String, Map<String, Object>>) policy.get("params");
    assertThat(params).isNotEmpty();
    params.forEach(
        (ten, p) -> {
          Map<String, Object> mien = (Map<String, Object>) p.get("mien");
          Object md = p.get("mac_dinh");
          assertThat(md).as("tham số %s thiếu giá trị mặc định", ten).isNotNull();
          if (mien == null) {
            return;
          }
          if (mien.containsKey("enum")) {
            assertThat((List<Object>) mien.get("enum")).as(ten).contains(md);
          }
          if (mien.containsKey("min")) {
            assertThat(((Number) md).doubleValue()).as(ten).isGreaterThanOrEqualTo(((Number) mien.get("min")).doubleValue());
          }
          if (mien.containsKey("max")) {
            assertThat(((Number) md).doubleValue()).as(ten).isLessThanOrEqualTo(((Number) mien.get("max")).doubleValue());
          }
        });
    // Tham số giả định chờ cơ quan tổ chức xác nhận phải được đánh dấu (DL-04).
    assertThat(params.get("difficulty_coefficients")).containsEntry("gia_dinh", true);
  }

  @ParameterizedTest
  @MethodSource("goi")
  void thamSoLuongDuyetTrongMienQt15(String id) {
    Set<String> roles = PolicyPackFixtures.realmRoles();
    List<Path> tep = PolicyPackFixtures.files(PolicyPackFixtures.pack(id).resolve("workflow-bindings"), "*.yaml");
    assertThat(tep).isNotEmpty();
    for (Path f : tep) {
      Map<String, Object> b = PolicyPackFixtures.yaml(f);
      Map<String, Object> params = (Map<String, Object>) b.get("params");
      Map<String, Map<String, Object>> mien = (Map<String, Map<String, Object>>) b.get("mien");
      List<Map<String, Object>> cap = (List<Map<String, Object>>) params.get("cap");
      String ten = f.getFileName().toString();
      assertThat(b.get("templateKey")).as(ten).isEqualTo(ten.replace(".yaml", ""));
      if (params.containsKey("so_cap")) {
        int soCap = ((Number) params.get("so_cap")).intValue();
        assertThat(cap).as(ten).hasSize(soCap);
        assertThat(soCap).as(ten).isBetween(intOf(mien.get("so_cap"), "min"), intOf(mien.get("so_cap"), "max"));
      }
      for (Map<String, Object> c : cap) {
        // Một bước có thể cho một hoặc nhiều vai trò (duyet-okr bước trình: manager hoặc member).
        Object vt = c.get("vai_tro");
        List<String> vaiTro = vt instanceof List<?> ds ? (List<String>) ds : List.of((String) vt);
        assertThat(roles).as("%s: vai trò %s", ten, vt).containsAll(vaiTro);
        if (c.get("thoi_han") == null) {
          continue; // bước lấy thời hạn từ tham số gói (xu-ly-khieu-nai: khieu_nai.thoi_han)
        }
        Matcher m = THOI_HAN.matcher(String.valueOf(c.get("thoi_han")));
        assertThat(m.matches()).as("%s: thời hạn %s", ten, c.get("thoi_han")).isTrue();
        assertThat(Integer.parseInt(m.group(1)))
            .as(ten)
            .isBetween(intOf(mien.get("thoi_han_ngay_lam_viec"), "min"), intOf(mien.get("thoi_han_ngay_lam_viec"), "max"));
      }
    }
  }

  private static int intOf(Map<String, Object> m, String k) {
    return ((Number) m.get(k)).intValue();
  }

  private String guard(String from, String command) {
    return transitions.stream()
        .filter(t -> from.equals(t.get("from")) && command.equals(t.get("command")))
        .map(t -> (String) t.get("guardCel"))
        .findFirst()
        .orElseThrow();
  }

  private boolean eval(String guard, List<Map<String, Object>> deliverables) {
    Map<String, Object> vars =
        Map.of(
            "actor", Map.of("person_id", "p1", "roles", List.of("manager")),
            "task", Map.of("chu_tri_id", "p2", "nguoi_giao_id", "p1", "nguoi_nghiem_thu_id", "p1", "ke_hoach", 1L, "so_san_pham_da_nop", 1L),
            "deliverables", deliverables,
            "change_request", Map.of("request_type", "HUY", "status", "DA_DUYET"));
    return (Boolean) cel.evaluate(guard, NGU_CANH_GUARD, CelValueType.BOOL, vars);
  }
}
