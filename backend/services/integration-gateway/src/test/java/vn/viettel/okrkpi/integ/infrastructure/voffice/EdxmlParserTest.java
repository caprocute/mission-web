package vn.viettel.okrkpi.integ.infrastructure.voffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EdxmlParserTest {

  static byte[] xml(String header) {
    return ("<edXMLEnvelope xmlns=\"http://www.e-doc.vn/Schema/\"><edXMLHeader><MessageHeader>"
            + header
            + "</MessageHeader></edXMLHeader><edXMLBody><OrganName>Không đọc</OrganName></edXMLBody>"
            + "</edXMLEnvelope>")
        .getBytes(StandardCharsets.UTF_8);
  }

  static final String FULL =
      "<From><OrganId>H01</OrganId><OrganName>Văn phòng Tỉnh uỷ</OrganName></From>"
          + "<To><OrganId>DV-01</OrganId><OrganName>Ban A</OrganName></To>"
          + "<To><OrganId>DV-02</OrganId></To>"
          + "<Code><CodeNumber>15</CodeNumber><CodeNotation>CV-VPTU</CodeNotation></Code>"
          + "<PromulgationInfo><Place>Hà Nội</Place><PromulgationDate>2026-10-18</PromulgationDate>"
          + "</PromulgationInfo><Subject> Triển khai nhiệm vụ </Subject>";

  @Test
  void docTieuDeEdxml() {
    Directive d = EdxmlParser.parse(xml(FULL));
    assertThat(d.docNumber()).isEqualTo("15/CV-VPTU");
    assertThat(d.issuedDate()).isEqualTo(LocalDate.of(2026, 10, 18));
    assertThat(d.issuer()).isEqualTo("Văn phòng Tỉnh uỷ");
    assertThat(d.recipientUnitId()).isEqualTo("DV-01");
    assertThat(d.summary()).isEqualTo("Triển khai nhiệm vụ");
    assertThat(
            EdxmlParser.parse(xml(FULL.replace("<CodeNotation>CV-VPTU</CodeNotation>", "")))
                .docNumber())
        .isEqualTo("15");
  }

  @Test
  void tuChoiGoiThieuTruongSaiNgayVaThucTheNgoai() {
    assertThatThrownBy(
            () ->
                EdxmlParser.parse(
                    xml(FULL.replace("<Subject> Triển khai nhiệm vụ </Subject>", ""))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EdxmlParser.parse(xml(FULL.replace("2026-10-18", "18/10/2026"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EdxmlParser.parse("<chua-dong>".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] xxe =
        ("<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<edXMLEnvelope><edXMLHeader><MessageHeader><Subject>&x;</Subject>"
                + "</MessageHeader></edXMLHeader></edXMLEnvelope>")
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> EdxmlParser.parse(xxe)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EdxmlParser.parse(new byte[EdxmlParser.MAX_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
