package vn.viettel.okrkpi.docgen.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.docgen.domain.TemplateItem;
import vn.viettel.okrkpi.docgen.domain.TemplateItemId;
import vn.viettel.okrkpi.docgen.domain.TemplateItemRepository;

/**
 * Bộ điều hợp lưu trữ mẫu trong bộ nhớ (outbound adapter ở gói {@code infrastructure}); khi sinh
 * service thật, task {@code DV-xx-01} thay bằng adapter JPA/JDBC trên schema riêng của service.
 */
@Repository
public class InMemoryTemplateItemRepository implements TemplateItemRepository {

  private final Map<TemplateItemId, TemplateItem> store = new ConcurrentHashMap<>();

  @Override
  public TemplateItem save(TemplateItem item) {
    Objects.requireNonNull(item, "item");
    store.put(item.id(), item);
    return item;
  }

  @Override
  public Optional<TemplateItem> findById(TemplateItemId id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(store.get(id));
  }

  @Override
  public List<TemplateItem> findAll() {
    return List.copyOf(store.values());
  }
}
