package vn.viettel.okrkpi.iam.events;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecordBase;
import vn.viettel.okrkpi.events.EventTopics;
import vn.vtokr.iam.v1.AuthEvent;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * Chuyển sự kiện Keycloak thành thông điệp {@code iam.auth.event.v1} và yêu cầu thông báo EVT-21. Chỉ mang loại sự
 * kiện, mã người dùng, client, mã phiên, mã lỗi Keycloak; không mang mật khẩu, mã OTP, token hay chi tiết sự kiện
 * ({@code details} có thể chứa tên đăng nhập, địa chỉ chuyển hướng).
 */
final class EventMapper {

  /** Tenant chưa xác định (đăng nhập sai với tên không tồn tại, người dùng chưa gắn tenant). */
  static final UUID UNKNOWN_TENANT = new UUID(0L, 0L);

  /** Loại sự kiện người dùng được đẩy lên topic (bỏ CODE_TO_TOKEN, REFRESH_TOKEN thành công vì quá dày). */
  static final Set<String> USER_EVENTS = Set.of("LOGIN", "LOGIN_ERROR", "LOGOUT", "LOGOUT_ERROR",
      "CODE_TO_TOKEN_ERROR", "REFRESH_TOKEN_ERROR", "UPDATE_TOTP", "REMOVE_TOTP", "UPDATE_CREDENTIAL",
      "REMOVE_CREDENTIAL", "UPDATE_PASSWORD", "USER_DISABLED_BY_TEMPORARY_LOCKOUT",
      "USER_DISABLED_BY_PERMANENT_LOCKOUT");
  static final Set<String> LOCKOUT = Set.of("USER_DISABLED_BY_TEMPORARY_LOCKOUT", "USER_DISABLED_BY_PERMANENT_LOCKOUT");

  /** Loại sự kiện tự đặt cho lệnh quản trị thu hồi mọi phiên của một người ({@code POST users/{id}/logout}). */
  static final String ADMIN_LOGOUT_USER = "ADMIN_LOGOUT_USER";

  static final String MSG_LOCKED = "MSG-00-02";
  static final String MSG_NEW_DEVICE = "MSG-00-08";

  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
      .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
  private static final SecureRandom RANDOM = new SecureRandom();

  private EventMapper() {
  }

  /** Ngữ cảnh chung của một sự kiện. */
  record Context(UUID tenantId, Instant occurredAt, UUID actorId, String traceparent) {
  }

  static Optional<Outgoing> authEvent(Context c, String type, UUID userId, String clientId, String sessionId,
      String error) {
    if (!USER_EVENTS.contains(type) && !ADMIN_LOGOUT_USER.equals(type)) {
      return Optional.empty();
    }
    AuthEvent payload = AuthEvent.newBuilder().setEventType(type).setUserId(userId).setClientId(clientId)
        .setSessionId(sessionId).setError(error).build();
    String key = c.tenantId() + ":" + (userId != null ? userId : sessionId != null ? sessionId : "-");
    return Optional.of(new Outgoing(EventTopics.IAM_AUTH_EVENT_V1, key, headers(c, "AuthEvent"), avro(payload)));
  }

  /** MSG-00-02: tài khoản tạm khoá đến {@code until} (null là khoá cố định). */
  static Outgoing lockedNotice(Context c, UUID userId, Instant until) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("gio", until == null ? "" : CLOCK.format(until));
    return notice(c, userId, MSG_LOCKED, params, null);
  }

  /** MSG-00-08: đăng nhập từ thiết bị mới; {@code noi} là ứng dụng đăng nhập (không ghi địa chỉ IP vào thông báo). */
  static Outgoing newDeviceNotice(Context c, UUID userId, String clientId) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("gio", CLOCK.format(c.occurredAt()));
    params.put("noi", clientId == null ? "" : clientId);
    return notice(c, userId, MSG_NEW_DEVICE, params, "/tai-khoan/bao-mat");
  }

  private static Outgoing notice(Context c, UUID userId, String template, Map<String, String> params,
      String deepLink) {
    YeuCauThongBao payload = YeuCauThongBao.newBuilder().setRecipientIds(List.of(userId)).setTemplateCode(template)
        .setParams(json(params)).setDeepLink(deepLink).setPriority("CAO").setAggregateVersion(0).build();
    return new Outgoing(EventTopics.EVT_21_YEU_CAU_THONG_BAO, c.tenantId() + ":" + userId,
        headers(c, "YeuCauThongBao"), avro(payload));
  }

  private static Map<String, String> headers(Context c, String type) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("id", uuidV7(c.occurredAt()).toString());
    h.put("tenantid", c.tenantId().toString());
    h.put("aggregateversion", "0");
    h.put("ce_type", type);
    h.put("classification", "C1");
    h.put("occurredat", c.occurredAt().toString());
    if (c.actorId() != null) {
      h.put("actorid", c.actorId().toString());
    }
    if (c.traceparent() != null && c.traceparent().matches("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) {
      h.put("traceparent", c.traceparent());
    }
    return h;
  }

  static byte[] avro(SpecificRecordBase record) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(128);
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    try {
      new SpecificDatumWriter<SpecificRecordBase>(record.getSchema(), record.getSpecificData()).write(record, encoder);
      encoder.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** UUIDv7 (RFC 9562): 48 bit mili giây, phần còn lại ngẫu nhiên. */
  static UUID uuidV7(Instant at) {
    long ms = at.toEpochMilli();
    long msb = (ms << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
    long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }

  private static String json(Map<String, String> params) {
    StringBuilder b = new StringBuilder("{");
    params.forEach((k, v) -> {
      if (b.length() > 1) {
        b.append(',');
      }
      b.append('"').append(k).append("\":\"").append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    });
    return b.append('}').toString();
  }
}
