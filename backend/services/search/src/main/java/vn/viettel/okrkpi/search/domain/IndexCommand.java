package vn.viettel.okrkpi.search.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Một thao tác ghi vào chỉ mục, dựng từ một sự kiện.
 *
 * <ul>
 *   <li>{@link Mode#REPLACE}: tài liệu dựng trọn từ một sự kiện (văn bản chỉ đạo EVT-24), ghi
 *       {@code index} với {@code version_type=external}, {@code version = aggregateVersion} đúng
 *       như TL-03;
 *   <li>{@link Mode#MERGE}: tài liệu nhiệm vụ ghép từ hai sự kiện mang hai nhóm trường khác nhau
 *       (EVT-04 giao việc, EVT-06 trạng thái). Mỗi nhóm giữ phiên bản riêng trong {@code
 *       versions.<nhóm>}; nhóm chỉ được ghi khi {@code aggregateVersion} mới lớn hơn phiên bản đã
 *       lưu của nhóm đó (điểm lệch với TL-03, xem wiki/14).
 * </ul>
 *
 * @param seq vị trí của sự kiện nguồn trong lô, để trả lỗi về đúng thông điệp
 */
public record IndexCommand(
    int seq,
    String tenantId,
    DocType type,
    String entityId,
    Mode mode,
    String group,
    long version,
    Map<String, Object> fields,
    Instant occurredAt) {

  public enum Mode {
    REPLACE,
    MERGE
  }

  public IndexCommand {
    tenantId = IndexNames.tenant(tenantId);
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(mode, "mode");
    if (mode == Mode.MERGE) {
      Objects.requireNonNull(group, "group");
    }
    fields = FieldPolicy.filter(type, fields);
  }

  /** Mã tài liệu, duy nhất trong chỉ mục chung: {@code <loại>:<mã thực thể>}. */
  public String docId() {
    return type.name() + ":" + entityId;
  }
}
