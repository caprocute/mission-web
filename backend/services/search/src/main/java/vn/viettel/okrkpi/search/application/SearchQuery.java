package vn.viettel.okrkpi.search.application;

import java.util.Objects;
import vn.viettel.okrkpi.search.domain.DocType;
import vn.viettel.okrkpi.search.domain.IndexNames;

/**
 * Truy vấn tìm kiếm đã kiểm cú pháp.
 *
 * @param tenantId tenant lấy từ JWT đã xác thực, không bao giờ từ tham số
 * @param type {@code null} là mọi loại
 * @param unitId lọc nhiệm vụ có đơn vị hoặc người này chủ trì, phối hợp (suy luận); {@code null} là
 *     không lọc
 */
public record SearchQuery(String tenantId, String text, DocType type, String unitId, int limit) {

  public static final int MAX_LIMIT = 200;

  public SearchQuery {
    tenantId = IndexNames.tenant(tenantId);
    Objects.requireNonNull(text, "text");
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit");
    }
  }
}
