package vn.viettel.okrkpi.integ.infrastructure.edxml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument.Attachment;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument.Organ;

/**
 * Phân tích và dựng gói EdXML ({@code edXMLEnvelope}: {@code edXMLHeader/MessageHeader}, {@code
 * Signature}, {@code edXMLBody}, {@code AttachmentEncoded/Attachment}). Đọc bằng StAX, tắt DTD và
 * thực thể ngoài (chống XXE), so khớp theo tên cục bộ nên không phụ thuộc tiền tố không gian tên;
 * dựng bằng tiền tố {@code edXML} của không gian tên {@value #NS}. Dựng rồi phân tích lại cho cùng
 * nội dung (khứ hồi). Chữ ký số chỉ được ghi nhận có hay không; ký và kiểm chữ ký ở DV-13-02.
 */
public final class Edxml {

  public static final String NS = "http://www.e-doc.vn/Schema/";
  static final String PREFIX = "edXML";

  private Edxml() {}

  /** Phân tích gói; lỗi cấu trúc hoặc thiếu trường bắt buộc là {@link IllegalArgumentException}. */
  public static EdxmlDocument parse(byte[] xml, int maxBytes) {
    if (xml.length > maxBytes) {
      throw new IllegalArgumentException(
          "Gói EdXML vượt quá " + (maxBytes / (1024 * 1024)) + " MB");
    }
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    Builder b = new Builder();
    Deque<String> path = new ArrayDeque<>();
    try {
      XMLStreamReader r = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
      try {
        while (r.hasNext()) {
          int event = r.next();
          if (event == XMLStreamConstants.START_ELEMENT) {
            String name = r.getLocalName();
            String parent = path.peek();
            path.push(name);
            if ("Signature".equals(name)) {
              b.signed = true;
            }
            if (path.contains("Attachment")) {
              attachmentField(b, name, r, path);
            } else if (path.contains("MessageHeader")) {
              headerField(b, name, parent, r, path);
            }
          } else if (event == XMLStreamConstants.END_ELEMENT) {
            String name = path.pop();
            if ("Attachment".equals(name)) {
              b.closeAttachment();
            } else if ("From".equals(name) || "To".equals(name)) {
              b.closeOrgan(name);
            }
          }
        }
      } finally {
        r.close();
      }
    } catch (XMLStreamException e) {
      throw new IllegalArgumentException("Gói EdXML không đúng cấu trúc", e);
    }
    return b.build();
  }

