package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.PdfStamper;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.RenderStamp;

/**
 * Đóng tem phiên bản vào XMP của tệp PDF/A bằng Apache PDFBox 3 (Apache 2.0, đã duyệt ở DV-08-06,
 * YC-PT-18). PDFBox không tự đổi {@code Producer} hay {@code ModDate} của từ điển Info như OpenPDF,
 * nên Info và XMP vẫn khớp nhau theo PDF/A.
 *
 * <p>Tem là các thuộc tính văn bản trong không gian tên {@value #NS} (tiền tố {@value #PREFIX}).
 * PDF/A chỉ nhận thuộc tính XMP ngoài lược đồ chuẩn khi có khai báo lược đồ mở rộng ({@code
 * pdfaExtension:schemas}), nên lớp này thêm khai báo đó: nối vào túi khai báo sẵn có nếu tệp đã có,
 * ngược lại tạo mới. Nội dung XMP cũ giữ nguyên từng byte, chỉ chèn thêm trước {@code </rdf:RDF>};
 * tệp lưu lại không nén luồng siêu dữ liệu. Tệp sau đó vẫn qua veraPDF ở {@code RenderService}.
 */
public class PdfBoxStamper implements PdfStamper {

  /** Không gian tên XMP của tem (suy luận, chưa có trong TL; đổi thì đổi cả bên đọc). */
  public static final String NS = "http://ns.mission.gov.vn/xmp/tem/1.0/";

  public static final String PREFIX = "mtem";

  static final String EXTENSION_NS = "http://www.aiim.org/pdfa/ns/extension/";
  static final String SCHEMA_NS = "http://www.aiim.org/pdfa/ns/schema#";
  static final String PROPERTY_NS = "http://www.aiim.org/pdfa/ns/property#";

  private static final Pattern ABOUT = Pattern.compile("rdf:about=\"([^\"]*)\"");
  private static final Pattern EXISTING_BAG =
      Pattern.compile("<pdfaExtension:schemas>\\s*<rdf:Bag>");

  private static final Map<String, String> DESCRIPTIONS =
      Map.of(
          RenderStamp.POLICY_PACK, "Mã bộ quy tắc đánh giá (Policy Pack) gắn với kỳ",
          RenderStamp.POLICY_PACK_VERSION, "Phiên bản bộ quy tắc đánh giá gắn với kỳ",
          RenderStamp.FORM_CODE, "Mã biểu mẫu",
          RenderStamp.TEMPLATE_VERSION, "Phiên bản mẫu biểu đã dùng",
          RenderStamp.PACKAGE_ID, "Mã gói hồ sơ",
          RenderStamp.INPUT_SHA256, "Băm SHA-256 của dữ liệu đầu vào",
          RenderStamp.REOPEN_REQUEST_ID, "Yêu cầu mở lại kỳ của lần sinh lại",
          RenderStamp.DRAFT, "Bản nháp ngoài gói hồ sơ");

