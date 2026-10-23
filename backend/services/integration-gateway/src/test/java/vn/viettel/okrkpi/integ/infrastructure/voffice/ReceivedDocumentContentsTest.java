package vn.viettel.okrkpi.integ.infrastructure.voffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.port.ReceivedDocumentParser.Paragraph;
import vn.viettel.okrkpi.integ.application.port.ReceivedDocumentParser.Parsed;
import vn.viettel.okrkpi.integ.infrastructure.edxml.Edxml;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument.Attachment;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument.Organ;

class ReceivedDocumentContentsTest {

  private final ReceivedDocumentContents contents =
      new ReceivedDocumentContents(new ObjectMapper());

  static EdxmlDocument edxml(List<Attachment> attachments) {
    return new EdxmlDocument(
        "0192f1a2-0000-7000-8000-00000216e001",
        new Organ("000.00.00.H01", "Ủy ban nhân dân tỉnh"),
        List.of(
            new Organ("000.00.12.H01", "Sở Thông tin và Truyền thông"),
            new Organ("000.00.13.H01", "Sở Tài chính")),
        "12",
        "CT-UBND",
        "Hà Nam",
        LocalDate.of(2026, 10, 5),
        "Chỉ thị",
        "Về việc triển khai chuyển đổi số",
        null,
        null,
        attachments,
        false);
  }

  @Test
  void edxmlReadsPlainTextAttachmentAsParagraphs() {
    byte[] xml =
        Edxml.build(
            edxml(
                List.of(
                    new Attachment(
                        "t1",
                        "noi-dung.txt",
                        "text/plain; charset=utf-8",
                        "Yêu cầu:\n\n  Giao Sở Thông tin và Truyền thông chủ trì.\r\nBáo cáo trước 30/11."
                            .getBytes(StandardCharsets.UTF_8)),
                    new Attachment("t2", "ban-ky.pdf", "application/pdf", new byte[] {1}))));

    Parsed p = contents.parse(VOfficeConnector.TYPE_EDXML, xml);

    assertThat(p.format()).isEqualTo("EdXML");
    assertThat(p.recipientExternalId()).isEqualTo("000.00.12.H01");
    assertThat(p.issuerHeader()).isEqualTo("Ủy ban nhân dân tỉnh");
    assertThat(p.title()).isEqualTo("Về việc triển khai chuyển đổi số");
    assertThat(p.recipients()).isEqualTo("Sở Thông tin và Truyền thông, Sở Tài chính");
    assertThat(p.paragraphs())
        .containsExactly(
            new Paragraph(1, "Yêu cầu:"),
            new Paragraph(2, "Giao Sở Thông tin và Truyền thông chủ trì."),
            new Paragraph(3, "Báo cáo trước 30/11."));
    assertThat(p.classification()).isEqualTo("C1");
  }

  @Test
  void edxmlWithoutTextFallsBackToSubject() {
    Parsed p = contents.parse("EDXML", Edxml.build(edxml(List.of())));
    assertThat(p.paragraphs())
        .containsExactly(new Paragraph(1, "Về việc triển khai chuyển đổi số"));
  }

  @Test
  void vofficeJsonParagraphsAndRecipients() {
    String body =
        """
        {"docNumber":"12/CT-UBND","issuer":"UBND tỉnh","summary":"Về việc A","issuedDate":"2026-10-05",
         "recipientUnitId":"VO-1","title":"Chỉ thị về việc A","preamble":"Căn cứ",
         "paragraphs":["Đoạn một", {"no":2,"text":"Đoạn hai"}, " "],
         "recipients":["Sở A", {"name":"Sở B"}],"classification":"c2"}
        """;
    Parsed p = contents.parse(VOfficeConnector.TYPE_JSON, body.getBytes(StandardCharsets.UTF_8));
    assertThat(p.format()).isEqualTo("vOffice");
    assertThat(p.recipientExternalId()).isEqualTo("VO-1");
    assertThat(p.issuerHeader()).isEqualTo("UBND tỉnh");
    assertThat(p.title()).isEqualTo("Chỉ thị về việc A");
    assertThat(p.preamble()).isEqualTo("Căn cứ");
    assertThat(p.paragraphs())
        .containsExactly(new Paragraph(1, "Đoạn một"), new Paragraph(2, "Đoạn hai"));
    assertThat(p.recipients()).isEqualTo("Sở A, Sở B");
    assertThat(p.classification()).isEqualTo("C2");
  }

  @Test
  void vofficeJsonContentSummaryFallbacks() {
    Parsed content =
        contents.parse(
            "VOFFICE_JSON",
            "{\"summary\":\"Về việc B\",\"content\":\"Dòng 1\\nDòng 2\",\"recipients\":\"Sở C\",\"classification\":\"X\"}"
                .getBytes(StandardCharsets.UTF_8));
    assertThat(content.paragraphs()).hasSize(2);
    assertThat(content.title()).isEqualTo("Về việc B");
    assertThat(content.recipients()).isEqualTo("Sở C");
    assertThat(content.classification()).isEqualTo("C1");

    Parsed summaryOnly =
        contents.parse(
            "VOFFICE_JSON", "{\"summary\":\"Về việc B\"}".getBytes(StandardCharsets.UTF_8));
    assertThat(summaryOnly.paragraphs()).containsExactly(new Paragraph(1, "Về việc B"));

    assertThatThrownBy(() -> contents.parse("VOFFICE_JSON", "{".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
