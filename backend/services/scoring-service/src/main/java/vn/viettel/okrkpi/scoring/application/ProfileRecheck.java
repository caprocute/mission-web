package vn.viettel.okrkpi.scoring.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.AuthzResourceProvider;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * Tính lại và đối chiếu ảnh chụp điểm của một hồ sơ ({@code recomputeAppraisalProfile}, DV-07-06;
 * TL-03 "tái lập kết quả", tiêu chí I6): mở tác vụ {@code KIEM_TRA_TAI_LAP} với phạm vi {@code
 * recheckOf} (tác vụ gốc) và {@code results} (kết quả mới nhất của hồ sơ) đúng dạng DL-12 R4 dùng.
 * Worker tính lại từ {@code score_input_v} bằng đúng phiên bản gói đã dùng và ghi kết quả đối chiếu
 * vào tác vụ kiểm tra; ảnh chụp hiện hành không bị ghi đè và không phát EVT-10.
 */
public class ProfileRecheck {

  /** Tài nguyên OPA: hồ sơ của kết quả gốc. */
  public record Resource(StoredScores.Stored stored) implements AuthzResourceProvider {
    @Override
    public AuthzResource toAuthzResource() {
      return AuthzResource.builder()
          .type("appraisal_profile")
          .id(stored.profileId().toString())
          .tenantId(stored.tenantId().toString())
          .ownerIds(List.of(stored.personId().toString()))
          .participants(Map.of("flow", List.of(stored.flow())))
          .build();
    }
  }

  /** Kiểm quyền trước khi mở tác vụ (giám sát viên, phạm vi tenant). */
  public static class Guard {
    @RequiresPermission(
        operationId = "recomputeAppraisalProfile",
        resourceType = "appraisal_profile")
    public void checkRecompute(Resource resource) {
      // Chỉ để chặn trước bởi PermissionInterceptor.
    }
  }

  private final StoredScores stored;
  private final ScoreJobStore jobs;
  private final Guard guard;

  public ProfileRecheck(StoredScores stored, ScoreJobStore jobs, Guard guard) {
    this.stored = Objects.requireNonNull(stored, "stored");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.guard = Objects.requireNonNull(guard, "guard");
  }

  /**
   * @return mã tác vụ kiểm tra
   * @throws ScoreTraceService.TraceNotFound hồ sơ chưa có kết quả trong tenant (404)
   */
  @Transactional
  public UUID request(UUID profileId, UUID actorId) {
    StoredScores.Stored s =
        stored.latest(profileId).orElseThrow(ScoreTraceService.TraceNotFound::new);
    guard.checkRecompute(new Resource(s));
    return jobs.open(
        new ScoreJob(
            s.tenantId(),
            Ids.newUuidV7(),
            s.periodId(),
            s.periodBucket(),
            ScoreJobKind.KIEM_TRA_TAI_LAP,
            CanonicalDoc.of(
                Map.of(
                    "recheckOf",
                    s.scoreJobId().toString(),
                    "results",
                    List.of(s.resultId().toString()))),
            s.policyPackVersionId(),
            null,
            1,
            actorId));
  }
}
