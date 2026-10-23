package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.SpecialCaseRulesPort;
import vn.viettel.okrkpi.config.api.SpecialCaseRulesPort.SpecialCaseRuleEntry;
import vn.viettel.okrkpi.dossier.domain.specialcase.SpecialCaseRule;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Bảng trường hợp đặc thù đọc theo gói của kỳ, thiếu thì dùng bảng gói khối Đảng v2.0. */
class PolicyPackSpecialCaseRulesTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();

  private final SpecialCaseRulesPort port = mock(SpecialCaseRulesPort.class);

  @Test
  void docBangCuaGoiTheoLuongDangTruoc() {
    when(port.rules(eq(TENANT), any(), eq("DANG")))
        .thenReturn(
            Optional.of(
                List.of(
                    new SpecialCaseRuleEntry(
                        "DIEU_DONG_DUOI_2_THANG",
                        1,
                        "PL 6 §1",
                        "Đơn vị cũ đánh giá",
                        "XEP_LOAI_TAI_DON_VI_CU",
                        null,
                        Map.of("nguong_thang", 3)))));
    List<SpecialCaseRule> rules =
        new PolicyPackSpecialCaseRules(() -> port, new DefaultSpecialCaseRules())
            .rules(TENANT, PERIOD);
    assertThat(rules)
        .singleElement()
        .satisfies(r -> assertThat(r.number("nguong_thang")).isEqualTo(3.0));
  }

  @Test
  void khongCoGoiDangThiThuNhaNuocRoiDungBangMacDinh() {
    when(port.rules(eq(TENANT), any(), eq("DANG"))).thenReturn(Optional.of(List.of()));
    when(port.rules(eq(TENANT), any(), eq("NHA_NUOC")))
        .thenReturn(
            Optional.of(
                List.of(
                    new SpecialCaseRuleEntry(
                        "KIEM_NHIEM", 3, "PL 6 §3", null, "BAO_LUU", null, null))));
    assertThat(
            new PolicyPackSpecialCaseRules(() -> port, new DefaultSpecialCaseRules())
                .rules(TENANT, PERIOD))
        .extracting(SpecialCaseRule::effect)
        .containsExactly("BAO_LUU");

    when(port.rules(eq(TENANT), any(), eq("NHA_NUOC"))).thenReturn(Optional.empty());
    assertThat(
            new PolicyPackSpecialCaseRules(() -> port, new DefaultSpecialCaseRules())
                .rules(TENANT, PERIOD))
        .hasSize(13);
    assertThat(
            new PolicyPackSpecialCaseRules(() -> null, new DefaultSpecialCaseRules())
                .rules(TENANT, PERIOD))
        .hasSize(13)
        .extracting(SpecialCaseRule::code)
        .startsWith("DIEU_DONG_DUOI_2_THANG");
  }
}
