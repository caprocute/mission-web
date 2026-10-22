package vn.viettel.okrkpi.iam.revocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.vtokr.iam.v1.AuthEvent;

/**
 * Đọc {@code iam.auth.event.v1} và ghi danh sách thu hồi vào Valkey cho plugin {@code vt-claims} của APISIX:
 * <ul>
 *   <li>{@code ADMIN_LOGOUT_USER} (báo truy cập bất thường, quản trị đăng xuất mọi phiên):
 *       {@code mission:revoked:user:<tenant>:<userId>} = thời điểm thu hồi (giây Unix); token có {@code iat} không
 *       sau thời điểm này bị từ chối;
 *   <li>{@code LOGOUT} có mã phiên: {@code mission:revoked:sid:<tenant>:<sid>} = thời điểm đăng xuất.
 * </ul>
 * Khoá hết hạn sau {@code mission.revocation.ttl} (mặc định 10 giờ, bằng thời hạn tối đa của phiên SSO và refresh
 * token trong mẫu realm); sau đó mọi token cũ đã tự hết hạn. Ghi lặp là an toàn (giá trị như nhau).
 */
@Component
public class RevocationListener {

  static final String USER_PREFIX = "mission:revoked:user:";
  static final String SID_PREFIX = "mission:revoked:sid:";
  private static final Logger LOG = LoggerFactory.getLogger(RevocationListener.class);

  private final StringRedisTemplate redis;
  private final Duration ttl;
  private final SpecificDatumReader<AuthEvent> reader;

  public RevocationListener(StringRedisTemplate redis, @Value("${mission.revocation.ttl:10h}") Duration ttl) {
    this.redis = redis;
    this.ttl = ttl;
    EventContracts.trustContractPackages();
    this.reader = new SpecificDatumReader<>(AuthEvent.class);
  }

  @KafkaListener(topics = EventTopics.IAM_AUTH_EVENT_V1, groupId = "${mission.revocation.group:iam-revocation}")
  public void onMessage(ConsumerRecord<String, byte[]> record) {
    AuthEvent event;
    try {
      event = reader.read(null, DecoderFactory.get().binaryDecoder(record.value(), null));
    } catch (IOException | RuntimeException e) {
      LOG.warn("Bỏ thông điệp iam.auth.event.v1 không giải mã được ở offset {}", record.offset());
      return;
    }
    String tenant = header(record, "tenantid");
    if (tenant == null) {
      return;
    }
    long at = occurredAt(header(record, "occurredat"));
    if ("ADMIN_LOGOUT_USER".equals(event.getEventType()) && event.getUserId() != null) {
      redis.opsForValue().set(USER_PREFIX + tenant + ":" + event.getUserId(), Long.toString(at), ttl);
    } else if ("LOGOUT".equals(event.getEventType()) && event.getSessionId() != null
        && event.getSessionId().length() <= 64) {
      redis.opsForValue().set(SID_PREFIX + tenant + ":" + event.getSessionId(), Long.toString(at), ttl);
    }
  }

  private static long occurredAt(String value) {
    try {
      return value == null ? Instant.now().getEpochSecond() : Instant.parse(value).getEpochSecond();
    } catch (DateTimeParseException e) {
      return Instant.now().getEpochSecond();
    }
  }

  private static String header(ConsumerRecord<?, ?> record, String name) {
    Header h = record.headers().lastHeader(name);
    if (h == null) {
      return null;
    }
    String v = new String(h.value(), StandardCharsets.UTF_8);
    try {
      return "tenantid".equals(name) ? UUID.fromString(v).toString() : v;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
