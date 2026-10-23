package vn.viettel.okrkpi.scoring.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.domain.OpenPeriod;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;

class PolicyPublishedHandlerTest {

  static final UUID T = PolicyPackCacheTest.TENANT;

  @Test
  void moTacVuTinhLaiChoKyGhimPhienBanMoi() {
    PolicyPackCacheTest.FakeSource source = new PolicyPackCacheTest.FakeSource();
    PolicyContent v1 = PolicyPackCacheTest.version("2.1.0", Map.of());
    PolicyContent v2 =
        PolicyPackCacheTest.version("2.2.0", Map.of("kpi_nhiem_vu", "(kpi_A + kpi_B) / 2.0"));
    source.publish(v1);
    PinnedPolicy p2 = source.publish(v2);
    UUID open = UUID.randomUUID();
    UUID stayOld = UUID.randomUUID();
    UUID frozen = UUID.randomUUID();
    UUID unbound = UUID.randomUUID();
    source.bindings.put(PolicyBindingKey.of(T, open, "DANG"), p2);
    source.bindings.put(PolicyBindingKey.of(T, open, "NHA_NUOC"), p2);
    source.bindings.put(PolicyBindingKey.of(T, stayOld, "DANG"), v1.pinned());
    source.bindings.put(
        PolicyBindingKey.of(T, frozen, "DANG"),
        new PinnedPolicy(p2.packId(), "x", p2.versionId(), p2.version(), p2.contentHash(), true));
    List<OpenPeriod> periods =
        List.of(
            new OpenPeriod(open, 20271, "DANG"),
            new OpenPeriod(open, 20271, "NHA_NUOC"),
            new OpenPeriod(stayOld, 20264, "DANG"),
            new OpenPeriod(frozen, 20263, "DANG"),
            new OpenPeriod(unbound, 20262, "DANG"));
    List<ScoreJob> opened = new ArrayList<>();
    ScoreJobStore jobs =
        new ScoreJobStore() {
          @Override
          public UUID open(ScoreJob job) {
            opened.add(job);
            return job.id();
          }

          @Override
          public void progress(UUID t, int b, UUID j, int d, int f, UUID a) {}
        };
    PolicyPackCache cache = new PolicyPackCache(source, PolicyCompilerTest.compiler(), 4);
    PolicyPublishedHandler handler = new PolicyPublishedHandler(cache, tenant -> periods, jobs);
    UUID event = UUID.randomUUID();

    List<UUID> ids =
        handler.onPublished(
            new PolicyPublishedHandler.Published(
                T, event, p2.packId(), p2.versionId(), p2.contentHash()));

    assertThat(ids).hasSize(1);
    ScoreJob job = opened.getFirst();
    assertThat(job.periodId()).isEqualTo(open);
    assertThat(job.periodBucket()).isEqualTo(20271);
    assertThat(job.kind()).isEqualTo(ScoreJobKind.TAM_TINH);
    assertThat(job.triggerEventId()).isEqualTo(event);
    assertThat(job.policyPackVersionId()).isEqualTo(p2.versionId());
    assertThat(job.actorId()).isEqualTo(PolicyPublishedHandler.SYSTEM_ACTOR);
    assertThat(job.scope().json()).contains("PHAT_HANH_POLICY_PACK").contains("NHA_NUOC");
    assertThat(cache.cached(p2.contentHash())).isPresent();
    assertThat(
            cache
                .forBinding(PolicyBindingKey.of(T, open, "DANG"))
                .evaluate("kpi_nhiem_vu", Map.of("kpi_A", 1.0, "kpi_B", 0.5)))
        .isEqualTo(0.75);
    assertThatThrownBy(() -> new PolicyPublishedHandler.Published(T, null, T, T, "x"))
        .isInstanceOf(NullPointerException.class);
  }
}
