package vn.viettel.okrkpi.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.viettel.okrkpi.analytics.application.Fakes.KY_Q4;
import static vn.viettel.okrkpi.analytics.application.Fakes.TENANT;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_HUYEN;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_KHAC;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_TINH;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.analytics.application.CoreDetailPort.Lookup;
import vn.viettel.okrkpi.analytics.domain.TraceChain;
import vn.viettel.okrkpi.analytics.domain.TraceLink;
import vn.viettel.okrkpi.analytics.domain.TraceObjectType;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.shared.error.BusinessException;

/** DV-15-06: mắt xích bị mission-core từ chối được che, chuỗi không đứt (BR-16-04). */
class TraceServiceTest {

  static final UUID SCORE = UUID.randomUUID();
  static final UUID PROFILE = UUID.randomUUID();
  static final UUID TASK = UUID.randomUUID();
  static final UUID KR = UUID.randomUUID();
  static final UUID OBJECTIVE = UUID.randomUUID();
  static final UUID PRODUCT = UUID.randomUUID();
  static final UUID EVIDENCE = UUID.randomUUID();

  final Map<String, Lookup<?>> answers = new HashMap<>();
  UUID scoreUnit = UNIT_HUYEN;

  final TraceFactsPort facts =
      new TraceFactsPort() {
        @Override
        public Optional<Score> score(UUID tenant, UUID scoreId) {
          return scoreId.equals(SCORE) ? Optional.of(sampleScore()) : Optional.empty();
        }

        @Override
        public Optional<Score> scoreOfTaskOwner(UUID tenant, UUID taskId) {
          return Optional.of(sampleScore());
        }

        @Override
        public List<Task> tasksOfProfile(UUID tenant, UUID profileId, int limit) {
          return List.of(sampleTask());
        }

        @Override
        public Optional<Task> task(UUID tenant, UUID taskId) {
          return taskId.equals(TASK) ? Optional.of(sampleTask()) : Optional.empty();
        }

        @Override
        public List<EvidenceRef> evidencesOfTask(UUID tenant, UUID taskId, int limit) {
          return List.of(new EvidenceRef(EVIDENCE, PRODUCT));
        }

        @Override
        public Optional<UUID> objectiveOfKeyResult(UUID tenant, UUID keyResultId) {
          return Optional.of(OBJECTIVE);
        }
      };

  final CoreDetailPort core =
      new CoreDetailPort() {
        @SuppressWarnings("unchecked")
        <T> Lookup<T> answer(String key) {
          return (Lookup<T>) answers.getOrDefault(key, new Lookup.Missing<>());
        }

        @Override
        public Lookup<Task> task(String bearer, UUID id) {
          return answer("task");
        }

        @Override
        public Lookup<List<Deliverable>> deliverables(String bearer, UUID taskId) {
          return answer("deliverables");
        }

        @Override
        public Lookup<Deliverable> deliverable(String bearer, UUID id) {
          return answer("deliverable");
        }

        @Override
        public Lookup<Evidence> evidence(String bearer, UUID id) {
          return answer("evidence");
        }

        @Override
        public Lookup<List<Plan>> plans(String bearer, UUID periodId, UUID unitId) {
          return answer("plans");
        }

        @Override
        public Lookup<KeyResult> keyResult(String bearer, UUID id) {
          return answer("kr");
        }

        @Override
        public Lookup<Objective> objective(String bearer, UUID id) {
          return answer("objective");
        }
      };

  final TraceService service =
      new TraceService(new ScopeService(new Fakes.Org()), new Fakes.Org(), facts, core);
  final Viewer leader = new Viewer(TENANT, UUID.randomUUID(), UNIT_TINH);

  TraceFactsPort.Score sampleScore() {
    LinkedHashMap<String, BigDecimal> parts = new LinkedHashMap<>();
    parts.put("THUONG_XUYEN", new BigDecimal("20.500"));
    parts.put("THEN_CHOT", new BigDecimal("63.600"));
    return new TraceFactsPort.Score(
        SCORE, PROFILE, KY_Q4, "quý IV", scoreUnit, new BigDecimal("91.200"), parts, null);
  }

  TraceFactsPort.Task sampleTask() {
    return new TraceFactsPort.Task(
        TASK, KY_Q4, UNIT_HUYEN, "THEN_CHOT", true, KR, "DANG_THUC_HIEN", null);
  }

  List<String> types(TraceChain c) {
    return c.links().stream().map(l -> l.objectType() + (l.masked() ? "*" : "")).toList();
  }

