package vn.viettel.okrkpi.integ.infrastructure.anchor;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.integ.application.anchor.AnchorService;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.infrastructure.cloudevents.CloudEventsHttpSender;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Bộ xử lý xác nhận của adapter tới đích neo: thân phản hồi là biên nhận đã ký của đích (JWS, token
 * RFC 3161 hoặc chứng từ lưu trữ, suy luận); ghi biên nhận trong giao dịch gửi của pipeline rồi trả
 * mã xác nhận ({@code X-Ack-Ref} hoặc băm SHA-256 của biên nhận). Thân rỗng hay quá {@link
 * #MAX_RECEIPT} byte thì báo lỗi để pipeline gửi lại.
 */
public class AnchorReceipts implements CloudEventsHttpSender.Acknowledger {

  static final int MAX_RECEIPT = 64 * 1024;

  private final Supplier<AnchorService> anchors;

  /**
   * {@code anchors} lấy lúc dùng: dịch vụ neo phụ thuộc pipeline, pipeline phụ thuộc adapter này.
   */
  public AnchorReceipts(Supplier<AnchorService> anchors) {
    this.anchors = Objects.requireNonNull(anchors, "anchors");
  }

  @Override
  public String acknowledge(Connector connector, UUID messageId, HttpResponse<byte[]> res)
      throws IOException {
    byte[] body = res.body();
    if (body == null || body.length == 0) {
      throw new IOException("Đích neo " + connector.code() + " không trả biên nhận");
    }
    if (body.length > MAX_RECEIPT) {
      throw new IOException("Biên nhận của đích neo " + connector.code() + " quá lớn");
    }
    anchors.get().receipt(TenantContext.require(), messageId, body);
    return res.headers()
        .firstValue(CloudEventsHttpSender.ACK_HEADER)
        .filter(v -> !v.isBlank())
        .orElseGet(() -> "sha256:" + sha256(body));
  }

  static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
