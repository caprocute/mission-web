package vn.viettel.okrkpi.analytics.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ba mắt xích đầu của chuỗi truy vết từ kho phân tích (BR-16-03): điểm, cấu phần, nhiệm vụ. Kho
 * không có cột C2 rõ; mã người giả danh chỉ dùng trong câu truy vấn, không trả ra (BR-16-04).
 */
public interface TraceFactsPort {

  /** Ảnh chụp điểm theo mã. */
  Optional<Score> score(UUID tenant, UUID scoreId);

  /** Ảnh chụp điểm hiện hành của người chủ trì nhiệm vụ trong kỳ của nhiệm vụ. */
  Optional<Score> scoreOfTaskOwner(UUID tenant, UUID taskId);

  /** Nhiệm vụ người của hồ sơ chủ trì trong kỳ của hồ sơ, việc then chốt trước. */
  List<Task> tasksOfProfile(UUID tenant, UUID profileId, int limit);

  Optional<Task> task(UUID tenant, UUID taskId);

  /** Minh chứng của nhiệm vụ (mã, sản phẩm), để vẫn che được mắt xích khi mission-core từ chối. */
  List<EvidenceRef> evidencesOfTask(UUID tenant, UUID taskId, int limit);

  /** Mục tiêu O chứa KR. */
  Optional<UUID> objectiveOfKeyResult(UUID tenant, UUID keyResultId);

  /**
   * Điểm, cấu phần theo thứ tự công thức. {@code components} giữ khoá {@code THUONG_XUYEN}, {@code
   * THEN_CHOT}, {@code TIEU_CHI_CHUNG}, {@code KPI} khi có giá trị.
   */
  record Score(
      UUID id,
      UUID profileId,
      UUID periodId,
      String periodName,
      UUID unitId,
      BigDecimal total,
      LinkedHashMap<String, BigDecimal> components,
      String policyVersion) {}

  record Task(
      UUID id,
      UUID periodId,
      UUID unitId,
      String taskGroup,
      boolean keyTask,
      UUID keyResultId,
      String status,
      BigDecimal progress) {}

  record EvidenceRef(UUID id, UUID deliverableId) {}
}
