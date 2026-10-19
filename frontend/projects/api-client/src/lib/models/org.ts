import type { components } from '../generated/org';

type Schemas = components['schemas'];

/** Cán bộ trong bản sao cây tổ chức (`task.org_replica`, đồng bộ từ SVC-13). */
export interface OrgPerson {
  id: string;
  name: string;
  title: string;
  unitId: string;
  unitName: string;
}

/** Đơn vị trong cây tổ chức. */
export interface OrgUnit {
  id: string;
  name: string;
  parentId: string | null;
}

/** Phân công còn hiệu lực của người dùng (QT-00 bước 7, `org_replica.assignment_v`). */
export type Assignment = Schemas['Assignment'];
export type AssignmentKind = Schemas['AssignmentKind'];
export type MyOrgProfile = Schemas['MyOrgProfile'];
export type WorkContext = Schemas['WorkContext'];

export const ASSIGNMENT_KIND_LABEL: Record<AssignmentKind, string> = {
  CHINH: 'Phân công chính',
  KIEM_NHIEM: 'Kiêm nhiệm',
  DIEU_DONG: 'Điều động',
  BIET_PHAI: 'Biệt phái',
  TAM_GIU: 'Tạm giữ',
};
