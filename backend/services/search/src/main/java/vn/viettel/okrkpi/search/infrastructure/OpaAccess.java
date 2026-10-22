package vn.viettel.okrkpi.search.infrastructure;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.search.application.AccessPort;
import vn.viettel.okrkpi.search.application.SearchHit;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaClient;
import vn.viettel.okrkpi.shared.authz.OpaDecision;
import vn.viettel.okrkpi.shared.authz.OpaInput;

/**
 * Kiểm quyền bằng OPA cùng pod theo chính sách {@code okrkpi.authz} (NEN-014, NEN-040), cùng chính
 * sách với lõi:
 *
 * <ul>
 *   <li>gọi API: {@code request = {module: search, method: GET}} theo ma trận API;
 *   <li>nhiệm vụ: chức năng {@code SCR-0601.xem} (xem chi tiết nhiệm vụ) với chủ trì, phối hợp lấy
 *       từ chỉ mục; đơn vị chủ quản lấy bằng mã chủ trì (EVT-04 ghi "người hoặc đơn vị chủ trì",
 *       suy luận);
 *   <li>văn bản chỉ đạo: chức năng {@code SCR-0502.nhan-van-ban}; EVT-24 chưa mang đơn vị nhận nên
 *       phạm vi Đv không khớp và kết quả bị loại (fail closed, câu hỏi mở ở wiki/14);
 *   <li>danh mục: {@code request = {module: catalog, method: GET}} trong tenant.
 * </ul>
 *
 * Quyết định của từng ứng viên hỏi song song bằng luồng ảo, tối đa 16 lời gọi cùng lúc; OPA lỗi là
 * từ chối.
 */
public final class OpaAccess implements AccessPort {

  static final String TASK_ACTION = "SCR-0601.xem";
  static final String SOURCE_DOC_ACTION = "SCR-0502.nhan-van-ban";
  private static final Logger LOG = LoggerFactory.getLogger(OpaAccess.class);

  private final OpaClient opa;
  private final AuthzSubjectResolver subjects;
  private final ExecutorService executor;
  private final Semaphore inFlight = new Semaphore(16);
  private final Clock clock;

  public OpaAccess(
      OpaClient opa, AuthzSubjectResolver subjects, ExecutorService executor, Clock clock) {
    this.opa = opa;
    this.subjects = subjects;
    this.executor = executor;
    this.clock = clock;
  }

  @Override
  public boolean maySearch(String tenantId) {
    OpaInput.Subject s = subjects.currentSubject();
    return decide(
        new OpaInput(
            s,
            null,
            new OpaInput.Request("search", "GET"),
            resource("search", null, tenantId, null, null, null),
            context()));
  }

  @Override
  public List<Boolean> mayView(String tenantId, List<SearchHit> hits) {
    OpaInput.Subject s = subjects.currentSubject();
    List<Future<Boolean>> futures = new ArrayList<>(hits.size());
    for (SearchHit h : hits) {
      OpaInput input = input(s, tenantId, h);
      futures.add(
          executor.submit(
              () -> {
                inFlight.acquire();
                try {
                  return decide(input);
                } finally {
                  inFlight.release();
                }
              }));
    }
    List<Boolean> out = new ArrayList<>(hits.size());
    for (Future<Boolean> f : futures) {
      try {
        out.add(f.get());
      } catch (ExecutionException e) {
        out.add(false);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        out.add(false);
      }
    }
    return out;
  }

  private OpaInput input(OpaInput.Subject s, String tenantId, SearchHit h) {
    return switch (h.type()) {
      case TASK -> {
        String lead = h.text("lead_assignee_id");
        yield new OpaInput(
            s,
            TASK_ACTION,
            null,
            resource(
                "task",
                h.entityId(),
                tenantId,
                lead,
                lead == null ? List.of() : List.of(lead),
                h.list("assignee_ids")),
            context());
      }
      case SOURCE_DOC ->
          new OpaInput(
              s,
              SOURCE_DOC_ACTION,
              null,
              resource("source_doc", h.entityId(), tenantId, null, null, null),
              context());
      case CATALOG_ITEM ->
          new OpaInput(
              s,
              null,
              new OpaInput.Request("catalog", "GET"),
              resource("catalog_item", h.entityId(), tenantId, null, null, null),
              context());
    };
  }

  private static OpaInput.Resource resource(
      String type,
      String id,
      String tenantId,
      String ownerUnit,
      List<String> owners,
      List<String> assignees) {
    return new OpaInput.Resource(
        type, id, tenantId, ownerUnit, owners, assignees, null, null, null, "C1", null, null, null,
        null, null);
  }

  private OpaInput.Context context() {
    return new OpaInput.Context(clock.instant().getEpochSecond());
  }

  private boolean decide(OpaInput input) {
    try {
      OpaDecision d = opa.decide(input);
      return d != null && d.isPermitted();
    } catch (RuntimeException e) {
      LOG.warn("OPA lỗi khi kiểm quyền tìm kiếm, từ chối: {}", e.getClass().getSimpleName());
      return false;
    }
  }
}
