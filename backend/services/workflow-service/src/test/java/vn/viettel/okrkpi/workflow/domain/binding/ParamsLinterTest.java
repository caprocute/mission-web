package vn.viettel.okrkpi.workflow.domain.binding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.viettel.okrkpi.workflow.application.binding.CelSkipConditions;

/** Mỗi quy tắc linter tham số luồng (SCR-1504 bảng trường dòng 3 đến 8) có ca xanh và ca đỏ. */
class ParamsLinterTest {

  private final ParamsLinter linter = new ParamsLinter(new CelSkipConditions());

  /** Mẫu bốn cấp kiểu SCR-1504: ký số bắt buộc ở cấp đầu và cuối, cấp giữa được bỏ qua. */
  private final TemplateParamRules rules =
      new TemplateParamRules(
          2,
          4,
          Set.of(TemplateParamRules.FIRST, TemplateParamRules.LAST),
          Set.of(TemplateParamRules.MIDDLE),
          List.of());

  static Map<String, Object> level(String step, String role) {
    Map<String, Object> m = new HashMap<>();
    m.put("buoc", step);
    m.put("vai_tro", role);
    m.put("ky_so", true);
    return m;
  }

  static Map<String, Object> valid() {
    List<Map<String, Object>> cap = new ArrayList<>();
    cap.add(level("TU_DANH_GIA", "member"));
    cap.add(level("THAM_DINH", "appraiser"));
    Map<String, Object> deXuat = level("DE_XUAT", "manager");
    deXuat.put("ky_so", false);
    deXuat.put(
        "bo_qua_khi",
        "nguoi_duoc_danh_gia.chuc_danh in ['CHUYEN_VIEN'] && muc_tham_dinh == muc_tu_de_xuat");
    cap.add(deXuat);
    Map<String, Object> quyetDinh = level("QUYET_DINH", "decider");
    quyetDinh.put("ky_lo", true);
    quyetDinh.put("han_ngay_lam_viec", 5);
    cap.add(quyetDinh);
    Map<String, Object> p = new HashMap<>();
    p.put("so_cap", 4);
    p.put("cap", cap);
    p.put("thoi_han_moi_buoc", "P3D_LV");
    p.put("nhac_truoc", "P1D_LV");
    p.put("khi_qua_han", "CHUYEN_CAP_PHO_UY_QUYEN");
    p.put("remindAfter", "P1D");
    p.put("signMethod", "USB_TOKEN");
    p.put("muc_tru_tien_do", 0.05);
    return p;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cap(Map<String, Object> p, int i) {
    return ((List<Map<String, Object>>) p.get("cap")).get(i);
  }

  private List<String> rulesOf(Consumer<Map<String, Object>> change) {
    Map<String, Object> p = valid();
    change.accept(p);
    LintResult r = linter.lint(p, rules);
    assertThat(r.passed()).isFalse();
    return r.issues().stream().map(LintIssue::rule).toList();
  }

  @Test
  void thamSoDungKhaiBaoThiDat() {
    LintResult r = linter.lint(valid(), rules);
    assertThat(r.issues()).isEmpty();
    assertThat(r.passed()).isTrue();
    assertThat(linter.steps(valid(), rules))
        .extracting(StepPreview::stepKey, StepPreview::signRequired, StepPreview::skippable)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("TU_DANH_GIA", true, false),
            org.assertj.core.groups.Tuple.tuple("THAM_DINH", true, false),
            org.assertj.core.groups.Tuple.tuple("DE_XUAT", false, true),
            org.assertj.core.groups.Tuple.tuple("QUYET_DINH", true, false));
  }

  @Test
  void soCapNgoaiKhoang() {
    assertThat(rulesOf(p -> ((List<?>) p.get("cap")).subList(1, 4).clear()))
        .contains(ParamsLinter.LEVEL_COUNT);
    assertThat(
            rulesOf(
                p -> {
                  @SuppressWarnings("unchecked")
                  List<Map<String, Object>> cap = (List<Map<String, Object>>) p.get("cap");
                  cap.add(2, level("THEM", "manager"));
                  p.put("so_cap", 5);
                }))
        .contains(ParamsLinter.LEVEL_COUNT);
    assertThat(rulesOf(p -> p.put("so_cap", 3))).containsExactly(ParamsLinter.LEVEL_COUNT);
    assertThat(rulesOf(p -> p.remove("cap"))).containsExactly(ParamsLinter.LEVEL_COUNT);
  }

