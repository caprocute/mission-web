import type { components as CommonComponents } from '../generated/common';
import type { components } from '../generated/org';
import { AssignmentKind } from './org';

/**
 * Cây tổ chức có lịch sử (SCR-0103, QT-01 bước 6) của SVC-04 org-service: bảng `org.org_unit`,
 * `org.org_unit_relation`, `org.assignment`, `org.subject_group`, `org.org_change_order` (TL-03),
 * hai trục thời gian `valid_from/valid_to` (hiệu lực) và `recorded_from/recorded_to` (ghi nhận).
 * Kiểu lấy từ hợp đồng `contracts/openapi/modules/org.yaml` (bản sinh `generated/org.ts`, WEB-072).
 */
type Schemas = components['schemas'];

/** `org.org_unit.unit_type`. */
export type OrgUnitType = Schemas['OrgUnitType'];

export const ORG_UNIT_TYPE_LABEL: Record<OrgUnitType, string> = {
  TO_CHUC_DANG: 'Tổ chức đảng',
  CHI_BO: 'Chi bộ',
  CO_QUAN: 'Cơ quan',
  DON_VI_SU_NGHIEP: 'Đơn vị sự nghiệp',
  PHONG_BAN: 'Phòng, ban',
  XA_PHUONG: 'Xã, phường',
  DOANH_NGHIEP: 'Doanh nghiệp',
};

/**
 * `org.org_unit.level`: cấp TW, tỉnh, xã (chính quyền hai cấp từ 01/07/2025). Hợp đồng để `level` là
 * chuỗi tự do (TL-03 chưa có CHECK IN); giao diện chỉ đưa ra ba giá trị này.
 */
export type OrgUnitLevel = 'TW' | 'TINH' | 'XA';
export const ORG_UNIT_LEVEL_LABEL: Record<OrgUnitLevel, string> = {
  TW: 'Trung ương',
  TINH: 'Cấp tỉnh',
  XA: 'Cấp xã',
};
/** Nhãn cấp; giá trị máy chủ trả ngoài ba cấp trên thì không in mã thô (quy tắc 9 AGENTS.md). */
export const orgUnitLevelLabel = (level: string | undefined): string =>
  ORG_UNIT_LEVEL_LABEL[level as OrgUnitLevel] ?? 'Chưa xác định';

/** Luồng đánh giá (`Flow` của `common.yaml`). */
export type OrgFlow = CommonComponents['schemas']['Flow'];

export const ORG_FLOW_LABEL: Record<OrgFlow, string> = {
  DANG: 'Đảng',
  NHA_NUOC: 'Nhà nước',
};

/** Cây Đảng hoặc cây chuyên môn (`org.org_unit_relation.tree`). */
export type OrgTreeKind = Schemas['OrgTreeKind'];

/** Khối đối tượng PL 2A (`org.subject_group`, mã `K1`..`K9`). */
export type SubjectGroup = Schemas['SubjectGroup'];

/** Một nút trên cây (vùng 2); `children` vắng hoặc rỗng khi `hasChildren` là nhánh chưa nạp. */
export type OrgTreeNode = Schemas['OrgTreeNode'];

/** `GET /org/units/tree` và `GET /org/units/{id}/subtree`. */
export type OrgTree = Schemas['OrgTree'];

/** Kết quả tìm đơn vị kèm tổ tiên từ gốc tới cha trực tiếp để mở nhánh. */
export type OrgUnitSearchHit = Schemas['OrgUnitSearchHit'];

/**
 * Thẻ chi tiết đơn vị (vùng 3): phiên bản có hiệu lực ở hai trục thời gian đang xem (`OrgUnit` của
 * hợp đồng; đặt tên `OrgUnitDetail` để không trùng `OrgUnit` rút gọn của `models/org.ts`).
 */
export type OrgUnitDetail = Schemas['OrgUnit'];

/** Thân `PUT /org/units/{id}`: tạo phiên bản mới từ `validFrom`, không ghi đè (BR-01-06). */
export type OrgUnitInput = Schemas['OrgUnitInput'];

/** Một phiên bản ở vùng 4 "Lịch sử thay đổi" (`GET /org/units/{id}/history`). */
export type OrgUnitVersion = Schemas['OrgUnitVersion'];

/** Phân công của đơn vị (vùng 5), `org.assignment`. */
export type OrgUnitAssignment = Schemas['OrgUnitAssignment'];

export const UNIT_ASSIGNMENT_KIND_LABEL: Record<AssignmentKind, string> = {
  CHINH: 'Chính',
  KIEM_NHIEM: 'Kiêm nhiệm',
  DIEU_DONG: 'Điều động',
  BIET_PHAI: 'Biệt phái',
  TAM_GIU: 'Tạm giữ',
};

/** Loại thay đổi tổ chức trong hộp "Thay đổi tổ chức". */
export type OrgChangeType = Schemas['OrgChangeType'];

export const ORG_CHANGE_TYPE_LABEL: Record<OrgChangeType, string> = {
  THANH_LAP: 'Thành lập',
  DOI_TEN: 'Đổi tên',
  SAP_NHAP: 'Sáp nhập',
  HOP_NHAT: 'Hợp nhất',
  CHIA_TACH: 'Chia tách',
  GIAI_THE: 'Giải thể',
};

/** Thân `POST /org/units/{id}/changes`. */
export type OrgChangeRequest = Schemas['OrgChangeRequest'];

/** Phản hồi 201 của `POST /org/units/{id}/changes` (`org.org_change_order`). */
export type OrgChangeOrder = Schemas['OrgChangeOrder'];
