package vn.viettel.okrkpi.integ.infrastructure.ioffice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Protocol;

/**
 * Máy khách giao diện văn bản đến của iOffice (VNPT) và hệ cùng mô hình (TL-01 "Các bộ kết nối":
 * REST hoặc SOAP của đối tác, chiều vào). Giao thức lấy từ {@code integ.connector.protocol}, địa
 * chỉ gốc từ {@code endpoint}, mã thông báo từ {@code secret_path}, nên đổi giả lập sang hệ thật
 * hay thêm tenant chỉ đổi bản ghi kết nối. Giao diện là suy luận khi chưa có tài liệu chính thức
 * (hợp đồng phía tiêu dùng ở {@code src/test/resources/ioffice-gia-lap}, wiki trang 14):
 *
 * <ul>
 *   <li>REST: {@code GET /api/v1/van-ban-den?tuThoiDiem=&soLuong=200}, trả {@code {"duLieu": [...],
 *       "thoiDiemTiepTheo": ...}}.
 *   <li>SOAP 1.1: {@code POST /ws/VanBanDenService}, thao tác {@code layDanhSachVanBanDen}; mỗi
 *       phần tử {@code vanBan} có cùng tên trường như REST, {@code noiNhan/donVi} là danh sách nơi
 *       nhận.
 * </ul>
 *
 * Phản hồi SOAP được chuẩn hoá về cùng dạng JSON với REST để một translator xử lý cả hai. Bộ đọc
 * XML tắt DTD và thực thể ngoài; nội dung trả về không ghi nhật ký.
 */
class IOfficeClient {

  static final String REST_PATH = "/api/v1/van-ban-den";
  static final String SOAP_PATH = "/ws/VanBanDenService";
  static final String SOAP_ACTION = "layDanhSachVanBanDen";
  static final String NS = "urn:ioffice:vanban:v1";
  static final int MAX_BYTES = 8 * 1024 * 1024;

  /** Trường lặp trong SOAP: phần tử con của chúng thành mảng trong JSON. */
  private static final Set<String> LISTS = Set.of("noiNhan", "tepDinhKem");

  private final HttpClient http;
  private final ObjectMapper json;
  private final ConnectorSecrets secrets;
  private final Duration timeout;
  private final XMLInputFactory xml;

  IOfficeClient(HttpClient http, ObjectMapper json, ConnectorSecrets secrets, Duration timeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.json = Objects.requireNonNull(json, "json");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.xml = XMLInputFactory.newFactory();
    xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    xml.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
  }

  ObjectMapper json() {
    return json;
  }

  /** Một trang văn bản đến từ thời điểm {@code since}, ở dạng JSON của REST. */
  JsonNode incoming(Connector connector, String since, int limit) throws IOException {
    return connector.protocol() == Protocol.SOAP
        ? soap(connector, since, limit)
        : rest(connector, since, limit);
  }

  private JsonNode rest(Connector connector, String since, int limit) throws IOException {
    URI uri =
        URI.create(
            base(connector)
                + REST_PATH
                + "?tuThoiDiem="
                + URLEncoder.encode(since, StandardCharsets.UTF_8)
                + "&soLuong="
                + limit);
    HttpRequest.Builder req =
        HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET();
    return json.readTree(send(connector, req));
  }

  private JsonNode soap(Connector connector, String since, int limit) throws IOException {
    String envelope =
        "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:vb=\""
            + NS
            + "\"><soapenv:Body><vb:"
            + SOAP_ACTION
            + "><vb:tuThoiDiem>"
            + escape(since)
            + "</vb:tuThoiDiem><vb:soLuong>"
            + limit
            + "</vb:soLuong></vb:"
            + SOAP_ACTION
            + "></soapenv:Body></soapenv:Envelope>";
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create(base(connector) + SOAP_PATH))
            .timeout(timeout)
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("SOAPAction", "\"" + SOAP_ACTION + "\"")
            .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8));
    return fromSoap(send(connector, req));
  }

  /** Chuẩn hoá phản hồi SOAP về dạng JSON của REST. */
  JsonNode fromSoap(byte[] body) throws IOException {
    ObjectNode out = json.createObjectNode();
    ArrayNode items = out.putArray("duLieu");
    try {
      XMLStreamReader r = xml.createXMLStreamReader(new ByteArrayInputStream(body));
      try {
        while (r.hasNext()) {
          if (r.next() != XMLStreamConstants.START_ELEMENT) {
            continue;
          }
          String name = r.getLocalName();
          if ("Fault".equals(name)) {
            throw new IOException("iOffice trả SOAP Fault");
          }
          if ("vanBan".equals(name)) {
            items.add(element(r));
          } else if ("thoiDiemTiepTheo".equals(name)) {
            out.put("thoiDiemTiepTheo", r.getElementText().trim());
          }
        }
      } finally {
        r.close();
      }
    } catch (XMLStreamException e) {
      throw new IOException("Phản hồi SOAP của iOffice không đọc được", e);
    }
    return out;
  }

  /** Phần tử hiện tại thành đối tượng JSON: phần tử lá thành chuỗi, danh sách thành mảng. */
  private ObjectNode element(XMLStreamReader r) throws XMLStreamException {
    ObjectNode node = json.createObjectNode();
    while (r.hasNext()) {
      int ev = r.next();
      if (ev == XMLStreamConstants.END_ELEMENT) {
        return node;
      }
      if (ev != XMLStreamConstants.START_ELEMENT) {
        continue;
      }
      String name = r.getLocalName();
      if (LISTS.contains(name)) {
        ArrayNode list = node.putArray(name);
        while (r.hasNext()) {
          int e2 = r.next();
          if (e2 == XMLStreamConstants.END_ELEMENT) {
            break;
          }
          if (e2 == XMLStreamConstants.START_ELEMENT) {
            list.add(element(r));
          }
        }
      } else {
        node.put(name, r.getElementText().trim());
      }
    }
    return node;
  }

  private byte[] send(Connector connector, HttpRequest.Builder req) throws IOException {
    if (connector.secretPath() != null) {
      secrets
          .token(connector.secretPath())
          .ifPresent(t -> req.header("Authorization", "Bearer " + t));
    }
    HttpResponse<byte[]> res;
    try {
      res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi gọi " + connector.code(), e);
    }
    if (res.statusCode() / 100 != 2) {
      throw new IOException("Kết nối " + connector.code() + " trả mã " + res.statusCode());
    }
    if (res.body().length > MAX_BYTES) {
      throw new IOException("Phản hồi của " + connector.code() + " vượt giới hạn kích thước");
    }
    return res.body();
  }

  private static String base(Connector connector) throws IOException {
    String endpoint = connector.endpoint();
    if (endpoint == null || !(endpoint.startsWith("https://") || endpoint.startsWith("http://"))) {
      throw new IOException("Địa chỉ kết nối " + connector.code() + " không hợp lệ");
    }
    return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
