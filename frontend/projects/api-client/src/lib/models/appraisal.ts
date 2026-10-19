import type { components } from '../generated/appraisals';
import type { components as DossierComponents } from '../generated/dossiers';

type Schemas = components['schemas'];

/**
 * Kiểu của MOD-04 `appraisal` (QT-09, QT-10), là bí danh của kiểu sinh từ
 * `contracts/openapi/modules/appraisals.yaml` (WEB-064), nên không cần kiểm thêm ở contract-check.
 */
export type RatingLevel = Schemas['RatingLevel'];
export type AppraisalFlow = Schemas['Flow'];
export type AppraisalProfileStatus = Schemas['AppraisalProfileStatus'];

/** Tên trạng thái hồ sơ đánh giá hiển thị cho người dùng. */
export const APPRAISAL_STATUS_LABEL: Record<AppraisalProfileStatus, string> = {
  CHUA_LAP: 'Chưa lập',
  TU_DANH_GIA: 'Đang tự đánh giá',
  CHO_THAM_DINH: 'Chờ thẩm định',
  DA_THAM_DINH: 'Đã thẩm định',
  CHO_QUYET_DINH: 'Chờ quyết định',
  DA_QUYET_DINH: 'Đã có quyết định',
  DA_KY_SO: 'Đã ký số',
  DA_KHOA: 'Đã khoá',
  KHIEU_NAI: 'Đang giải quyết khiếu nại',
};
export type GeneralCriteriaLevel = Schemas['GeneralCriteriaLevel'];
export type GeneralCriteriaItem = Schemas['GeneralCriteriaItem'];
export type GeneralCriteriaResult = Schemas['GeneralCriteriaResult'];
export type AppraisalProfileRow = Schemas['AppraisalProfileRow'];
export type AppraisalProfilePage = Schemas['AppraisalProfilePage'];
export type AppraisalProfile = Schemas['AppraisalProfile'];
export type AppraisalSelfAssessment = Schemas['AppraisalSelfAssessment'];
export type AppraisalSelfAssessmentInput = Schemas['AppraisalSelfAssessmentInput'];
export type AppraisalSignRequestInput = Schemas['AppraisalSignRequestInput'];
export type AppraisalSignProvider = Schemas['AppraisalSignProvider'];
export type AppraisalRatingProposal = Schemas['AppraisalRatingProposal'];
export type AppraisalRuleCheck = Schemas['AppraisalRuleCheck'];
export type AppraisalScoreTaskLine = Schemas['AppraisalScoreTaskLine'];
export type AppraisalScoreGroup = Schemas['AppraisalScoreGroup'];
export type AppraisalScoreSnapshot = Schemas['AppraisalScoreSnapshot'];
export type AppraisalRatingExplanation = Schemas['AppraisalRatingExplanation'];
export type AppraisalScoreView = Schemas['AppraisalScoreView'];
export type AppraisalScoreTraceStep = Schemas['AppraisalScoreTraceStep'];
export type AppraisalScoreTrace = Schemas['AppraisalScoreTrace'];
export type RatingLevelKind = Schemas['RatingLevelKind'];
export type AppraisalRatingLevels = Schemas['AppraisalRatingLevels'];
export type AppraisalAnomalyHint = Schemas['AppraisalAnomalyHint'];
export type AppraisalSignature = Schemas['AppraisalSignature'];
export type AppraisalReviewInput = Schemas['AppraisalReviewInput'];
export type AppraisalReturnInput = Schemas['AppraisalReturnInput'];
export type AppraisalExportInput = Schemas['AppraisalExportInput'];

/**
 * B, C cán bộ tự nhận cho một nhiệm vụ (`self_assessment.claims_json`, SCR-0902 trường 5). Hợp đồng chỉ
 * có `delayDays`; `delayFactor` (hệ số C tự nhận đã quy đổi theo Policy Pack) là phần mở rộng của dữ
 * liệu giả lập chờ hợp đồng bổ sung (wiki/14).
 */
export type AppraisalTaskClaim = Schemas['AppraisalTaskClaim'] & { delayFactor?: number };

/**
 * Phiếu tự đánh giá kèm phần mở rộng chờ hợp đồng cho SCR-0902 (wiki/14): `claimedScore` là điểm tự
 * tính theo B, C cán bộ tự nhận (thẻ "Điểm tự tính", trường 4).
 */
export type AppraisalSelfAssessmentView = Omit<AppraisalSelfAssessment, 'claims'> & {
  claims?: AppraisalTaskClaim[];
  claimedScore?: number;
};

/**
 * Dòng nhiệm vụ của ảnh chụp điểm kèm `acceptanceNote`: căn cứ điều chỉnh lấy từ `product.acceptance`
 * khi B hoặc C nghiệm thu khác tự nhận (SCR-0902 trường 5), phần mở rộng chờ hợp đồng (wiki/14).
 */
export type AppraisalScoreTaskLineView = AppraisalScoreTaskLine & { acceptanceNote?: string };

/** Yêu cầu xem trước biểu mẫu của SVC-11 (`POST /dossiers/forms/preview`, dossiers.yaml). */
export type DossierFormPreviewRequest = DossierComponents['schemas']['DossierFormPreviewRequest'];

/** Nhãn mức xếp loại (baseline mục 9.1), thứ tự từ cao xuống thấp. */
export const RATING_LEVEL_LABEL: Record<RatingLevel, string> = {
  HTXSNV: 'Hoàn thành xuất sắc nhiệm vụ',
  HTTNV: 'Hoàn thành tốt nhiệm vụ',
  HTNV: 'Hoàn thành nhiệm vụ',
  KHTNV: 'Không hoàn thành nhiệm vụ',
};

export const RATING_LEVELS: readonly RatingLevel[] = ['HTXSNV', 'HTTNV', 'HTNV', 'KHTNV'];

/** Nhãn mức tiêu chí chung (BR-09-05). */
export const GENERAL_CRITERIA_LEVEL_LABEL: Record<GeneralCriteriaLevel, string> = {
  THUC_HIEN_TOT: 'Thực hiện tốt',
  DAM_BAO: 'Đảm bảo',
  CO_BAN_DAM_BAO: 'Cơ bản đảm bảo',
  KHONG_DAM_BAO: 'Không đảm bảo',
};

export const APPRAISAL_FLOW_LABEL: Record<AppraisalFlow, string> = {
  DANG: 'Luồng Đảng',
  NHA_NUOC: 'Luồng Nhà nước',
};
