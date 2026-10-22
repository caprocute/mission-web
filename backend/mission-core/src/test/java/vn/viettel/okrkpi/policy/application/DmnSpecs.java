package vn.viettel.okrkpi.policy.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Column;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Row;

/**
 * Đọc tệp DMN của gói thành {@link DecisionTableSpec} cho kiểm thử (CORE-05-07): cột lấy {@code
 * inputExpression}, dòng lấy {@code inputEntry} (bỏ ô {@code -}) và {@code outputEntry} bỏ ngoặc
 * kép. Khi nhập gói, bảng do người soạn lưu qua SCR-1502 nên không có bộ đọc DMN ở mã chạy.
 */
final class DmnSpecs {

  private DmnSpecs() {}

  /** Bảng theo {@code classification.bang_quyet_dinh} của manifest. */
  @SuppressWarnings("unchecked")
  static Map<String, DecisionTableSpec> of(Path packDir, Map<String, Object> manifest) {
    Map<String, DecisionTableSpec> out = new LinkedHashMap<>();
    Map<String, Object> cls = (Map<String, Object>) manifest.get("classification");
    for (Map<String, Object> t : (List<Map<String, Object>>) cls.get("bang_quyet_dinh")) {
      out.put(
          (String) t.get("table_key"),
          read(packDir.resolve((String) t.get("dmn")), (String) t.get("hit_policy")));
    }
    return out;
  }

  static DecisionTableSpec read(Path file, String hitPolicy) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      Document doc = f.newDocumentBuilder().parse(Files.newInputStream(file));
      List<Column> columns = new ArrayList<>();
      NodeList inputs = doc.getElementsByTagNameNS("*", "input");
      for (int i = 0; i < inputs.getLength(); i++) {
        Element in = (Element) inputs.item(i);
        Element expr = (Element) in.getElementsByTagNameNS("*", "inputExpression").item(0);
        columns.add(
            new Column(text(expr).strip(), in.getAttribute("label"), expr.getAttribute("typeRef")));
      }
      List<Row> rows = new ArrayList<>();
      NodeList rules = doc.getElementsByTagNameNS("*", "rule");
      for (int i = 0; i < rules.getLength(); i++) {
        Element rule = (Element) rules.item(i);
        NodeList entries = rule.getElementsByTagNameNS("*", "inputEntry");
        Map<String, String> cells = new LinkedHashMap<>();
        for (int j = 0; j < entries.getLength(); j++) {
          String cell = text((Element) entries.item(j)).strip();
          if (!cell.equals("-") && !cell.isEmpty()) {
            cells.put(columns.get(j).name(), cell);
          }
        }
        Element output = (Element) rule.getElementsByTagNameNS("*", "outputEntry").item(0);
        rows.add(new Row(cells, text(output).strip().replace("\"", ""), "", ""));
      }
      return new DecisionTableSpec(hitPolicy, columns, rows);
    } catch (IOException
        | javax.xml.parsers.ParserConfigurationException
        | org.xml.sax.SAXException e) {
      throw new IllegalStateException("Không đọc được " + file, e);
    }
  }

  /** {@code rules_json} như {@code PolicyDecisionTableService} lưu. */
  static String rulesJson(DecisionTableSpec spec) {
    List<Map<String, Object>> inputs = new ArrayList<>();
    spec.columns()
        .forEach(c -> inputs.add(Map.of("name", c.name(), "label", c.label(), "type", c.type())));
    List<Map<String, Object>> rules = new ArrayList<>();
    spec.rows()
        .forEach(
            r ->
                rules.add(
                    Map.of(
                        "inputs", r.inputs(),
                        "ratingLevel", r.ratingLevel(),
                        "clause", r.clause(),
                        "explanation", r.explanation())));
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .writeValueAsString(Map.of("inputs", inputs, "rules", rules));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(Element e) {
    return e.getElementsByTagNameNS("*", "text").item(0).getTextContent();
  }
}
