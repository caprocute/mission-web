package vn.viettel.okrkpi.iam.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * DV-02-05 · QT-00 · Nhận sự kiện của Keycloak, đặt thông điệp vào {@link DurableQueue}: mọi loại sự kiện trong
 * {@link EventMapper#USER_EVENTS} lên {@code iam.auth.event.v1}; khoá tài khoản sinh MSG-00-02, đăng nhập từ thiết bị
 * mới sinh MSG-00-08 (EVT-21); lệnh quản trị đăng xuất mọi phiên của một người thành {@code ADMIN_LOGOUT_USER} để
 * consumer ghi danh sách thu hồi cho APISIX.
 * <p>Tenant của người dùng là thuộc tính {@code tenant_id} (đồng bộ người dùng DV-02-06 ghi). Thiết bị nhận diện bằng
 * băm SHA-256 của User-Agent, lưu tối đa {@value #MAX_DEVICES} giá trị gần nhất ở thuộc tính
 * {@value #DEVICES_ATTRIBUTE}; lần đăng nhập đầu tiên chỉ ghi nhận, không báo (suy luận, ghi ở wiki trang 14). Khi
 * đăng nhập qua luồng {@code mission-browser} (DV-02-03), thiết bị và vị trí mới do bước đánh giá MFA quyết định và ghi
 * ở chi tiết sự kiện {@code mission_device_new}.
 */
final class MissionEventListenerProvider implements EventListenerProvider {

  static final String TENANT_ATTRIBUTE = "tenant_id";
  static final String DEVICES_ATTRIBUTE = "mission_known_devices";
  static final int MAX_DEVICES = 10;

  private static final Logger LOG = Logger.getLogger(MissionEventListenerProvider.class);
  private static final Pattern ADMIN_LOGOUT = Pattern.compile("^users/([0-9a-fA-F-]{36})/logout$");

  private final KeycloakSession session;
  private final DurableQueue queue;

  MissionEventListenerProvider(KeycloakSession session, DurableQueue queue) {
    this.session = session;
    this.queue = queue;
  }

  @Override
  public void onEvent(Event event) {
    try {
      String type = event.getType().name();
      if (!EventMapper.USER_EVENTS.contains(type)) {
        return;
      }
      UUID userId = uuid(event.getUserId());
      String userAgent = header("User-Agent");
      String traceparent = header("traceparent");
      Instant at = Instant.ofEpochMilli(event.getTime());
      // Luồng mission-browser (DV-02-03) đã tự đánh giá và ghi thiết bị; luồng khác dùng dấu vân tay User-Agent.
      String fromFlow = event.getDetails() == null ? null
          : event.getDetails().get(MissionMfaPolicyAuthenticator.DETAIL_NEW_DEVICE);
      String key = "LOGIN".equals(type) && fromFlow == null ? deviceKey(userAgent) : null;
      Lookup found = userId == null ? new Lookup(EventMapper.UNKNOWN_TENANT, false, 0)
          : lookup(event.getRealmId(), event.getUserId(), key);
      Lookup lookup = fromFlow == null || !"LOGIN".equals(type) ? found
          : new Lookup(found.tenant(), Boolean.parseBoolean(fromFlow), found.waitSeconds());
      EventMapper.Context c = new EventMapper.Context(lookup.tenant(), at, userId, traceparent);
      EventMapper.authEvent(c, type, userId, event.getClientId(), event.getSessionId(), event.getError())
          .ifPresent(queue::offer);
      if (userId != null && EventMapper.LOCKOUT.contains(type)) {
        Instant until = "USER_DISABLED_BY_TEMPORARY_LOCKOUT".equals(type) && lookup.waitSeconds() > 0
            ? at.plusSeconds(lookup.waitSeconds()) : null;
        queue.offer(EventMapper.lockedNotice(c, userId, until));
      }
      if (lookup.newDevice()) {
        queue.offer(EventMapper.newDeviceNotice(c, userId, event.getClientId()));
      }
    } catch (RuntimeException e) {
      // Không bao giờ làm hỏng luồng đăng nhập; chỉ ghi loại lỗi.
      LOG.warnf("Không xử lý được sự kiện Keycloak: %s", e.getClass().getSimpleName());
    }
  }

  @Override
  public void onEvent(AdminEvent event, boolean includeRepresentation) {
    try {
      if (event.getOperationType() != OperationType.ACTION || event.getResourcePath() == null) {
        return;
      }
      Matcher m = ADMIN_LOGOUT.matcher(event.getResourcePath());
      if (!m.matches()) {
        return;
      }
      UUID userId = uuid(m.group(1));
      Lookup lookup = lookup(event.getRealmId(), m.group(1), null);
      UUID admin = event.getAuthDetails() == null ? null : uuid(event.getAuthDetails().getUserId());
      EventMapper.Context c = new EventMapper.Context(lookup.tenant(), Instant.ofEpochMilli(event.getTime()), admin,
          header("traceparent"));
      EventMapper.authEvent(c, EventMapper.ADMIN_LOGOUT_USER, userId, null, null, null).ifPresent(queue::offer);
    } catch (RuntimeException e) {
      LOG.warnf("Không xử lý được sự kiện quản trị Keycloak: %s", e.getClass().getSimpleName());
    }
  }

  @Override
  public void close() {
  }

  /** Tenant, thiết bị mới (khi có {@code deviceKey}) và thời gian khoá tạm của realm, trong một giao dịch riêng. */
  private record Lookup(UUID tenant, boolean newDevice, int waitSeconds) {
  }

  private Lookup lookup(String realmId, String userId, String deviceKey) {
    KeycloakSessionFactory factory = session.getKeycloakSessionFactory();
    Lookup[] out = {new Lookup(EventMapper.UNKNOWN_TENANT, false, 0)};
    try {
      KeycloakModelUtils.runJobInTransaction(factory, s -> lookupIn(s, realmId, userId, deviceKey, out));
    } catch (RuntimeException e) {
      // Tra cứu lỗi không được làm mất sự kiện: gửi với tenant chưa xác định.
      LOG.warnf("Không tra được người dùng cho sự kiện: %s", e.getClass().getSimpleName());
    }
    return out[0];
  }

  private static void lookupIn(KeycloakSession s, String realmId, String userId, String deviceKey, Lookup[] out) {
    RealmModel realm = s.realms().getRealm(realmId);
    if (realm == null) {
      return;
    }
    s.getContext().setRealm(realm);
    UserModel user = s.users().getUserById(realm, userId);
    if (user == null) {
      return;
    }
    UUID tenant = uuid(user.getFirstAttribute(TENANT_ATTRIBUTE));
    boolean fresh = false;
    if (deviceKey != null) {
      List<String> known = user.getAttributeStream(DEVICES_ATTRIBUTE).collect(Collectors.toCollection(ArrayList::new));
      if (!known.contains(deviceKey)) {
        fresh = !known.isEmpty();
        known.add(deviceKey);
        while (known.size() > MAX_DEVICES) {
          known.remove(0);
        }
        user.setAttribute(DEVICES_ATTRIBUTE, known);
      }
    }
    out[0] = new Lookup(tenant == null ? EventMapper.UNKNOWN_TENANT : tenant, fresh,
        realm.getWaitIncrementSeconds());
  }

  private String header(String name) {
    try {
      var headers = session.getContext().getRequestHeaders();
      return headers == null ? null : headers.getHeaderString(name);
    } catch (RuntimeException e) {
      return null;
    }
  }

  static String deviceKey(String userAgent) {
    String ua = userAgent == null ? "" : userAgent.trim();
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(ua.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(h, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static UUID uuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
