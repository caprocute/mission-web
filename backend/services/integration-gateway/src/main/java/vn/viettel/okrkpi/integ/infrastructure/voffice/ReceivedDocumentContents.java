package vn.viettel.okrkpi.integ.infrastructure.voffice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import vn.viettel.okrkpi.integ.application.port.ReceivedDocumentParser;
import vn.viettel.okrkpi.integ.infrastructure.edxml.Edxml;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument;

/**
 * Toàn văn văn bản chỉ đạo từ gói đã nhận (CORE-02-16). Gói vOffice JSON đọc {@code paragraphs}
 * (chuỗi hoặc {@code {no,text}}) hoặc {@code content} tách theo dòng; gói EdXML (vOffice, trục liên
 * thông) chỉ có nội dung ở tệp đính kèm {@code text/plain}, thiếu thì đoạn 1 là trích yếu (suy
 * luận: tệp PDF, DOCX chưa trích chữ ở gateway).
 */
public class ReceivedDocumentContents implements ReceivedDocumentParser {

  static final int MAX_BYTES = 32 * 1024 * 1024;
  static final Set<String> LEVELS = Set.of("C0", "C1", "C2", "C3");

  private final ObjectMapper json;

  public ReceivedDocumentContents(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public Parsed parse(String messageType, byte[] raw) {
    if (messageType != null && messageType.toUpperCase(Locale.ROOT).contains("JSON")) {
      return fromJson(raw);
    }
    return fromEdxml(raw);
  }

  private Parsed fromEdxml(byte[] raw) {
    EdxmlDocument d = Edxml.parse(raw, MAX_BYTES);
    StringBuilder text = new StringBuilder();
    for (EdxmlDocument.Attachment a : d.attachments()) {
      if (a.contentType() != null
          && a.contentType().toLowerCase(Locale.ROOT).startsWith("text/plain")) {
        text.append(new String(a.content(), StandardCharsets.UTF_8)).append('\n');
      }
    }
    List<Paragraph> paragraphs = split(text.toString());
    if (paragraphs.isEmpty() && d.subject() != null) {
      paragraphs = List.of(new Paragraph(1, d.subject()));
    }
    return new Parsed(
        "EdXML",
        d.firstRecipient(),
        d.from() == null ? "" : nz(d.from().name()),
        nz(d.subject()),
        "",
        paragraphs,
        d.to().stream()
            .map(EdxmlDocument.Organ::name)
            .filter(Objects::nonNull)
            .collect(Collectors.joining(", ")),
        "C1");
  }

  private Parsed fromJson(byte[] raw) {
    JsonNode n;
    try {
      n = json.readTree(raw);
    } catch (IOException e) {
      throw new IllegalArgumentException("Gói vOffice không đọc được", e);
    }
    List<Paragraph> paragraphs = new ArrayList<>();
    JsonNode items = n.path("paragraphs");
    if (items.isArray()) {
      for (JsonNode p : items) {
        String text = p.isTextual() ? p.asText() : p.path("text").asText("");
        if (!text.isBlank()) {
          paragraphs.add(new Paragraph(paragraphs.size() + 1, text.strip()));
        }
      }
    } else {
      paragraphs.addAll(split(n.path("content").asText("")));
    }
    String summary = n.path("summary").asText("");
    if (paragraphs.isEmpty() && !summary.isBlank()) {
      paragraphs.add(new Paragraph(1, summary));
    }
    JsonNode to = n.path("recipients");
    String recipients =
        to.isArray()
            ? java.util.stream.StreamSupport.stream(to.spliterator(), false)
                .map(r -> r.isTextual() ? r.asText() : r.path("name").asText(""))
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(", "))
            : to.asText("");
    String level = n.path("classification").asText("C1").toUpperCase(Locale.ROOT);
    return new Parsed(
        "vOffice",
        n.path("recipientUnitId").asText(null),
        n.path("issuerHeader").asText(n.path("issuer").asText("")),
        n.path("title").asText(summary),
        n.path("preamble").asText(""),
        paragraphs,
        recipients,
        LEVELS.contains(level) ? level : "C1");
  }

  static List<Paragraph> split(String text) {
    List<Paragraph> out = new ArrayList<>();
    for (String line : text.split("\\R")) {
      String s = line.strip();
      if (!s.isEmpty()) {
        out.add(new Paragraph(out.size() + 1, s));
      }
    }
    return out;
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
