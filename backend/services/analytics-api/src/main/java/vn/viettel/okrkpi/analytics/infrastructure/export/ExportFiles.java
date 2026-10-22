package vn.viettel.okrkpi.analytics.infrastructure.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import vn.viettel.okrkpi.analytics.application.ExportPorts;
import vn.viettel.okrkpi.analytics.domain.ReportDoc;
import vn.viettel.okrkpi.analytics.domain.TraceChain;
import vn.viettel.okrkpi.analytics.domain.TraceLink;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Dựng tệp xuất: PDF qua Gotenberg (route Chromium chuyển HTML, khổ A4), XLSX ghi theo luồng bằng
 * FastExcel, JSON chuỗi bằng chứng bằng Jackson, gói ZIP khi xuất nhiều định dạng. HTML chỉ gồm văn
 * bản đã thoát ký tự, không có script.
 */
final class ExportFiles implements ExportPorts.Files {

  private final ResilientClient gotenberg;
  private final ObjectMapper json;
  private final Clock clock;

  ExportFiles(ResilientClient gotenberg, ObjectMapper json, Clock clock) {
    this.gotenberg = gotenberg;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public byte[] pdf(ReportDoc doc) {
    byte[] html = html(doc).getBytes(StandardCharsets.UTF_8);
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "files",
        new ByteArrayResource(html) {
          @Override
          public String getFilename() {
            return "index.html";
          }
        });
    form.add("paperWidth", "8.27");
    form.add("paperHeight", "11.7");
    form.add("marginTop", "0.6");
    form.add("marginBottom", "0.6");
    form.add("marginLeft", "0.6");
    form.add("marginRight", "0.6");
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.MULTIPART_FORM_DATA);
    byte[] pdf =
        gotenberg.exchange(HttpMethod.POST, "/forms/chromium/convert/html", form, h, byte[].class);
    if (pdf == null || pdf.length == 0) {
      throw new IllegalStateException("Gotenberg trả tệp rỗng");
    }
    return pdf;
  }

  static String html(ReportDoc doc) {
    StringBuilder b = new StringBuilder(4096);
    b.append("<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\"><title>")
        .append(esc(doc.title()))
        .append("</title><style>")
        .append("body{font-family:'Noto Sans','DejaVu Sans',Arial,sans-serif;font-size:11pt;")
        .append(
            "color:#1a1a1a}h1{font-size:16pt;margin:0 0 8pt}h2{font-size:13pt;margin:14pt 0 6pt}")
        .append("p.note{margin:2pt 0;color:#444}table{border-collapse:collapse;width:100%}")
        .append("th,td{border:1px solid #999;padding:3pt 5pt;text-align:left;vertical-align:top;")
        .append("word-break:break-word}th{background:#eee}")
        .append("</style></head><body><h1>")
        .append(esc(doc.title()))
        .append("</h1>");
    for (String n : doc.notes()) {
      b.append("<p class=\"note\">").append(esc(n)).append("</p>");
    }
    for (ReportDoc.Section s : doc.sections()) {
      b.append("<h2>").append(esc(s.title())).append("</h2><table><thead><tr>");
      for (String h : s.headers()) {
        b.append("<th>").append(esc(h)).append("</th>");
      }
      b.append("</tr></thead><tbody>");
      if (s.rows().isEmpty()) {
        b.append("<tr><td colspan=\"")
            .append(Math.max(1, s.headers().size()))
            .append("\">Chưa có số liệu</td></tr>");
      }
      for (List<String> row : s.rows()) {
        b.append("<tr>");
        for (String c : row) {
          b.append("<td>").append(esc(c)).append("</td>");
        }
        b.append("</tr>");
      }
      b.append("</tbody></table>");
    }
    return b.append("</body></html>").toString();
  }

  static String esc(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '<' -> b.append("&lt;");
        case '>' -> b.append("&gt;");
        case '&' -> b.append("&amp;");
        case '"' -> b.append("&quot;");
        case '\'' -> b.append("&#39;");
        default -> b.append(c);
      }
    }
    return b.toString();
  }

  @Override
  public byte[] xlsx(ReportDoc doc) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      Workbook wb = new Workbook(out, "Mission", "1.0");
      int n = 0;
      for (ReportDoc.Section s : doc.sections()) {
        Worksheet ws = wb.newWorksheet(sheetName(s.title(), ++n));
        int r = 0;
        ws.value(r, 0, doc.title());
        ws.style(r, 0).bold().set();
        r++;
        for (String note : doc.notes()) {
          ws.value(r++, 0, note);
        }
        r++;
        for (int c = 0; c < s.headers().size(); c++) {
          ws.value(r, c, s.headers().get(c));
          ws.style(r, c).bold().fillColor("EEEEEE").set();
        }
        r++;
        for (List<String> row : s.rows()) {
          for (int c = 0; c < row.size(); c++) {
            ws.value(r, c, row.get(c));
          }
          r++;
        }
        ws.finish();
      }
      if (n == 0) {
        wb.newWorksheet("Báo cáo").value(0, 0, doc.title());
      }
      wb.finish();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** Tên trang tính: tối đa 31 ký tự, không có ký tự Excel cấm, không trùng. */
  private static String sheetName(String title, int n) {
    String clean = title == null ? "" : title.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
    String prefix = n + ". ";
    int max = 31 - prefix.length();
    return prefix + (clean.length() > max ? clean.substring(0, max) : clean);
  }

  @Override
  public byte[] traceJson(TraceChain chain, List<String> linkHashes, String chainHash) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("focus", Map.of("objectType", chain.focusType().name(), "id", chain.focusId()));
    root.put("generatedAt", clock.instant().toString());
    root.put("hashAlgorithm", "SHA-256");
    root.put("chainHash", chainHash);
    if (chain.ledgerRef() != null) {
      root.put("ledgerRef", chain.ledgerRef());
    }
    List<Map<String, Object>> links = new ArrayList<>();
    for (int i = 0; i < chain.links().size(); i++) {
      TraceLink l = chain.links().get(i);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("objectType", l.objectType().name());
      m.put("id", l.id());
      m.put("label", l.label());
      put(m, "value", l.value());
      put(m, "status", l.status());
      put(m, "versionNo", l.versionNo());
      put(m, "unitName", l.unitName());
      m.put("masked", l.masked());
      put(m, "msg", l.msg());
      if (!l.masked()) {
        put(m, "details", l.details());
      }
      m.put("hash", linkHashes.get(i));
      links.add(m);
    }
    root.put("links", links);
    try {
      return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void put(Map<String, Object> m, String k, Object v) {
    if (v != null) {
      m.put(k, v);
    }
  }

  @Override
  public byte[] zip(Map<String, byte[]> entries) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      for (var e : entries.entrySet()) {
        z.putNextEntry(new ZipEntry(e.getKey()));
        z.write(e.getValue());
        z.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
