package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.viettel.okrkpi.policy.domain.PolicyManifestRules;

/** Hai gói gốc mẫu trong {@code data/policy-pack/} qua được trình kiểm manifest (CORE-05-02). */
class PolicyManifestFixturesTest {

  @ParameterizedTest
  @ValueSource(strings = {PolicyPackFixtures.GOI_DANG, PolicyPackFixtures.GOI_NHA_NUOC})
  void samplePacksMatchManifestSchema(String id) {
    Map<String, Object> manifest =
        PolicyPackFixtures.yaml(PolicyPackFixtures.pack(id).resolve("policy.yaml"));
    assertThat(PolicyManifestRules.check(manifest)).isEmpty();
    assertThat(((Map<?, ?>) manifest.get("metadata")).get("id")).isEqualTo(id);
  }
}
