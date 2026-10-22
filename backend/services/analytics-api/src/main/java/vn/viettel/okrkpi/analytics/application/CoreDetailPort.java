package vn.viettel.okrkpi.analytics.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * API chi tiết của mission-core gọi bằng token của chính người xem (chuyển tiếp, không dùng tài
 * khoản dịch vụ), để mission-core kiểm quyền từng mắt xích và ghi nhật ký truy cập (BR-16-04).
 * {@code bearer} là token gốc của người xem, tầng ứng dụng chỉ chuyển qua.
 */
public interface CoreDetailPort {

  Lookup<Task> task(String bearer, UUID id);

  Lookup<List<Deliverable>> deliverables(String bearer, UUID taskId);

  Lookup<Deliverable> deliverable(String bearer, UUID id);

  Lookup<Evidence> evidence(String bearer, UUID id);

  Lookup<List<Plan>> plans(String bearer, UUID periodId, UUID unitId);

  Lookup<KeyResult> keyResult(String bearer, UUID id);

  Lookup<Objective> objective(String bearer, UUID id);

  /** Kết quả một lần đọc: có, bị từ chối (403) hoặc không có (404). */
  sealed interface Lookup<T> {
    record Found<T>(T value) implements Lookup<T> {}

    record Denied<T>() implements Lookup<T> {}

    record Missing<T>() implements Lookup<T> {}
  }

  record Task(
      UUID id,
      String code,
      String title,
      String status,
      String unitName,
      UUID periodId,
      UUID keyResultId,
      Integer progress) {}

  record Deliverable(
      UUID id,
      UUID taskId,
      String name,
      String unit,
      BigDecimal planned,
      BigDecimal accepted,
      String status) {}

  record Evidence(
      UUID id,
      UUID deliverableId,
      UUID taskId,
      String title,
      String currentStatus,
      Integer currentVersionNo,
      List<Version> versions) {}

  record Version(int versionNo, String status) {}

  record Plan(UUID id, String code, int versionNo, String status, String unitName) {}

  record KeyResult(UUID id, UUID objectiveId, String code, String title, String status) {}

  record Objective(
      UUID id, String code, String title, String status, String unitName, List<Basis> bases) {}

  record Basis(UUID id, String docNumber, String title) {}
}
