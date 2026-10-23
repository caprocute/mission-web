package vn.viettel.okrkpi.appraisal.infrastructure.web;

import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardResource;
import vn.viettel.okrkpi.appraisal.application.review.ReviewParticipants;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * Điểm kiểm quyền chức năng của SCR-0902, SCR-0903 qua OPA (NEN-040): phạm vi cây đơn vị, luồng và
 * ràng buộc tách vai trò 15.1, 15.2 của ma trận trên năm vai đã tham gia hồ sơ. Từ chối do tách vai
 * trò được {@code PermissionInterceptor} đổi sang {@code APPRAISAL-403-SEPARATION-OF-DUTIES}
 * (MSG-09-08). Khi tắt OPA (môi trường phát triển) các phương thức không kiểm gì; dịch vụ vẫn kiểm
 * BR-09-14.
 */
@Component
public class AppraisalReviewGuard {

  @RequiresPermission(action = "SCR-0902.ky", resourceType = ReviewParticipants.TYPE)
  public void appraise(ReviewParticipants resource) {
    // Kiểm ở PermissionInterceptor.
  }

  @RequiresPermission(action = "SCR-0902.tra-lai", resourceType = ReviewParticipants.TYPE)
  public void returnProfile(ReviewParticipants resource) {
    // Kiểm ở PermissionInterceptor.
  }

  @RequiresPermission(action = "SCR-0903.de-xuat", resourceType = ReviewParticipants.TYPE)
  public void propose(ReviewParticipants resource) {
    // Kiểm ở PermissionInterceptor.
  }

  /** Lập đề xuất bố trí lại trên hồ sơ năm (SCR-1001, CORE-04-11). */
  @RequiresPermission(action = "SCR-1001.bo-tri-lai", resourceType = ReviewParticipants.TYPE)
  public void reassign(ReviewParticipants resource) {
    // Kiểm ở PermissionInterceptor.
  }

  /** Xem danh sách phân nhóm của đơn vị (SCR-1002, CORE-04-12; dữ liệu C2, BR-10-10). */
  @RequiresPermission(
      action = "SCR-1002.xem",
      resourceType = ProspectBoardResource.TYPE,
      classification = "C2")
  public void viewProspects(ProspectBoardResource resource) {
    // Kiểm ở PermissionInterceptor.
  }

  /** Xếp nhóm, lưu nháp (SCR-1002). */
  @RequiresPermission(
      action = "SCR-1002.xep-nhom",
      resourceType = ProspectBoardResource.TYPE,
      classification = "C2")
  public void arrangeProspects(ProspectBoardResource resource) {
    // Kiểm ở PermissionInterceptor.
  }

  /** Trình tập thể lãnh đạo (SCR-1002). */
  @RequiresPermission(action = "SCR-1002.trinh", resourceType = ProspectBoardResource.TYPE)
  public void submitProspects(ProspectBoardResource resource) {
    // Kiểm ở PermissionInterceptor.
  }

  @RequiresPermission(
      action = "SCR-0902.xem",
      resourceType = ReviewParticipants.TYPE,
      classification = "C2")
  public void viewForAppraisal(ReviewParticipants resource) {
    // Kiểm ở PermissionInterceptor.
  }

  @RequiresPermission(
      action = "SCR-0903.xem",
      resourceType = ReviewParticipants.TYPE,
      classification = "C2")
  public void viewResult(ReviewParticipants resource) {
    // Kiểm ở PermissionInterceptor.
  }

  /**
   * Người thẩm định trong phạm vi (SCR-0902) hoặc quản lý, người quyết định (SCR-0903) được đọc hồ
   * sơ. Gọi qua proxy Spring để OPA được hỏi.
   */
  public static boolean canView(AppraisalReviewGuard guard, ReviewParticipants resource) {
    if (guard == null) {
      return false;
    }
    try {
      guard.viewForAppraisal(resource);
      return true;
    } catch (BusinessException | StepUpRequiredException e) {
      // thử chức năng xem kết quả
    }
    try {
      guard.viewResult(resource);
      return true;
    } catch (BusinessException | StepUpRequiredException e) {
      return false;
    }
  }
}
