package vn.viettel.okrkpi.scoring.infrastructure.policy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Đổi ô đầu vào dạng khoảng FEEL ({@code [0.9..1)}) và dạng danh sách FEEL ({@code "A","B"} hay
 * {@code 1, 2}: khớp khi bằng một trong các giá trị) mà Flowable DMN 7.2 không phân tích được sang
 * biểu thức tương đương {@code ${x == "A" || x == "B"}} theo biểu thức đầu vào của cột. Ô khác giữ
 * nguyên. Đọc XML với xử lý an toàn, cấm DOCTYPE.
 */
final class FeelUnaryLists {

  private static final Pattern LITERAL = Pattern.compile("\"[^\"]*\"|-?\\d+(\\.\\d+)?|true|false");
  private static final Pattern INTERVAL =
      Pattern.compile(
          "([\\[(\\]])\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:\\.\\d+)?)\\s*([\\])\\[])");
  private static final Pattern COMPARISON = Pattern.compile("(<=|>=|<|>)\\s*(-?\\d+(\\.\\d+)?)");

  private FeelUnaryLists() {}

  /**
   * Biểu thức đầu vào của các cột ở bảng quyết định đầu tiên; rỗng khi XML không đọc được (lỗi nạp
   * do Flowable báo).
   */
  static List<String> inputs(String xml) {
    try {
      NodeList tables = parse(xml).getElementsByTagNameNS("*", "decisionTable");
      if (tables.getLength() == 0) {
        return List.of();
      }
      List<String> out = new ArrayList<>();
      for (String s : columns((Element) tables.item(0))) {
        if (s != null) {
          out.add(s);
        }
      }
      return List.copyOf(out);
    } catch (ParserConfigurationException | SAXException | IOException e) {
      return List.of();
    }
  }

  static String rewrite(String xml) {
    try {
      Document doc = parse(xml);
      boolean changed = false;
      NodeList tables = doc.getElementsByTagNameNS("*", "decisionTable");
      for (int t = 0; t < tables.getLength(); t++) {
        Element table = (Element) tables.item(t);
        List<String> inputs = columns(table);
        for (Element rule : children(table, "rule")) {
          List<Element> entries = children(rule, "inputEntry");
          for (int i = 0; i < entries.size() && i < inputs.size(); i++) {
            Element text = first(entries.get(i), "text");
            if (text == null || inputs.get(i) == null) {
              continue;
            }
            String replaced = expression(inputs.get(i), text.getTextContent().strip());
            if (replaced != null) {
              text.setTextContent(replaced);
              changed = true;
            }
          }
        }
      }
      if (!changed) {
        return xml;
      }
      var transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      StringWriter out = new StringWriter();
      transformer.transform(new DOMSource(doc), new StreamResult(out));
      return out.toString();
    } catch (ParserConfigurationException | SAXException | IOException | TransformerException e) {
      return xml;
    }
  }

  private static Document parse(String xml)
      throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setExpandEntityReferences(false);
    return f.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private static List<String> columns(Element table) {
    List<String> inputs = new ArrayList<>();
    for (Element input : children(table, "input")) {
      Element expr = first(input, "inputExpression");
      Element text = expr == null ? null : first(expr, "text");
      inputs.add(text == null ? null : text.getTextContent().strip());
    }
    return inputs;
  }

  /**
   * Biểu thức thay cho ô danh sách hoặc ô khoảng ({@code [0.9..1)}: dấu ngoặc vuông là gồm biên,
   * ngoặc tròn hay ngoặc vuông quay ngược là không gồm); {@code null} khi ô không thuộc hai dạng
   * đó.
   */
  static String expression(String input, String cell) {
    var interval = INTERVAL.matcher(cell);
    if (interval.matches()) {
      String low = "[".equals(interval.group(1)) ? " >= " : " > ";
      String high = "]".equals(interval.group(4)) ? " <= " : " < ";
      return "${"
          + input
          + low
          + interval.group(2)
          + " && "
          + input
          + high
          + interval.group(3)
          + "}";
    }
    List<String> parts = split(cell);
    if (parts.size() < 2) {
      return null;
    }
    List<String> terms = new ArrayList<>();
    for (String p : parts) {
      if (LITERAL.matcher(p).matches()) {
        terms.add(input + " == " + p);
      } else {
        var m = COMPARISON.matcher(p);
        if (!m.matches()) {
          return null;
        }
        terms.add(input + " " + m.group(1) + " " + m.group(2));
      }
    }
    return "${" + String.join(" || ", terms) + "}";
  }

  /** Tách theo dấu phẩy nằm ngoài chuỗi trích dẫn. */
  static List<String> split(String cell) {
    List<String> parts = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (char c : cell.toCharArray()) {
      if (c == '"') {
        quoted = !quoted;
      }
      if (c == ',' && !quoted) {
        parts.add(cur.toString().strip());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    parts.add(cur.toString().strip());
    return parts;
  }

  private static List<Element> children(Element parent, String local) {
    List<Element> out = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && local.equals(e.getLocalName())) {
        out.add(e);
      }
    }
    return out;
  }

  private static Element first(Element parent, String local) {
    List<Element> found = children(parent, local);
    return found.isEmpty() ? null : found.getFirst();
  }
}
