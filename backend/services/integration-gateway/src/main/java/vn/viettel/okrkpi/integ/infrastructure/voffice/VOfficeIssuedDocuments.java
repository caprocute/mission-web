package vn.viettel.okrkpi.integ.infrastructure.voffice;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * Tra cứu văn bản đã ban hành của đơn vị trên vOffice ({@code GET
 * /api/v2/documents?unitId=&q=&limit=&cursor=}) cho minh chứng (BR-07-13): trạng thái chữ ký số lấy
 * theo kết quả vOffice kiểm khi nhận tệp gốc; tệp gốc giữ nguyên ở vOffice, chỉ trả tham chiếu.
 */
public class VOfficeIssuedDocuments implements IssuedDocumentSource {

  private final String code;
  private final VOfficeClient client;

  VOfficeIssuedDocuments(String code, VOfficeClient client) {
    this.code = Objects.requireNonNull(code, "code");
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public String connectorCode() {
    return code;
  }

  @Override
  public Result search(
      Connector connector, String externalUnitId, String q, int limit, String cursor)
      throws IOException {
    java.util.Map<String, String> query = new java.util.LinkedHashMap<>();
    query.put("unitId", externalUnitId);
    query.put("q", q);
    query.put("limit", String.valueOf(limit));
    query.put("cursor", cursor);
    JsonNode body = client.get(connector, VOfficeClient.DOCUMENTS, Map.copyOf(withoutNulls(query)));
    List<IssuedDocument> items = new ArrayList<>();
    for (JsonNode n : body.path("items")) {
      LocalDate date;
      try {
        date = LocalDate.parse(n.path("issuedDate").asText(""));
      } catch (DateTimeParseException e) {
        date = null;
      }
      items.add(
          new IssuedDocument(
              n.path("id").asText(""),
              n.path("docNumber").asText(""),
              date,
              n.path("summary").asText(""),
              n.path("signerName").asText(null),
              n.path("signatureValid").asBoolean(false)));
    }
    String next = body.path("nextCursor").asText(null);
    return new Result(items, next == null || next.isBlank() ? null : next);
  }

  private static Map<String, String> withoutNulls(Map<String, String> m) {
    m.values().removeIf(Objects::isNull);
    return m;
  }
}
