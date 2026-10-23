package vn.viettel.okrkpi.workflow.infrastructure.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.domain.binding.SimulationSample;
import vn.viettel.okrkpi.workflow.domain.template.BindingUsage;
import vn.viettel.okrkpi.workflow.domain.template.TestCaseResult;
import vn.viettel.okrkpi.workflow.domain.template.TestReport;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateCatalog;

/**
 * Engine thử chạy mẫu soạn trên SCR-1505 (DV-06-09): đếm câu SQL mỗi lần hoàn thành user task, mẫu
 * nhiều hẹn giờ biên vượt ngân sách 20 câu, mẫu duyệt soạn lại chạy đủ kịch bản với tham số của
 * từng wf_binding và gỡ lần triển khai sau khi chạy.
 */
class SandboxTemplateTestEngineTest {

  static final ObjectMapper JSON = new ObjectMapper();
  static SandboxBindingSimulator engine;

  @BeforeAll
  static void setUp() {
    engine = new SandboxBindingSimulator(JSON, TemplateCatalog.load(JSON));
  }

  @AfterAll
  static void tearDown() {
    engine.close();
  }

  static String fixture(String name, String code) throws IOException {
    try (InputStream in =
        SandboxTemplateTestEngineTest.class.getResourceAsStream("/mau-soan/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("__MA__", code);
    }
  }

  @Test
  void mauDonGianDatVaTrongNganSach() throws IOException {
    List<TestCaseResult> cases =
        engine.run("mau-don-gian", fixture("don-gian.bpmn20.xml", "mau-don-gian"), List.of());
    assertThat(cases).hasSize(1);
    TestCaseResult c = cases.get(0);
    assertThat(c.passed()).isTrue();
    assertThat(c.completions()).isEqualTo(2);
    assertThat(c.maxSqlPerCompletion()).isPositive().isLessThanOrEqualTo(20);
    System.out.println("Câu SQL mỗi lần hoàn thành (mẫu đơn giản): " + c.maxSqlPerCompletion());
    assertThat(new TestReport(cases, 0, TestReport.DEFAULT_SQL_BUDGET).passed()).isTrue();
  }

  @Test
  void mauNhieuHenGioBienVuotNganSach() throws IOException {
    List<TestCaseResult> cases =
        engine.run(
            "mau-nhieu-hen-gio",
            fixture("nhieu-hen-gio.bpmn20.xml", "mau-nhieu-hen-gio"),
            List.of());
    assertThat(cases).hasSize(1);
    System.out.println(
        "Câu SQL mỗi lần hoàn thành (25 hẹn giờ biên): " + cases.get(0).maxSqlPerCompletion());
    assertThat(cases.get(0).maxSqlPerCompletion()).isGreaterThan(20);
    TestReport report = new TestReport(cases, 0, TestReport.DEFAULT_SQL_BUDGET);
    assertThat(report.withinBudget()).isFalse();
    assertThat(report.passed()).isFalse();
  }

  @Test
  void mauDuyetSoanLaiChayMoiKichBanVoiThamSoCuaBinding() throws IOException {
    String xml;
    try (InputStream in = getClass().getResourceAsStream("/processes/duyet-okr.bpmn20.xml")) {
      xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    BindingUsage binding =
        new BindingUsage(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "OKR",
            "DANG",
            SandboxBindingSimulatorTest.threeLevels());
    List<TestCaseResult> cases = engine.run("duyet-okr", xml, List.of(binding));
    long dang =
        java.util.Arrays.stream(SimulationSample.values())
            .filter(s -> "DANG".equals(s.flow()))
            .count();
    assertThat(cases).hasSize((int) dang);
    assertThat(cases).allMatch(TestCaseResult::passed);
    assertThat(cases).allMatch(c -> c.paramsLabel().equals(binding.label()));
    System.out.println(
        "Câu SQL mỗi lần hoàn thành (duyet-okr): "
            + cases.stream().mapToInt(TestCaseResult::maxSqlPerCompletion).max().orElse(0));
    // Không còn lần triển khai mẫu soạn trong engine thử: danh mục đóng gói vẫn là bản mới nhất.
    List<TestCaseResult> again = engine.run("duyet-okr", xml, List.of());
    assertThat(again).allMatch(TestCaseResult::passed);
  }

  @Test
  void mauKhongKhoiDongDuocThiKhongDat() {
    String broken =
        "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " targetNamespace=\"t\"><process id=\"mau-khac\" isExecutable=\"true\">"
            + "<startEvent id=\"s\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/>"
            + "<endEvent id=\"e\"/></process></definitions>";
    List<TestCaseResult> cases = engine.run("mau-ten-khac", broken, List.of());
    assertThat(cases).hasSize(1);
    assertThat(cases.get(0).passed()).isFalse();
    assertThat(Map.of("end", cases.get(0).endState())).containsEntry("end", "KHONG_KHOI_DONG_DUOC");
  }
}
