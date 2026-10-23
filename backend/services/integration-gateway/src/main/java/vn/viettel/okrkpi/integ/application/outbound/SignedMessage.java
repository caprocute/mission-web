package vn.viettel.okrkpi.integ.application.outbound;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.port.PayloadSigner;

/**
 * Thông điệp HTTP gửi ra đã ký (DV-13-02): thân, kiểu nội dung và tiêu đề kèm theo.
 *
 * @param body thân gửi đi
 * @param contentType {@code application/cloudevents+json} (chữ ký tách) hoặc {@code
 *     application/jose} (JWS gọn)
 * @param headers tiêu đề bổ sung, ví dụ {@code X-JWS-Signature}
 */
public record SignedMessage(byte[] body, String contentType, Map<String, String> headers) {

  /** Kiểu nội dung CloudEvents chế độ cấu trúc. */
  public static final String CLOUDEVENTS_JSON = "application/cloudevents+json";

  /** Kiểu nội dung JWS dạng gọn (RFC 7515 mục 9.2.1). */
  public static final String JOSE = "application/jose";

  /**
   * Tiêu đề mang chữ ký tách; tên theo thuộc tính {@code x-jws-signature} của AsyncAPI, đặt ở tiêu
   * đề HTTP vì tên thuộc tính mở rộng CloudEvents không được có dấu gạch.
   */
  public static final String SIGNATURE_HEADER = "X-JWS-Signature";

  public SignedMessage {
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(contentType, "contentType");
    headers = Map.copyOf(headers);
  }

  /** Ký phong bì CloudEvents theo cách bên nhận yêu cầu. */
  public static SignedMessage sign(byte[] cloudEvent, JwsMode mode, PayloadSigner signer) {
    Objects.requireNonNull(cloudEvent, "cloudEvent");
    return switch (Objects.requireNonNull(mode, "mode")) {
      case DETACHED ->
          new SignedMessage(
              cloudEvent,
              CLOUDEVENTS_JSON,
              Map.of(SIGNATURE_HEADER, signer.signDetached(cloudEvent)));
      case COMPACT ->
          new SignedMessage(
              signer.signCompact(cloudEvent).getBytes(StandardCharsets.US_ASCII), JOSE, Map.of());
    };
  }
}
