package vn.viettel.okrkpi.integ.infrastructure.truc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.OutboundSender;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.infrastructure.edxml.Edxml;

/**
 * Chiều ra của Trục liên thông qua X-Road (DV-13-05, BR-11-11): mỗi lần gửi trước hết hỏi trạng
 * thái văn bản theo mã {@code DocumentId} của gói ({@code GET <dịch vụ>/edocs/{id}/status}); chưa
 * có thì gửi {@code PUT <dịch vụ>/edocs/{id}} (gửi lại cùng mã là thao tác rỗng ở Trục), đã có thì
 * không gửi lại. Chỉ coi xong khi bên nhận có biên nhận ({@code DA_NHAN}); bên nhận từ chối là lỗi
 * để worker thử lại theo lịch rồi chuyển thư chết. Hợp đồng REST của Trục là suy luận cho bản giả
 * lập (YC-PT-13).
 */
class TrucEdocSender implements OutboundSender {

  static final String RECEIVED = "DA_NHAN";
  static final String REJECTED = "TU_CHOI";

  private final String code;
  private final XRoadClient client;
  private final ObjectMapper json;

  TrucEdocSender(String code, XRoadClient client, ObjectMapper json) {
    this.code = Objects.requireNonNull(code, "code");
    this.client = Objects.requireNonNull(client, "client");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public String connectorCode() {
    return code;
  }

  @Override
  public Optional<String> send(
      Connector connector, UUID messageId, String messageType, byte[] payload) throws IOException {
    String docId = Edxml.documentId(payload);
    if (docId == null) {
      docId = messageId.toString();
    }
    String path = "/edocs/" + URLEncoder.encode(docId, StandardCharsets.UTF_8);
    XRoadClient.Reply status = client.get(connector, path + "/status");
    if (status.status() == 404) {
      XRoadClient.Reply put = client.put(connector, path, payload, "application/xml");
      if (put.status() / 100 != 2) {
        throw new IOException(
            "Trục " + connector.code() + " không nhận văn bản, mã " + put.status());
      }
      return ack(put, docId);
    }
    if (status.status() / 100 != 2) {
      throw new IOException("Trục " + connector.code() + " trả mã " + status.status());
    }
    return ack(status, docId);
  }

  private Optional<String> ack(XRoadClient.Reply reply, String docId) throws IOException {
    if (reply.body() == null || reply.body().length == 0) {
      return Optional.empty();
    }
    JsonNode body = json.readTree(reply.body());
    String s = body.path("status").asText("");
    if (REJECTED.equals(s)) {
      throw new IOException("Bên nhận từ chối văn bản " + docId);
    }
    if (RECEIVED.equals(s)) {
      String receipt = body.path("receiptId").asText("");
      return Optional.of(receipt.isBlank() || receipt.length() > 128 ? docId : receipt);
    }
    return Optional.empty();
  }
}
