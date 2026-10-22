package vn.viettel.okrkpi.task.infrastructure.client;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.infrastructure.search.SearchClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskCandidates;

/**
 * {@link SimilarTaskCandidates} qua SVC-16 (CORE-02-15). Lọc đơn vị của SVC-16 là đơn vị hoặc người
 * thực hiện chứ không phải đơn vị sở hữu nhiệm vụ, nên chỉ lọc kỳ ở SVC-16 và để dịch vụ lọc đơn vị
 * trên CSDL. Chuyển tiếp {@code Authorization} của người gọi.
 */
public class HttpSimilarTaskCandidates implements SimilarTaskCandidates {

  private final SearchClient client;

  public HttpSimilarTaskCandidates(SearchClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public List<UUID> candidates(
      TenantId tenant, UUID periodId, UUID unitId, String title, int limit, String authorization) {
    if (title.length() < 2) {
      return List.of();
    }
    try {
      return client.search(tenant, title, "TASK", periodId, limit, authorization).stream()
          .map(SearchClient.Hit::id)
          .toList();
    } catch (IllegalStateException e) {
      throw new Unavailable(e.getMessage(), e);
    }
  }
}
