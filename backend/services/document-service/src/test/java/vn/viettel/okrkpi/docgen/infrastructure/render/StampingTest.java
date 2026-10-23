package vn.viettel.okrkpi.docgen.infrastructure.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.docx4j.TraversalUtil;
import org.docx4j.model.structure.SectionWrapper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.RenderStamp;

/** Tem phiên bản ở chân trang DOCX và trong XMP của PDF (DV-11-04, BR-11-03). */
class StampingTest {

  private static final UUID PACKAGE = UUID.fromString("0192a3f4-1102-7000-8000-000000000142");
  private static final RenderStamp STAMP =
      new RenderStamp("PP-DANG", "1.3.0", "MAU_1A", 2, PACKAGE, new byte[32]);

  private static final String XMP_TEMPLATE =
      """
      <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
      <rdf:Description rdf:about="uuid:abc" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
      <pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance>
      </rdf:Description>
      %s
      </rdf:RDF>
      </x:xmpmeta>
      <?xpacket end="w"?>
      """;

  private static byte[] pdf(String xmp) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      if (xmp != null) {
        PDMetadata metadata = new PDMetadata(doc);
        metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
        doc.getDocumentCatalog().setMetadata(metadata);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void stampIsWrittenToXmpWithExtensionSchemaAndReadBack() throws Exception {
    byte[] stamped = new PdfBoxStamper().stamp(pdf(XMP_TEMPLATE.formatted("")), STAMP);
    Map<String, String> read = PdfBoxStamper.read(stamped).orElseThrow();
    assertThat(read).isEqualTo(STAMP.properties());
    try (PDDocument doc = Loader.loadPDF(stamped)) {
      String xmp =
          new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertThat(xmp)
          .contains("<pdfaid:part>2</pdfaid:part>")
          .contains("<pdfaExtension:schemas><rdf:Bag>")
          .contains("<pdfaSchema:namespaceURI>" + PdfBoxStamper.NS)
          .contains("rdf:about=\"uuid:abc\" xmlns:mtem=")
          .contains("<?xpacket end=\"w\"?>");
      assertThat(doc.getDocumentCatalog().getMetadata().getCOSObject().getFilters()).isNull();
    }
  }

  @Test
  void existingExtensionBagIsReusedAndUnstampedFileReadsEmpty() throws Exception {
    String existing =
        "<rdf:Description rdf:about=\"uuid:abc\""
            + " xmlns:pdfaExtension=\"http://www.aiim.org/pdfa/ns/extension/\">"
            + "<pdfaExtension:schemas><rdf:Bag></rdf:Bag></pdfaExtension:schemas>"
            + "</rdf:Description>";
    String xmp = XMP_TEMPLATE.formatted(existing);
    String injected = PdfBoxStamper.inject(xmp, STAMP);
    assertThat(injected.split("<pdfaExtension:schemas>", -1)).hasSize(2);
    assertThat(PdfBoxStamper.read(new PdfBoxStamper().stamp(pdf(xmp), STAMP))).isPresent();
    assertThat(PdfBoxStamper.read(pdf(XMP_TEMPLATE.formatted("")))).isEmpty();
    assertThat(PdfBoxStamper.read(pdf(null))).isEmpty();
    assertThat(PdfBoxStamper.read("khong-phai-pdf".getBytes(StandardCharsets.UTF_8))).isEmpty();
  }

  @Test
  void fileWithoutXmpOrUnreadableIsRejected() throws Exception {
    byte[] noXmp = pdf(null);
    assertThatThrownBy(() -> new PdfBoxStamper().stamp(noXmp, STAMP))
        .isInstanceOf(RejectedDocument.class)
        .hasMessageContaining("XMP");
    assertThatThrownBy(() -> new PdfBoxStamper().stamp("x".getBytes(StandardCharsets.UTF_8), STAMP))
        .isInstanceOf(RejectedDocument.class);
  }

  @Test
  void footerLineIsAddedToEverySectionOnce() throws Exception {
    WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().addParagraphOfText("Họ và tên: ${hoTen}");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    pkg.save(out);

    Docx4jMerger merger = new Docx4jMerger();
    byte[] merged = merger.merge(out.toByteArray(), Map.of("hoTen", "A & B"), STAMP.footerText());
    WordprocessingMLPackage result = WordprocessingMLPackage.load(new ByteArrayInputStream(merged));
    List<SectionWrapper> sections = result.getDocumentModel().getSections();
    FooterPart footer = sections.get(0).getHeaderFooterPolicy().getDefaultFooter();
    assertThat(footer).isNotNull();
    StringBuilder text = new StringBuilder();
    new TraversalUtil(
        footer.getContent(),
        new TraversalUtil.CallbackImpl() {
          @Override
          public List<Object> apply(Object o) {
            if (o instanceof org.docx4j.wml.Text t) {
              text.append(t.getValue());
            }
            return null;
          }
        });
    assertThat(text.toString()).isEqualTo(STAMP.footerText());
    assertThat(result.getMainDocumentPart().getXML()).contains("A &amp; B");

    // Mẫu đã có chân trang: thêm dòng vào chân trang sẵn có, không tạo chân trang thứ hai.
    byte[] again = merger.merge(merged, Map.of(), "Dòng thứ hai");
    WordprocessingMLPackage twice = WordprocessingMLPackage.load(new ByteArrayInputStream(again));
    FooterPart same =
        twice.getDocumentModel().getSections().get(0).getHeaderFooterPolicy().getDefaultFooter();
    assertThat(same.getContent()).hasSize(2);
    assertThat(merger.merge(out.toByteArray(), Map.of())).isNotEmpty();
  }
}
