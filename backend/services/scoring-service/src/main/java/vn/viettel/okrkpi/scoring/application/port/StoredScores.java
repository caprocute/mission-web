package vn.viettel.okrkpi.scoring.application.port;

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
      String inputDoc) {}

  /** Kết quả mới nhất của hồ sơ (mọi luồng). */
  Optional<Stored> latest(UUID profileId);

  /** Kết quả mới nhất có nhiệm vụ {@code taskId} trong {@code input_doc.nhiem_vu}. */
  Optional<Stored> latestWithTask(UUID taskId);
}