  @Test
  void deniedTaskKeyResultAndObjectiveAreMaskedButChainContinues() {
    answers.put("task", new Lookup.Denied<>());
    answers.put("kr", new Lookup.Denied<>());
    answers.put("objective", new Lookup.Denied<>());
    answers.put(
        "evidence",
        new Lookup.Found<>(
            new CoreDetailPort.Evidence(
                EVIDENCE, PRODUCT, TASK, "Biên bản", "DA_NOP", 1, List.of())));
    answers.put(
        "deliverable",
        new Lookup.Found<>(
            new CoreDetailPort.Deliverable(PRODUCT, TASK, "Báo cáo", null, null, null, null)));
    TraceChain c =
        service.chain(leader, "Bearer x", TraceObjectType.SAN_PHAM, PRODUCT).orElseThrow();
    assertThat(types(c))
        .containsExactly(
            "DIEM",
            "CAU_PHAN",
            "KPI_NHIEM_VU*",
            "KET_QUA_THEN_CHOT*",
            "SAN_PHAM*",
            "MINH_CHUNG",
            "MUC_TIEU*");
    assertThat(c.links().get(5).msg()).isNull();
    assertThat(c.links().get(2).label()).isEqualTo("Nhiệm vụ");
    assertThat(c.links().get(2).msg()).isEqualTo(TraceLink.MSG_MASKED);
  }

  @Test
  void focusOutsideScopeOrDeniedIsRefusedAndUnknownIsEmpty() {
    scoreUnit = UNIT_KHAC;
    assertThatThrownBy(() -> service.chain(leader, "b", TraceObjectType.DIEM, SCORE))
        .isInstanceOf(BusinessException.class);
    scoreUnit = UNIT_HUYEN;
    assertThat(service.chain(leader, "b", TraceObjectType.DIEM, UUID.randomUUID())).isEmpty();
    answers.put("task", new Lookup.Denied<>());
    assertThatThrownBy(() -> service.chain(leader, "b", TraceObjectType.KPI_NHIEM_VU, TASK))
        .isInstanceOf(BusinessException.class);
    // Không có ở kho nhưng mission-core từ chối: vẫn là từ chối, không lộ là có hay không.
    assertThatThrownBy(
            () -> service.chain(leader, "b", TraceObjectType.KPI_NHIEM_VU, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    answers.clear();
    assertThat(service.chain(leader, "b", TraceObjectType.KPI_NHIEM_VU, UUID.randomUUID()))
        .isEmpty();
    assertThat(service.chain(leader, "b", TraceObjectType.MINH_CHUNG, EVIDENCE)).isEmpty();
    assertThat(service.chain(leader, "b", TraceObjectType.KET_QUA_THEN_CHOT, KR)).isEmpty();
    assertThat(service.chain(leader, "b", TraceObjectType.MUC_TIEU, OBJECTIVE)).isEmpty();
    answers.put("objective", new Lookup.Denied<>());
    assertThatThrownBy(() -> service.chain(leader, "b", TraceObjectType.MUC_TIEU, OBJECTIVE))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.chain(leader, "b", TraceObjectType.CAN_CU, OBJECTIVE))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void keyResultFocusLeadsToObjectiveAndBasis() {
    answers.put(
        "kr",
        new Lookup.Found<>(
            new CoreDetailPort.KeyResult(KR, OBJECTIVE, "KR1", "Hoàn thành", "DA_DUYET")));
    answers.put(
        "objective",
        new Lookup.Found<>(
            new CoreDetailPort.Objective(
                OBJECTIVE,
                "O1",
                "Mục tiêu",
                "DA_DUYET",
                "Huyện uỷ",
                List.of(new CoreDetailPort.Basis(UUID.randomUUID(), null, "Nghị quyết")))));
    TraceChain c = service.chain(leader, "b", TraceObjectType.KET_QUA_THEN_CHOT, KR).orElseThrow();
    assertThat(types(c)).containsExactly("KET_QUA_THEN_CHOT", "MUC_TIEU", "CAN_CU");
    assertThat(c.links().get(0).label()).isEqualTo("KR1 – Hoàn thành");
    assertThat(c.links().get(2).label()).isEqualTo("Nghị quyết");
  }

  @Test
  void numbersUseVietnameseDecimalComma() {
    assertThat(TraceService.number(new BigDecimal("91.200"))).isEqualTo("91,2");
    assertThat(TraceService.number(new BigDecimal("0.7834"))).isEqualTo("0,783");
    assertThat(TraceService.number(new BigDecimal("100.000"))).isEqualTo("100");
    assertThat(TraceService.number(null)).isNull();
    assertThat(TraceService.componentId(SCORE, "KPI"))
        .isEqualTo(TraceService.componentId(SCORE, "KPI"));
  }
}
