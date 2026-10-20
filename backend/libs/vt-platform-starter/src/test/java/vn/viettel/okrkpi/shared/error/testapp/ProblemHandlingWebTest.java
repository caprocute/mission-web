package vn.viettel.okrkpi.shared.error.testapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import vn.viettel.okrkpi.shared.error.ProblemSchema;

/**
 * Kiểm thử Web MVC của NEN-009: controller thử ném lần lượt các lỗi chuẩn; mọi phản hồi phải là
 * {@code application/problem+json}, hợp lệ theo lược đồ {@code ProblemDetails} của {@code
 * common.yaml}, không chứa tên lớp Java hay câu SQL.
 */
@SpringBootTest(classes = TestApplication.class, properties = "okrkpi.problem.module=TASK")
@AutoConfigureMockMvc
class ProblemHandlingWebTest {

  private static final Pattern LEAK =
      Pattern.compile(
          "(?i)(java\\.|jakarta\\.|org\\.springframework|org\\.hibernate|vn\\.viettel|exception"
              + "|select \\*|select .+ from|update [a-z_.]+ set|insert into|delete from|row was updated"
              + "|stacktrace|\\bat [a-z]+\\.[a-z]+|bearer|nguyenvana|okr\\.allow)");
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

  private static ProblemSchema schema;

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @BeforeAll
  static void loadSchema() {
    schema = ProblemSchema.fromCommonYaml();
  }

  @Test
  void loiNghiepVuTheoDanhMuc() throws Exception {
    JsonNode p = problem(get("/probe/business").header("traceparent", TRACEPARENT), 409);

    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/period-locked");
    assertThat(p.get("title").asText()).isEqualTo("Kỳ đánh giá đã khoá");
    assertThat(p.get("code").asText()).isEqualTo("TASK-409-PERIOD-LOCKED");
    assertThat(p.get("detail").asText()).startsWith("Kỳ Quý IV/2026");
    assertThat(p.get("instance").asText()).isEqualTo("/probe/business");
    assertThat(p.get("traceId").asText()).isEqualTo(TRACE_ID);
    assertThat(p.has("errors")).isFalse();
  }

  @Test
  void loiNghiepVuMangMsgCuaDanhMuc() throws Exception {
    JsonNode p = problem(get("/probe/business-msg"), 403);

    assertThat(p.get("code").asText()).isEqualTo("OKR-403-SELF-APPROVAL");
    assertThat(p.get("msg").asText()).isEqualTo("MSG-02-11");
    assertThat(p.get("title").asText()).isEqualTo("Không thể tự phê duyệt");
    assertThat(p.get("traceId").asText()).matches("[0-9a-f]{32}");
  }

  @Test
  void loiNghiepVuKemLoiTheoTruongVaMaQuyTac() throws Exception {
    JsonNode p = problem(get("/probe/business-fields"), 422);

    assertThat(p.get("code").asText()).isEqualTo("OKR-422-VALIDATION");
    assertThat(p.get("msg").asText()).isEqualTo("MSG-02-05");
    assertThat(p.at("/errors/0/field").asText()).isEqualTo("weights");
    assertThat(p.at("/errors/0/rule").asText()).isEqualTo("BR-02-05");
  }

  @Test
  void kiemDuLieuThanYeuCauTra422CoErrors() throws Exception {
    String body =
        """
        {"percent": 150, "note": "ghi chú quá dài vượt mức", "reason": " ",
         "items": [{"name": "a"}, {"name": ""}]}
        """;
    JsonNode p =
        problem(post("/probe/progress").contentType(MediaType.APPLICATION_JSON).content(body), 422);

    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/validation");
    assertThat(p.get("title").asText()).isEqualTo("Dữ liệu chưa hợp lệ");
    assertThat(p.get("code").asText()).isEqualTo("COMMON-422-VALIDATION");
    assertThat(fields(p))
        .containsExactlyInAnyOrder(
            "percent=Giá trị tối đa là 100",
            "note=Độ dài tối đa là 10",
            "reason=Phải nêu lý do điều chỉnh",
            "items[1].name=Trường này là bắt buộc");
  }

  @Test
  void kiemThamSoTruyVanTra400CoErrors() throws Exception {
    JsonNode p = problem(get("/probe/items").param("limit", "500"), 400);

    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/bad-request");
    assertThat(p.has("code")).isFalse();
    assertThat(fields(p)).containsExactly("limit=Giá trị tối đa là 200");
  }

  @Test
  void constraintViolationTuLopUngDungTra422() throws Exception {
    JsonNode p = problem(get("/probe/constraint"), 422);

    assertThat(p.get("code").asText()).isEqualTo("COMMON-422-VALIDATION");
    assertThat(fields(p))
        .containsExactly(
            "note=Độ dài tối đa là 10",
            "percent=Giá trị tối đa là 100",
            "reason=Phải nêu lý do điều chỉnh");
  }

  @Test
  void thanYeuCauSaiCuPhapTra400() throws Exception {
    JsonNode p =
        problem(
            post("/probe/progress")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"percent\":"),
            400);

