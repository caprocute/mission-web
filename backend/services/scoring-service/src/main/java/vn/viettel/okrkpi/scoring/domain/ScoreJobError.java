package vn.viettel.okrkpi.scoring.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Một dòng hàng đợi lỗi ({@code scoring.score_job_error}): hồ sơ không tính được, hoặc cả trang đầu
 * vào không đọc được ({@code profileId} trống), sau khi lô đã thử lại. {@code detail} là mô tả kỹ
 * thuật, không chứa dữ liệu C2.
 */
public record ScoreJobError(
    UUID profileId, UUID personId, String flow, String reasonCode, String detail) {

  /** Đầu vào của hồ sơ thiếu hoặc sai dạng. */
  public static final String INPUT_INVALID = "DAU_VAO_KHONG_HOP_LE";

  /** Phiên bản Policy Pack lõi ghi nhận khác phiên bản ghim của kỳ (BR-08-07). */
  public static final String POLICY_MISMATCH = "PHIEN_BAN_KHONG_KHOP";

  /** Không phân giải được Policy Pack của hồ sơ. */
  public static final String POLICY_MISSING = "KHONG_CO_POLICY_PACK";

  /** Biểu thức hay bảng quyết định lỗi khi tính hồ sơ. */
  public static final String SCORING_FAILED = "LOI_TINH_DIEM";

  /** Không đọc được trang đầu vào sau khi thử lại. */
  public static final String READ_FAILED = "DOC_DAU_VAO_LOI";

  /** Lô đọc được nhưng ghi kết quả lỗi sau khi thử lại. */
  public static final String WRITE_FAILED = "GHI_KET_QUA_LOI";

  /** Kết quả gốc cần tính lại đối chiếu không còn tập đầu vào. */
  public static final String SOURCE_MISSING = "KHONG_CO_KET_QUA_GOC";

  private static final int MAX_DETAIL = 500;

  public ScoreJobError {
    Objects.requireNonNull(reasonCode, "reasonCode");
    if (detail != null && detail.length() > MAX_DETAIL) {
      detail = detail.substring(0, MAX_DETAIL);
    }
  }

  /** Lỗi của cả trang đầu vào. */
  public static ScoreJobError page(String reasonCode, String detail) {
    return new ScoreJobError(null, null, null, reasonCode, detail);
  }
}
