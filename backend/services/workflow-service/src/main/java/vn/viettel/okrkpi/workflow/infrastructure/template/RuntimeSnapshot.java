package vn.viettel.okrkpi.workflow.infrastructure.template;

import java.util.Map;
import java.util.Set;

/**
 * Ảnh chụp engine cho quy tắc 8: các phiên bản định nghĩa còn tiến trình chạy của từng mẫu, và các
 * mẫu mà lần triển khai sắp tới sẽ sinh phiên bản mới (nội dung khác phiên bản mới nhất).
 *
 * @param runningVersions mã mẫu → các phiên bản còn tiến trình chạy
 * @param changedTemplates mã mẫu sẽ có phiên bản mới
 */
public record RuntimeSnapshot(Map<String, Set<Integer>> runningVersions, Set<String> changedTemplates) {

  /** Ảnh chụp rỗng (CI, engine mới). */
  public static final RuntimeSnapshot EMPTY = new RuntimeSnapshot(Map.of(), Set.of());

  public RuntimeSnapshot {
    runningVersions = runningVersions == null ? Map.of() : Map.copyOf(runningVersions);
    changedTemplates = changedTemplates == null ? Set.of() : Set.copyOf(changedTemplates);
  }

  /** Số phiên bản sẽ còn chạy sau lần triển khai sắp tới. */
  public int versionsAfterDeploy(String templateCode) {
    int running = runningVersions.getOrDefault(templateCode, Set.of()).size();
    return changedTemplates.contains(templateCode) ? running + 1 : running;
  }
}
