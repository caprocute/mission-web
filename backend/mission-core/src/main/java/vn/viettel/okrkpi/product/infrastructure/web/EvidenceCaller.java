package vn.viettel.okrkpi.product.infrastructure.web;

import java.util.Optional;
import vn.viettel.okrkpi.product.application.evidence.EvidenceActor;

/**
 * Người gọi API sản phẩm và minh chứng (cán bộ và vai trò realm); không có bean thì dùng chủ thể
 * của {@code AuthzSubjectResolver} (JWT). Bean riêng dùng cho kiểm thử hoặc khi cell đặt bộ trích
 * khác.
 */
public interface EvidenceCaller {
  Optional<EvidenceActor> current();
}
