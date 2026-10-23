package vn.viettel.okrkpi.scoring.application.port;

import java.util.List;
import java.util.UUID;

/**
 * Người được giao xử lý tài nguyên, đưa vào {@code designated_ids} của OPA cho phạm vi {@code Gn}
 * (wiki 15a): người nghiệm thu của nhiệm vụ (xem trước điểm), người xử lý khiếu nại hay người đề
 * nghị mở lại của hồ sơ (tính thử). SVC-07 không giữ dữ liệu giao việc nên nguồn ở MOD-03, MOD-04,
 * MOD-07 (wiki 14).
 */
public interface Designations {

  List<UUID> forTask(UUID taskId);

  List<UUID> forProfile(UUID profileId);

  /** Chưa có nguồn: không ai được coi là người được giao, chỉ tài khoản dịch vụ qua được OPA. */
  Designations NONE =
      new Designations() {
        @Override
        public List<UUID> forTask(UUID taskId) {
          return List.of();
        }

        @Override
        public List<UUID> forProfile(UUID profileId) {
          return List.of();
        }
      };
}
