package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.SpecialCaseRulesPort.SpecialCaseRuleEntry;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cổng bảng {@code truong_hop_dac_thu} của MOD-05 cho MOD-06 (CORE-06-05). */
class PolicySpecialCaseRulesAdapterTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final PeriodId PERIOD = PeriodId.of(UUID.randomUUID());
  static final UUID VERSION = UUID.randomUUID();

  private final PolicyResolutionService resolution = mock(PolicyResolutionService.class);
  private final PolicySpecialCaseRulesAdapter adapter =
      new PolicySpecialCaseRulesAdapter(resolution);

  @Test
  void docBangCuaPhienBanPhanGiaiChoKy() {
    Map<String, Object> s10 = new HashMap<>();
    s10.put("ma", "NGHI_HUU_THOI_VIEC");
    s10.put("muc", 10);
    s10.put("legal_ref", "PL 6 §10");
    s10.put("cach_xu_ly", "Đánh giá đến thời điểm nghỉ.");
    s10.put("hieu_ung", "XEP_LOAI_BINH_THUONG");
    s10.put("hieu_ung_khi_khong_du", "KHONG_XEP_LOAI");
    Map<String, Object> params = new HashMap<>();
    params.put("thoi_gian_toi_thieu_thang", null);
    s10.put("tham_so", params);
    Map<String, Object> bare =
        Map.of("ma", "KIEM_NHIEM", "legal_ref", "PL 6 §3", "hieu_ung", "BAO_LUU");
    when(resolution.resolve(TENANT, PERIOD, "DANG", null, null))
        .thenReturn(
            Optional.of(
                new ResolvedPack(
                    UUID.randomUUID(),
                    "khoi-dang",
                    VERSION,
                    "2.0.0",
                    "h",
                    null,
                    UUID.randomUUID(),
                    true)));
    when(resolution.manifest(TENANT, VERSION))
        .thenReturn(
            Optional.of(
                Map.of(
                    "truong_hop_dac_thu",
                    Arrays.asList(
                        s10, bare, Map.of("ma", "THIEU_HIEU_UNG", "legal_ref", "x"), "rac"))));
    List<SpecialCaseRuleEntry> rules = adapter.rules(TENANT, PERIOD, "DANG").orElseThrow();
    assertThat(rules).hasSize(2);
    SpecialCaseRuleEntry first = rules.get(0);
    assertThat(first.section()).isEqualTo(10);
    assertThat(first.effectWhenInsufficient()).isEqualTo("KHONG_XEP_LOAI");
    assertThat(first.params()).containsKey("thoi_gian_toi_thieu_thang");
    assertThat(rules.get(1).section()).isZero();
    assertThat(rules.get(1).handling()).isNull();
    assertThat(rules.get(1).effectWhenInsufficient()).isNull();
    assertThat(rules.get(1).params()).isEmpty();
  }

  @Test
  void khongPhanGiaiDuocHoacKhongCoBangThiRong() {
    when(resolution.resolve(TENANT, PERIOD, "NHA_NUOC", null, null)).thenReturn(Optional.empty());
    assertThat(adapter.rules(TENANT, PERIOD, "NHA_NUOC")).isEmpty();
    when(resolution.resolve(TENANT, PERIOD, "DANG", null, null))
        .thenReturn(
            Optional.of(
                new ResolvedPack(
                    UUID.randomUUID(),
                    "khoi-dang",
                    VERSION,
                    "2.0.0",
                    "h",
                    null,
                    UUID.randomUUID(),
                    true)));
    when(resolution.manifest(TENANT, VERSION)).thenReturn(Optional.of(Map.of("params", Map.of())));
    assertThat(adapter.rules(TENANT, PERIOD, "DANG")).isEmpty();
  }
}
