package vn.viettel.okrkpi.integ.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Công cụ che bản ghi tương tác (DV-13-11): giá trị giả nhất quán, không sót dữ liệu cá nhân. */
class RecordingMaskerTest {

  static final byte[] KHOA = "khoa-thu-nghiem-32-byte-cho-hmac".getBytes(StandardCharsets.UTF_8);

  static JsonNode banGhiTho() {
    return PactMatcherTest.j(
        "{'provider':'hrm-nhan-su','daChe':false,'exchanges':[{"
            + "'request':{'method':'GET','path':'/api/persons','query':{'q':['0912345678']},"
            + "'headers':{'Authorization':'Bearer that','Cookie':'phien=1','Accept':'application/json'}},"
            + "'response':{'status':200,'headers':{'Set-Cookie':'p=2','Content-Type':'application/json'},"
            + "'body':{'items':["
            + "{'externalId':'NS-1','fullName':'Phạm Thị Hồng','nationalId':'001190012345',"
            + "'birthDate':'1985-03-14','phone':'0912345678','email':'hong@donvi.gov.vn',"
            + "'Họ và tên':'Phạm Thị Hồng','Số định danh':'001190012345','Ngày sinh':'14/03/1985',"
            + "'diaChi':'12 Trần Phú','giaTri':7.2,'daCongBo':true,"
            + "'ghiChu':'Gọi +84983123456 hoặc 0397654321, thư hong.pham@gmail.com, CCCD 079085001234'},"
            + "{'externalId':'NS-2','fullName':'Phạm Thị Hồng','nationalId':'001190012345'},"
            + "{'externalId':'NS-3','fullName':'Lê Văn Cường','nationalId':'036092000111'}]}}}]}");
  }

  @Test
  void cheDuLieuCaNhanVaGiuCauTruc() {
    JsonNode out = new RecordingMasker(KHOA).mask(banGhiTho());
    assertThat(out.path("daChe").asBoolean()).isTrue();
    JsonNode p = out.at("/exchanges/0/response/body/items/0");
    assertThat(p.path("externalId").asText()).isEqualTo("NS-1");
    assertThat(p.path("fullName").asText())
        .isNotEqualTo("Phạm Thị Hồng")
        .matches("\\p{L}+ \\p{L}+ \\p{L}+");
    assertThat(p.path("nationalId").asText()).matches("000\\d{9}");
    assertThat(p.path("Số định danh").asText()).isEqualTo(p.path("nationalId").asText());
    assertThat(p.path("Họ và tên").asText()).isEqualTo(p.path("fullName").asText());
    assertThat(p.path("birthDate").asText()).isEqualTo("1985-01-01");
    assertThat(p.path("Ngày sinh").asText()).isEqualTo("01/01/1985");
    assertThat(p.path("phone").asText()).matches("0000\\d{6}");
    assertThat(p.path("email").asText()).endsWith("@example.invalid");
    assertThat(p.path("diaChi").asText()).startsWith("Địa chỉ đã che");
    assertThat(p.path("giaTri").decimalValue()).isEqualByComparingTo("7.2");
    assertThat(p.path("daCongBo").asBoolean()).isTrue();
    String ghiChu = p.path("ghiChu").asText();
    assertThat(ghiChu).startsWith("Gọi ").contains(" hoặc ", ", thư ", ", CCCD 000");
    assertThat(RecordingMasker.PHONE.matcher(ghiChu).find()).isFalse();
    assertThat(RecordingMasker.CCCD.matcher(ghiChu).find()).isFalse();
    assertThat(RecordingMasker.EMAIL.matcher(ghiChu).find()).isFalse();
    assertThat(out.at("/exchanges/0/request/query/q/0").asText())
        .matches("0000\\d{6}")
        .isEqualTo(p.path("phone").asText());
    assertThat(out.at("/exchanges/0/request/headers/Authorization").asText())
        .isEqualTo("Bearer ma-gia-lap");
    assertThat(out.at("/exchanges/0/request/headers").has("Cookie")).isFalse();
    assertThat(out.at("/exchanges/0/response/headers").has("Set-Cookie")).isFalse();
    assertThat(out.at("/exchanges/0/response/headers/Content-Type").asText())
        .isEqualTo("application/json");
  }

  @Test
  void giaTriGiaNhatQuanTrongMotLanCheVaKhacGiuaCacKhoa() {
    JsonNode a = new RecordingMasker(KHOA).mask(banGhiTho());
    JsonNode items = a.at("/exchanges/0/response/body/items");
    assertThat(items.get(1).path("fullName")).isEqualTo(items.get(0).path("fullName"));
    assertThat(items.get(1).path("nationalId")).isEqualTo(items.get(0).path("nationalId"));
    assertThat(items.get(2).path("nationalId")).isNotEqualTo(items.get(0).path("nationalId"));
    assertThat(new RecordingMasker(KHOA).mask(banGhiTho())).isEqualTo(a);
    JsonNode b =
        new RecordingMasker("khoa-khac".getBytes(StandardCharsets.UTF_8)).mask(banGhiTho());
    assertThat(b.at("/exchanges/0/response/body/items/0/nationalId"))
        .isNotEqualTo(items.get(0).path("nationalId"));
  }

  @Test
  void banGocKhongBiSua() {
    JsonNode raw = banGhiTho();
    new RecordingMasker(KHOA).mask(raw);
    assertThat(raw.at("/exchanges/0/response/body/items/0/nationalId").asText())
        .isEqualTo("001190012345");
  }

  @Test
  void chuanHoaTenTruong() {
    assertThat(RecordingMasker.normalize("Họ và tên")).isEqualTo("hovaten");
    assertThat(RecordingMasker.normalize("Số định danh")).isEqualTo("sodinhdanh");
    assertThat(RecordingMasker.normalize("signer_name")).isEqualTo("signername");
  }
}
