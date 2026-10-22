package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import org.docx4j.model.datastorage.migration.VariablePrepare;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.DocxMerger;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;

/**
 * Trộn dữ liệu vào mẫu DOCX bằng docx4j 11 (Apache 2.0): gom các đoạn chạy bị Word tách để chỗ giữ
 * {@code ${ten}} liền một khối ({@link VariablePrepare}), rồi thay giá trị đã thoát ký tự XML.
 */
public class Docx4jMerger implements DocxMerger {

  @Override
  public byte[] merge(byte[] template, Map<String, String> fields) {
    try {
      WordprocessingMLPackage pkg =
          WordprocessingMLPackage.load(new ByteArrayInputStream(template));
      VariablePrepare.prepare(pkg);
      Map<String, String> escaped = new HashMap<>();
      fields.forEach((k, v) -> escaped.put(k, escape(v)));
      pkg.getMainDocumentPart().variableReplace(escaped);
      ByteArrayOutputStream out = new ByteArrayOutputStream(template.length * 2);
      pkg.save(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new RejectedDocument("Không trộn được dữ liệu vào mẫu DOCX: " + e.getMessage());
    }
  }

  static String escape(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&apos;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
