package vn.viettel.okrkpi.workflow.infrastructure.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.domain.template.TemplateLintFinding;

/**
 * Linter máy chủ của mẫu soạn trên SCR-1505: bốn quy tắc chống mạng nhện và giới hạn tải BS-01 (≤
 * 12 user task mỗi nhánh, ≤ 50 phần tử đa thể hiện, chỉ external worker, không signal phát rộng,
 * không định nghĩa gắn tenant).
 */
class AuthoredTemplateLinterTest {

  final AuthoredTemplateLinter linter = new AuthoredTemplateLinter();

  static String fixture(String name, String code) throws IOException {
    try (InputStream in =
        AuthoredTemplateLinterTest.class.getResourceAsStream("/mau-soan/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("__MA__", code);
    }
  }

  static String process(String code, String body) {
    return "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
        + " xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"t\">"
        + "<process id=\""
        + code
        + "\" isExecutable=\"true\">"
        + body
        + "</process></definitions>";
  }

  static List<String> rules(List<TemplateLintFinding> findings) {
    return findings.stream().map(TemplateLintFinding::rule).distinct().toList();
  }

  @Test
  void mauHopLeKhongViPham() throws IOException {
    assertThat(linter.lint("mau-a", fixture("don-gian.bpmn20.xml", "mau-a"))).isEmpty();
    assertThat(linter.lint("mau-b", fixture("nhieu-hen-gio.bpmn20.xml", "mau-b"))).isEmpty();
  }

  @Test
  void mauDongGoiSoanLaiVanDat() throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/processes/duyet-okr.bpmn20.xml")) {
      String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(linter.lint("duyet-okr", xml)).isEmpty();
    }
  }

  @Test
  void quaMuoiHaiUserTaskTrenMotNhanh() throws IOException {
    List<TemplateLintFinding> f =
        linter.lint("mau-c", fixture("qua-12-user-task.bpmn20.xml", "mau-c"));
    assertThat(rules(f)).containsExactly("R9_TOI_DA_12_USER_TASK_MOI_NHANH");
    assertThat(f.get(0).message()).contains("13");
  }

  @Test
  void nhanhSongSongDemRiengVaVongLapKhongDemLai() {
    StringBuilder body = new StringBuilder("<startEvent id=\"s\"/><parallelGateway id=\"g\"/>");
    body.append("<sequenceFlow id=\"f0\" sourceRef=\"s\" targetRef=\"g\"/>");
    for (int branch = 0; branch < 2; branch++) {
      String prev = "g";
      for (int i = 0; i < 8; i++) {
        String id = "t" + branch + i;
        body.append("<userTask id=\"").append(id).append("\"/>");
        body.append("<sequenceFlow id=\"f")
            .append(id)
            .append("\" sourceRef=\"")
            .append(prev)
            .append("\" targetRef=\"")
            .append(id)
            .append("\"/>");
        prev = id;
      }
      body.append("<sequenceFlow id=\"e")
          .append(branch)
          .append("\" sourceRef=\"")
          .append(prev)
          .append("\" targetRef=\"end\"/>");
    }
    // Cạnh quay lui từ cuối nhánh về đầu nhánh không làm nhánh dài thêm.
    body.append("<sequenceFlow id=\"back\" sourceRef=\"t07\" targetRef=\"t00\"/>");
    body.append("<endEvent id=\"end\"/>");
    assertThat(linter.lint("mau-d", process("mau-d", body.toString()))).isEmpty();
  }

  @Test
  void tienTrinhConTinhTheoNhanhDaiNhatBenTrong() {
    StringBuilder inner = new StringBuilder("<startEvent id=\"ss\"/>");
    String prev = "ss";
    for (int i = 0; i < 7; i++) {
      inner.append("<userTask id=\"u").append(i).append("\"/>");
      inner
          .append("<sequenceFlow id=\"fu")
          .append(i)
          .append("\" sourceRef=\"")
          .append(prev)
          .append("\" targetRef=\"u")
          .append(i)
          .append("\"/>");
      prev = "u" + i;
    }
    String body =
        "<startEvent id=\"s\"/><sequenceFlow id=\"a\" sourceRef=\"s\" targetRef=\"sub\"/>"
            + "<subProcess id=\"sub\">"
            + inner
            + "</subProcess>"
            + "<sequenceFlow id=\"b\" sourceRef=\"sub\" targetRef=\"sub2\"/>"
            + "<subProcess id=\"sub2\">"
            + inner
                .toString()
                .replace("\"u", "\"v")
                .replace("\"fu", "\"fv")
                .replace("\"ss\"", "\"ss2\"")
                .replace("sourceRef=\"ss2\"", "sourceRef=\"ss2\"")
            + "</subProcess>"
            + "<sequenceFlow id=\"c\" sourceRef=\"sub2\" targetRef=\"e\"/><endEvent id=\"e\"/>";
    assertThat(rules(linter.lint("mau-e", process("mau-e", body))))
        .containsExactly("R9_TOI_DA_12_USER_TASK_MOI_NHANH");
  }

  @Test
  void daTheHienQuaNamMuoiPhanTu() {
    String body =
        "<startEvent id=\"s\"/><sequenceFlow id=\"a\" sourceRef=\"s\" targetRef=\"u\"/>"
            + "<userTask id=\"u\"><multiInstanceLoopCharacteristics isSequential=\"true\">"
            + "<loopCardinality>60</loopCardinality></multiInstanceLoopCharacteristics></userTask>"
            + "<sequenceFlow id=\"b\" sourceRef=\"u\" targetRef=\"e\"/><endEvent id=\"e\"/>";
    assertThat(rules(linter.lint("mau-f", process("mau-f", body))))
        .containsExactly("R10_DA_THE_HIEN_TOI_DA_50");
    String parallel = body.replace("isSequential=\"true\"", "isSequential=\"false\"");
    assertThat(rules(linter.lint("mau-f", process("mau-f", parallel))))
        .contains("R6_SONG_SONG_TOI_DA_20", "R10_DA_THE_HIEN_TOI_DA_50");
  }

  @Test
  void chiExternalWorkerKhongSignalKhongGanTenant() {
    String body =
        "<startEvent id=\"s\"/><sequenceFlow id=\"a\" sourceRef=\"s\" targetRef=\"svc\"/>"
            + "<serviceTask id=\"svc\" flowable:class=\"vn.vd.GoiNgoai\"/>"
            + "<sequenceFlow id=\"b\" sourceRef=\"svc\" targetRef=\"sig\"/>"
            + "<intermediateThrowEvent id=\"sig\"><signalEventDefinition signalRef=\"x\"/>"
            + "</intermediateThrowEvent>"
            + "<sequenceFlow id=\"c\" sourceRef=\"sig\" targetRef=\"e\"/><endEvent id=\"e\"/>";
    String xml =
        process("mau-g", body)
            .replace("isExecutable=\"true\"", "isExecutable=\"true\" flowable:tenantId=\"t1\"");
    assertThat(rules(linter.lint("mau-g", xml)))
        .contains("R1_KHONG_GAN_TENANT", "R3_KHONG_CHEO_TENANT", "R4_KHONG_GOI_DONG_BO");
  }

  @Test
  void maProcessKhacMaMauVaDataObjectVaCallActivityDong() {
    String body =
        "<startEvent id=\"s\"/><dataObject id=\"d\"/>"
            + "<callActivity id=\"call\" calledElement=\"${tenMau}\"/>"
            + "<sequenceFlow id=\"a\" sourceRef=\"s\" targetRef=\"call\"/>"
            + "<sequenceFlow id=\"b\" sourceRef=\"call\" targetRef=\"e\"/><endEvent id=\"e\"/>";
    assertThat(rules(linter.lint("mau-h", process("mau-khac", body))))
        .contains("R1_KHONG_GAN_TENANT", "R2_MOT_TANG_CALL_ACTIVITY", "R5_BIEN_DUOI_4KB");
  }

  @Test
  void khongDocDuocBpmn() {
    assertThat(rules(linter.lint("mau-i", "<khong-phai-xml")))
        .containsExactly("R7_KIEM_THU_MO_TA_CHU_SO_HUU");
    assertThat(
            rules(
                linter.lint(
                    "mau-i",
                    "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"/>")))
        .containsExactly("R1_KHONG_GAN_TENANT");
  }
}
