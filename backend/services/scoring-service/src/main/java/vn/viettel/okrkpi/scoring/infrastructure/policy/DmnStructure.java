package vn.viettel.okrkpi.scoring.infrastructure.policy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import vn.viettel.okrkpi.scoring.domain.DecisionTable;

/**
 * Đọc cấu trúc bảng quyết định đầu tiên của tệp DMN gốc (trước khi đổi ô cho Flowable): cột, dòng,
 * ô, đầu ra và chú thích theo tên khai ở {@code <annotation name>} (DMN 1.3+). XML đọc an toàn như
 * {@link FeelUnaryLists}.
 */
final class DmnStructure {

  private DmnStructure() {}

  /** Cấu trúc bảng; {@code null} khi XML không có bảng quyết định. */
  static DecisionTable parse(String key, String xml)
      throws ParserConfigurationException, SAXException, IOException {
    NodeList tables = FeelUnaryLists.parse(xml).getElementsByTagNameNS("*", "decisionTable");
    if (tables.getLength() == 0) {
      return null;
    }
    Element table = (Element) tables.item(0);
    List<DecisionTable.Column> columns = new ArrayList<>();
    for (Element input : FeelUnaryLists.children(table, "input")) {
      Element expr = FeelUnaryLists.first(input, "inputExpression");
      Element text = expr == null ? null : FeelUnaryLists.first(expr, "text");
      columns.add(
          new DecisionTable.Column(
              text == null ? "" : text.getTextContent().strip(),
              input.getAttribute("label"),
              expr == null ? "" : expr.getAttribute("typeRef")));
    }
    List<String> annotationNames = new ArrayList<>();
    for (Element a : FeelUnaryLists.children(table, "annotation")) {
      annotationNames.add(a.getAttribute("name"));
    }
    List<DecisionTable.Rule> rules = new ArrayList<>();
    for (Element rule : FeelUnaryLists.children(table, "rule")) {
      List<String> cells = new ArrayList<>();
      for (Element entry : FeelUnaryLists.children(rule, "inputEntry")) {
        cells.add(text(entry));
      }
      List<Element> outputs = FeelUnaryLists.children(rule, "outputEntry");
      Map<String, String> annotations = new LinkedHashMap<>();
      List<Element> entries = FeelUnaryLists.children(rule, "annotationEntry");
      for (int i = 0; i < entries.size() && i < annotationNames.size(); i++) {
        annotations.put(annotationNames.get(i), text(entries.get(i)));
      }
      Element description = FeelUnaryLists.first(rule, "description");
      rules.add(
          new DecisionTable.Rule(
              rule.getAttribute("id"),
              description == null ? null : description.getTextContent().strip(),
              cells,
              outputs.isEmpty() ? null : text(outputs.getFirst()),
              annotations));
    }
    String hit = table.getAttribute("hitPolicy");
    return new DecisionTable(key, hit.isBlank() ? "UNIQUE" : hit, columns, rules);
  }

  private static String text(Element parent) {
    Element t = FeelUnaryLists.first(parent, "text");
    return t == null ? "" : t.getTextContent().strip();
  }
}
