package vn.viettel.okrkpi.integ.application.anchor;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Cổng nội bộ của ledger-service cho luồng neo (suy luận, wiki trang 14): chỉ {@code svc_ledger}
 * được ghi schema {@code ledger}, nên SVC-13 hỏi gói neo cấp cell và trả biên nhận qua API nội bộ,
 * không ghi thẳng CSDL và không đặt mã EVT mới.
 */
public interface AnchorLedger {

  /** Biên nhận của một đích cần ghi vào {@code ledger.anchor_receipt}. */
  record Receipt(
      UUID dispatchId,
      UUID cellSealId,
      AnchorTarget target,
      String connectorCode,
      byte[] receipt,
      Instant receivedAt) {}

  /** Gói neo của cell cho ngày kết thúc ở {@code windowEnd}; rỗng khi ledger-service chưa dựng. */
  Optional<CellAnchor> cellAnchor(String cellCode, Instant windowEnd) throws IOException;

  /**
   * Gửi biên nhận; ledger-service khử trùng theo {@code dispatchId} và UQ ({@code cell_seal_id},
   * {@code anchor_target}), nên gửi lại sau sự cố không ghi hai lần.
   */
  void submit(Receipt receipt) throws IOException;
}
