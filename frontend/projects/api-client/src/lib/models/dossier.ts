import type { components } from '../generated/dossiers';

type Schemas = components['schemas'];

/**
 * Kiểu của MOD-06 `dossier` cho hội nghị kiểm điểm (QT-11, SCR-1101, WEB-040), là bí danh của kiểu sinh từ
 * `contracts/openapi/modules/dossiers.yaml` nên không cần kiểm thêm ở contract-check.
 */
export type DossierMeetingType = Schemas['DossierMeetingType'];
export type DossierMeetingStatus = Schemas['DossierMeetingStatus'];
export type DossierMeetingRole = Schemas['DossierMeetingRole'];
export type DossierParticipantStatus = Schemas['DossierParticipantStatus'];
export type DossierCommentKind = Schemas['DossierCommentKind'];
export type DossierCommentPhase = Schemas['DossierCommentPhase'];
export type DossierRemediationStatus = Schemas['DossierRemediationStatus'];
export type DossierDeadlineCheck = Schemas['DossierDeadlineCheck'];
export type DossierMeetingParticipant = Schemas['DossierMeetingParticipant'];
export type DossierVote = Schemas['DossierVote'];
export type DossierFormSummary = Schemas['DossierFormSummary'];
export type DossierMeetingSummary = Schemas['DossierMeetingSummary'];
export type DossierMeeting = Schemas['DossierMeeting'];
export type DossierMeetingPage = Schemas['DossierMeetingPage'];
export type DossierMeetingCreate = Schemas['DossierMeetingCreate'];
export type DossierMeetingUpdate = Schemas['DossierMeetingUpdate'];
export type DossierAttendance = Schemas['DossierAttendance'];
export type DossierMinutesRequest = Schemas['DossierMinutesRequest'];
export type DossierComment = Schemas['DossierComment'];
export type DossierCommentPage = Schemas['DossierCommentPage'];
export type DossierCommentCreate = Schemas['DossierCommentCreate'];
export type DossierRemediationItem = Schemas['DossierRemediationItem'];
export type DossierRemediationItemPage = Schemas['DossierRemediationItemPage'];
export type DossierRemediationItemUpdate = Schemas['DossierRemediationItemUpdate'];
export type DossierSpecialCase = Schemas['DossierSpecialCase'];
export type DossierSpecialCasePage = Schemas['DossierSpecialCasePage'];

/** Tên loại hội nghị hiển thị cho người dùng. */
export const MEETING_TYPE_LABEL: Record<DossierMeetingType, string> = {
  KIEM_DIEM_QUY: 'Kiểm điểm quý',
  KIEM_DIEM_NAM: 'Kiểm điểm cuối năm',
  CHUYEN_DE: 'Kiểm điểm chuyên đề',
};

/** Tên trạng thái hội nghị hiển thị cho người dùng. */
export const MEETING_STATUS_LABEL: Record<DossierMeetingStatus, string> = {
  DU_KIEN: 'Dự kiến',
  DA_GUI_DU_THAO: 'Đã gửi dự thảo',
  DA_HOP: 'Đã họp',
  DA_LAP_BIEN_BAN: 'Đã lập biên bản',
  DA_KY: 'Đã ký biên bản',
};

/** Vai trò trong hội nghị hiển thị cho người dùng. */
export const MEETING_ROLE_LABEL: Record<DossierMeetingRole, string> = {
  CHU_TRI: 'Chủ trì',
  THU_KY: 'Thư ký hội nghị',
  THANH_VIEN: 'Thành viên',
  NGUOI_DUOC_KIEM_DIEM: 'Người được kiểm điểm',
};

export const PARTICIPANT_STATUS_LABEL: Record<DossierParticipantStatus, string> = {
  DA_GOP_Y: 'Đã góp ý',
  DA_DOC: 'Đã đọc',
  CHUA_MO: 'Chưa mở',
  CO_MAT: 'Có mặt',
  VANG_CO_LY_DO: 'Vắng có lý do',
};

export const COMMENT_KIND_LABEL: Record<DossierCommentKind, string> = {
  UU_DIEM: 'Ưu điểm',
  HAN_CHE: 'Hạn chế',
  KIEN_NGHI: 'Kiến nghị',
};

export const REMEDIATION_STATUS_LABEL: Record<DossierRemediationStatus, string> = {
  MO: 'Chưa bắt đầu',
  DANG_KHAC_PHUC: 'Đang khắc phục',
  DA_KHAC_PHUC: 'Đã khắc phục',
  CHUA_KHAC_PHUC: 'Chưa khắc phục',
};
