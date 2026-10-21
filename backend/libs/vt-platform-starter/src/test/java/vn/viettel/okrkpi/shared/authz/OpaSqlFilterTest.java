package vn.viettel.okrkpi.shared.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpaSqlFilterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID UNIT_U1 = UUID.fromString("aaaa1111-1111-1111-1111-111111111111");
  private static final UUID UNIT_U2 = UUID.fromString("aaaa2222-2222-2222-2222-222222222222");

  private final OpaInput.Subject subject =
      OpaInput.Subject.user(
          "eeee1111-1111-1111-1111-111111111111",
          TENANT_ID.toString(),
          UNIT_U1.toString(),
          List.of("leader"),
          "aal2",
          10_000L,
          List.of("NHA_NUOC"));

  private final ResourceColumnMapping mapping =
      ResourceColumnMapping.defaultForAlias("t", "NHA_NUOC");

  @Test
  void translatesEqualityMemberOfAndUnitSubtreeQueries() throws Exception {
    String astJson =
        """
                {
                  "queries": [
                    [
                      {
                        "index": 0,
                        "terms": [
                          {"type": "ref", "value": [{"type": "var", "value": "eq"}]},
                          {"type": "ref", "value": [
                            {"type": "var", "value": "input"},
                            {"type": "string", "value": "resource"},
                            {"type": "string", "value": "tenant_id"}
                          ]},
                          {"type": "string", "value": "11111111-1111-1111-1111-111111111111"}
                        ]
                      },
                      {
                        "index": 1,
                        "terms": [
                          {"type": "ref", "value": [{"type": "var", "value": "eq"}]},
                          {"type": "ref", "value": [
                            {"type": "var", "value": "input"},
                            {"type": "string", "value": "resource"},
                            {"type": "string", "value": "ancestor_unit_id"}
                          ]},
                          {"type": "string", "value": "aaaa1111-1111-1111-1111-111111111111"}
                        ]
                      }
                    ]
                  ]
                }
                """;

    OpaSqlFilter filter = new OpaSqlFilter(new NoopOpaClient(), new OpaProperties());
    SqlPredicate pred = filter.translateCompileResult(MAPPER.readTree(astJson), subject, mapping);

    assertThat(pred.sql())
        .isEqualTo(
            "t.tenant_id = ? AND t.owner_unit_id IN (SELECT uc.descendant_id FROM org_replica.unit_closure_v uc WHERE uc.tenant_id = ? AND uc.tree = ? AND uc.ancestor_id = ?)");
    assertThat(pred.params()).containsExactly(TENANT_ID, TENANT_ID, "NHA_NUOC", UNIT_U1);
  }

  @Test
  void translatesMemberOfAndOrgSubtreeReferenceFromTl01() throws Exception {
    String astJson =
        """
                {
                  "queries": [
                    [
                      {
                        "index": 0,
                        "terms": [
                          {"type": "ref", "value": [
                            {"type": "var", "value": "internal"},
                            {"type": "string", "value": "member_2"}
                          ]},
                          {"type": "ref", "value": [
                            {"type": "var", "value": "input"},
                            {"type": "string", "value": "resource"},
                            {"type": "string", "value": "owner_unit_id"}
                          ]},
                          {"type": "array", "value": [
                            {"type": "string", "value": "aaaa1111-1111-1111-1111-111111111111"},
                            {"type": "string", "value": "aaaa2222-2222-2222-2222-222222222222"}
                          ]}
                        ]
                      },
                      {
                        "index": 1,
                        "terms": [
                          {"type": "ref", "value": [{"type": "var", "value": "eq"}]},
                          {"type": "ref", "value": [
                            {"type": "var", "value": "input"},
                            {"type": "string", "value": "resource"},
                            {"type": "string", "value": "owner_unit_id"}
                          ]},
                          {"type": "ref", "value": [
                            {"type": "var", "value": "data"},
                            {"type": "string", "value": "org"},
                            {"type": "string", "value": "11111111-1111-1111-1111-111111111111"},
                            {"type": "string", "value": "subtree"},
                            {"type": "string", "value": "aaaa1111-1111-1111-1111-111111111111"},
                            {"type": "var", "value": "$0"}
                          ]}
                        ]
                      }
                    ]
                  ]
                }
                """;

    OpaSqlFilter filter = new OpaSqlFilter(new NoopOpaClient(), new OpaProperties());
    SqlPredicate pred = filter.translateCompileResult(MAPPER.readTree(astJson), subject, mapping);

    assertThat(pred.sql())
        .isEqualTo(
            "t.owner_unit_id IN (?, ?) AND t.owner_unit_id IN (SELECT uc.descendant_id FROM org_replica.unit_closure_v uc WHERE uc.tenant_id = ? AND uc.tree = ? AND uc.ancestor_id = ?)");
    assertThat(pred.params()).containsExactly(UNIT_U1, UNIT_U2, TENANT_ID, "NHA_NUOC", UNIT_U1);
  }

  @Test
  void emptyQueriesReturnsFalsePredicateAndRejectsUntranslatableAst() throws Exception {
    OpaSqlFilter filter = new OpaSqlFilter(new NoopOpaClient(), new OpaProperties());

    SqlPredicate empty =
        filter.translateCompileResult(MAPPER.readTree("{\"queries\": []}"), subject, mapping);
    assertThat(empty.isAlwaysFalse()).isTrue();
    assertThat(empty.sql()).isEqualTo("1 = 0");

    // Negated expression must be rejected (fail closed)
    String negatedAst =
        """
                {
                  "queries": [[{
                    "negated": true,
                    "terms": [
                      {"type": "ref", "value": [{"type": "var", "value": "eq"}]},
                      {"type": "ref", "value": [
                        {"type": "var", "value": "input"},
                        {"type": "string", "value": "resource"},
                        {"type": "string", "value": "tenant_id"}
                      ]},
                      {"type": "string", "value": "11111111-1111-1111-1111-111111111111"}
                    ]
                  }]]
                }
                """;
    assertThatThrownBy(
            () -> filter.translateCompileResult(MAPPER.readTree(negatedAst), subject, mapping))
        .isInstanceOf(UntranslatablePolicyException.class);

    // Unmapped field must be rejected (fail closed)
    String unmappedFieldAst =
        """
                {
                  "queries": [[{
                    "terms": [
                      {"type": "ref", "value": [{"type": "var", "value": "eq"}]},
                      {"type": "ref", "value": [
                        {"type": "var", "value": "input"},
                        {"type": "string", "value": "resource"},
                        {"type": "string", "value": "unknown_secret_col"}
                      ]},
                      {"type": "string", "value": "abc"}
                    ]
                  }]]
                }
                """;
    assertThatThrownBy(
            () ->
                filter.translateCompileResult(MAPPER.readTree(unmappedFieldAst), subject, mapping))
        .isInstanceOf(UntranslatablePolicyException.class);
  }

  @Test
  void cachesCompiledPredicateBySubjectActionAndBundleVersion() throws Exception {
    AtomicInteger compileCalls = new AtomicInteger();
    JsonNode fixedAst =
        MAPPER.readTree(
            """
                        {
                          "queries": [[{
                            "terms": [
                              {"type": "ref", "value": [{"type": "var", "value": "eq"}]},
                              {"type": "ref", "value": [
                                {"type": "var", "value": "input"},
                                {"type": "string", "value": "resource"},
                                {"type": "string", "value": "tenant_id"}
                              ]},
                              {"type": "string", "value": "11111111-1111-1111-1111-111111111111"}
                            ]
                          }]]
                        }
                        """);

    OpaClient countingClient =
        new NoopOpaClient() {
          private String rev = "v1";

          @Override
          public JsonNode compile(String query, Map<String, Object> input, List<String> unknowns) {
            compileCalls.incrementAndGet();
            return fixedAst;
          }

          @Override
          public String currentBundleRevision() {
            return rev;
          }

          @Override
          public void invalidateCache(String newBundleRevision) {
            this.rev = newBundleRevision;
          }
        };

    OpaSqlFilter filter = new OpaSqlFilter(countingClient, new OpaProperties());
    SqlPredicate p1 = filter.buildPredicate(subject, "SCR-0203.xem", mapping);
    SqlPredicate p2 = filter.buildPredicate(subject, "SCR-0203.xem", mapping);

    assertThat(p1).isEqualTo(p2);
    assertThat(compileCalls.get()).isEqualTo(1);

    // Đổi phiên bản bundle thì phải gọi lại /v1/compile
    countingClient.invalidateCache("v2");
    filter.buildPredicate(subject, "SCR-0203.xem", mapping);
    assertThat(compileCalls.get()).isEqualTo(2);
  }

  static class NoopOpaClient implements OpaClient {
    @Override
    public OpaDecision decide(OpaInput input) {
      return OpaDecision.denyClosed("noop");
    }

    @Override
    public JsonNode compile(String query, Map<String, Object> input, List<String> unknowns) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String currentBundleRevision() {
      return "v1";
    }

    @Override
    public void invalidateCache(String newBundleRevision) {}
  }
}
