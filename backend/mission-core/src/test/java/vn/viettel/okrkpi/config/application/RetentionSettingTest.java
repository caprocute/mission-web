package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.Period;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.application.port.TenantSettingRepository.Setting;
import vn.viettel.okrkpi.config.domain.RetentionClass;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** DL-13: bảy lớp lưu giữ đọc từ {@code config.tenant_setting}, mặc định TL-03 và mức sàn. */
class RetentionSettingTest {

  private static final Instant NOW = Instant.parse("2027-01-04T01:00:00Z");
  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final ObjectMapper JSON = new ObjectMapper();

  private final FeatureFlagAndSettingServiceTest.Settings repo =
      new FeatureFlagAndSettingServiceTest.Settings();
  private final TenantSettingService service =
      new TenantSettingService(repo, (t, a, x, y, i, g, v) -> {}, JSON, Clock.fixed(NOW));

  @Test
  void defaultsFollowTl03AndAreMarkedProposed() {
    Map<RetentionClass, RetentionClass.Rule> r = service.retention(TENANT);

    assertThat(r).hasSize(7);
    assertThat(r.values()).allMatch(RetentionClass.Rule::proposed);
    assertThat(r.get(RetentionClass.HO_SO_CAN_BO).hot()).isEqualTo(Period.ofYears(5));
    assertThat(r.get(RetentionClass.HO_SO_CAN_BO).total()).isEqualTo(Period.ofYears(20));
    assertThat(r.get(RetentionClass.THUC_HIEN).total()).isEqualTo(Period.ofYears(10));
    assertThat(r.get(RetentionClass.SO_CAI).hot()).isEqualTo(Period.ofYears(2));
    assertThat(r.get(RetentionClass.KY_THUAT).total()).isEqualTo(Period.ofDays(14));
    assertThat(RetentionClass.THUC_HIEN.ending()).isEqualTo(RetentionClass.Ending.XOA_BANG_MAT_MA);
    assertThat(RetentionClass.VAN_HANH.archived()).isFalse();
  }

  @Test
  void tenantValueOverridesDefaultWhenValid() {
    put(RetentionClass.THUC_HIEN, "P4Y", "P12Y");

    RetentionClass.Rule r = service.retention(TENANT).get(RetentionClass.THUC_HIEN);

    assertThat(r.proposed()).isFalse();
    assertThat(r.hot()).isEqualTo(Period.ofYears(4));
    assertThat(r.total()).isEqualTo(Period.ofYears(12));
  }

  @Test
  void ledgerBelowNfr09FloorOrMalformedValueFallsBackToDefault() {
    put(RetentionClass.SO_CAI, "P1Y", "P5Y");
    put(RetentionClass.VET_TINH, "P3Y", "P2Y");
    put(RetentionClass.KHIEU_NAI, "năm năm", "P20Y");

    Map<RetentionClass, RetentionClass.Rule> r = service.retention(TENANT);

    assertThat(r.get(RetentionClass.SO_CAI)).isEqualTo(RetentionClass.SO_CAI.defaults());
    assertThat(r.get(RetentionClass.VET_TINH)).isEqualTo(RetentionClass.VET_TINH.defaults());
    assertThat(r.get(RetentionClass.KHIEU_NAI)).isEqualTo(RetentionClass.KHIEU_NAI.defaults());
    assertThat(service.get(TENANT, RetentionClass.SO_CAI.settingKey(), null)).isEmpty();
  }

  @Test
  void ruleRejectsLedgerTotalBelowTenYears() {
    assertThatThrownBy(
            () ->
                new RetentionClass.Rule(
                    RetentionClass.SO_CAI, Period.ofYears(2), Period.ofYears(9), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SO_CAI");
  }

  private void put(RetentionClass c, String hot, String total) {
    ObjectNode v = JSON.createObjectNode().put("hot", hot).put("total", total);
    repo.insert(
        TENANT,
        new Setting(
            UUID.randomUUID(),
            c.settingKey(),
            null,
            v,
            TenantSettingService.RETENTION_SCHEMA,
            null,
            1,
            NOW),
        UUID.randomUUID());
  }
}
