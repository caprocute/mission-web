package vn.viettel.okrkpi.scoring.infrastructure.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.scoring.application.policy.SamplePolicyPack;
import vn.viettel.okrkpi.scoring.application.port.DecisionTables;
import vn.viettel.okrkpi.scoring.domain.DecisionTableSource;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.shared.error.BusinessException;

/** Ba bảng DMN của gói mẫu nạp được vào Flowable DMN độc lập và cho đúng mức xếp loại. */
class FlowableDecisionTablesTest {

  private static FlowableDecisionTables tables;

  @BeforeAll
  static void start() {
    tables = new FlowableDecisionTables();
  }

  @AfterAll
  static void stop() {
    tables.destroy();
  }

  static Map<String, Object> hoSo(double diem, String tieuChi, boolean loaiTru) {
    Map<String, Object> v = new HashMap<>();
    v.put("diem", diem);
    v.put("muc_tieu_chi_chung", tieuChi);
    v.put("loai_tru", loaiTru);
    v.put("ty_le_O", 1.0);
    v.put("dat_ty_le_vuot", true);
    v.put("giai_phap_dot_pha_da_kiem_chung", true);
    v.put("truot_then_chot_chu_quan", false);
    return v;
  }

  @Test
  void bangMauChoDungMucXepLoai() {
    PolicyContent pack = SamplePolicyPack.content();
    DecisionTables.Compiled c = tables.compile(pack.pinned().contentHash(), pack.tables());

    assertThat(c.tableKeys())
        .containsExactlyInAnyOrder("tieu_chi_chung", "xep_loai", "xep_loai_nam");
    assertThat(c.evaluate("xep_loai", hoSo(95, "THUC_HIEN_TOT", false)))
        .containsEntry("xep_loai", "HTXSNV");
    assertThat(c.evaluate("xep_loai", hoSo(85, "DAM_BAO", false)))
        .containsEntry("xep_loai", "HTTNV");
    assertThat(c.evaluate("xep_loai", hoSo(95, "THUC_HIEN_TOT", true)))
        .containsEntry("xep_loai", "KHTNV");
    assertThat(c.evaluate("xep_loai", hoSo(59.9, "THUC_HIEN_TOT", false)))
        .containsEntry("xep_loai", "KHTNV");
    assertThatThrownBy(() -> c.evaluate("khong_co", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void haiPhienBanCungMaQuyetDinhKhongLanNhau() {
    PolicyContent pack = SamplePolicyPack.content();
    String xml = pack.tables().get(1).dmnXml();
    String doiNguong = xml.replace("&gt;= 90", "&gt;= 96");
    DecisionTables.Compiled cu = tables.compile("aa".repeat(32), List.of(src(xml)));
    DecisionTables.Compiled moi = tables.compile("bb".repeat(32), List.of(src(doiNguong)));

    assertThat(cu.evaluate("xep_loai", hoSo(95, "THUC_HIEN_TOT", false)))
        .containsEntry("xep_loai", "HTXSNV");
    assertThat(moi.evaluate("xep_loai", hoSo(95, "THUC_HIEN_TOT", false)))
        .containsEntry("xep_loai", "HTTNV");
  }

  @Test
  void bangHongBiTuChoiKhiNap() {
    assertThatThrownBy(
            () -> tables.compile("cc".repeat(32), List.of(new DecisionTableSource("x", "<khong"))))
        .isInstanceOf(BusinessException.class)
        .hasMessage("POLICY-422-FEEL");
    assertThat(tables.compile("dd".repeat(32), List.of()).tableKeys()).isEmpty();
  }

  private static DecisionTableSource src(String xml) {
    return new DecisionTableSource("xep_loai", xml);
  }
}
