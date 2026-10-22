package vn.viettel.okrkpi.iam.sync;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * DV-02-07 · EVT-14: Keycloak không giữ cây tổ chức; thứ duy nhất về đơn vị nằm trong Keycloak là ngữ cảnh làm việc
 * người dùng đã chọn ({@code PUT /org/me/context}, DV-02-02), lưu ở thuộc tính {@value #CONTEXT_UNIT} và
 * {@value #CONTEXT_ASSIGNMENT}. Khi đơn vị đã hết hiệu lực (giải thể, sáp nhập, {@code validTo} không sau hôm nay),
 * ngữ cảnh trỏ vào đơn vị đó bị gỡ và mọi phiên của người dùng bị đăng xuất để token mới không còn mang đơn vị cũ.
 * Đơn vị hết hiệu lực trong tương lai không được xử lý trước: mapper của DV-02-02 kiểm hiệu lực phân công lúc cấp token.
 * Lặp lại an toàn: lần sau không còn người dùng nào mang ngữ cảnh đó.
 */
public class UnitSyncService {

  static final String CONTEXT_UNIT = "mission_context_unit";
  static final String CONTEXT_ASSIGNMENT = "mission_context_assignment";

  private final Directory directory;

  public UnitSyncService(Directory directory) {
    this.directory = Objects.requireNonNull(directory, "directory");
  }

  /** Trả số người dùng bị gỡ ngữ cảnh. */
  public int apply(UUID tenant, UUID unitId, LocalDate validTo, LocalDate today) {
    if (validTo == null || validTo.isAfter(today)) {
      return 0;
    }
    int cleared = 0;
    for (Map<String, Object> u : directory.findUsersByAttribute(CONTEXT_UNIT, unitId.toString())) {
      Map<String, Object> user = new LinkedHashMap<>(u);
      Map<String, Object> attrs = attributes(user);
      if (!List.of(tenant.toString()).equals(attrs.get(PersonSyncService.TENANT_ATTRIBUTE))) {
        continue;
      }
      attrs.remove(CONTEXT_UNIT);
      attrs.remove(CONTEXT_ASSIGNMENT);
      user.put("attributes", attrs);
      UUID id = UUID.fromString(String.valueOf(user.get("id")));
      directory.updateUser(id, user);
      directory.logout(id);
      cleared++;
    }
    return cleared;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> attributes(Map<String, Object> user) {
    Object a = user.get("attributes");
    return a instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
  }
}
