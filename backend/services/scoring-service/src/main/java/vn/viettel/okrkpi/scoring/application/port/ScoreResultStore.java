package vn.viettel.okrkpi.scoring.application.port;

import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;

/**
 * Cổng ghi lô kết quả: tập đầu vào (dùng lại khi trùng băm), kết quả và vết tính. Chỉ ghi thêm,
 * không có thao tác sửa hay xoá (TL-01 "Tính lại tái lập được").
 */
public interface ScoreResultStore {

  /** Ghi một lô trong giao dịch đang mở của bên gọi. */
  Appended append(ScoreBatch batch);

  /**
   * Kết quả ghi lô.
   *
   * @param newInputs số tập đầu vào mới (tập trùng băm đã có thì không ghi lại)
   * @param versions thứ tự của từng kết quả trong các kết quả của cùng hồ sơ (1, 2, ...), dùng làm
   *     {@code aggregateVersion} của EVT-10 vì khoá sự kiện là {@code tenant:profileId}
   */
  record Appended(int newInputs, Map<UUID, Long> versions) {
    public Appended {
      versions = Map.copyOf(versions);
    }
  }
}
