package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.policy.application.PolicyPreviewService.Change;
import vn.viettel.okrkpi.policy.application.PolicyPreviewService.ChangeType;
import vn.viettel.okrkpi.policy.application.PolicyPreviewService.Evaluation;
import vn.viettel.okrkpi.policy.application.PolicyPreviewService.Kind;
import vn.viettel.okrkpi.policy.application.PolicyPreviewService.VersionDiff;
import vn.viettel.okrkpi.policy.application.port.GoldenTestCaseRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyDecisionTableRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec;
import vn.viettel.okrkpi.policy.domain.GoldenCaseSource;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.policy.domain.ResultComparison;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * So sánh phiên bản và tính thử một ca của CORE-05-08 trên gói Đảng giả lập: đủ thêm, xoá, sửa ở
 * quy tắc, tham số, bảng quyết định, ràng buộc luồng duyệt và manifest; tính thử cho cùng kết quả
 * với bộ ca của DL-03 và bộ chạy CORE-05-07, đệm nóng không biên dịch lại.
 */
class PolicyPreviewServiceTest {

  private static final String PACK = "khoi-dang/quy-dinh-moi";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();
  private final PolicyPackRepository packs = mock(PolicyPackRepository.class);
  private final GoldenTestCaseRepository cases = mock(GoldenTestCaseRepository.class);
  private final Map<UUID, List<DecisionTable>> tables = new HashMap<>();
  private final Map<UUID, List<PolicyRule>> rules = new HashMap<>();
  private final Map<String, GoldenTestCase> golden = new HashMap<>();
  private PolicyPack pack;
  private PolicyPack parent;
  private PolicyEvaluators evaluators;
  private PolicyPreviewService service;
  private PolicyPackVersion base;
  private PolicyPackVersion target;

  private PolicyPackVersion version(String number, Map<String, Object> manifest) throws Exception {
    PolicyPackVersion v =
        new PolicyPackVersion(
            UUID.randomUUID(),
            pack.id(),
            number,
            PolicyVersionStatus.NHAP,
            null,
            JSON.writeValueAsString(manifest),
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            "Nháp",
            Instant.now(),
            author,
            Instant.now(),
            author,
            0);
    when(packs.findVersion(tenant, pack.id(), number)).thenReturn(Optional.of(v));
    return v;
  }

  private static List<DecisionTable> tables(UUID versionId, Map<String, DecisionTableSpec> specs) {
    List<DecisionTable> out = new ArrayList<>();
    specs.forEach(
        (key, spec) ->
            out.add(
                new DecisionTable(
                    UUID.randomUUID(),
                    versionId,
                    key,
                    "<dmn/>",
                    spec.hitPolicy(),
                    new byte[32],
                    "",
                    DmnSpecs.rulesJson(spec),
                    "{}")));
    return out;
  }

  private static PolicyRule rule(UUID versionId, String key, String expression, String params) {
    return new PolicyRule(
        UUID.randomUUID(), versionId, key, expression, "DOUBLE", "{}", "PL 3", 10, params, true);
  }