  /** Mã văn bản ({@code DocumentId}) đọc nhanh từ tiêu đề, không giải mã tệp đính kèm. */
  public static String documentId(byte[] xml) {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      XMLStreamReader r = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
      try {
        while (r.hasNext()) {
          int event = r.next();
          if (event == XMLStreamConstants.START_ELEMENT) {
            String name = r.getLocalName();
            if ("DocumentId".equals(name)) {
              String v = r.getElementText().trim();
              return v.isEmpty() ? null : v;
            }
            if ("edXMLBody".equals(name) || "AttachmentEncoded".equals(name)) {
              return null;
            }
          }
        }
        return null;
      } finally {
        r.close();
      }
    } catch (XMLStreamException e) {
      throw new IllegalArgumentException("Gói EdXML không đúng cấu trúc", e);
    }
  }

  /** Dựng gói EdXML UTF-8. */
  public static byte[] build(EdxmlDocument d) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      XMLStreamWriter w =
          XMLOutputFactory.newFactory().createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
      w.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
      w.setPrefix(PREFIX, NS);
      w.writeStartElement(PREFIX, "edXMLEnvelope", NS);
      w.writeNamespace(PREFIX, NS);
      w.writeStartElement(PREFIX, "edXMLHeader", NS);
      w.writeStartElement(PREFIX, "MessageHeader", NS);
      organ(w, "From", d.from());
      for (Organ o : d.to()) {
        organ(w, "To", o);
      }
      leaf(w, "DocumentId", d.documentId());
      w.writeStartElement(PREFIX, "Code", NS);
      leaf(w, "CodeNumber", d.codeNumber());
      leaf(w, "CodeNotation", d.codeNotation());
      w.writeEndElement();
      w.writeStartElement(PREFIX, "PromulgationInfo", NS);
      leaf(w, "Place", d.place());
      leaf(
          w,
          "PromulgationDate",
          d.promulgationDate() == null ? null : d.promulgationDate().toString());
      w.writeEndElement();
      if (d.documentType() != null) {
        w.writeStartElement(PREFIX, "DocumentType", NS);
        leaf(w, "TypeName", d.documentType());
        w.writeEndElement();
      }
      leaf(w, "Subject", d.subject());
      if (d.signerName() != null || d.signerPosition() != null) {
        w.writeStartElement(PREFIX, "SignerInfo", NS);
        leaf(w, "Position", d.signerPosition());
        leaf(w, "FullName", d.signerName());
        w.writeEndElement();
      }
      w.writeEndElement(); // MessageHeader
      w.writeEndElement(); // edXMLHeader
      w.writeStartElement(PREFIX, "edXMLBody", NS);
      w.writeEndElement();
      if (!d.attachments().isEmpty()) {
        w.writeStartElement(PREFIX, "AttachmentEncoded", NS);
        for (Attachment a : d.attachments()) {
          w.writeStartElement(PREFIX, "Attachment", NS);
          leaf(w, "ContentId", a.contentId());
          leaf(w, "ContentType", a.contentType());
          leaf(w, "AttachmentName", a.name());
          leaf(w, "ContentTransferEncoded", "base64");
          leaf(w, "Value", Base64.getEncoder().encodeToString(a.content()));
          w.writeEndElement();
        }
        w.writeEndElement();
      }
      w.writeEndElement(); // edXMLEnvelope
      w.writeEndDocument();
      w.close();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("Không dựng được gói EdXML", e);
    }
    return out.toByteArray();
  }

  private static void organ(XMLStreamWriter w, String element, Organ o) throws XMLStreamException {
    if (o == null) {
      return;
    }
    w.writeStartElement(PREFIX, element, NS);
    leaf(w, "OrganId", o.id());
    leaf(w, "OrganName", o.name());
    w.writeEndElement();
  }

  private static void leaf(XMLStreamWriter w, String element, String value)
      throws XMLStreamException {
    if (value == null) {
      return;
    }
    w.writeStartElement(PREFIX, element, NS);
    w.writeCharacters(value);
    w.writeEndElement();
  }

  private static void headerField(
      Builder b, String name, String parent, XMLStreamReader r, Deque<String> path)
      throws XMLStreamException {
    switch (name) {
      case "DocumentId" -> b.documentId = text(r, path);
      case "CodeNumber" -> b.codeNumber = text(r, path);
      case "CodeNotation" -> b.codeNotation = text(r, path);
      case "Place" -> b.place = text(r, path);
      case "PromulgationDate" -> b.date = text(r, path);
      case "TypeName" -> b.documentType = text(r, path);
      case "Subject" -> b.subject = text(r, path);
      case "FullName" -> {
        if ("SignerInfo".equals(parent)) {
          b.signerName = text(r, path);
        }
      }
      case "Position" -> {
        if ("SignerInfo".equals(parent)) {
          b.signerPosition = text(r, path);
        }
      }
      case "OrganId" -> {
        if ("From".equals(parent) || "To".equals(parent)) {
          b.organId = text(r, path);
        }
      }
      case "OrganName" -> {
        if ("From".equals(parent) || "To".equals(parent)) {
          b.organName = text(r, path);
        }
      }
      default -> {
        // Bỏ qua phần tử khác của tiêu đề.
      }
    }
  }

  private static void attachmentField(Builder b, String name, XMLStreamReader r, Deque<String> path)
      throws XMLStreamException {
    switch (name) {
      case "ContentId" -> b.attId = text(r, path);
      case "ContentType" -> b.attType = text(r, path);
      case "AttachmentName" -> b.attName = text(r, path);
      case "Value" -> {
        String v = text(r, path);
        try {
          b.attContent = v == null ? new byte[0] : Base64.getMimeDecoder().decode(v);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("Tệp đính kèm EdXML không phải base64", e);
        }
      }
      default -> {
        // Bỏ qua phần tử khác của tệp đính kèm.
      }
    }
  }

  /** Đọc văn bản của phần tử hiện tại; {@code getElementText} tiêu thụ luôn thẻ đóng. */
  private static String text(XMLStreamReader r, Deque<String> path) throws XMLStreamException {
    String v = r.getElementText().trim();
    path.pop();
    return v.isEmpty() ? null : v;
  }

  private static final class Builder {
    String documentId;
    String codeNumber;
    String codeNotation;
    String place;
    String date;
    String documentType;
    String subject;
    String signerName;
    String signerPosition;
    String organId;
    String organName;
    Organ from;
    final List<Organ> to = new ArrayList<>();
    String attId;
    String attType;
    String attName;
    byte[] attContent;
    final List<Attachment> attachments = new ArrayList<>();
    boolean signed;

    void closeOrgan(String element) {
      Organ o = new Organ(organId, organName);
      if ("From".equals(element)) {
        if (from == null) {
          from = o;
        }
      } else if (organId != null || organName != null) {
        to.add(o);
      }
      organId = null;
      organName = null;
    }

    void closeAttachment() {
      attachments.add(
          new Attachment(attId, attName, attType, attContent == null ? new byte[0] : attContent));
      attId = null;
      attType = null;
      attName = null;
      attContent = null;
    }

    EdxmlDocument build() {
      if (codeNumber == null
          || date == null
          || subject == null
          || from == null
          || from.name() == null) {
        throw new IllegalArgumentException(
            "Gói EdXML thiếu số, ngày, trích yếu hoặc cơ quan ban hành");
      }
      LocalDate issued;
      try {
        issued = LocalDate.parse(date);
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("Ngày ban hành trong gói EdXML không hợp lệ", e);
      }
      return new EdxmlDocument(
          documentId,
          from,
          to,
          codeNumber,
          codeNotation,
          place,
          issued,
          documentType,
          subject,
          signerName,
          signerPosition,
          attachments,
          signed);
    }
  }
}
