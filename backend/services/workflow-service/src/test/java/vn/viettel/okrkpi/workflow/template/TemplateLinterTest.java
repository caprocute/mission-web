package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import vn.viettel.okrkpi.workflow.infrastructure.template.BpmnTemplateLinter;
import vn.viettel.okrkpi.workflow.infrastructure.template.LintRule;
import vn.viettel.okrkpi.workflow.infrastructure.template.LintViolation;
import vn.viettel.okrkpi.workflow.infrastructure.template.RuntimeSnapshot;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateCatalog;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateDeployer;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateSource;

/**
 * Linter "chống mạng nhện" (TL-01 G2, tám quy tắc) chạy trong {@code mvn verify}: ba mẫu thật đạt
 * cả tám quy tắc (kể cả lớp kiểm thử khai báo có thật), và linter đỏ trên fixture vi phạm từng quy
 * tắc ({@code src/test/resources/linter-fixtures/}).
 */
class TemplateLinterTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final BpmnTemplateLinter LINTER =
      new BpmnTemplateLinter(TemplateDeployer.PURE_DELEGATES, TemplateLinterTest::testClassExists);

  static boolean testClassExists(String name) {
    try {
      Class.forName(name, false, TemplateLinterTest.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  static TemplateCatalog fixture(String folder) {
    return TemplateCatalog.load(
        JSON, "classpath*:linter-fixtures/" + folder + "/*.bpmn20.xml", TemplateCatalog.DECISIONS);
  }

  @Test
  void baMauThatDatTamQuyTac() {
    TemplateCatalog catalog = TemplateCatalog.load(JSON);
    assertThat(catalog.templates())
        .extracting(t -> t.descriptor().templateCode())
        .containsExactlyInAnyOrder("duyet-okr", "duyet-ke-hoach-quy", "duyet-de-nghi");
    assertThat(catalog.decisionKeys()).contains("chon-cap-duyet");
    List<LintViolation> violations =
        LINTER.lint(catalog.templates(), catalog.decisionKeys(), RuntimeSnapshot.EMPTY);
    assertThat(violations).isEmpty();
  }

  @Test
  void fixtureHopLeDat() {
    TemplateCatalog catalog = fixture("hop-le");
    assertThat(LINTER.lint(catalog.templates(), catalog.decisionKeys(), RuntimeSnapshot.EMPTY))
        .isEmpty();
  }

  static Stream<Arguments> fixtures() {
    return Stream.of(
        Arguments.of("r1", LintRule.R1_KHONG_GAN_TENANT, "mau-r1"),
        Arguments.of("r2", LintRule.R2_MOT_TANG_CALL_ACTIVITY, "mau-a"),
        Arguments.of("r3", LintRule.R3_KHONG_CHEO_TENANT, "mau-r3"),
        Arguments.of("r4", LintRule.R4_KHONG_GOI_DONG_BO, "mau-r4"),
        Arguments.of("r5", LintRule.R5_BIEN_DUOI_4KB, "mau-r5"),
        Arguments.of("r6", LintRule.R6_SONG_SONG_TOI_DA_20, "mau-r6"),
        Arguments.of("r7", LintRule.R7_KIEM_THU_MO_TA_CHU_SO_HUU, "mau-r7"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("fixtures")
  void linterDoTrenFixtureViPham(String folder, LintRule rule, String template) {
    TemplateCatalog catalog = fixture(folder);
    List<LintViolation> violations =
        LINTER.lint(catalog.templates(), catalog.decisionKeys(), RuntimeSnapshot.EMPTY);
    assertThat(violations)
        .as("Fixture %s phải vi phạm đúng quy tắc %s", folder, rule)
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.rule()).isEqualTo(rule))
        .anySatisfy(v -> assertThat(v.template()).isEqualTo(template));
  }

  @Test
  void r1TrienKhaiGanTenantBiChan() {
    TemplateSource valid = fixture("hop-le").templates().get(0);
    TemplateSource bound =
        new TemplateSource(valid.resourceName(), valid.bpmnXml(), valid.descriptor(), "tenant-hcm");
    assertThat(LINTER.lint(List.of(bound), Set.of(), RuntimeSnapshot.EMPTY))
        .extracting(LintViolation::rule)
        .containsExactly(LintRule.R1_KHONG_GAN_TENANT);
  }

  @Test
  void r8KhongPhatHanhPhienBanThuTuKhiConBaPhienBanDangChay() {
    TemplateCatalog catalog = fixture("hop-le");
    RuntimeSnapshot threeRunning =
        new RuntimeSnapshot(Map.of("mau-hop-le", Set.of(1, 2, 3)), Set.of("mau-hop-le"));
    assertThat(LINTER.lint(catalog.templates(), catalog.decisionKeys(), threeRunning))
        .extracting(LintViolation::rule)
        .containsExactly(LintRule.R8_TOI_DA_3_PHIEN_BAN);
    // Không đổi nội dung (không sinh phiên bản mới) thì vẫn đạt.
    RuntimeSnapshot unchanged = new RuntimeSnapshot(Map.of("mau-hop-le", Set.of(1, 2, 3)), Set.of());
    assertThat(LINTER.lint(catalog.templates(), catalog.decisionKeys(), unchanged)).isEmpty();
  }

  @Test
  void bpmnCoDoctypeBiTuChoi() {
    TemplateSource xxe =
        new TemplateSource(
            "xxe.bpmn20.xml",
            "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">&x;</definitions>",
            null,
            null);
    assertThat(LINTER.lint(List.of(xxe), Set.of(), RuntimeSnapshot.EMPTY)).isNotEmpty();
  }

  @Test
  void trienKhaiLaiKhongSinhPhienBanMoiVaLinterDoChanTrienKhai() {
    StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
    cfg.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    cfg.setDisableIdmEngine(true);
    cfg.setDisableEventRegistry(true);
    cfg.addConfigurator(new org.flowable.dmn.engine.configurator.DmnEngineConfigurator());
    ProcessEngine engine = cfg.buildProcessEngine();
    try {
      TemplateDeployer deployer =
          new TemplateDeployer(engine.getRepositoryService(), engine.getRuntimeService(), JSON);
      deployer.deploy(TemplateCatalog.load(JSON));
      deployer.deploy(TemplateCatalog.load(JSON));
      assertThat(engine.getRepositoryService().createProcessDefinitionQuery().count()).isEqualTo(3);
      assertThat(
              engine.getRepositoryService().createProcessDefinitionQuery()
                  .processDefinitionWithoutTenantId().count())
          .isEqualTo(3);
      assertThatThrownBy(() -> deployer.deploy(fixture("r4")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("R4_KHONG_GOI_DONG_BO");
      assertThat(engine.getRepositoryService().createProcessDefinitionQuery().count()).isEqualTo(3);
    } finally {
      engine.close();
    }
  }
}
