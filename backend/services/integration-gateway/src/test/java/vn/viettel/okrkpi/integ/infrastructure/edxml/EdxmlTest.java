package vn.viettel.okrkpi.integ.infrastructure.edxml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument.Attachment;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument.Organ;

/** Gói EdXML dựng ra đọc lại được đúng nội dung (DV-13-05). */
class EdxmlTest {

  static EdxmlDocument document() {
    return new EdxmlDocument(
        "0192f1a2-0000-7000-8000-00000013e002",
        new Organ("000.00.00.H01", "Tỉnh uỷ Hà Nam"),
        List.of(new Organ("000.00.00.G01", "Ban Tổ chức Trung ương")),
        "12",
        "HS-BTC",
        "Hà Nam",
        LocalDate.of(2026, 10, 23),
        "Hồ sơ cán bộ",
        "Hồ sơ cán bộ diện Trung ương quản lý <đợt 1> & bổ sung",
        null,
        null,
        List.of(
            new Attachment(
                "tep-1", "ho-so.p7m", "application/pkcs7-mime", new byte[] {0, 1, 2, -1})),
        false);
  }

  @Test
  void dungRoiDocLaiGiuNguyenNoiDung() {
    byte[] xml = Edxml.build(document());
    EdxmlDocument d = Edxml.parse(xml, 1 << 20);
    assertThat(d.documentId()).isEqualTo("0192f1a2-0000-7000-8000-00000013e002");
    assertThat(d.docNumber()).isEqualTo("12/HS-BTC");
    assertThat(d.from().name()).isEqualTo("Tỉnh uỷ Hà Nam");
    assertThat(d.firstRecipient()).isEqualTo("000.00.00.G01");
    assertThat(d.subject()).isEqualTo("Hồ sơ cán bộ diện Trung ương quản lý <đợt 1> & bổ sung");
    assertThat(d.promulgationDate()).isEqualTo(LocalDate.of(2026, 10, 23));
    assertThat(d.attachments())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.name()).isEqualTo("ho-so.p7m");
              assertThat(a.content()).containsExactly(0, 1, 2, -1);
            });
    assertThat(d.signed()).isFalse();
    assertThat(Edxml.documentId(xml)).isEqualTo(d.documentId());
  }

  @Test
  void goiQuaTranHoacKhongPhaiEdxmlBiTuChoi() {
    byte[] xml = Edxml.build(document());
    assertThatThrownBy(() -> Edxml.parse(xml, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Edxml.parse("<a>".getBytes(StandardCharsets.UTF_8), 1000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Edxml.documentId("<khac/>".getBytes(StandardCharsets.UTF_8))).isNull();
  }
}
