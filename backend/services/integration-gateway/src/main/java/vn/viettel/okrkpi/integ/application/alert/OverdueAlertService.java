package vn.viettel.okrkpi.integ.application.alert;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.alert.OutboundAlerts.Failed;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cảnh báo quản trị khi thông điệp ra của các kết nối theo dõi (EVT-12 tới CSDL CBCCVC) hết lượt
 * thử lại mà bên nhận chưa xác nhận (DV-13-09). Mỗi thông điệp chỉ cảnh báo một lần; dấu cảnh báo
 * và yêu cầu thông báo ghi cùng giao dịch trong ngữ cảnh tenant của thông điệp.
 */
public class OverdueAlertService {

  private final OutboundAlerts store;
  private final AdminAlerts alerts;
  private final Set<String> connectorCodes;
  private final TransactionOperations tx;

  public OverdueAlertService(
      OutboundAlerts store,
      AdminAlerts alerts,
      Set<String> connectorCodes,
      TransactionOperations tx) {
    this.store = Objects.requireNonNull(store, "store");
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.connectorCodes = Set.copyOf(connectorCodes);
    this.tx = Objects.requireNonNull(tx, "tx");
  }

  /** Quét và cảnh báo; trả số thông điệp vừa cảnh báo. */
  public int scan(int limit) {
    if (connectorCodes.isEmpty()) {
      return 0;
    }
    List<Failed> failed = store.failedUnalerted(connectorCodes, limit);
    int n = 0;
    for (Failed f : failed) {
      TenantId tenant = TenantId.of(f.tenantId());
      boolean[] sent = new boolean[1];
      TenantContext.run(
          tenant,
          () ->
              tx.executeWithoutResult(
                  s -> {
                    if (store.markAlerted(tenant, Ids.newUuidV7(), f.messageId())) {
                      alerts.outboundOverdue(tenant, f.connectorCode(), f.messageId());
                      sent[0] = true;
                    }
                  }));
      if (sent[0]) {
        n++;
      }
    }
    return n;
  }
}
