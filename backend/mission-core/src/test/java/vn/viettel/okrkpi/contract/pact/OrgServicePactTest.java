package vn.viettel.okrkpi.contract.pact;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.infrastructure.e2ee.OrgRecoveryKeyClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * QA-02 · bên tiêu dùng service với service: {@link OrgRecoveryKeyClient} của mission-core gọi
 * org-service (SVC-04) theo {@code src/test/resources/pacts/mission-core-org-service.json}. Máy giả
 * lập bên cung cấp ({@link PactMockProvider}) chỉ trả phản hồi của hợp đồng khi yêu cầu khớp tương
 * tác; org-service xác minh cùng tệp ở {@code MissionCorePactIT}.
 */
class OrgServicePactTest {

  static final Path PACT = Path.of("src/test/resources/pacts/mission-core-org-service.json");

  @Test
  void docKhoaPhucHoiHienHanh() throws Exception {
    Pact.Interaction i = Pact.load(PACT).interaction("khoá phục hồi hiện hành của tenant");
    try (PactMockProvider org = new PactMockProvider(i)) {
      Optional<OrgRecoveryKeyClient.Current> current = client(org).current(tenant(i));
      assertThat(org.mismatches()).isEmpty();
      assertThat(current).isPresent();
      assertThat(current.get().id().toString()).isEqualTo(i.response().at("/body/id").asText());
      assertThat(current.get().fingerprint()).hasSize(64);
    }
  }

  @Test
  void tenantChuaCoKhoaThiRong() throws Exception {
    Pact.Interaction i = Pact.load(PACT).interaction("tenant chưa có khoá phục hồi");
    try (PactMockProvider org = new PactMockProvider(i)) {
      assertThat(client(org).current(tenant(i))).isEmpty();
      assertThat(org.mismatches()).isEmpty();
    }
  }

  private static OrgRecoveryKeyClient client(PactMockProvider org) {
    String token = org.token();
    return new OrgRecoveryKeyClient(RestClient.builder(), org.baseUrl(), () -> token);
  }

  private static TenantId tenant(Pact.Interaction i) {
    return TenantId.of(UUID.fromString(i.request().at("/headers/X-Tenant-Id").asText()));
  }
}
