package vn.viettel.okrkpi.okr.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc mục tiêu và kết quả then chốt ({@code okr :: api}) của {@code MOD-01 · OKR Engine} mở
 * cho {@code MOD-02 (task)} và {@code MOD-04 (appraisal)} (TL-01 mục 2.1).
 */
public interface OkrApi {

  /**
   * Tìm thông tin tóm tắt của một kết quả then chốt (Key Result) để phục vụ gắn kết nhiệm vụ hoặc
   * tổng hợp điểm đánh giá.
   *
   * @param tenantId định danh tenant
   * @param keyResultId định danh kết quả then chốt
   * @return bản tóm tắt KR nếu tồn tại
   */
  Optional<KeyResultSummary> findKeyResultById(TenantId tenantId, UUID keyResultId);

  /**
   * Ảnh chụp đầy đủ của một KR để MOD-02 kiểm khi giao việc (CORE-02-14): trạng thái mục tiêu cha,
   * kỳ, đơn vị, người sở hữu và tiến độ tính theo {@code ProgressCalculator}.
   */
  default Optional<KeyResultSnapshot> findKeyResult(TenantId tenantId, UUID keyResultId) {
    return Optional.empty();
  }

  /**
   * KR thuộc các mục tiêu {@code DA_DUYET} của một đơn vị trong một kỳ, sắp theo mã, để MOD-02 gợi
   * ý dòng kế hoạch (CORE-02-05).
   */
  default List<KeyResultSnapshot> listApprovedKeyResults(
      TenantId tenantId, PeriodId periodId, UUID unitId) {
    return List.of();
  }

  /**
   * Mốc của KR kiểu đo {@code MOC} (CORE-01-14, BS-03 DC-04), sắp theo thứ tự, để {@code inputs}
   * của MOD-04 đọc ngày đạt và số ngày làm việc chậm; KR không đo theo mốc trả rỗng.
   */
  default List<MilestoneSnapshot> listMilestones(TenantId tenantId, UUID keyResultId) {
    return List.of();
  }

  /**
   * Một mốc của KR.
   *
   * @param seq thứ tự mốc, từ 1
   * @param dueDate hạn của mốc
   * @param recognitionCondition điều kiện công nhận, có thể {@code null}
   * @param achievedAt thời điểm ghi nhận đạt, {@code null} khi chưa đạt
   * @param evidenceRef tham chiếu minh chứng, có thể {@code null}
   * @param lateWorkingDays số ngày làm việc chậm so với hạn (tới ngày đạt, chưa đạt thì tới hôm
   *     nay)
   */
  record MilestoneSnapshot(
      int seq,
      LocalDate dueDate,
      String recognitionCondition,
      Instant achievedAt,
      String evidenceRef,
      long lateWorkingDays) {}

  /**
   * Ảnh chụp KR kèm ngữ cảnh mục tiêu cha công bố qua {@link OkrApi}.
   *
   * @param id định danh KR
   * @param tenantId định danh tenant
   * @param periodId kỳ của KR
   * @param objectiveId mục tiêu cha
   * @param objectiveStatus trạng thái mục tiêu cha ({@code NHAP}, {@code CHO_DUYET}, {@code
   *     DA_DUYET}, {@code DONG})
   * @param ownerType {@code TAP_THE} hoặc {@code CA_NHAN}
   * @param ownerUnitId đơn vị sở hữu mục tiêu cha
   * @param ownerPersonId người sở hữu khi mục tiêu cá nhân, ngược lại {@code null}
   * @param code mã KR
   * @param title tên KR
   * @param weight trọng số trong mục tiêu (0..1)
   * @param progress tiến độ 0..1
   * @param dueDate hạn KR
   * @param riskFlag cờ rủi ro
   */
  record KeyResultSnapshot(
      UUID id,
      TenantId tenantId,
      PeriodId periodId,
      UUID objectiveId,
      String objectiveStatus,
      String ownerType,
      UUID ownerUnitId,
      UUID ownerPersonId,
      String code,
      String title,
      BigDecimal weight,
      BigDecimal progress,
      LocalDate dueDate,
      String riskFlag) {

    public KeyResultSnapshot {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(tenantId, "tenantId must not be null");
      Objects.requireNonNull(periodId, "periodId must not be null");
      Objects.requireNonNull(objectiveId, "objectiveId must not be null");
    }

    /** Mục tiêu cha đã được duyệt, KR được dùng để giao việc. */
    public boolean approved() {
      return "DA_DUYET".equals(objectiveStatus);
    }
  }

  /**
   * Ảnh chụp tóm tắt kết quả then chốt công bố qua cổng {@link OkrApi}.
   *
   * @param id định danh KR
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @param title tiêu đề kết quả then chốt
   * @param weight trọng số KR
   * @param progressPercent tiến độ đạt được (0..100)
   */
  record KeyResultSummary(
      UUID id,
      TenantId tenantId,
      PeriodId periodId,
      String title,
      double weight,
      double progressPercent) {

    public KeyResultSummary {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(tenantId, "tenantId must not be null");
      Objects.requireNonNull(periodId, "periodId must not be null");
      Objects.requireNonNull(title, "title must not be null");
    }
  }
}
