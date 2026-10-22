package vn.viettel.okrkpi.iam.sync;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Áp trạng thái nhân sự của EVT-16 lên người dùng Keycloak.
 *
 * <ul>
 *   <li>Chưa có: tạo người dùng có {@code id} bằng {@code personId}, tên đăng nhập bằng mã đó (tên đăng nhập thật đến
 *       từ IdP của cơ quan hoặc SCIM), rồi gắn vào Organization của tenant.
 *   <li>Đã có: cập nhật họ tên và trạng thái; thôi công tác thì khoá và đăng xuất mọi phiên.
 * </ul>
 *
 * <p>Khử trùng và thứ tự: phiên bản thực thể gốc ({@code aggregateVersion}) đã áp được lưu ở thuộc tính
 * {@value #VERSION_ATTRIBUTE} của chính người dùng; sự kiện có phiên bản không lớn hơn bị bỏ. Kafka giữ thứ tự theo
 * khoá {@code tenantId:personId} và mỗi phân vùng chỉ một luồng xử lý, nên đọc rồi ghi trên cùng người dùng không
 * tranh chấp. Chỉ đồng bộ trường C1 có trong sự kiện; số định danh, ngày sinh, liên hệ không bao giờ sang Keycloak.
 */
public class PersonSyncService {

  static final String TENANT_ATTRIBUTE = "tenant_id";
  static final String VERSION_ATTRIBUTE = "mission_source_version";
  static final String ACTIVE = "DANG_CONG_TAC";

  /** Kết quả áp một sự kiện. */
  public enum Outcome {
    CREATED,
    UPDATED,
    DISABLED,
    SKIPPED
  }

  /** Dữ liệu C1 của EVT-16 cần cho Keycloak. */
  public record PersonState(UUID tenantId, UUID personId, String displayName, String status, long version) {}

  private final Directory directory;

  public PersonSyncService(Directory directory) {
    this.directory = Objects.requireNonNull(directory, "directory");
  }

  public Outcome apply(PersonState p) {
    boolean active = ACTIVE.equals(p.status());
    Optional<Map<String, Object>> existing = directory.findUser(p.personId());
    if (existing.isEmpty()) {
      String org = directory.organizationOf(p.tenantId())
          .orElseThrow(() -> new Directory.TransientException("Tenant chưa có Organization ở cell", null));
      Map<String, Object> user = new LinkedHashMap<>();
      user.put("id", p.personId().toString());
      user.put("username", p.personId().toString());
      user.put("enabled", active);
      names(user, p.displayName());
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put(TENANT_ATTRIBUTE, List.of(p.tenantId().toString()));
      attrs.put(VERSION_ATTRIBUTE, List.of(Long.toString(p.version())));
      user.put("attributes", attrs);
      directory.createUser(user);
      directory.addMember(org, p.personId());
      return active ? Outcome.CREATED : Outcome.DISABLED;
    }
    Map<String, Object> user = new LinkedHashMap<>(existing.get());
    Map<String, Object> attrs = attributes(user);
    String tenant = first(attrs.get(TENANT_ATTRIBUTE));
    if (tenant != null && !tenant.equals(p.tenantId().toString())) {
      throw new Directory.PermanentException("Người dùng đã thuộc tenant khác");
    }
    String applied = first(attrs.get(VERSION_ATTRIBUTE));
    if (applied != null && parse(applied) >= p.version()) {
      return Outcome.SKIPPED;
    }
    boolean wasEnabled = !Boolean.FALSE.equals(user.get("enabled"));
    user.put("enabled", active);
    names(user, p.displayName());
    attrs.put(TENANT_ATTRIBUTE, List.of(p.tenantId().toString()));
    attrs.put(VERSION_ATTRIBUTE, List.of(Long.toString(p.version())));
    user.put("attributes", attrs);
    directory.updateUser(p.personId(), user);
    if (tenant == null) {
      directory.organizationOf(p.tenantId()).ifPresent(org -> directory.addMember(org, p.personId()));
    }
    if (!active) {
      if (wasEnabled) {
        directory.logout(p.personId());
      }
      return Outcome.DISABLED;
    }
    return Outcome.UPDATED;
  }

  /** Họ tên tiếng Việt: tên gọi là từ cuối, phần trước là họ và tên đệm. */
  static void names(Map<String, Object> user, String displayName) {
    String full = displayName == null ? "" : displayName.trim().replaceAll("\\s+", " ");
    int cut = full.lastIndexOf(' ');
    user.put("firstName", cut < 0 ? full : full.substring(cut + 1));
    user.put("lastName", cut < 0 ? "" : full.substring(0, cut));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> attributes(Map<String, Object> user) {
    Object a = user.get("attributes");
    return a instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
  }

  private static String first(Object values) {
    if (values instanceof List<?> l && !l.isEmpty()) {
      return String.valueOf(l.get(0));
    }
    return null;
  }

  private static long parse(String v) {
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      return Long.MIN_VALUE;
    }
  }
}
