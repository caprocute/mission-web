package vn.viettel.okrkpi.oversight.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.oversight.application.port.ReopenImpactSimulator;
import vn.viettel.okrkpi.oversight.application.port.ReopenImpactSimulator.Row;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository.Adjustment;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.shared.infrastructure.scoring.ScoringSimulationClient;
import vn.viettel.okrkpi.shared.infrastructure.scoring.ScoringSimulationClient.Outcome;
import vn.viettel.okrkpi.shared.infrastructure.scoring.ScoringSimulationClient.Result;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class ScoringReopenImpactSimulatorTest {

  static final TenantId T = TenantId.of(UUID.randomUUID());
  static final UUID P1 = UUID.randomUUID();
  static final UUID P2 = UUID.randomUUID();
  static final UUID P3 = UUID.randomUUID();
  static final UUID TASK = UUID.randomUUID();

  /** Client giả trả kết quả theo hồ sơ và ghi lại lời gọi. */
  static final class FakeClient extends ScoringSimulationClient {
    final Map<UUID, Result> results = new HashMap<>();
    final List<List<Adjustment>> calls = new ArrayList<>();
    final List<String> auths = new ArrayList<>();

    FakeClient() {
      super("http://scoring", java.time.Duration.ofSeconds(1));
    }

    @Override
    public Result simulate(
        TenantId tenant, UUID profileId, List<Adjustment> adjustments, String authorization) {
      calls.add(adjustments);
      auths.add(authorization);
      return results.get(profileId);
    }
  }

  @Test
  void moiHoSoHaiDongVaDemBieuMauCanSinhLai() {
    FakeClient client = new FakeClient();
    client.results.put(
        P1,
        new Result(
            "KHOI_DANG v2.0",
            new Outcome(new BigDecimal("91.20"), "HTTNV"),
            new Outcome(new BigDecimal("92.1"), "HTXSNV")));
    client.results.put(
        P2,
        new Result(
            "KHOI_DANG v2.0",
            new Outcome(new BigDecimal("80"), "HTTNV"),
            new Outcome(new BigDecimal("80.0"), "HTTNV")));
    ScoringReopenImpactSimulator sim = new ScoringReopenImpactSimulator(client);
    ReopenImpactSimulator.Impact impact =
        sim.simulate(
            T,
            new ReopenImpactSimulator.Request(
                UUID.randomUUID(),
                ReopenScope.HO_SO,
                List.of(P1, P2, P3),
                List.of(P1, P2, P3),
                List.of(
                    new Adjustment("C:" + TASK, "0.85", "1", "Miễn trừ"),
                    new Adjustment("XEP_LOAI", "HTTNV", "HTXSNV", "Không tính thử"),
                    new Adjustment("B:khong-phai-uuid", null, " 2.5 ", null),
                    new Adjustment("A", null, "chữ", null),
                    new Adjustment(" ", null, "1", null)),
                "Bearer x"));
    assertThat(impact.policyVersion()).isEqualTo("KHOI_DANG v2.0");
    assertThat(impact.rows())
        .containsExactly(
            new Row(P1, "TONG_DIEM", "91.2", "92.1"),
            new Row(P1, "XEP_LOAI", "HTTNV", "HTXSNV"),
            new Row(P2, "TONG_DIEM", "80", "80"),
            new Row(P2, "XEP_LOAI", "HTTNV", "HTTNV"));
    // P1 đổi kết quả; P2 không đổi; P3 chưa có kết quả gốc.
    assertThat(impact.formsToRegenerate()).isEqualTo(2);
    assertThat(client.calls).hasSize(3);
    assertThat(client.calls.get(0))
        .containsExactly(
            new ScoringSimulationClient.Adjustment(
                "C", TASK, new BigDecimal("0.85"), BigDecimal.ONE, "Miễn trừ"),
            new ScoringSimulationClient.Adjustment("B", null, null, new BigDecimal("2.5"), null));
    assertThat(client.auths).containsOnly("Bearer x");
    assertThat(
            new ReopenImpactSimulator.Request(
                    UUID.randomUUID(),
                    ReopenScope.HO_SO,
                    List.of(),
                    List.of(P1),
                    List.of(),
                    "Bearer x")
                .toString())
        .doesNotContain("Bearer");
  }

  @Test
  void khongCoDieuChinhTinhDuocThiKhongGoi() {
    FakeClient client = new FakeClient();
    ScoringReopenImpactSimulator sim = new ScoringReopenImpactSimulator(client);
    ReopenImpactSimulator.Impact impact =
        sim.simulate(
            T,
            new ReopenImpactSimulator.Request(
                UUID.randomUUID(),
                ReopenScope.HO_SO,
                List.of(P1),
                List.of(P1),
                List.of(new Adjustment("XEP_LOAI", null, "HTXSNV", null))));
    assertThat(impact.rows()).isEmpty();
    assertThat(impact.formsToRegenerate()).isZero();
    assertThat(client.calls).isEmpty();
  }

  @Test
  void diemThieuVanSoSanhDuoc() {
    FakeClient client = new FakeClient();
    client.results.put(
        P1,
        new Result(null, new Outcome(null, "HTTNV"), new Outcome(new BigDecimal("1"), "HTTNV")));
    client.results.put(P2, new Result("v1", new Outcome(null, "X"), new Outcome(null, "X")));
    ReopenImpactSimulator.Impact impact =
        new ScoringReopenImpactSimulator(client)
            .simulate(
                T,
                new ReopenImpactSimulator.Request(
                    UUID.randomUUID(),
                    ReopenScope.HO_SO,
                    List.of(P1, P2),
                    List.of(P1, P2),
                    List.of(new Adjustment("A", "1", "2", null))));
    assertThat(impact.policyVersion()).isEqualTo("v1");
    assertThat(impact.rows().get(0)).isEqualTo(new Row(P1, "TONG_DIEM", null, "1"));
    assertThat(impact.formsToRegenerate()).isEqualTo(2);
  }
}
