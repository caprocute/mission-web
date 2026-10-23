package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.policy.domain.PolicyRule;

class ManifestRuleImportTest {

  private final ObjectMapper json = new ObjectMapper();
  private final UUID version = UUID.randomUUID();

  private static Map<String, Object> manifest() {
    return Map.of(
        "params",
        Map.of(
            "tru_tien_do", Map.of("type", "double", "mac_dinh", 0.05),
            "loai_ngay", Map.of("type", "string", "mac_dinh", "NGAY_LAM_VIEC"),
            "htxs.nguong_ty_le_vuot", Map.of("type", "double", "mac_dinh", 0.3)),
        "definitions",
        Map.of(
            "nhiem_vu",
            Map.of(
                "kpi_C",
                Map.of(
                    "expression",
                    "math.greatest(0.0, 1.0 - tham_so.tru_tien_do * double(ngay_cham))",
                    "result_type",
                    "DECIMAL",
                    "legal_ref",
                    "PL 3 mục 3",
                    "eval_order",
                    40,
                    "overridable",
                    false)),
            "ho_so",
            Map.of(
                "diem",
                Map.of(
                    "expression",
                    "diem_ket_qua",
                    "result_type",
                    "DECIMAL",
                    "rounding",
                    Map.of("digits", 1, "mode", "NUA_LEN"),
                    "eval_order",
                    100),
                "dat_ty_le_vuot",
                Map.of(
                    "expression",
                    "ty_le_vuot >= tham_so.htxs.nguong_ty_le_vuot",
                    "result_type",
                    "BOOLEAN",
                    "eval_order",
                    120,
                    "overridable",
                    true))),
        "definitions_nam",
        Map.of(
            "nam",
            Map.of(
                "diem",
                Map.of("expression", "x", "result_type", "DECIMAL"),
                "cham_nam",
                Map.of(
                    "expression", "tham_so.loai_ngay == 'NGAY_LICH'",
                    "result_type", "BOOLEAN"))));
  }

  private PolicyRule rule(List<PolicyRule> rules, String key) {
    return rules.stream().filter(r -> r.ruleKey().equals(key)).findFirst().orElseThrow();
  }

  @Test
  void eachDefinitionBecomesOneRuleWithReferencedParams() {
    List<PolicyRule> rules = ManifestRuleImport.rules(version, manifest(), json);

    assertThat(rules)
        .extracting(PolicyRule::ruleKey)
        .containsExactlyInAnyOrder("kpi_C", "diem", "dat_ty_le_vuot", "cham_nam");
    PolicyRule c = rule(rules, "kpi_C");
    assertThat(c.versionId()).isEqualTo(version);
    assertThat(c.evalOrder()).isEqualTo(40);
    assertThat(c.legalRef()).isEqualTo("PL 3 mục 3");
    assertThat(c.roundingJson()).isEqualTo("{}");
    assertThat(c.paramsJson()).isEqualTo("{\"tru_tien_do\":0.05}");
    assertThat(rule(rules, "diem").roundingJson()).contains("\"digits\":1");
    assertThat(rule(rules, "dat_ty_le_vuot").paramsJson())
        .isEqualTo("{\"htxs.nguong_ty_le_vuot\":0.3}");
    assertThat(rule(rules, "dat_ty_le_vuot").overridable()).isTrue();
    assertThat(rule(rules, "cham_nam").paramsJson()).isEqualTo("{\"loai_ngay\":\"NGAY_LAM_VIEC\"}");
  }

  @Test
  void definitionKeyRepeatedInYearBlockKeepsQuarterRule() {
    List<PolicyRule> rules = ManifestRuleImport.rules(version, manifest(), json);
    assertThat(rules.stream().filter(r -> r.ruleKey().equals("diem"))).hasSize(1);
    assertThat(rule(rules, "diem").expression()).isEqualTo("diem_ket_qua");
  }
}
