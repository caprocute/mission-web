package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.flowable.dmn.api.DmnDecisionService;
import org.flowable.dmn.engine.DmnEngine;
import org.flowable.dmn.engine.impl.cfg.StandaloneInMemDmnEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test case quyết định của bảng {@code chon-cap-duyet} (TL-01 G2 "Ví dụ DMN chọn cấp duyệt", chính
 * sách COLLECT) trên DMN engine thật: bốn quy tắc mẫu của TL-01 và năm quy tắc cho ba mẫu phần 1.
 */
class ChonCapDuyetDmnTest {

  private static DmnEngine engine;
  private static DmnDecisionService decisions;

  @BeforeAll
  static void deploy() {
    StandaloneInMemDmnEngineConfiguration cfg = new StandaloneInMemDmnEngineConfiguration();
    cfg.setJdbcUrl("jdbc:h2:mem:dmn-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    cfg.setDatabaseSchemaUpdate("true");
    engine = cfg.buildDmnEngine();
    engine.getDmnRepositoryService()
        .createDeployment()
        .addClasspathResource("dmn/chon-cap-duyet.dmn")
        .deploy();
    decisions = engine.getDmnDecisionService();
  }

  @AfterAll
  static void close() {
    engine.close();
  }

  static List<Map<String, Object>> evaluate(String khoi, String loai, boolean head) {
    return decisions
        .createExecuteDecisionBuilder()
        .decisionKey("chon-cap-duyet")
        .variables(Map.of("khoi", khoi, "loaiHoSo", loai, "laNguoiDungDau", head))
        .executeDecision();
  }

  static String level(Map<String, Object> row) {
    return ((Number) row.get("thuTu")).intValue()
        + ":"
        + row.get("role")
        + ":"
        + row.get("scope")
        + ":"
        + ((Number) row.get("hanNgayLamViec")).intValue();
  }

  static Stream<Arguments> cases() {
    return Stream.of(
        // r1 + r2: khối Đảng, hồ sơ quý, không phải người đứng đầu: hai cấp.
        Arguments.of(
            "DANG",
            "QUY",
            false,
            List.of("1:appraiser:DON_VI_TRUC_TIEP:3", "2:decider:CAP_UY_CO_THAM_QUYEN:5")),
        // r3: người đứng đầu do cấp trên trực tiếp quyết định.
        Arguments.of("DANG", "QUY", true, List.of("1:decider:CAP_TREN_TRUC_TIEP:5")),
        // r4: khối Nhà nước, quý hoặc năm, mọi người.
        Arguments.of("NHA_NUOC", "QUY", false, List.of("1:manager:QUAN_LY_TRUC_TIEP:3")),
        Arguments.of("NHA_NUOC", "NAM", true, List.of("1:manager:QUAN_LY_TRUC_TIEP:3")),
        // r5: OKR do leader cấp trên trực tiếp duyệt, mọi khối.
        Arguments.of("DANG", "OKR", false, List.of("1:leader:CAP_TREN_TRUC_TIEP:3")),
        Arguments.of("NHA_NUOC", "OKR", true, List.of("1:leader:CAP_TREN_TRUC_TIEP:3")),
        // r6, r7: kế hoạch quý.
        Arguments.of("NHA_NUOC", "KE_HOACH_QUY", false, List.of("1:leader:DON_VI_TRUC_TIEP:3")),
        Arguments.of("DANG", "KE_HOACH_QUY", true, List.of("1:leader:CAP_TREN_TRUC_TIEP:3")),
        // r8, r9: đề nghị vượt ngưỡng, 2 ngày làm việc.
        Arguments.of("DANG", "DE_NGHI", false, List.of("1:leader:DON_VI_TRUC_TIEP:2")),
        Arguments.of("NHA_NUOC", "DE_NGHI", true, List.of("1:leader:CAP_TREN_TRUC_TIEP:2")));
  }

  @ParameterizedTest(name = "{0} {1} nguoiDungDau={2}")
  @MethodSource("cases")
  void chonDungCacCap(String khoi, String loai, boolean head, List<String> expected) {
    assertThat(evaluate(khoi, loai, head).stream().map(ChonCapDuyetDmnTest::level))
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void khongTrungQuyTacNaoThiKhongCoCap() {
    // Khối Nhà nước không có quy tắc cho hồ sơ "THANG"; khối Đảng năm chưa khai.
    assertThat(evaluate("NHA_NUOC", "THANG", false)).isEmpty();
    assertThat(evaluate("DANG", "NAM", false)).isEmpty();
  }
}