  @Test
  void vaiTroKhongCoTrongDanhMuc() {
    assertThat(rulesOf(p -> cap(p, 1).put("vai_tro", "truong_phong")))
        .containsExactly(ParamsLinter.ROLE);
    assertThat(rulesOf(p -> cap(p, 1).remove("vai_tro"))).containsExactly(ParamsLinter.ROLE);
  }

  @Test
  void phamViDonViSai() {
    assertThat(rulesOf(p -> cap(p, 0).put("pham_vi", "TOAN_QUOC")))
        .containsExactly(ParamsLinter.SCOPE);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 16, 30})
  void hanCapNgoaiMotDenMuoiLamNgay(int days) {
    assertThat(rulesOf(p -> cap(p, 3).put("han_ngay_lam_viec", days)))
        .containsExactly(ParamsLinter.DURATION);
  }

  @ParameterizedTest
  @ValueSource(strings = {"P0D_LV", "P16D_LV", "P3D", "3"})
  void thoiHanVaNhacTruocSaiDang(String value) {
    assertThat(rulesOf(p -> p.put("thoi_han_moi_buoc", value)))
        .containsExactly(ParamsLinter.DURATION);
    assertThat(rulesOf(p -> p.put("nhac_truoc", value))).containsExactly(ParamsLinter.DURATION);
  }

  @Test
  void khongBoDuocBuocKySoBatBuoc() {
    assertThat(rulesOf(p -> cap(p, 3).put("ky_so", false)))
        .containsExactly(ParamsLinter.MANDATORY_SIGN);
    assertThat(rulesOf(p -> cap(p, 0).put("ky_so", false)))
        .containsExactly(ParamsLinter.MANDATORY_SIGN);
  }

  @Test
  void dieuKienBoQuaChiOCapMauChoPhep() {
    assertThat(rulesOf(p -> cap(p, 0).put("bo_qua_khi", "khoi == 'DANG'")))
        .containsExactly(ParamsLinter.SKIP);
    assertThat(rulesOf(p -> cap(p, 3).put("bo_qua_khi", "true")))
        .containsExactly(ParamsLinter.SKIP);
  }

  @ParameterizedTest
  @ValueSource(strings = {"muc_tham_dinh", "muc_tham_dinh ==", "bien_la == 1", "1 + 1", "  "})
  void dieuKienBoQuaPhaiLaCelTraVeBool(String expression) {
    List<String> found = rulesOf(p -> cap(p, 2).put("bo_qua_khi", expression));
    assertThat(found).containsExactly(ParamsLinter.CEL_TYPE);
    Map<String, Object> p = valid();
    cap(p, 2).put("bo_qua_khi", expression);
    assertThat(linter.lint(p, rules).onlyCelType()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(doubles = {-0.01, 0.21, 0.5, 0.125})
  void mucTruTienDoNgoaiKhoang(double value) {
    assertThat(rulesOf(p -> p.put("muc_tru_tien_do", value)))
        .containsExactly(ParamsLinter.PROGRESS_DEDUCTION);
  }

  @Test
  void mucTruTienDoBien() {
    for (double ok : new double[] {0.0, 0.2, 0.15}) {
      Map<String, Object> p = valid();
      p.put("muc_tru_tien_do", ok);
      assertThat(linter.lint(p, rules).passed()).as("%s", ok).isTrue();
    }
    assertThat(rulesOf(p -> p.put("muc_tru_tien_do", "0,1")))
        .containsExactly(ParamsLinter.PROGRESS_DEDUCTION);
  }

  @Test
  void hanhViQuaHanNhacSauPhuongThucKy() {
    assertThat(rulesOf(p -> p.put("khi_qua_han", "HUY"))).containsExactly(ParamsLinter.OVERDUE);
    assertThat(rulesOf(p -> p.put("remindAfter", "PT10M"))).containsExactly(ParamsLinter.REMIND);
    assertThat(rulesOf(p -> p.put("signMethod", "SMS"))).containsExactly(ParamsLinter.SIGN_METHOD);
  }

  @Test
  void capBoQuaTheoBienHoSoMau() {
    assertThat(linter.skippedOrders(valid(), SimulationSample.CHUYEN_VIEN_MUC_KHOP.variables()))
        .containsExactly(3);
    assertThat(linter.skippedOrders(valid(), SimulationSample.DANG_DUYET_DU_CAP.variables()))
        .isEmpty();
  }

  @Test
  void khaiBaoThamSoChoBieuMau() {
    Map<String, Object> schema = ParamsLinter.paramSchema(rules);
    assertThat(schema).containsEntry("type", "object");
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    assertThat(props).containsKeys("so_cap", "cap", "thoi_han_moi_buoc", "khi_qua_han");
  }
}
