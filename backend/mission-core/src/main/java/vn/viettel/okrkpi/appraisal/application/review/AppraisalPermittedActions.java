package vn.viettel.okrkpi.appraisal.application.review;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.ProfileCommand;

/**
 * Thao tác người gọi còn làm được trên một hồ sơ đánh giá ({@code permittedActions}, YC-PT-21),
 * theo khoá màn hình của ma trận phân quyền. Cùng điều kiện với {@code SelfAssessmentService} và
 * {@link ReviewService}: trạng thái nguồn của {@link ProfileCommand}, chủ hồ sơ với phiếu tự đánh
 * giá, tách vai BR-09-14 với thẩm định và đề xuất. Vai trò do giao diện ghép thêm; số lần trả lại
 * tối đa của Policy Pack không kiểm trước.
 */
public final class AppraisalPermittedActions {

  public static final String LUU = "SCR-0901.luu";
  public static final String KY_NOP = "SCR-0901.ky-nop";
  public static final String TRA_LAI = "SCR-0902.tra-lai";
  public static final String KY = "SCR-0902.ky";
  public static final String DE_XUAT = "SCR-0903.de-xuat";

  private AppraisalPermittedActions() {}

  /**
   * {@code acceptors} chỉ được gọi khi hồ sơ chờ thẩm định và người gọi không phải chủ hồ sơ hay
   * người quyết định, để danh sách không phải tra người nghiệm thu cho từng dòng.
   */
  public static Set<String> of(ProfileSummary p, UUID actor, Supplier<Set<UUID>> acceptors) {
    Set<String> out = new LinkedHashSet<>();
    if (actor == null || p.status() == AppraisalStatus.DA_KHOA) {
      return out;
    }
    if (p.status() == AppraisalStatus.TU_DANH_GIA && actor.equals(p.personId())) {
      out.add(LUU);
      out.add(KY_NOP);
    }
    if (ProfileCommand.APPRAISE.from().contains(p.status())
        && !actor.equals(p.personId())
        && !actor.equals(p.deciderId())
        && participants(p, acceptors.get()).mayAppraise(actor)) {
      out.add(TRA_LAI);
      out.add(KY);
    }
    if (ProfileCommand.PROPOSE.from().contains(p.status())
        && participants(p, Set.of()).mayPropose(actor)) {
      out.add(DE_XUAT);
    }
    return out;
  }

  private static ReviewParticipants participants(ProfileSummary p, Set<UUID> acceptors) {
    return new ReviewParticipants(
        null,
        p.id(),
        p.orgUnitId(),
        p.flow(),
        p.personId(),
        acceptors,
        p.appraiserId(),
        p.deciderId());
  }
}