  @Override
  public byte[] stamp(byte[] pdf, RenderStamp stamp) {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      PDDocumentCatalog catalog = doc.getDocumentCatalog();
      PDMetadata current = catalog.getMetadata();
      String xmp =
          current == null ? null : new String(current.toByteArray(), StandardCharsets.UTF_8);
      if (xmp == null || !xmp.contains("</rdf:RDF>")) {
        throw new RejectedDocument("Tệp PDF/A không có siêu dữ liệu XMP để đóng tem phiên bản.");
      }
      PDMetadata stamped = new PDMetadata(doc);
      stamped.importXMPMetadata(inject(xmp, stamp).getBytes(StandardCharsets.UTF_8));
      catalog.setMetadata(stamped);
      ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 4096);
      doc.save(out, CompressParameters.NO_COMPRESSION);
      return out.toByteArray();
    } catch (IOException e) {
      throw new RejectedDocument("Không đóng được tem phiên bản vào tệp PDF: " + e.getMessage());
    }
  }

  /** Chèn khai báo lược đồ mở rộng và giá trị tem vào gói XMP. */
  static String inject(String xmp, RenderStamp stamp) {
    Matcher about = ABOUT.matcher(xmp);
    String subject = about.find() ? about.group(1) : "";
    StringBuilder values =
        new StringBuilder("<rdf:Description rdf:about=\"")
            .append(escape(subject))
            .append("\" xmlns:")
            .append(PREFIX)
            .append("=\"")
            .append(NS)
            .append("\">");
    StringBuilder properties = new StringBuilder();
    stamp
        .properties()
        .forEach(
            (name, value) -> {
              values.append('<').append(PREFIX).append(':').append(name).append('>');
              values.append(escape(value));
              values.append("</").append(PREFIX).append(':').append(name).append('>');
              properties
                  .append("<rdf:li rdf:parseType=\"Resource\"><pdfaProperty:name>")
                  .append(name)
                  .append("</pdfaProperty:name><pdfaProperty:valueType>Text")
                  .append("</pdfaProperty:valueType><pdfaProperty:category>external")
                  .append("</pdfaProperty:category><pdfaProperty:description>")
                  .append(escape(DESCRIPTIONS.getOrDefault(name, name)))
                  .append("</pdfaProperty:description></rdf:li>");
            });
    values.append("</rdf:Description>");
    String schema =
        "<rdf:li rdf:parseType=\"Resource\" xmlns:pdfaSchema=\""
            + SCHEMA_NS
            + "\" xmlns:pdfaProperty=\""
            + PROPERTY_NS
            + "\"><pdfaSchema:schema>Tem phiên bản hồ sơ số</pdfaSchema:schema>"
            + "<pdfaSchema:namespaceURI>"
            + NS
            + "</pdfaSchema:namespaceURI><pdfaSchema:prefix>"
            + PREFIX
            + "</pdfaSchema:prefix><pdfaSchema:property><rdf:Seq>"
            + properties
            + "</rdf:Seq></pdfaSchema:property></rdf:li>";
    Matcher bag = EXISTING_BAG.matcher(xmp);
    String withSchema;
    if (bag.find()) {
      withSchema = xmp.substring(0, bag.end()) + schema + xmp.substring(bag.end());
    } else {
      String extension =
          "<rdf:Description rdf:about=\""
              + escape(subject)
              + "\" xmlns:pdfaExtension=\""
              + EXTENSION_NS
              + "\"><pdfaExtension:schemas><rdf:Bag>"
              + schema
              + "</rdf:Bag></pdfaExtension:schemas></rdf:Description>";
      withSchema = insertBeforeRdfEnd(xmp, extension);
    }
    return insertBeforeRdfEnd(withSchema, values.toString());
  }

  private static String insertBeforeRdfEnd(String xmp, String fragment) {
    int end = xmp.lastIndexOf("</rdf:RDF>");
    return xmp.substring(0, end) + fragment + xmp.substring(end);
  }

  /**
   * Đọc tem từ XMP của một tệp; rỗng khi tệp không có tem. Dùng khi đối chiếu tệp với manifest và
   * trong kiểm thử.
   */
  public static Optional<Map<String, String>> read(byte[] pdf) {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      PDMetadata metadata = doc.getDocumentCatalog().getMetadata();
      if (metadata == null) {
        return Optional.empty();
      }
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      NodeList nodes =
          factory
              .newDocumentBuilder()
              .parse(new ByteArrayInputStream(metadata.toByteArray()))
              .getElementsByTagNameNS(NS, "*");
      Map<String, String> out = new LinkedHashMap<>();
      for (int i = 0; i < nodes.getLength(); i++) {
        Node node = nodes.item(i);
        if (node instanceof Element e) {
          out.put(e.getLocalName(), e.getTextContent());
        }
      }
      return out.isEmpty() ? Optional.empty() : Optional.of(out);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  static String escape(String value) {
    return Docx4jMerger.escape(value);
  }
}
