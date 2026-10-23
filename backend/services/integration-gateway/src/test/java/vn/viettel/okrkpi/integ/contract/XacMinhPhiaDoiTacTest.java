package vn.viettel.okrkpi.integ.contract;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Xác minh phía đối tác (DV-13-11) khi đối tác chưa có môi trường thử: phát lại hợp đồng tới bản
 * ghi tương tác đã che ở {@code data/golden/integ-recordings} và tới bản giả lập vOffice của
 * DV-13-03; kiểm thử âm tính chứng minh đổi giả định trường bắt buộc hoặc đối tác bỏ, đổi kiểu một
 * trường làm xác minh thất bại.
 */
class XacMinhPhiaDoiTacTest {

  static List<String> verify(Pact pact, Recordings recordings) {
    WireMockServer s = recordings.server();
    try {
      return PactVerifier.of(s.baseUrl()).failures(pact);
    } finally {
      s.stop();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "integration-gateway-voffice.json, voffice-van-ban",
    "integration-gateway-hrm.json, hrm-nhan-su",
    "integration-gateway-lgsp.json, lgsp-chi-tieu-ktxh"
  })
  void hopDongKhopBanGhiTuongTacDaChe(String file, String provider) {
    Pact pact = Pact.load(file);
    assertThat(pact.provider()).isEqualTo(provider);
    assertThat(verify(pact, Recordings.load(provider))).isEmpty();
  }

  @Test
  void hopDongVOfficeKhopBanGiaLapCuaDv1303() {
    WireMockServer s =
        new WireMockServer(
            wireMockConfig().dynamicPort().usingFilesUnderClasspath("voffice-gia-lap"));
    s.start();
    try {
      assertThat(
              PactVerifier.of(s.baseUrl()).failures(Pact.load("integration-gateway-voffice.json")))
          .isEmpty();
    } finally {
      s.stop();
    }
  }

  @Test
  void tinNhanDayKhopVanBanChiDaoDaGhi() {
    Pact.Message m = Pact.load("integration-gateway-voffice-tin-nhan.json").messages().get(0);
    List<JsonNode> items = Recordings.load("voffice-van-ban").items("/api/v2/directives", "/items");
    assertThat(items).hasSizeGreaterThanOrEqualTo(2);
    for (JsonNode item : items) {
      assertThat(PactVerifier.verifyMessage(m, item)).isEmpty();
    }
    ObjectNode thieu = (ObjectNode) items.get(0).deepCopy();
    thieu.remove("recipientUnitId");
    thieu.put("version", "2");
    assertThat(PactVerifier.verifyMessage(m, thieu))
        .containsExactlyInAnyOrder(
            "$.version: cần số nguyên, nhận string", "$.recipientUnitId: thiếu");
  }

  @Test
  void themTruongBatBuocVaoHopDongThiXacMinhDo() {
    ObjectNode raw = Pact.load("integration-gateway-voffice.json").copyRaw();
    ((ObjectNode) raw.at("/interactions/0/response/body/items/0")).put("mucDoUuTien", "CAO");
    ((ObjectNode) raw.at("/interactions/3/response/body")).put("soTrang", 3);
    assertThat(verify(Pact.of(raw), Recordings.load("voffice-van-ban")))
        .containsExactlyInAnyOrder(
            "kéo trang văn bản chỉ đạo đầu tiên theo con trỏ thời điểm: $.items[0].mucDoUuTien: thiếu",
            "kéo trang văn bản chỉ đạo đầu tiên theo con trỏ thời điểm: $.items[1].mucDoUuTien: thiếu",
            "siêu dữ liệu tệp gốc của văn bản đã ban hành: $.soTrang: thiếu");
  }

  @Test
  void doiTacBoHoacDoiKieuTruongThiXacMinhDo() {
    Recordings r = Recordings.load("hrm-nhan-su");
    List<JsonNode> files = new ArrayList<>();
    for (JsonNode f : r.files()) {
      JsonNode c = f.deepCopy();
      for (JsonNode x : c.path("exchanges")) {
        JsonNode items = x.at("/response/body/items");
        if (items.isArray() && !items.isEmpty()) {
          ((ObjectNode) items.get(0)).remove("birthDate");
          ((ObjectNode) items.get(1)).put("externalId", "NS 0458/x");
        }
        if (x.at("/response/body/value").isNumber()) {
          ((ObjectNode) x.at("/response/body")).put("value", "3,4");
        }
      }
      files.add(c);
    }
    assertThat(
            verify(Pact.load("integration-gateway-hrm.json"), new Recordings(r.provider(), files)))
        .containsExactlyInAnyOrder(
            "kéo trang nhân sự đầu tiên theo con trỏ: $.items[0].birthDate: thiếu",
            "kéo trang nhân sự đầu tiên theo con trỏ: $.items[1].externalId: không khớp mẫu"
                + " [A-Za-z0-9._-]{1,64}",
            "đọc giá trị chỉ số chuyên môn: $.value: cần số, nhận string");
  }

  @Test
  void doiTacKhongCoTuongTacThiBaoMaTrangThai() {
    WireMockServer s = new WireMockServer(wireMockConfig().dynamicPort());
    s.start();
    try {
      assertThat(PactVerifier.of(s.baseUrl()).failures(Pact.load("integration-gateway-lgsp.json")))
          .containsExactly(
              "kéo trang chỉ tiêu kinh tế xã hội theo thời điểm cập nhật: mã trạng thái 404, hợp đồng"
                  + " cần 200");
    } finally {
      s.stop();
    }
  }
}
