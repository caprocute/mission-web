package vn.viettel.okrkpi.workflow.infrastructure.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.viettel.okrkpi.workflow.application.binding.CelSkipConditions;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;
import vn.viettel.okrkpi.workflow.domain.binding.ParamsLinter;
import vn.viettel.okrkpi.workflow.domain.binding.SampleRun;
import vn.viettel.okrkpi.workflow.domain.binding.SimulationSample;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateInfo;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateParamRules;
import vn.viettel.okrkpi.workflow.infrastructure.template.CatalogTemplateDirectory;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateCatalog;

/** Engine thử chạy đúng ba mẫu thật với tham số tuỳ chỉnh, kể cả cấp bị bỏ qua. */
class SandboxBindingSimulatorTest {

  static final ObjectMapper JSON = new ObjectMapper();
  static SandboxBindingSimulator simulator;
  static CatalogTemplateDirectory directory;

  @BeforeAll
  static void setUp() {
    TemplateCatalog catalog = TemplateCatalog.load(JSON);
    simulator = new SandboxBindingSimulator(JSON, catalog);
    directory = new CatalogTemplateDirectory(catalog);
  }

  @AfterAll
  static void tearDown() {
    simulator.close();
  }

  static Map<String, Object> threeLevels() {
    List<Map<String, Object>> cap = new ArrayList<>();
    cap.add(new HashMap<>(Map.of("vai_tro", "manager", "pham_vi", "DON_VI_TRUC_TIEP")));
    cap.add(new HashMap<>(Map.of("vai_tro", "leader", "pham_vi", "CAP_TREN_TRUC_TIEP")));
    cap.add(
        new HashMap<>(
            Map.of("vai_tro", "leader", "pham_vi", "CAP_UY_CO_THAM_QUYEN", "ky_so", true)));
    Map<String, Object> p = new HashMap<>();
    p.put("cap", cap);
    p.put("thoi_han_moi_buoc", "P3D_LV");
    return p;
  }

  @Test
  void dungSanEngineKhiSanSangNenLanMoPhongDauKhongPhaiCho() {
    SandboxBindingSimulator warm = new SandboxBindingSimulator(JSON, TemplateCatalog.load(JSON));
    try {
      warm.warmUp();
      TemplateInfo info = directory.find("duyet-okr", 1).orElseThrow();
      long start = System.nanoTime();
      SampleRun run = warm.run(info, threeLevels(), SimulationSample.values()[0], List.of());
      long ms = (System.nanoTime() - start) / 1_000_000;
      assertThat(run.passed()).isTrue();
      // Hạn đọc REST nội bộ của mission-core là 3 giây; engine đã dựng thì một lần chạy nhanh hơn
      // nhiều.
      assertThat(ms).isLessThan(3_000);
    } finally {
      warm.close();
    }
  }

  @ParameterizedTest
  @EnumSource(ApprovalTemplate.class)
  void moiHoSoMauDatVoiBaCap(ApprovalTemplate template) {
    TemplateInfo info = directory.find(template.code(), 1).orElseThrow();
    for (SimulationSample sample : SimulationSample.values()) {
      SampleRun run = simulator.run(info, threeLevels(), sample, List.of());
      assertThat(run.passed()).as("%s %s", template, sample).isTrue();
      assertThat(run.endState()).isEqualTo(sample.expectedEnd());
    }
  }

  @Test
  void capBiBoQuaKhongSinhViec() {
    TemplateInfo base = directory.find("duyet-okr", 1).orElseThrow();
    TemplateInfo skippable =
        new TemplateInfo(
            base.key(),
            base.name(),
            base.versionNo(),
            base.processKind(),
            new TemplateParamRules(2, 4, Set.of(), Set.of(TemplateParamRules.MIDDLE), List.of()));
    Map<String, Object> p = threeLevels();
    @SuppressWarnings("unchecked")
    Map<String, Object> middle = ((List<Map<String, Object>>) p.get("cap")).get(1);
    middle.put("buoc", "DE_XUAT");
    middle.put("bo_qua_khi", "nguoi_duoc_danh_gia.chuc_danh == 'CHUYEN_VIEN'");
    ParamsLinter linter = new ParamsLinter(new CelSkipConditions());
    assertThat(linter.lint(p, skippable.rules()).passed()).isTrue();

    SimulationSample sample = SimulationSample.CHUYEN_VIEN_MUC_KHOP;
    SampleRun run =
        simulator.run(skippable, p, sample, linter.skippedOrders(p, sample.variables()));
    assertThat(run.skippedSteps()).containsExactly("DE_XUAT");
    assertThat(run.passed()).isTrue();
    assertThat(run.endState()).isEqualTo(SimulationSample.DA_DUYET);
  }

  @Test
  void phamViKhongPhanGiaiDuocThiKhongDat() {
    TemplateInfo info = directory.find("duyet-okr", 1).orElseThrow();
    Map<String, Object> p = threeLevels();
    @SuppressWarnings("unchecked")
    Map<String, Object> first = ((List<Map<String, Object>>) p.get("cap")).get(0);
    first.put("vai_tro", "KHONG HOP LE");
    SampleRun run = simulator.run(info, p, SimulationSample.DANG_DUYET_DU_CAP, List.of());
    assertThat(run.passed()).isFalse();
  }

  @Test
  void danhMucMauCoKhaiBaoThamSo() {
    assertThat(directory.published())
        .extracting(TemplateInfo::key, TemplateInfo::processKind)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("duyet-de-nghi", "DE_NGHI"),
            org.assertj.core.groups.Tuple.tuple("duyet-ke-hoach-quy", "KE_HOACH_QUY"),
            org.assertj.core.groups.Tuple.tuple("duyet-okr", "OKR"));
    TemplateInfo plan = directory.find("duyet-ke-hoach-quy", 1).orElseThrow();
    assertThat(plan.name()).isEqualTo("Duyệt kế hoạch quý");
    assertThat(plan.rules().mustSign(3, 3, "CAP_3")).isTrue();
    assertThat(ParamsLinter.defaultSteps(plan.rules())).hasSize(2);
  }
}
