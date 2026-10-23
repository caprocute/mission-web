package vn.viettel.okrkpi.scoring.application.port;

import java.util.Map;
import java.util.Set;
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
   * Kết quả {@code OK} của lô trùng kết quả mới nhất của cùng hồ sơ và luồng ở cả {@code
   * input_hash} lẫn {@code policy_content_hash} (tính lại luỹ đẳng, DV-07-05).
   *
   * @return mã kết quả ({@code resultId}) của các phần tử không cần ghi
   */
  Set<UUID> unchanged(ScoreBatch batch);

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
