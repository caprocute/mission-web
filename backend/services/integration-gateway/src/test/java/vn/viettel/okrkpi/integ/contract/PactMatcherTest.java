package vn.viettel.okrkpi.integ.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Luật so khớp Pact v3 tự hiện thực (DV-13-11). */
class PactMatcherTest {

  static JsonNode j(String s) {
    try {
      return Pact.JSON.readTree(s.replace('\'', '"'));
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  @Test
  void khongCoLuatThiSoBangGiaTriVaChoPhepTruongThuaOPhanHoi() {
    assertThat(PactMatcher.body(j("{'a':1,'b':'x'}"), j("{'a':1.0,'b':'x','c':true}"), null, true))
        .isEmpty();
    assertThat(PactMatcher.body(j("{'a':1}"), j("{'a':2}"), null, true))
        .containsExactly("$.a: giá trị khác hợp đồng (number)");
    assertThat(PactMatcher.body(j("{'a':1}"), j("{'a':1,'c':2}"), null, false))
        .containsExactly("$.c: trường không có trong hợp đồng");
    assertThat(PactMatcher.body(j("{'a':[1,2]}"), j("{'a':[1]}"), null, true))
        .containsExactly("$.a: cần 2 phần tử, nhận 1");
  }

  @Test
  void luatKieuLanXuongNutConVaMangCoMin() {
    JsonNode rules = j("{'$.items':{'matchers':[{'match':'type','min':1}]}}");
    JsonNode expected = j("{'items':[{'id':'x','n':1,'o':{'p':'q'}}]}");
    assertThat(
            PactMatcher.body(
                expected,
                j("{'items':[{'id':'y','n':5,'o':{'p':'r'}},{'id':'z','n':6,'o':{'p':'s'}}]}"),
                rules,
                true))
        .isEmpty();
    assertThat(PactMatcher.body(expected, j("{'items':[]}"), rules, true))
        .containsExactly("$.items: số phần tử 0 ngoài [1, ∞]");
    assertThat(PactMatcher.body(expected, j("{'items':[{'id':1,'n':5,'o':{}}]}"), rules, true))
        .containsExactly("$.items[0].id: cần kiểu string, nhận number", "$.items[0].o.p: thiếu");
  }

  @Test
  void luatCuTheThangLuatKieuCuaCha() {
    JsonNode rules =
        j(
            "{'$':{'matchers':[{'match':'type'}]},"
                + "'$.items[*].id':{'matchers':[{'match':'regex','regex':'VB-[0-9]+'}]},"
                + "'$.n':{'matchers':[{'match':'integer'}]},"
                + "'$.t':{'matchers':[{'match':'timestamp','format':'yyyy-MM-dd\\u0027T\\u0027HH:mm:ssXXX'}]},"
                + "'$.d':{'matchers':[{'match':'date','format':'yyyy-MM-dd'}]},"
                + "'$.c':{'combine':'OR','matchers':[{'match':'null'},{'match':'type'}]}}");
    JsonNode e =
        j("{'items':[{'id':'VB-1'}],'n':1,'t':'2026-10-20T01:00:00Z','d':'2026-10-19','c':null}");
    assertThat(
            PactMatcher.body(
                e,
                j(
                    "{'items':[{'id':'VB-2'}],'n':7,'t':'2026-10-20T08:00:00+07:00','d':'2026-01-31','c':'x'}"),
                rules,
                true))
        .isEmpty();
    assertThat(
            PactMatcher.body(
                e,
                j("{'items':[{'id':'X-2'}],'n':7.5,'t':'20/10/2026','d':'2026-13-01','c':null}"),
                rules,
                true))
        .containsExactlyInAnyOrder(
            "$.items[0].id: không khớp mẫu VB-[0-9]+",
            "$.n: cần số nguyên, nhận number",
            "$.t: không đúng định dạng yyyy-MM-dd'T'HH:mm:ssXXX",
            "$.d: không đúng định dạng yyyy-MM-dd");
  }

  @Test
  void soGiaTriThamSoTheoLuat() {
    JsonNode rules = j("{'limit':{'matchers':[{'match':'regex','regex':'[0-9]{1,3}'}]}}");
    assertThat(PactMatcher.value("limit", "20", "200", rules)).isTrue();
    assertThat(PactMatcher.value("limit", "20", "2000", rules)).isFalse();
    assertThat(PactMatcher.value("unitId", "DV-01", "DV-01", rules)).isTrue();
    assertThat(PactMatcher.value("unitId", "DV-01", "DV-02", rules)).isFalse();
  }

  @Test
  void loiKhongChuaGiaTriThuc() {
    assertThat(PactMatcher.body(j("{'ten':'A'}"), j("{'ten':'Nguyễn Văn Bí Mật'}"), null, true))
        .singleElement()
        .asString()
        .doesNotContain("Bí Mật");
  }
}
