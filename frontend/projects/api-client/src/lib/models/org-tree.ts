import { AssignmentKind } from './org';

/**
 * Cây tổ chức có lịch sử (SCR-0103, QT-01 bước 6) của SVC-04 org-service: bảng `org.org_unit`,
 * `org.org_unit_relation`, `org.assignment`, `org.subject_group`, `org.org_change_order` (TL-03),
 * hai trục thời gian `valid_from/valid_to` (hiệu lực) và `recorded_from/recorded_to` (ghi nhận).
 */

/** `org.org_unit.unit_type`. */
export type OrgUnitType =
  | 'TO_CHUC_DANG'
  | 'CHI_BO'
  | 'CO_QUAN'
  | 'DON_VI_SU_NGHIEP'
  | 'PHONG_BAN'
  | 'XA_PHUONG'
  | 'DOANH_NGHIEP';

export const ORG_UNIT_TYPE_LABEL: Record<OrgUnitType, string> = {
  TO_CHUC_DANG: 'Tổ chức đảng',
  CHI_BO: 'Chi bộ',
  CO_QUAN: 'Cơ quan',
  DON_VI_SU_NGHIEP: 'Đơn vị sự nghiệp',
  PHONG_BAN: 'Phòng, ban',
  XA_PHUONG: 'Xã, phường',
  DOANH_NGHIEP: 'Doanh nghiệp',
};

/** `org.org_unit.level`: cấp TW, tỉnh, xã (chính quyền hai cấp từ 01/07/2025). */
export type OrgUnitLevel = 'TW' | 'TINH' | 'XA';
export const ORG_UNIT_LEVEL_LABEL: Record<OrgUnitLevel, string> = {
  TW: 'Trung ương',
  TINH: 'Cấp tỉnh',
  XA: 'Cấp xã',
};

export type OrgFlow = 'DANG' | 'NHA_NUOC';

/** Khối đối tượng PL 2A (`org.subject_group`, mã `K1`..`K9`). */
export interface SubjectGroup {
  id: string;
  code: string;
  name: string;
  flow: OrgFlow | 'CA_HAI';
}

/** Một nút trên cây (vùng 2), nạp lười theo nhánh. */
export interface OrgTreeNode {
  id: string;
  code: string;
  name: string;
  parentId: string | null;
  unitType: OrgUnitType;
  /** Số đơn vị con hiện có ở thời điểm xem; 0 là lá. */
  childCount: number;
  /** Số phân công còn hiệu lực, hiện cạnh tên (ví dụ "Ban Tổ chức Tỉnh uỷ · 42"). */
  assignmentCount: number;
  validFrom: string;
  /** `null` là còn hiệu lực (`valid_to = infinity`). */
  validTo: string | null;
  /** Ngày hợp nhất, sáp nhập khi đơn vị có đơn vị tiền thân. */
  mergedOn?: string;
}

/** Tổng số dưới chân cây: đơn vị, khối đối tượng, vị trí việc làm. */
export interface OrgTreeStats {
  unitCount: number;
  subjectGroupCount: number;
  positionCount: number;
}

export interface OrgTreeResponse {
  items: OrgTreeNode[];
  stats: OrgTreeStats;
}

/** Kết quả tìm đơn vị: nút kèm chuỗi tổ tiên để mở nhánh. */
export interface OrgUnitMatch extends OrgTreeNode {
  ancestorIds: string[];
  path: string;
}

export interface OrgUnitRef {
  id: string;
  code: string;
  name: string;
}

/** Thẻ chi tiết đơn vị (vùng 3): phiên bản có hiệu lực ở hai trục thời gian đang xem. */
export interface OrgUnitDetail {
  id: string;
  versionId: string;
  code: string;
  /** Mã CSDL CBCCVC qua `integ.mapping_xref`. */
  externalCode?: string;
  name: string;
  shortName?: string;
  level: OrgUnitLevel;
  unitType: OrgUnitType;
  subjectGroupId: string;
  subjectGroup: SubjectGroup;
  flows: OrgFlow[];
  validFrom: string;
  validTo: string | null;
  recordedFrom: string;
  recordedBy: string;
  parent: OrgUnitRef | null;
  predecessors: OrgUnitRef[];
  /** Đơn vị nhận dữ liệu sạch từ nguồn ngoài: không cho sửa trên nền tảng. */
  syncedFrom?: 'CBCCVC' | 'DANG_VIEN';
  rowVersion: number;
  /** Chỉ có ở phản hồi ghi: MSG-01-10 khi hiệu lực rơi vào kỳ đã khoá. */
  warning?: string;
}

