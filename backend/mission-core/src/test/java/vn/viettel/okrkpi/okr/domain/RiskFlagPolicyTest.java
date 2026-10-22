package vn.viettel.okrkpi.okr.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CORE-01-09: bảng chuyển cờ rủi ro của KR theo BR-03-06 và TL-02a QT-03 Hình 7.7. */
class RiskFlagPolicyTest {

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({
    "'', BINH_THUONG",
    "CAO, BINH_THUONG",
    "THAP, THEO_DOI",
    "THAP THAP, RUI_RO",
    "THAP CAO THAP, THEO_DOI",
    "THAP TRUNG_BINH, BINH_THUONG",
    "THAP THAP CAO TRUNG_BINH, BINH_THUONG",
    "THAP THAP CAO THAP, RUI_RO",
    "THAP THAP CAO, RUI_RO",
    "THAP THAP THAP, RUI_RO",
    "THAP THAP CAO CAO THAP, THEO_DOI",
  })
  void chuoiCheckIn(String sequence, String expected) {
    List<String> confidences = sequence.isBlank() ? List.of() : List.of(sequence.split(" "));
    assertThat(RiskFlagPolicy.replay(confidences)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} → {1}: {2}")
  @CsvSource({
    "BINH_THUONG, RUI_RO, true",
    "THEO_DOI, RUI_RO, true",
    "RUI_RO, RUI_RO, false",
    "BINH_THUONG, THEO_DOI, false",
  })
  void chiThongBaoKhiVuaChuyenRuiRo(String before, String after, boolean expected) {
    assertThat(RiskFlagPolicy.becameAtRisk(before, after)).isEqualTo(expected);
  }
}