    assertThat(p.get("title").asText()).isEqualTo("Yêu cầu không hợp lệ");
  }

  @Test
  void saiKieuThamSoDuongDanTra400() throws Exception {
    JsonNode p = problem(get("/probe/tasks/khong-phai-uuid"), 400);

    assertThat(p.get("detail").asText()).doesNotContain("UUID");
  }

  @Test
  void khoaLacQuanTra409TheoModuleCauHinh() throws Exception {
    JsonNode p = problem(get("/probe/optimistic"), 409);

    assertThat(p.get("type").asText())
        .isEqualTo("https://okrkpi.vn/problems/concurrent-modification");
    assertThat(p.get("title").asText()).isEqualTo("Dữ liệu đã được người khác cập nhật");
    assertThat(p.get("code").asText()).isEqualTo("TASK-409-VERSION-CONFLICT");
  }

  @Test
  void khoaLacQuanTra409TheoGoiModuleCuaController() throws Exception {
    JsonNode p = problem(get("/okr-probe/optimistic"), 409);

    assertThat(p.get("code").asText()).isEqualTo("OKR-409-VERSION-CONFLICT");
  }

  @Test
  void lechPhienBanIfMatchTra409KemPhienBan() throws Exception {
    JsonNode p = problem(get("/probe/version-conflict"), 409);

    assertThat(p.get("code").asText()).isEqualTo("TASK-409-VERSION-CONFLICT");
    assertThat(p.get("detail").asText())
        .isEqualTo("Phiên bản bạn sửa là 6, phiên bản hiện tại là 7.");
  }

  @Test
  void thieuIfMatchTra428() throws Exception {
    JsonNode p = problem(put("/probe/tasks/01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77"), 428);

    assertThat(p.get("type").asText())
        .isEqualTo("https://okrkpi.vn/problems/precondition-required");
    assertThat(p.get("title").asText()).isEqualTo("Thiếu thông tin phiên bản dữ liệu");
  }

  @Test
  void coIfMatchThiKhongLoi() throws Exception {
    MvcResult r =
        mvc.perform(
                put("/probe/tasks/01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77")
                    .header("If-Match", "\"7\""))
            .andReturn();

    assertThat(r.getResponse().getStatus()).isEqualTo(204);
  }

  @Test
  void ngoaiLeThieuDieuKienTienQuyetTra428() throws Exception {
    problem(get("/probe/precondition"), 428);
  }

  @Test
  void khongCoQuyenTra403() throws Exception {
    JsonNode p = problem(get("/probe/forbidden"), 403);

    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/forbidden");
    assertThat(p.get("code").asText()).isEqualTo("COMMON-403-OUT-OF-SCOPE");
    assertThat(p.get("title").asText()).isEqualTo("Không có quyền thực hiện thao tác này");
  }

  @Test
  void chuaXacThucTra401() throws Exception {
    JsonNode p = problem(get("/probe/unauthenticated"), 401);

    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/unauthorized");
  }

  @Test
  void ngoaiLeLaTra500ChungKhongLoSql() throws Exception {
    JsonNode p = problem(get("/probe/boom").header("traceparent", TRACEPARENT), 500);

    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/internal-error");
    assertThat(p.get("title").asText()).isEqualTo("Hệ thống đang gặp sự cố");
    assertThat(p.get("traceId").asText()).isEqualTo(TRACE_ID);
  }

  @Test
  void duongDanKhongTonTaiTra404() throws Exception {
    JsonNode p = problem(get("/khong-ton-tai"), 404);

    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/not-found");
  }

  @Test
  void phuongThucKhongHoTroTra405GiuHeaderAllow() throws Exception {
    MvcResult r = mvc.perform(delete("/probe/business")).andReturn();

    check(r, 405);
    assertThat(r.getResponse().getHeader(HttpHeaders.ALLOW)).contains("GET");
  }

  @Test
  void kieuNoiDungKhongHoTroTra415() throws Exception {
    problem(post("/probe/progress").contentType(MediaType.TEXT_PLAIN).content("x"), 415);
  }

  private JsonNode problem(RequestBuilder request, int status) throws Exception {
    return check(mvc.perform(request).andReturn(), status);
  }

  private JsonNode check(MvcResult r, int status) throws Exception {
    String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
    assertThat(MediaType.parseMediaType(r.getResponse().getContentType()))
        .matches(t -> t.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON), body);
    JsonNode node = json.readTree(body);
    assertThat(schema.validate(node, "ProblemDetails")).as(body).isEmpty();
    assertThat(node.get("status").asInt()).isEqualTo(status);
    assertThat(node.get("type").asText()).startsWith("https://okrkpi.vn/problems/");
    assertThat(node.get("traceId").asText()).matches("[0-9a-f]{32}");
    assertThat(LEAK.matcher(body).find()).as("Phản hồi lộ thông tin nội bộ: %s", body).isFalse();
    return node;
  }

  private static List<String> fields(JsonNode p) {
    List<String> out = new ArrayList<>();
    p.get("errors")
        .forEach(e -> out.add(e.get("field").asText() + "=" + e.get("message").asText()));
    return out;
  }
}
