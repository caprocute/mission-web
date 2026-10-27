package vn.viettel.okrkpi.task.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanSignatures.Request;

class HttpPlanSignaturesKeyTest {

  private static Request req(UUID version, String key) {
    return new Request(
        TenantId.of(UUID.randomUUID()),
        UUID.randomUUID(),
        version,
        1,
        "SIM",
        UUID.randomUUID(),
        key,
        "token");
  }

  @Test
  void khoaChongGuiTrungKhongQua64KyTuVaXacDinh() {
    UUID version = UUID.randomUUID();
    String longKey = UUID.randomUUID() + "-" + UUID.randomUUID();
    String k1 = HttpPlanSignatures.idempotencyKey(req(version, longKey));
    String k2 = HttpPlanSignatures.idempotencyKey(req(version, longKey));
    assertThat(k1).hasSizeLessThanOrEqualTo(64).isEqualTo(k2);
    assertThat(HttpPlanSignatures.idempotencyKey(req(UUID.randomUUID(), longKey))).isNotEqualTo(k1);
  }
}
