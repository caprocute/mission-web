package vn.viettel.okrkpi.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ErrorCatalogTest {

  private final ErrorCatalog catalog = ErrorCatalog.standard();

  @Test
  void danhMucChuanNapDuMaTuHopDong() throws Exception {
    JsonNode raw;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(ErrorCatalog.RESOURCE)) {
      raw = new ObjectMapper().readTree(in);
    }
    assertThat(catalog.size()).isEqualTo(raw.get("count").asInt()).isGreaterThanOrEqualTo(179);
    assertThat(catalog.size()).isEqualTo(raw.get("codes").size());
  }

  @Test
  void moiMaCoHttpStatusKhopPhanSoTrongMa() {
    for (ErrorCode c : catalog.all()) {
      String[] parts = c.code().split("-", 3);
      assertThat(Integer.parseInt(parts[1])).as(c.code()).isEqualTo(c.status());
      assertThat(c.status()).as(c.code()).isBetween(400, 599);
      assertThat(parts[0]).isEqualTo(c.module());
      assertThat(parts[2]).isEqualTo(c.name());
      assertThat(c.type()).startsWith(ProblemTypes.BASE_URI);
      assertThat(c.title()).isNotBlank();
      if (c.msg() != null) {
        assertThat(c.msg()).matches("MSG-\\d{2}-\\d{2}");
      }
    }
  }

  @Test
  void khongCoMaTrungTrongTepNguon() throws Exception {
    JsonNode raw;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(ErrorCatalog.RESOURCE)) {
      raw = new ObjectMapper().readTree(in);
    }
    Set<String> seen = new HashSet<>();
    for (JsonNode n : raw.get("codes")) {
      assertThat(seen.add(n.get("code").asText())).as(n.get("code").asText()).isTrue();
    }
  }

  @Test
  void maChungCuaKhungCoTrongDanhMucDungNhuHopDong() {
    ErrorCode validation = catalog.require("COMMON-422-VALIDATION");
    assertThat(validation.type()).isEqualTo("https://okrkpi.vn/problems/validation");
    assertThat(validation.title()).isEqualTo("Dữ liệu chưa hợp lệ");

    ErrorCode forbidden = catalog.require("COMMON-403-OUT-OF-SCOPE");
    assertThat(forbidden.type()).isEqualTo("https://okrkpi.vn/problems/forbidden");

    ErrorCode locked = catalog.require("TASK-409-PERIOD-LOCKED");
    assertThat(locked.type()).isEqualTo("https://okrkpi.vn/problems/period-locked");
    assertThat(locked.title()).isEqualTo("Kỳ đánh giá đã khoá");

    assertThat(catalog.require("OVERSIGHT-403-FOUR-EYES").msg()).isEqualTo("MSG-13-01");
    assertThat(catalog.contains("COMMON-422-IDEMPOTENCY-MISMATCH")).isTrue();
    assertThat(catalog.modules()).contains("TASK", "OKR", "COMMON", "APPRAISAL");
  }

  @Test
  void maKhongCoTieuDeRiengDungTieuDeChungTheoStatus() {
    ErrorCode c = catalog.require("PERIOD-409-OVERLAP");
    assertThat(c.title()).isEqualTo(ProblemTypes.forStatus(409).title());
  }

  @Test
  void maLaBiTuChoi() {
    assertThatThrownBy(() -> catalog.require("TASK-409-KHONG-CO"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TASK-409-KHONG-CO");
    assertThat(catalog.find(null)).isEmpty();
    assertThat(catalog.contains(null)).isFalse();
    assertThatThrownBy(() -> new BusinessException("TASK-409-KHONG-CO"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void xungDotPhienBanCoSanHoacDungTheoMau() {
    ErrorCode task = catalog.versionConflict("TASK");
    assertThat(task).isSameAs(catalog.require("TASK-409-VERSION-CONFLICT"));

    ErrorCode common = catalog.versionConflict("COMMON");
    assertThat(common.code()).isEqualTo("COMMON-409-VERSION-CONFLICT");
    assertThat(common.status()).isEqualTo(409);
    assertThat(common.type()).isEqualTo(ProblemTypes.CONCURRENT_MODIFICATION);
    assertThat(common.msg()).isNull();

    assertThatThrownBy(() -> catalog.versionConflict("task"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> catalog.versionConflict(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void danhMucSaiBiTuChoiKhiNap() {
    assertThatThrownBy(
            () ->
                load(
                    "{\"codes\":["
                        + entry("TASK-409-A", 409)
                        + ","
                        + entry("TASK-409-A", 409)
                        + "]}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("trùng");
    assertThatThrownBy(() -> load("{\"codes\":[" + entry("TASK-409-A", 422) + "]}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> load("{\"codes\":[" + entry("task-409-a", 409) + "]}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> load("{\"khac\":[]}")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> load("{khong-phai-json")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                load(
                    "{\"codes\":[{\"code\":\"TASK-409-A\",\"status\":409,"
                        + "\"type\":\"https://khac.vn/a\"}]}"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void danhMucHopLeNapDuocVaDienModuleTen() {
    ErrorCatalog c =
        load(
            "{\"codes\":[{\"code\":\"TASK-409-A\",\"status\":409,"
                + "\"type\":\"https://okrkpi.vn/problems/a\",\"title\":\"Tiêu đề\",\"msg\":\"MSG-01-02\"}]}");
    ErrorCode a = c.require("TASK-409-A");
    assertThat(a.module()).isEqualTo("TASK");
    assertThat(a.name()).isEqualTo("A");
    assertThat(a.title()).isEqualTo("Tiêu đề");
    assertThat(a.msg()).isEqualTo("MSG-01-02");
    assertThat(c.all()).containsExactly(a);
  }

  @Test
  void errorCodeKiemRangBuoc() {
    String type = ProblemTypes.BASE_URI + "a";
    assertThatThrownBy(() -> new ErrorCode("TASK-409-A", "OKR", 409, "A", type, "t", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ErrorCode("TASK-409-A", "TASK", 409, "B", type, "t", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ErrorCode("TASK-409-A", "TASK", 409, "A", type, "t", "MSG-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void businessExceptionMangMaMsgVaLoiTruong() {
    BusinessException ex =
        new BusinessException(
            "OKR-422-VALIDATION",
            "Tổng trọng số chưa đủ",
            List.of(ProblemFieldError.of("weights", "Phải bằng 100%", "BR-02-05")),
            "MSG-02-05");
    assertThat(ex.getMessage()).isEqualTo("OKR-422-VALIDATION");
    assertThat(ex.errorCode().status()).isEqualTo(422);
    assertThat(ex.detail()).isEqualTo("Tổng trọng số chưa đủ");
    assertThat(ex.msg()).isEqualTo("MSG-02-05");
    assertThat(ex.errors()).hasSize(1);
    assertThat(ex.getStackTrace()).isEmpty();

    assertThat(new BusinessException("OKR-403-SELF-APPROVAL").msg()).isEqualTo("MSG-02-11");
    assertThat(new BusinessException("PERIOD-409-OVERLAP", "x").msg()).isNull();
    assertThatThrownBy(() -> new BusinessException("OKR-422-VALIDATION", null, List.of(), "BR-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ErrorCatalog load(String json) {
    return ErrorCatalog.fromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
  }

  private static String entry(String code, int status) {
    return "{\"code\":\""
        + code
        + "\",\"status\":"
        + status
        + ",\"type\":\"https://okrkpi.vn/problems/a\"}";
  }
}