/** Loại thay đổi tổ chức trong hộp "Thay đổi tổ chức". */
export type OrgChangeKind =
  'THANH_LAP' | 'DOI_TEN' | 'SAP_NHAP' | 'HOP_NHAT' | 'CHIA_TACH' | 'GIAI_THE';

export const ORG_CHANGE_KIND_LABEL: Record<OrgChangeKind, string> = {
  THANH_LAP: 'Thành lập',
  DOI_TEN: 'Đổi tên',
  SAP_NHAP: 'Sáp nhập',
  HOP_NHAT: 'Hợp nhất',
  CHIA_TACH: 'Chia tách',
  GIAI_THE: 'Giải thể',
};

/** Loại sự kiện ở vùng 4 (gồm cả thay đổi phân công). */
export type OrgHistoryKind = OrgChangeKind | 'SUA' | 'BO_NHIEM' | 'DIEU_DONG';

export const ORG_HISTORY_KIND_LABEL: Record<OrgHistoryKind, string> = {
  ...ORG_CHANGE_KIND_LABEL,
  SUA: 'Sửa thông tin',
  BO_NHIEM: 'Bổ nhiệm',
  DIEU_DONG: 'Điều động',
};

/** Một dòng lịch sử thay đổi (vùng 4). */
export interface OrgHistoryEntry {
  id: string;
  kind: OrgHistoryKind;
  /** Ngày hiệu lực theo quyết định (`valid_from`). */
  effectiveOn: string;
  /** Thời điểm ghi nhận (`recorded_from`). */
  recordedAt: string;
  title: string;
  detail: string;
  orderNo?: string;
}

/** Phân công của đơn vị (vùng 5), `org.assignment`. */
export interface UnitAssignment {
  id: string;
  personName: string;
  jobTitle: string;
  kind: AssignmentKind;
  /** Điều động: `DI` khi người của đơn vị đi nơi khác, `DEN` khi đến. */
  direction?: 'DI' | 'DEN';
  validFrom: string;
  validTo: string | null;
}

export const UNIT_ASSIGNMENT_KIND_LABEL = (a: Pick<UnitAssignment, 'kind' | 'direction'>) =>
  ({
    CHINH: 'Chính',
    KIEM_NHIEM: 'Kiêm nhiệm',
    DIEU_DONG: a.direction === 'DI' ? 'Điều động đi' : 'Điều động đến',
    BIET_PHAI: 'Biệt phái',
    TAM_GIU: 'Tạm giữ',
  })[a.kind];

/** Thân `PUT /org/units/{id}`: tạo phiên bản mới từ `validFrom`, không ghi đè (BR-01-06). */
export interface OrgUnitUpdate {
  name: string;
  shortName?: string;
  externalCode?: string;
  level: OrgUnitLevel;
  unitType: OrgUnitType;
  subjectGroupId: string;
  flows: OrgFlow[];
  validFrom: string;
}

/** Thân `POST /org/units/{id}/changes`. */
export interface OrgChangeInput {
  kind: OrgChangeKind;
  effectiveOn: string;
  /** Văn bản căn cứ (`org.org_change_order.order_no`). */
  orderNo: string;
  /** Tên mới (đổi tên), tên đơn vị mới (thành lập, hợp nhất) hoặc danh sách tên (chia tách). */
  names?: string[];
  /** Đơn vị tiền thân khi sáp nhập, hợp nhất (BR-01-07). */
  predecessorIds?: string[];
}

export interface OrgChangeResult {
  /** Đơn vị nhận kết quả (đơn vị hợp nhất, sáp nhập, thành lập…). */
  unitId: string;
  movedAssignments: number;
  /** MSG-01-11 khi có phân công chuyển theo. */
  message?: string;
  /** MSG-01-10 khi hiệu lực rơi vào kỳ đã khoá. */
  warning?: string;
}
