package vn.viettel.okrkpi.policy.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.PolicyBinding;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest.Scope;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Trạng thái saga phát hành ({@code policy.publish_saga}) và các thao tác trên {@code
 * policy.policy_binding} mà saga cần (CORE-05-10).
 */
public interface PolicyPublishSagaRepository {

  void insert(TenantId tenant, PolicyPublishSaga saga);

  /** Ghi trạng thái nếu {@code row_version} còn bằng {@code expected}. */
  boolean store(TenantId tenant, PolicyPublishSaga saga, long expected);

  Optional<PolicyPublishSaga> find(TenantId tenant, UUID sagaId);

  /** Lần chạy mới nhất của phiên bản. */
  Optional<PolicyPublishSaga> findLatest(TenantId tenant, UUID versionId);

  /** Gắn hiện có đúng tổ hợp kỳ và phạm vi; trả kèm phiên bản đang gắn. */
  Optional<BindingRow> findBinding(TenantId tenant, UUID periodId, Scope scope);

  void insertBinding(
      TenantId tenant, UUID bindingId, UUID versionId, UUID periodId, Scope scope, UUID actor);

  /** Đổi phiên bản của gắn chưa đóng băng; {@code false} khi gắn đã đóng băng hoặc không còn. */
  boolean rebind(TenantId tenant, UUID bindingId, UUID versionId, UUID actor);

  /** Gỡ gắn chưa đóng băng; {@code false} khi gắn đã đóng băng hoặc không còn. */
  boolean unbind(TenantId tenant, UUID bindingId);

  /** Các gắn đang trỏ tới phiên bản. */
  List<PolicyBinding> bindings(TenantId tenant, UUID versionId);

  /** Một dòng {@code policy_binding}. */
  record BindingRow(UUID id, UUID versionId, boolean frozen) {}
}
