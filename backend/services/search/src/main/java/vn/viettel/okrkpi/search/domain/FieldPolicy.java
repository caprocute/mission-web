package vn.viettel.okrkpi.search.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Danh sách trường được phép đưa vào OpenSearch theo phân loại dữ liệu (TL-03 bảng phân loại: chỉ
 * C0, C1; C2, C3 không bao giờ vào OpenSearch). Mapping của chỉ mục đặt {@code dynamic: strict},
 * nên trường ngoài danh sách cũng bị cụm từ chối; đây là lớp lọc thứ nhất ở indexer.
 *
 * <p>Trường bị loại có chủ ý: người chuyển trạng thái ({@code changedBy}), mã lý do, trọng số nhiệm
 * vụ, mã kết nối, mã văn bản phía hệ ngoài và khoá đối tượng tệp (không phục vụ tìm kiếm và không
 * cần cho kết quả).
 */
public final class FieldPolicy {

  public static final String TENANT_ID = "tenant_id";
  public static final String DOC_TYPE = "doc_type";
  public static final String ENTITY_ID = "entity_id";
  public static final String UPDATED_AT = "updated_at";
  public static final String VERSIONS = "versions";

  private static final Set<String> COMMON =
      Set.of(TENANT_ID, DOC_TYPE, ENTITY_ID, UPDATED_AT, VERSIONS);

  private static final Map<DocType, Set<String>> ALLOWED =
      Map.of(
          DocType.TASK,
              Set.of(
                  "title",
                  "status",
                  "is_overdue",
                  "is_key_task",
                  "is_restricted",
                  "due_date",
                  "lead_assignee_id",
                  "assignee_ids",
                  "plan_item_id"),
          DocType.SOURCE_DOC, Set.of("title", "doc_number", "issuer", "summary", "issued_date"),
          DocType.CATALOG_ITEM, Set.of("title"));

  private FieldPolicy() {}

  public static boolean allowed(DocType type, String field) {
    return COMMON.contains(field) || ALLOWED.get(type).contains(field);
  }

  /**
   * Giữ lại trường được phép, giữ nguyên thứ tự; giá trị {@code null} vẫn giữ để indexer xoá trường
   * cũ.
   */
  public static Map<String, Object> filter(DocType type, Map<String, Object> fields) {
    Map<String, Object> out = new LinkedHashMap<>();
    fields.forEach(
        (k, v) -> {
          if (allowed(type, k)) {
            out.put(k, v);
          }
        });
    return out;
  }
}
