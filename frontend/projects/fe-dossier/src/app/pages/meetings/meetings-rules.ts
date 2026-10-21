import { DossierMeetingCreate, DossierMeetingType } from '@vt/api-client';

/** Dữ liệu của hộp "Lập hội nghị" (QT-11 bước 1). */
export interface CreateForm {
  meetingType: DossierMeetingType;
  periodId: string;
  /** Giá trị của ô `datetime-local` (giờ địa phương). */
  scheduledAt: string;
  location: string;
  subjectPersonId: string;
  chairPersonId: string;
  secretaryPersonId: string;
  participantIds: string[];
}

/** Kiểm tra nhanh ở trình duyệt; khoá là tên trường của hợp đồng để khớp lỗi 422 của máy chủ. */
export function createErrors(f: CreateForm): Record<string, string> {
  const e: Record<string, string> = {};
  if (!f.periodId) e['periodId'] = 'Chọn kỳ kiểm điểm.';
  if (!f.scheduledAt) e['scheduledAt'] = 'Nhập ngày giờ hội nghị.';
  if (f.location.length > 500) e['location'] = 'Địa điểm tối đa 500 ký tự.';
  if (!f.chairPersonId) e['chairPersonId'] = 'Chọn chủ trì hội nghị.';
  if (!f.secretaryPersonId) e['secretaryPersonId'] = 'Chọn thư ký hội nghị.';
  else if (f.secretaryPersonId === f.chairPersonId)
    e['secretaryPersonId'] = 'Thư ký phải khác chủ trì.';
  return e;
}

export function toCreate(f: CreateForm, orgUnitId: string): DossierMeetingCreate {
  const special = new Set([f.chairPersonId, f.secretaryPersonId, f.subjectPersonId]);
  return {
    periodId: f.periodId,
    orgUnitId,
    meetingType: f.meetingType,
    scheduledAt: new Date(f.scheduledAt).toISOString(),
    ...(f.location.trim() ? { location: f.location.trim() } : {}),
    chairPersonId: f.chairPersonId,
    secretaryPersonId: f.secretaryPersonId,
    ...(f.subjectPersonId ? { subjectPersonId: f.subjectPersonId } : {}),
    participantPersonIds: f.participantIds.filter((id) => !special.has(id)),
  };
}
