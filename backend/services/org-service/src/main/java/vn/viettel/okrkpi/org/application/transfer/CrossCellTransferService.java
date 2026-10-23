package vn.viettel.okrkpi.org.application.transfer;

import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.port.IntegrationGatewayPort;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;

/**
 * DV-13-10 · Điều động cán bộ liên cell đã được duyệt (QT-01; TL-03 "Đồng bộ giữa các cell", luồng thứ ba). Sau khi
 * quyết định và việc đóng phân công đã cam kết, yêu cầu SVC-13 đóng gói hồ sơ mang theo với mã gói tất định. Gateway
 * chưa phản hồi thì trả {@code CHO_GUI}: quyết định đã ghi, bên gọi gửi lại đúng yêu cầu để thử đóng gói lần nữa (không
 * ghi lặp nhờ mã tất định ở cả hai phía).
 */
@Service
public class CrossCellTransferService {

  /** Trạng thái khi gateway chưa nhận yêu cầu đóng gói. */
  public static final String PENDING = "CHO_GUI";

  /** Kết quả: quyết định, mã gói và trạng thái gói ở gateway. */
  public record Result(UUID orderId, UUID packageId, boolean created, String packageStatus) {
  }

  private final CrossCellTransferRecorder recorder;
  private final IntegrationGatewayPort gateway;

  public CrossCellTransferService(CrossCellTransferRecorder recorder, IntegrationGatewayPort gateway) {
    this.recorder = recorder;
    this.gateway = gateway;
  }

  public Result transfer(Actor actor, CrossCellTransferRecorder.Command cmd) {
    CrossCellTransferRecorder.Recorded r = recorder.record(actor, cmd);
    String status;
    try {
      status = gateway.requestCellTransfer(actor.tenantId(), new IntegrationGatewayPort.CellTransferRequest(
          r.packageId(), cmd.personId(), cmd.sourceTenantCode(), cmd.targetCell(), cmd.targetTenantId(),
          cmd.targetTenantCode()));
    } catch (IntegrationUnavailableException e) {
      status = PENDING;
    }
    return new Result(r.orderId(), r.packageId(), r.created(), status);
  }
}
