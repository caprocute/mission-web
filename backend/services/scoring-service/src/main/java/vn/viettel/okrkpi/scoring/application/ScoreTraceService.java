package vn.viettel.okrkpi.scoring.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.scoring.application.port.ScoreTraceStore;
import vn.viettel.okrkpi.scoring.domain.ScoreTrace;
import vn.viettel.okrkpi.scoring.domain.TraceStep;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.AuthzResourceProvider;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;

/**
 * Xem vết tính điểm của hồ sơ ({@code getAppraisalScoreTrace}, DV-07-03): đọc kết quả mới nhất rồi
 * hỏi OPA với tài nguyên là hồ sơ (người được đánh giá là chủ sở hữu; phạm vi đơn vị và cấp duyệt
 * do chính sách tra theo hồ sơ, wiki 15a). Hồ sơ chưa có kết quả hoặc nằm ngoài tenant trả {@link
 * TraceNotFound} (404, không lộ sự tồn tại).
 */
public class ScoreTraceService {

  /** Không có vết tính cho hồ sơ trong phạm vi người gọi. */
  public static class TraceNotFound extends RuntimeException {
    public TraceNotFound() {
      super("Không tìm thấy kết quả tính điểm của hồ sơ.");
    }
  }

  /** Tài nguyên OPA của vết tính. */
  public record ProfileTrace(ScoreTrace trace) implements AuthzResourceProvider {
    @Override
    public AuthzResource toAuthzResource() {
      return AuthzResource.builder()
          .type("appraisal_profile")
          .id(trace.profileId().toString())
          .tenantId(trace.tenantId().toString())
          .ownerIds(List.of(trace.personId().toString()))
          .participants(Map.of("flow", List.of(trace.flow())))
          .build();
    }
  }

  private final ScoreTraceStore store;
  private final Guard guard;

  public ScoreTraceService(ScoreTraceStore store, Guard guard) {
    this.store = Objects.requireNonNull(store, "store");
    this.guard = Objects.requireNonNull(guard, "guard");
  }

  /**
   * Vết tính đã kiểm quyền.
   *
   * @param taskId chỉ giữ bước của một nhiệm vụ ({@code task_id} trong giá trị vào); {@code null}
   *     lấy mọi bước
   */
  @Transactional(readOnly = true)
  public ScoreTrace find(UUID profileId, String flow, UUID taskId) {
    ScoreTrace trace = store.latest(profileId, flow).orElseThrow(TraceNotFound::new);
    guard.checkRead(new ProfileTrace(trace));
    if (taskId == null) {
      return trace;
    }
    String id = taskId.toString();
    List<TraceStep> steps = trace.steps().stream().filter(s -> id.equals(taskOf(s))).toList();
    return new ScoreTrace(
        trace.resultId(),
        trace.tenantId(),
        trace.profileId(),
        trace.personId(),
        trace.flow(),
        trace.policyVersion(),
        trace.engineVersion(),
        steps);
  }

  /** Nhiệm vụ của bước, {@code null} khi là bước cấp nhóm hay hồ sơ. */
  public static String taskOf(TraceStep step) {
    String json = step.inputs().json();
    String key = "\"task_id\":\"";
    int i = json.indexOf(key);
    if (i < 0) {
      return null;
    }
    int start = i + key.length();
    int end = json.indexOf('"', start);
    return end < 0 ? null : json.substring(start, end);
  }

  /** Điểm chặn OPA, tách bean để {@code PermissionInterceptor} bọc được. */
  public static class Guard {
    @RequiresPermission(operationId = "getAppraisalScoreTrace", resourceType = "appraisal_profile")
    public void checkRead(ProfileTrace trace) {
      // Chỉ để chặn trước bởi PermissionInterceptor.
    }
  }
}
