package vn.viettel.okrkpi.policy.application;

import java.io.StringWriter;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Column;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Row;

/**
 * Sinh {@code dmn_xml} theo DMN 1.4 ({@code https://www.omg.org/spec/DMN/20211108/MODEL/}) cho bảng
 * xếp loại, cùng bố cục với các tệp {@code dmn/*.dmn} của gói mẫu: điều khoản và giải thích là hai
 * cột chú thích ({@code annotation}). Đầu ra xác định (định danh sinh theo khoá bảng) để {@code
 * dmn_hash} ổn định (CORE-05-05).
 */
final class DmnXmlWriter {

  static final String DMN14 = "https://www.omg.org/spec/DMN/20211108/MODEL/";
  static final String FEEL14 = "https://www.omg.org/spec/DMN/20211108/FEEL/";
  static final String OUTPUT = "xep_loai";

  private DmnXmlWriter() {}

  static String write(String packCode, String tableKey, String legalRef, DecisionTableSpec spec) {
    String id = tableKey.replace('_', '-').replace('.', '-');
    StringWriter out = new StringWriter();
    try {
      XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
      w.writeStartDocument("UTF-8", "1.0");
      w.writeStartElement("definitions");
      w.writeDefaultNamespace(DMN14);
      w.writeAttribute("id", id + "-dinh-nghia");
      w.writeAttribute("name", tableKey);
      w.writeAttribute("namespace", "https://policy.okrkpi.vn/" + packCode);
      w.writeAttribute("expressionLanguage", FEEL14);
      w.writeAttribute("typeLanguage", FEEL14);
      w.writeStartElement("decision");
      w.writeAttribute("id", id);
      w.writeAttribute("name", tableKey);
      if (legalRef != null && !legalRef.isBlank()) {
        text(w, "description", legalRef);
      }
      w.writeEmptyElement("variable");
      w.writeAttribute("id", id + "-var");
      w.writeAttribute("name", OUTPUT);
      w.writeAttribute("typeRef", "string");
      w.writeStartElement("decisionTable");
      w.writeAttribute("id", id + "-bang");
      w.writeAttribute("hitPolicy", spec.hitPolicy());
      List<Column> columns = spec.columns();
      for (int c = 0; c < columns.size(); c++) {
        Column col = columns.get(c);
        w.writeStartElement("input");
        w.writeAttribute("id", id + "-in" + (c + 1));
        w.writeAttribute(
            "label", col.label() == null || col.label().isBlank() ? col.name() : col.label());
        w.writeStartElement("inputExpression");
        w.writeAttribute("id", id + "-in" + (c + 1) + "-expr");
        w.writeAttribute("typeRef", col.type());
        text(w, "text", col.name());
        w.writeEndElement();
        w.writeEndElement();
      }
      w.writeStartElement("output");
      w.writeAttribute("id", id + "-out");
      w.writeAttribute("name", OUTPUT);
      w.writeAttribute("typeRef", "string");
      w.writeStartElement("outputValues");
      w.writeAttribute("id", id + "-out-values");
      text(w, "text", "\"HTXSNV\",\"HTTNV\",\"HTNV\",\"KHTNV\"");
      w.writeEndElement();
      w.writeEndElement();
      for (String annotation : List.of("dieu_khoan", "giai_thich")) {
        w.writeEmptyElement("annotation");
        w.writeAttribute("name", annotation);
      }
      List<Row> rows = spec.rows();
      for (int r = 0; r < rows.size(); r++) {
        Row row = rows.get(r);
        String rowId = id + "-dong-" + (r + 1);
        w.writeStartElement("rule");
        w.writeAttribute("id", rowId);
        for (int c = 0; c < columns.size(); c++) {
          String cell = row.inputs().get(columns.get(c).name());
          w.writeStartElement("inputEntry");
          w.writeAttribute("id", rowId + "-in" + (c + 1));
          text(w, "text", cell == null || cell.isBlank() ? "-" : cell.strip());
          w.writeEndElement();
        }
        w.writeStartElement("outputEntry");
        w.writeAttribute("id", rowId + "-out");
        text(w, "text", "\"" + row.ratingLevel() + "\"");
        w.writeEndElement();
        for (String note : List.of(row.clause(), row.explanation())) {
          w.writeStartElement("annotationEntry");
          text(w, "text", note);
          w.writeEndElement();
        }
        w.writeEndElement();
      }
      w.writeEndElement();
      w.writeEndElement();
      w.writeEndElement();
      w.writeEndDocument();
      w.close();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("Không sinh được DMN XML", e);
    }
    return out.toString();
  }

  private static void text(XMLStreamWriter w, String element, String value)
      throws XMLStreamException {
    w.writeStartElement(element);
    w.writeCharacters(value);
    w.writeEndElement();
  }
}
