package vn.viettel.okrkpi.appraisal.infrastructure.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Hỏi MOD-03 theo từng phần tối đa 200 mã, gộp kết quả. */
class ProductConfirmedEvidenceTest {

  @Test
  void chiaLanHoiVaGopKetQua() {
    List<Integer> sizes = new ArrayList<>();
    EvidenceQueryApi api =
        (tenant, person, ids) -> {
          sizes.add(ids.size());
          assertThat(ids.size()).isLessThanOrEqualTo(EvidenceQueryApi.MAX_IDS);
          return new HashSet<>(
              ids.stream().filter(id -> id.getLeastSignificantBits() % 2 == 0).toList());
        };
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 450; i++) {
      ids.add(new UUID(1, i));
    }
    ids.add(new UUID(1, 0));
    Set<UUID> confirmed =
        new ProductConfirmedEvidence(api)
            .confirmed(TenantId.of(UUID.randomUUID()), UUID.randomUUID(), ids);
    assertThat(confirmed).hasSize(225);
    assertThat(sizes).containsExactly(200, 200, 50);
    assertThat(
            new ProductConfirmedEvidence(api)
                .confirmed(TenantId.of(UUID.randomUUID()), UUID.randomUUID(), List.of()))
        .isEmpty();
  }
}
