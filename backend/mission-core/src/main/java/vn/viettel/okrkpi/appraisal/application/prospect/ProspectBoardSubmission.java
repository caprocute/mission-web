package vn.viettel.okrkpi.appraisal.application.prospect;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.BoardView;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectCouncil;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Trình tập thể: giao dịch trình của {@link ProspectBoardService} commit trước, sau đó mới khởi
 * động user task ở workflow-service (không gọi mạng trong giao dịch). workflow-service chưa nhận
 * thì danh sách vẫn ở {@code DA_TRINH}, lần khởi động sau idempotent theo phiên bản danh sách.
 */
public class ProspectBoardSubmission {

  private final ProspectBoardService service;
  private final ProspectCouncil council;

  public ProspectBoardSubmission(ProspectBoardService service, ProspectCouncil council) {
    this.service = Objects.requireNonNull(service, "service");
    this.council = council;
  }

  public BoardView submit(
      TenantId tenant, UUID periodId, UUID orgUnitId, long expectedVersion, UUID actor) {
    BoardView board = service.submit(tenant, periodId, orgUnitId, expectedVersion, actor);
    if (council != null) {
      council
          .start(tenant, board.boardId(), periodId, orgUnitId, board.boardVersion(), actor)
          .ifPresent(process -> service.recordProcess(tenant, board, process));
    }
    return board;
  }
}