  private GoldenTestCase golden(
      UUID packId, String code, Map<String, Object> input, GoldenCaseStatus status)
      throws Exception {
    return new GoldenTestCase(
        UUID.randomUUID(),
        packId,
        code,
        "Ca " + code,
        JSON.writeValueAsString(input),
        "{}",
        "",
        GoldenCaseSource.BA,
        status,
        UUID.randomUUID(),
        Instant.now(),
        new byte[] {1},
        author,
        1);
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    parent =
        new PolicyPack(
            UUID.randomUUID(),
            "goc",
            "Gói gốc",
            "KHOI_DANG",
            "DANG",
            PackOrigin.GOC_TOAN_CUC,
            null,
            "QĐ",
            null,
            null,
            0);
    pack =
        new PolicyPack(
            UUID.randomUUID(),
            PACK,
            "Gói",
            "KHOI_DANG",
            "DANG",
            PackOrigin.GOC_TOAN_CUC,
            parent.id(),
            "QĐ",
            null,
            null,
            0);
    when(packs.findPack(tenant, pack.id())).thenReturn(Optional.of(pack));
    when(packs.findVersion(any(), any(), anyString())).thenReturn(Optional.empty());

    Map<String, Object> manifest = PolicyEvaluatorTest.manifest(PACK);
    base = version("2.0.0", manifest);
    Map<String, DecisionTableSpec> specs =
        DmnSpecs.of(PolicyEvaluatorTest.DATA.resolve(PACK), manifest);
    tables.put(base.id(), tables(base.id(), specs));
    rules.put(
        base.id(),
        List.of(rule(base.id(), "kpi_rieng", "1.0", "{}"), rule(base.id(), "bo_di", "2.0", "{}")));

    Map<String, Object> changed = JSON.readValue(JSON.writeValueAsString(manifest), Map.class);
    Map<String, Object> params = (Map<String, Object>) changed.get("params");
    ((Map<String, Object>) params.get("tru_tien_do")).put("mac_dinh", 0.1);
    changed.put("workflow_bindings", Map.of("thu_muc", "luong-moi/"));
    changed.put("ghi_chu_moi", Map.of("noi_dung", "Bổ sung"));
    target = version("2.1.0", changed);
    Map<String, DecisionTableSpec> targetSpecs = new LinkedHashMap<>(specs);
    String first = targetSpecs.keySet().iterator().next();
    DecisionTableSpec spec = targetSpecs.get(first);
    List<DecisionTableSpec.Row> rows = new ArrayList<>(spec.rows());
    DecisionTableSpec.Row r0 = rows.get(0);
    rows.set(
        0,
        new DecisionTableSpec.Row(
            r0.inputs(), r0.ratingLevel(), r0.clause(), r0.explanation() + " (sửa)"));
    rows.add(rows.get(rows.size() - 1));
    targetSpecs.put(first, new DecisionTableSpec(spec.hitPolicy(), spec.columns(), rows));
    targetSpecs.put("bang_moi", new DecisionTableSpec("FIRST", spec.columns(), spec.rows()));
    String removed = new ArrayList<>(specs.keySet()).get(specs.size() - 1);
    targetSpecs.remove(removed);
    tables.put(target.id(), tables(target.id(), targetSpecs));
    rules.put(
        target.id(),
        List.of(
            rule(target.id(), "kpi_rieng", "1.0 + 0.0", "{\"loai_ngay\":\"NGAY_LICH\"}"),
            rule(target.id(), "them_vao", "3.0", "{}")));

    when(packs.decisionTables(any(), any()))
        .thenAnswer(a -> tables.getOrDefault(a.<UUID>getArgument(1), List.of()));
    when(packs.rules(any(), any()))
        .thenAnswer(a -> rules.getOrDefault(a.<UUID>getArgument(1), List.of()));
    when(cases.findCaseByCode(any(), any(), anyString()))
        .thenAnswer(
            a -> Optional.ofNullable(golden.get(a.<UUID>getArgument(1) + "/" + a.getArgument(2))));

    PolicyDecisionTableService dt =
        new PolicyDecisionTableService(
            packs, mock(PolicyDecisionTableRepository.class), mock(PolicyAudit.class), JSON);
    evaluators = new PolicyEvaluators(packs, dt, JSON);
    service = new PolicyPreviewService(packs, cases, dt, evaluators, JSON);
  }

  private List<Change> of(VersionDiff d, Kind kind) {
    return d.changes().stream().filter(c -> c.kind() == kind).toList();
  }

