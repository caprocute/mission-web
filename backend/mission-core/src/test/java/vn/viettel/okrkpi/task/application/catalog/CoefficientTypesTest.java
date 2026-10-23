package vn.viettel.okrkpi.task.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class CoefficientTypesTest {

  @Test
  void docThamSoLoaiHeSo() {
    assertThat(CoefficientTypes.parse(Map.of("he_so_nhom", List.of("NHOM_1"), "khac", "x")))
        .containsEntry("he_so_nhom", Set.of("NHOM_1"))
        .containsEntry("khac", Set.of());
    assertThat(CoefficientTypes.parse(List.of("a", "b"))).containsOnlyKeys("a", "b");
    assertThat(CoefficientTypes.parse(7)).isEmpty();
    assertThat(
            new CoefficientTypes(null).declared(TenantId.of(UUID.randomUUID()), UUID.randomUUID()))
        .isEmpty();

    UUID version = UUID.randomUUID();
    PolicyQueryApi pack =
        new PolicyQueryApi() {
          @Override
          public Optional<PolicyFormulaView> findActivePolicy(
              TenantId tenantId, PeriodId periodId, String code) {
            return Optional.empty();
          }

          @Override
          public Optional<ResolvedPack> resolve(
              TenantId tenantId, PeriodId periodId, String flow, UUID group, UUID unit) {
            return "DANG".equals(flow)
                ? Optional.of(
                    new ResolvedPack(
                        UUID.randomUUID(), "P", version, "1", "h", null, version, false))
                : Optional.empty();
          }

          @Override
          public Optional<Object> parameter(TenantId tenantId, UUID versionId, String key) {
            return CoefficientTypes.KEY.equals(key)
                ? Optional.of(Map.of("he_so_nhom", List.of("NHOM_1")))
                : Optional.empty();
          }
        };
    assertThat(
            new CoefficientTypes(pack).declared(TenantId.of(UUID.randomUUID()), UUID.randomUUID()))
        .containsOnlyKeys("he_so_nhom");
    assertThat(new CoefficientTypes(pack).declared(TenantId.of(UUID.randomUUID()), null)).isEmpty();
  }
}
