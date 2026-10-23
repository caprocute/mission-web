package vn.viettel.okrkpi.scoring.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Đọc kết quả gốc để tính thử (DV-07-08): kết quả {@code OK} mới nhất của hồ sơ trong tenant hiện
 * hành cùng {@code input_doc} của nó ở {@code scoring.score_input_v}. Chỉ đọc.
 */
public interface StoredScores {

  /**
   * Kết quả gốc.
   *
   * @param policyContentHash băm SHA-256 hex của phiên bản gói đã dùng (BR-08-07)
   * @param inputDoc {@code input_doc} dạng JSON
   * @param scoreJobId tác vụ đã ghi kết quả
   */
  record Stored(
      UUID resultId,
      UUID tenantId,
      UUID periodId,
      UUID profileId,
      UUID personId,
      String flow,
      UUID policyPackVersionId,
      String policyContentHash,
      String policyVersion,
      String inputDoc,
      int periodBucket,
      UUID scoreJobId) {}

  /** Hồ sơ có nhiệm vụ trong tập đầu vào của kết quả mới nhất ở một kỳ chưa chốt. */
  record Affected(UUID periodId, int periodBucket, UUID profileId, String flow) {}

  /**
   * Kết quả mới nhất của hồ sơ (mọi luồng). Kết quả của tác vụ {@code KIEM_TRA_TAI_LAP} không phải
   * ảnh chụp nên không bao giờ được trả.
   */
  Optional<Stored> latest(UUID profileId);

  /** Kết quả mới nhất có nhiệm vụ {@code taskId} trong {@code input_doc.nhiem_vu}. */
  Optional<Stored> latestWithTask(UUID taskId);

  /** Một kết quả theo mã, kể cả khi không còn là kết quả mới nhất (tính lại đối chiếu). */
  Optional<Stored> byId(int periodBucket, UUID resultId);

  /**
   * Hồ sơ có nhiệm vụ {@code taskId} trong tập đầu vào của kết quả mới nhất, ở các kỳ chưa có tác
   * vụ {@code CHOT_KY} xong (tính dần theo EVT-06, EVT-08).
   */
  List<Affected> withTask(UUID taskId);

  /** Phân vùng của kỳ theo kết quả đã có; trống khi kỳ chưa có kết quả. */
  Optional<Integer> bucketOf(UUID periodId);

  /**
   * Kết quả gốc theo luồng của kỳ để tính lại hậu kiểm bằng phiên bản gốc (BR-13-06): kết quả mới
   * nhất của tác vụ {@code CHOT_KY}, nếu chưa có thì kết quả mới nhất bất kỳ; mỗi luồng một dòng.
   */
  List<Stored> originals(UUID periodId, int periodBucket);
}
