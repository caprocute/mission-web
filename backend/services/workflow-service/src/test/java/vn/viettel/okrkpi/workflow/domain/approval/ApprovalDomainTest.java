package vn.viettel.okrkpi.workflow.domain.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.domain.Decision;

/** Quy tắc miền của luồng duyệt phần 1 (DV-06-03). */
class ApprovalDomainTest {

  @Test
  void capDuyetKiemThuTuVaiTroVaHan() {
    assertThat(new ApprovalRule(1, "leader", ApprovalScope.DON_VI_TRUC_TIEP, 3).workingDays())
        .isEqualTo(3);
    assertThatThrownBy(() -> new ApprovalRule(0, "leader", ApprovalScope.DON_VI_TRUC_TIEP, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalRule(1, "Lanh dao", ApprovalScope.DON_VI_TRUC_TIEP, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalRule(1, "leader", ApprovalScope.DON_VI_TRUC_TIEP, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalRule(1, "leader", ApprovalScope.DON_VI_TRUC_TIEP, 31))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ApprovalLevel(
                    1, "leader", ApprovalScope.DON_VI_TRUC_TIEP, null, Instant.EPOCH, 1))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void thamSoMacDinhVaKiemGiaTri() {
    ApprovalParams defaults = ApprovalParams.defaults(ApprovalFlow.NHA_NUOC);
    assertThat(defaults.remindAfter()).isEqualTo("P2D");
    assertThat(defaults.allowDelegation()).isTrue();
    assertThat(defaults.levels()).isEmpty();
    assertThat(new ApprovalParams("PT1H", false, null, "MYSIGN").levels()).isEmpty();
    assertThatThrownBy(() -> new ApprovalParams("2 ngay", true, List.of(), "MYSIGN"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalParams("PT10M", true, List.of(), "MYSIGN"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalParams("P31D", true, List.of(), "MYSIGN"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalParams("P1D", true, List.of(), "KY_TAY"))
        .isInstanceOf(IllegalArgumentException.class);
    ApprovalRule rule = new ApprovalRule(1, "leader", ApprovalScope.DON_VI_TRUC_TIEP, 1);
    List<ApprovalRule> nine = java.util.Collections.nCopies(9, rule);
    assertThatThrownBy(() -> new ApprovalParams("P1D", true, nine, "MYSIGN"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void maHoaCapTuyChinhHaiChieu() {
    List<ApprovalRule> rules =
        List.of(
            new ApprovalRule(1, "manager", ApprovalScope.DON_VI_TRUC_TIEP, 2),
            new ApprovalRule(2, "leader", ApprovalScope.CAP_UY_CO_THAM_QUYEN, 5));
    String encoded = ApprovalVariables.encodeRules(rules);
    assertThat(encoded).isEqualTo("manager:DON_VI_TRUC_TIEP:2;leader:CAP_UY_CO_THAM_QUYEN:5");
    assertThat(ApprovalVariables.decodeRules(encoded)).isEqualTo(rules);
    assertThat(ApprovalVariables.decodeRules(null)).isEmpty();
    assertThat(ApprovalVariables.decodeRules(" ")).isEmpty();
    assertThatThrownBy(() -> ApprovalVariables.decodeRules("leader:DON_VI_TRUC_TIEP"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalVariables.decodeRules("leader:KHAC:2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalVariables.decodeRules("leader:DON_VI_TRUC_TIEP:hai"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void kichThuocBienTinhTheoUtf8() {
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("tieuDe", "Đ");
    vars.put("rong", null);
    assertThat(ApprovalVariables.sizeOf(vars)).isEqualTo("tieuDe".length() + 2 + "rong".length());
    assertThat(ApprovalVariables.MAX_START_BYTES + ApprovalVariables.MAX_LEVELS_BYTES)
        .isLessThan(ApprovalVariables.MAX_TOTAL_BYTES);
  }

  @Test
  void ngayLamViecBoQuaCuoiTuanNgayNghiVaTinhNgayLamBu() {
    LocalDate friday = LocalDate.of(2026, 10, 23);
    assertThat(WorkingDays.plus(friday, 1, Map.of())).isEqualTo(LocalDate.of(2026, 10, 26));
    assertThat(WorkingDays.plus(friday, 0, Map.of())).isEqualTo(friday);
    Map<LocalDate, Boolean> overrides = new HashMap<>();
    overrides.put(LocalDate.of(2026, 10, 26), false);
    overrides.put(LocalDate.of(2026, 10, 24), true);
    // Thứ Bảy làm bù được tính, thứ Hai nghỉ lễ bị bỏ qua.
    assertThat(WorkingDays.plus(friday, 2, overrides)).isEqualTo(LocalDate.of(2026, 10, 27));
    assertThatThrownBy(() -> WorkingDays.plus(friday, -1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    Map<LocalDate, Boolean> allHolidays = new HashMap<>();
    for (int i = 1; i <= 400; i++) {
      allHolidays.put(friday.plusDays(i), false);
    }
    assertThatThrownBy(() -> WorkingDays.plus(friday, 1, allHolidays))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void maDanhMucDocDung() {
    assertThat(ApprovalScope.parse("CAP_TREN_TRUC_TIEP"))
        .contains(ApprovalScope.CAP_TREN_TRUC_TIEP);
    assertThat(ApprovalScope.parse("KHAC")).isEmpty();
    assertThat(ApprovalScope.parse(null)).isEmpty();
    assertThat(ApprovalFlow.parse("NHA_NUOC")).contains(ApprovalFlow.NHA_NUOC);
    assertThat(ApprovalFlow.parse("QUAN_DOI")).isEmpty();
    assertThat(ApprovalTemplate.byCode("duyet-ke-hoach-quy"))
        .contains(ApprovalTemplate.DUYET_KE_HOACH_QUY);
    assertThat(ApprovalTemplate.byCode("tpl-appraisal-review")).isEmpty();
    assertThat(ApprovalTemplate.byTopic("core-change-request-reject"))
        .contains(
            new ApprovalTemplate.TopicAction(ApprovalTemplate.DUYET_DE_NGHI, Decision.TRA_LAI));
    assertThat(ApprovalTemplate.byTopic("core-okr-approve"))
        .contains(new ApprovalTemplate.TopicAction(ApprovalTemplate.DUYET_OKR, Decision.DUYET));
    assertThat(ApprovalTemplate.byTopic("notify-remind")).isEmpty();
    assertThat(ApprovalTemplate.DUYET_KE_HOACH_QUY.businessType()).isEqualTo("PLAN");
    assertThat(ApprovalTemplate.DUYET_KE_HOACH_QUY.processKind()).isEqualTo("KE_HOACH_QUY");
  }

  @Test
  void donViChonCapTrenTheoLuong() {
    UUID party = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    OrgUnit unit = new OrgUnit(UUID.randomUUID(), "PHONG_BAN", party, admin);
    assertThat(unit.parentOn(ApprovalFlow.DANG)).isEqualTo(party);
    assertThat(unit.parentOn(ApprovalFlow.NHA_NUOC)).isEqualTo(admin);
    assertThat(unit.isPartyOrganization()).isFalse();
    assertThat(new OrgUnit(party, OrgUnit.PARTY_ORGANIZATION, null, null).isPartyOrganization())
        .isTrue();
  }
}
