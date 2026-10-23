package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.RecoveryKeySettingApi;
import vn.viettel.okrkpi.config.application.port.RecoveryKeySource;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-07-06: đồng bộ khoá phục hồi của SVC-04 vào cấu hình tenant {@code e2ee.recovery-key}. */
class RecoveryKeySettingServiceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final String FP1 = "1".repeat(64);
  private static final String FP2 = "2".repeat(64);

  private final FeatureFlagAndSettingServiceTest.Settings repo =
      new FeatureFlagAndSettingServiceTest.Settings();
  private final List<String> audits = new ArrayList<>();
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-10-23T01:00:00Z"));
  private final TenantSettingService settings =
      new TenantSettingService(
          repo, (t, a, action, y, i, g, v) -> audits.add(action + "@" + v), JSON, now::get);
  private final AtomicReference<Optional<RecoveryKeySource.Current>> org =
      new AtomicReference<>(Optional.empty());
  private int calls;
  private final RecoveryKeySource source =
      t -> {
        calls++;
        return org.get();
      };

  @Test
  void dongBoKhoaMoiGiuVanTayCuVaHoiLaiTheoTtl() {
    var service = new RecoveryKeySettingService(settings, source, now::get, Duration.ofMinutes(5));
    assertThat(service.current(TENANT)).isEmpty();

    UUID k1 = UUID.randomUUID();
    org.set(Optional.of(new RecoveryKeySource.Current(k1, FP1)));
    assertThat(service.current(TENANT)).as("chưa hết ttl nên chưa hỏi lại").isEmpty();
    now.set(now.get().plus(Duration.ofMinutes(6)));
    JsonNode v = service.current(TenantId.of(TENANT.value())).orElseThrow();
    assertThat(v.path("recipientId").asText()).isEqualTo(k1.toString());
    assertThat(v.path("fingerprints").get(0).asText()).isEqualTo(FP1);
    assertThat(audits).containsExactly(TenantSettingService.RECOVERY_SYNC_ACTION + "@0");

    UUID k2 = UUID.randomUUID();
    org.set(Optional.of(new RecoveryKeySource.Current(k2, FP2)));
    now.set(now.get().plus(Duration.ofMinutes(6)));
    v = service.current(TENANT).orElseThrow();
    assertThat(v.path("recipientId").asText()).isEqualTo(k2.toString());
    assertThat(v.path("fingerprints")).hasSize(2);
    assertThat(v.path("fingerprints").get(0).asText()).isEqualTo(FP2);
    assertThat(v.path("fingerprints").get(1).asText()).isEqualTo(FP1);

    now.set(now.get().plus(Duration.ofMinutes(6)));
    service.current(TENANT);
    assertThat(audits).as("giá trị đã khớp thì không ghi lại").hasSize(2);
    assertThat(calls).isEqualTo(4);
  }

  @Test
  void nguonLoiThiDungGiaTriDaLuuVaKhongCoNguonThiChiDoc() {
    settings.syncRecoveryKey(TENANT, UUID.randomUUID(), FP1);
    var failing =
        new RecoveryKeySettingService(
            settings,
            t -> {
              throw new IllegalStateException("org-service không trả lời");
            },
            now::get,
            Duration.ofMinutes(5));
    assertThat(failing.current(TENANT)).isPresent();
    assertThat(
            new RecoveryKeySettingService(settings, null, now::get, Duration.ZERO).current(TENANT))
        .isPresent();
  }

  @Test
  void gioiHanSoVanTayGiuLai() {
    for (int i = 0; i < TenantSettingService.RECOVERY_FINGERPRINTS_KEPT + 2; i++) {
      assertThat(
              settings.syncRecoveryKey(
                  TENANT, UUID.randomUUID(), String.valueOf((char) ('3' + i)).repeat(64)))
          .isTrue();
    }
    JsonNode v = settings.get(TENANT, RecoveryKeySettingApi.KEY, null).orElseThrow();
    assertThat(v.path("fingerprints")).hasSize(TenantSettingService.RECOVERY_FINGERPRINTS_KEPT);
    assertThat(v.path("fingerprints").get(0).asText()).isEqualTo("9".repeat(64));
    repo.failInsert = true;
    repo.rows.clear();
    assertThat(settings.syncRecoveryKey(TENANT, UUID.randomUUID(), FP1)).isFalse();
  }
}
