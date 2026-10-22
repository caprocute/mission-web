package vn.viettel.okrkpi.config.application;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Kiểm SVG của UI Pack theo danh sách cho phép (CORE-08-10, TL-01 "An toàn của SDUI"): chỉ phần tử
 * vẽ tĩnh, không {@code script}, {@code foreignObject}, không thuộc tính {@code on*}, {@code href}
 * chỉ trỏ nội bộ ({@code #id}), không {@code javascript:} hay {@code url()} ra ngoài, không DOCTYPE
 * hay thực thể. Làm sạch theo nghĩa từ chối: tệp cần bỏ bất cứ thứ gì thì khác bản gốc và bị trả
 * lại.
 */
public final class SvgSanitizer {

  static final Set<String> ELEMENTS =
      Set.of(
          "svg",
          "g",
          "path",
          "rect",
          "circle",
          "ellipse",
          "line",
          "polyline",
          "polygon",
          "text",
          "tspan",
          "defs",
          "lineargradient",
          "radialgradient",
          "stop",
          "clippath",
          "mask",
          "title",
          "desc",
          "use",
          "symbol",
          "pattern",
          "metadata");

  private SvgSanitizer() {}

  /** Danh sách vi phạm, rỗng khi SVG an toàn. */
  public static List<String> violations(byte[] svg) {
    List<String> problems = new ArrayList<>();
    Element root;
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      f.setNamespaceAware(true);
      DocumentBuilder b = f.newDocumentBuilder();
      b.setErrorHandler(null);
      root = b.parse(new ByteArrayInputStream(svg)).getDocumentElement();
    } catch (Exception e) {
      problems.add("Tệp SVG không đọc được hoặc có khai báo DOCTYPE");
      return problems;
    }
    if (!"svg".equals(local(root))) {
      problems.add("Tệp không phải SVG");
      return problems;
    }
    walk(root, problems);
    return problems;
  }

  private static void walk(Element e, List<String> problems) {
    String name = local(e);
    if (!ELEMENTS.contains(name)) {
      problems.add("Phần tử <" + name + "> không được phép");
      return;
    }
    NamedNodeMap attrs = e.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++) {
      Attr a = (Attr) attrs.item(i);
      String attr =
          (a.getLocalName() == null ? a.getName() : a.getLocalName()).toLowerCase(Locale.ROOT);
      String value = a.getValue().trim();
      String compact = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
      if (attr.startsWith("on")) {
        problems.add("Thuộc tính sự kiện " + attr + " không được phép");
      } else if (attr.equals("href") && !value.startsWith("#")) {
        problems.add("Liên kết ra ngoài trong <" + name + "> không được phép");
      } else if (compact.contains("javascript:")
          || compact.contains("expression(")
          || compact.contains("@import")
          || (compact.contains("url(") && !compact.matches(".*url\\(#[^)]*\\).*"))) {
        problems.add("Thuộc tính " + attr + " của <" + name + "> có nội dung không được phép");
      }
    }
    NodeList children = e.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        walk((Element) n, problems);
      } else if (n.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
          || n.getNodeType() == Node.ENTITY_REFERENCE_NODE) {
        problems.add("Tệp SVG có chỉ thị xử lý không được phép");
      }
    }
  }

  private static String local(Element e) {
    String n = e.getLocalName() == null ? e.getTagName() : e.getLocalName();
    return n.toLowerCase(Locale.ROOT);
  }
}