  @Test
  void diffListsAddedRemovedAndChangedParts() {
    VersionDiff d = service.diff(tenant, pack.id(), "2.1.0", "2.0.0");
    assertThat(d.base()).isEqualTo("2.0.0");
    assertThat(d.target()).isEqualTo("2.1.0");

    assertThat(of(d, Kind.PARAM))
        .anySatisfy(
            c -> {
              assertThat(c.key()).isEqualTo("tru_tien_do");
              assertThat(c.path()).isEqualTo("mac_dinh");
              assertThat(c.change()).isEqualTo(ChangeType.SUA);
              assertThat(c.before()).isEqualTo(0.05);
              assertThat(c.after()).isEqualTo(0.1);
            })
        .anySatisfy(
            c -> {
              assertThat(c.key()).isEqualTo("kpi_rieng");
              assertThat(c.path()).isEqualTo("params.loai_ngay");
              assertThat(c.change()).isEqualTo(ChangeType.THEM);
            });
    assertThat(of(d, Kind.WORKFLOW_BINDING))
        .singleElement()
        .satisfies(c -> assertThat(c.after()).isEqualTo("luong-moi/"));
    assertThat(of(d, Kind.MANIFEST))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.key()).isEqualTo("ghi_chu_moi");
              assertThat(c.change()).isEqualTo(ChangeType.THEM);
            });
    assertThat(of(d, Kind.RULE))
        .extracting(Change::key, Change::change)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("kpi_rieng", ChangeType.SUA),
            org.assertj.core.groups.Tuple.tuple("bo_di", ChangeType.XOA),
            org.assertj.core.groups.Tuple.tuple("them_vao", ChangeType.THEM));
    List<Change> tableChanges = of(d, Kind.DECISION_TABLE);
    assertThat(tableChanges)
        .anySatisfy(
            c -> {
              assertThat(c.path()).isEqualTo("rules[0].explanation");
              assertThat(c.change()).isEqualTo(ChangeType.SUA);
              assertThat(String.valueOf(c.after())).endsWith("(sửa)");
            })
        .anySatisfy(
            c -> {
              assertThat(c.path()).startsWith("rules[");
              assertThat(c.change()).isEqualTo(ChangeType.THEM);
            })
        .anySatisfy(
            c -> {
              assertThat(c.key()).isEqualTo("bang_moi");
              assertThat(c.change()).isEqualTo(ChangeType.THEM);
              assertThat(c.before()).isNull();
            })
        .anySatisfy(c -> assertThat(c.change()).isEqualTo(ChangeType.XOA));

    assertThat(service.diff(tenant, pack.id(), "2.0.0", "2.0.0").changes()).isEmpty();
    assertThatThrownBy(() -> service.diff(tenant, pack.id(), "2.1.0", "9.9.9"))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> service.diff(tenant, UUID.randomUUID(), "2.1.0", "2.0.0"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void evaluateMatchesGoldenSuiteAndUsesWarmCache() throws Exception {
    rules.put(base.id(), List.of());
    GoldenSuiteYaml.Suite suite = PolicyEvaluatorTest.suite(PACK);
    int checked = 0;
    for (GoldenSuiteYaml.Entry e : suite.entries()) {
      golden.put(
          pack.id() + "/" + e.caseCode(),
          golden(pack.id(), e.caseCode(), e.input(), GoldenCaseStatus.DA_DUYET));
      Evaluation r = service.evaluateCase(tenant, pack.id(), "2.0.0", e.caseCode());
      assertThat(r.caseCode()).isEqualTo(e.caseCode());
      for (Map.Entry<String, Object> x : e.expected().entrySet()) {
        if (ResultComparison.IGNORED.contains(x.getKey())) {
          continue;
        }
        assertThat(ResultComparison.same(x.getValue(), r.outputs().get(x.getKey()), 2))
            .as(e.caseCode() + " " + x.getKey())
            .isTrue();
      }
      checked++;
    }
    assertThat(checked).isEqualTo(suite.entries().size());
    assertThat(evaluators.compiledCount()).isEqualTo(1);

    Evaluation q = service.evaluateCase(tenant, pack.id(), "2.0.0", "Q-001");
    assertThat(q.trace()).isNotEmpty();
    assertThat(q.trace()).anySatisfy(t -> assertThat(t.ruleKey()).endsWith("kpi_nhiem_vu"));
    assertThat(q.ratingLevel()).isIn(DecisionTableSpec.LEVELS);
    assertThat(q.explanation()).isNotBlank().contains("2.0.0").doesNotContain("{");
    assertThat(q.outputs()).containsKey("nhiem_vu");
    assertThat((List<?>) q.outputs().get("nhiem_vu"))
        .allSatisfy(
            t ->
                assertThat(
                        ((Map<?, ?>) t).containsKey("ma")
                            && ((Map<?, ?>) t).containsKey("kpi_nhiem_vu"))
                    .isTrue());
    // Gói mẫu đặt khoá kpi_A, kpi_B, kpi_C; phần xem trước trả theo tên A, B, C của hợp đồng.
    assertThat((List<?>) q.outputs().get("nhiem_vu"))
        .allSatisfy(
            t -> assertThat(((Map<?, ?>) t).keySet().containsAll(List.of("A", "B", "C"))).isTrue());

    // Đo p95 để sau (YC-PT-19, wiki 14); ở đây chỉ kiểm đệm nóng không biên dịch lại.
    for (int i = 0; i < 3; i++) {
      service.evaluateCase(tenant, pack.id(), "2.0.0", "Q-001");
    }
    assertThat(evaluators.compiledCount()).isEqualTo(1);

    // Phiên bản nháp sửa quy tắc thì biên dịch lại.
    rules.put(base.id(), List.of(rule(base.id(), "kpi_rieng", "1.0", "{}")));
    service.evaluateCase(tenant, pack.id(), "2.0.0", "Q-001");
    assertThat(evaluators.compiledCount()).isEqualTo(2);
  }

  @Test
  void evaluateRejectsCasesOutsideGoldenSuite() throws Exception {
    GoldenSuiteYaml.Entry e = PolicyEvaluatorTest.suite(PACK).entries().get(0);
    rules.put(base.id(), List.of());
    golden.put(
        parent.id() + "/P-1", golden(parent.id(), "P-1", e.input(), GoldenCaseStatus.DA_DUYET));
    assertThat(service.evaluateCase(tenant, pack.id(), "2.0.0", "P-1").caseCode()).isEqualTo("P-1");

    golden.put(pack.id() + "/C-1", golden(pack.id(), "C-1", e.input(), GoldenCaseStatus.NGUNG));
    assertThatThrownBy(() -> service.evaluateCase(tenant, pack.id(), "2.0.0", "C-1"))
        .isInstanceOf(BusinessException.class)
        .hasMessage("POLICY-422-CASE-NOT-GOLDEN");
    assertThatThrownBy(() -> service.evaluateCase(tenant, pack.id(), "2.0.0", "KHONG-CO"))
        .hasMessage("POLICY-422-CASE-NOT-GOLDEN");
    assertThatThrownBy(() -> service.evaluateCase(tenant, pack.id(), "9.0.0", "C-1"))
        .isInstanceOf(NoSuchElementException.class);

    golden.put(
        pack.id() + "/C-2",
        golden(pack.id(), "C-2", Map.of("truong_hop", "KHONG_CO"), GoldenCaseStatus.NHAP));
    assertThatThrownBy(() -> service.evaluateCase(tenant, pack.id(), "2.0.0", "C-2"))
        .hasMessage("POLICY-422-CEL-TYPE");

    PolicyPack orphan =
        new PolicyPack(
            UUID.randomUUID(),
            "le",
            "Gói lẻ",
            "KHOI_DANG",
            "DANG",
            PackOrigin.GOC_TOAN_CUC,
            null,
            "QĐ",
            null,
            null,
            0);
    when(packs.findPack(tenant, orphan.id())).thenReturn(Optional.of(orphan));
    when(packs.findVersion(eq(tenant), eq(orphan.id()), eq("2.0.0"))).thenReturn(Optional.of(base));
    assertThatThrownBy(() -> service.evaluateCase(tenant, orphan.id(), "2.0.0", "P-1"))
        .hasMessage("POLICY-422-CASE-NOT-GOLDEN");
  }

  @Test
  void explanationTemplatesAreRendered() {
    assertThat(
            PolicyPreviewService.render(
                "Tỷ lệ vượt {ty_le_vuot} dưới {nguong}, {la}",
                Map.of("ty_le_vuot", 0.225, "la", "x")))
        .isEqualTo("Tỷ lệ vượt 0,23 dưới {nguong}, x");
    assertThat(PolicyPreviewService.format(91)).isEqualTo("91");
    assertThat(PolicyPreviewService.format(91.20)).isEqualTo("91,2");
    assertThat(PolicyPreviewService.format(true)).isEqualTo("true");
    PolicyEvaluator.Outcome none =
        new PolicyEvaluator.Outcome(
            Map.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    assertThat(PolicyPreviewService.explanation(none, null, Map.of())).isNull();
    assertThat(PolicyPreviewService.explanation(none, "HTNV", Map.of())).isNull();
  }
}
