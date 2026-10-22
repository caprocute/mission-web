package vn.viettel.okrkpi.esign.domain;

import java.util.List;
import java.util.Optional;

/** Cổng ra (outbound port) lưu trữ thực thể mẫu; cài đặt nằm ở gói {@code infrastructure}. */
public interface TemplateItemRepository {

  TemplateItem save(TemplateItem item);

  Optional<TemplateItem> findById(TemplateItemId id);

  List<TemplateItem> findAll();
}
