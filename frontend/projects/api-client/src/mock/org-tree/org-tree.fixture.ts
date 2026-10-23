import { AssignmentKind } from '../../lib/models/org';
import {
  OrgChangeType,
  OrgFlow,
  OrgUnitLevel,
  OrgUnitType,
  SubjectGroup,
} from '../../lib/models/org-tree';
import { resolveRelativeDates } from '../mock-router';

/**
 * Fixture cây tổ chức có lịch sử của Tỉnh Thái Nguyên (SCR-0103, TL-02a Hình 6.9). Dữ liệu hư cấu,
 * dựng theo hai trục thời gian như `org.org_unit`: mỗi phiên bản có `validFrom/validTo` và
 * `recordedFrom/recordedTo` (`null` là vô cực). Ngày `@today±n` quy đổi khi nạp.
 */
export interface UnitVersion {
  versionId: string;
  id: string;
  code: string;
  /** Mã CSDL CBCCVC (`integ.mapping_xref.external_id`). */
  nationalCode?: string;
  name: string;
  shortName?: string;
  level: OrgUnitLevel;
  unitType: OrgUnitType;
  subjectGroupId: string;
  flows: OrgFlow[];
  parentId: string | null;
  predecessorIds: string[];
  /** Chỉ dùng trong giả lập: đơn vị nhận dữ liệu từ nguồn ngoài, máy chủ từ chối sửa (409). */
  syncedFrom?: 'CBCCVC' | 'DANG_VIEN';
  validFrom: string;
  validTo: string | null;
  recordedFrom: string;
  recordedTo: string | null;
  recordedBy: string;
  /**
   * Thời điểm ghi nhận đầu tiên của nội dung khi đoạn hiệu lực được ghi lại (tách phiên bản), để
   * lịch sử không báo nhầm là ghi muộn. Chỉ dùng trong giả lập.
   */
  recordedFirst?: string;
  /** Quyết định làm phát sinh phiên bản (`OrgUnitVersion.changeType`, `changeOrder`). */
  changeType?: OrgChangeType;
  changeOrder?: { id: string; orderNo: string; orderDate?: string };
  changedFields?: string[];
}

export interface AssignmentRow {
  id: string;
  unitId: string;
  personId: string;
  personName: string;
  jobTitleName: string;
  kind: AssignmentKind;
  branch?: 'DANG' | 'CHINH_QUYEN';
  validFrom: string;
  validTo: string | null;
  recordedFrom: string;
}

/** Chín khối đối tượng PL 2A; tên khối là suy luận (PL 2A chưa có trong bộ TL). */
export const SUBJECT_GROUPS: SubjectGroup[] = [
  { id: 'sg-k1', code: 'K1', name: 'Khối 1 · cơ quan đảng', flow: 'DANG' },
  { id: 'sg-k2', code: 'K2', name: 'Khối 2 · Mặt trận, đoàn thể', flow: 'CA_HAI' },
  { id: 'sg-k3', code: 'K3', name: 'Khối 3 · cơ quan dân cử', flow: 'NHA_NUOC' },
  { id: 'sg-k4', code: 'K4', name: 'Khối 4 · cơ quan hành chính', flow: 'NHA_NUOC' },
  { id: 'sg-k5', code: 'K5', name: 'Khối 5 · đơn vị sự nghiệp giáo dục', flow: 'NHA_NUOC' },
  { id: 'sg-k6', code: 'K6', name: 'Khối 6 · đơn vị sự nghiệp y tế', flow: 'NHA_NUOC' },
  { id: 'sg-k7', code: 'K7', name: 'Khối 7 · đơn vị sự nghiệp khác', flow: 'NHA_NUOC' },
  { id: 'sg-k8', code: 'K8', name: 'Khối 8 · chính quyền cấp xã', flow: 'CA_HAI' },
  { id: 'sg-k9', code: 'K9', name: 'Khối 9 · doanh nghiệp nhà nước', flow: 'CA_HAI' },
];

const REORG = '2025-07-01'; // sắp xếp đơn vị hành chính cấp xã, chính quyền hai cấp
const MERGE = '2025-03-01';
const BASE = '2020-01-01';
const BASE_REC = '2020-01-02T08:00:00+07:00';

type Seed = Partial<UnitVersion> &
  Pick<UnitVersion, 'id' | 'code' | 'name' | 'unitType' | 'subjectGroupId' | 'parentId'>;

function v(seed: Seed): UnitVersion {
  return {
    versionId: `${seed.id}-v1`,
    level: 'TINH',
    flows: ['NHA_NUOC'],
    predecessorIds: [],
    validFrom: BASE,
    validTo: null,
    recordedFrom: BASE_REC,
    recordedTo: null,
    recordedBy: 'Hoàng Đức Dũng',
    ...seed,
  };
}

const SO = [
  ['so-tc', 'TN-UB-STC', 'Sở Tài chính'],
  ['so-gd', 'TN-UB-SGD', 'Sở Giáo dục và Đào tạo'],
  ['so-yt', 'TN-UB-SYT', 'Sở Y tế'],
  ['so-xd', 'TN-UB-SXD', 'Sở Xây dựng'],
  ['so-nnmt', 'TN-UB-SNNMT', 'Sở Nông nghiệp và Môi trường'],
  ['so-khcn', 'TN-UB-SKHCN', 'Sở Khoa học và Công nghệ'],
  ['so-vhttdl', 'TN-UB-SVHTTDL', 'Sở Văn hoá, Thể thao và Du lịch'],
  ['so-ct', 'TN-UB-SCT', 'Sở Công Thương'],
  ['so-tp', 'TN-UB-STP', 'Sở Tư pháp'],
  ['ttra', 'TN-UB-TTR', 'Thanh tra tỉnh'],
];

/** Tên xã, phường mới sau sắp xếp (hư cấu, ghép từ địa danh). */
const XA_A = ['Phú', 'Đại', 'Tân', 'Đồng', 'Võ', 'Quân', 'Thần', 'La', 'Yên', 'Vạn', 'Hoá', 'Minh'];
const XA_B = ['Bình', 'Từ', 'Cương', 'Hỷ', 'Nhai', 'Chu', 'Sa', 'Hiên', 'Lạc', 'Xuân', 'Thượng'];

function xaNames(): string[] {
  const out: string[] = [];
  for (const a of XA_A) for (const b of XA_B) if (out.length < 92) out.push(`${a} ${b}`);
  return out.filter((n) => n !== 'Phú Bình');
}

function seedUnits(): UnitVersion[] {
  const units: UnitVersion[] = [
    v({
      id: 'u-tn',
      code: 'TN-DB',
      name: 'Đảng bộ tỉnh Thái Nguyên',
      unitType: 'TO_CHUC_DANG',
      subjectGroupId: 'sg-k1',
      flows: ['DANG'],
      parentId: null,
    }),
    v({
      id: 'u-btc',
      code: 'TN-TU-BTC',
      name: 'Ban Tổ chức Tỉnh uỷ',
      unitType: 'CO_QUAN',
      subjectGroupId: 'sg-k1',
      flows: ['DANG'],
      parentId: 'u-tn',
    }),
    v({
      id: 'u-vptu',
      code: 'TN-TU-VP',
      name: 'Văn phòng Tỉnh uỷ',
      unitType: 'CO_QUAN',
      subjectGroupId: 'sg-k1',
      flows: ['DANG'],
      parentId: 'u-tn',
    }),
    v({
      id: 'u-ubnd',
      code: 'TN-UB',
      name: 'UBND tỉnh',
      shortName: 'UBND tỉnh Thái Nguyên',
      unitType: 'CO_QUAN',
      subjectGroupId: 'sg-k4',
      parentId: 'u-tn',
    }),
    // Sở Nội vụ cũ và Sở LĐ-TB&XH kết thúc khi hợp nhất 01/03/2025
    v({
      id: 'u-snv-cu',
      code: 'TN-UB-SNV-CU',
      name: 'Sở Nội vụ (cũ)',
      unitType: 'CO_QUAN',
      subjectGroupId: 'sg-k4',
      parentId: 'u-ubnd',
      validTo: MERGE,
    }),
    v({
      id: 'u-sldtbxh',
      code: 'TN-UB-SLDTBXH',
      name: 'Sở Lao động – Thương binh và Xã hội',
      unitType: 'CO_QUAN',
      subjectGroupId: 'sg-k4',
      parentId: 'u-ubnd',
      validTo: MERGE,
    }),
    v({
      id: 'u-snv',
      code: 'TN-UB-SNV',
      nationalCode: '000.20.21.H56',
      name: 'Sở Nội vụ',
      shortName: 'Sở Nội vụ tỉnh Thái Nguyên',
      unitType: 'CO_QUAN',
      subjectGroupId: 'sg-k4',
      flows: ['NHA_NUOC', 'DANG'],
      parentId: 'u-ubnd',
      predecessorIds: ['u-snv-cu', 'u-sldtbxh'],
      validFrom: MERGE,
      recordedFrom: '2025-02-28T16:20:00+07:00',
      changeType: 'HOP_NHAT',
      changeOrder: { id: 'co-512', orderNo: '512/QĐ-UBND', orderDate: '2025-02-25' },
    }),
    v({
      id: 'u-snv-vp',
      code: 'TN-UB-SNV-VP',
      name: 'Văn phòng Sở',
      unitType: 'PHONG_BAN',
      subjectGroupId: 'sg-k4',
      parentId: 'u-snv',
      validFrom: MERGE,
      recordedFrom: '2025-02-28T16:20:00+07:00',
    }),
    v({
      id: 'u-snv-tcbc',
      code: 'TN-UB-SNV-TCBC',
      name: 'Phòng Tổ chức, biên chế',
      unitType: 'PHONG_BAN',
      subjectGroupId: 'sg-k4',
      parentId: 'u-snv',
      validFrom: MERGE,
      recordedFrom: '2025-02-28T16:20:00+07:00',
    }),
    // đổi tên hiệu lực @today-34 nhưng ghi nhận @today-33 (ghi muộn một ngày)
    v({
      id: 'u-snv-ccvc',
      versionId: 'u-snv-ccvc-v1',
      code: 'TN-UB-SNV-CCVC',
      name: 'Phòng Công chức',
      unitType: 'PHONG_BAN',
      subjectGroupId: 'sg-k4',
      parentId: 'u-snv',
      validFrom: MERGE,
      recordedFrom: '2025-02-28T16:20:00+07:00',
      recordedTo: '@today-33T09:12:00+07:00',
    }),
    v({
      id: 'u-snv-ccvc',
      versionId: 'u-snv-ccvc-v2',
      code: 'TN-UB-SNV-CCVC',
      name: 'Phòng Công chức',
      unitType: 'PHONG_BAN',
      subjectGroupId: 'sg-k4',
      parentId: 'u-snv',
      validFrom: MERGE,
      validTo: '@today-34',
      recordedFrom: '@today-33T09:12:00+07:00',
      recordedFirst: '2025-02-28T16:20:00+07:00',
    }),
    v({
      id: 'u-snv-ccvc',
      versionId: 'u-snv-ccvc-v3',
      code: 'TN-UB-SNV-CCVC',
      name: 'Phòng Công chức, viên chức',
      unitType: 'PHONG_BAN',
      subjectGroupId: 'sg-k4',
      parentId: 'u-snv',
      validFrom: '@today-34',
      recordedFrom: '@today-33T09:12:00+07:00',
      changeType: 'DOI_TEN',
      changeOrder: { id: 'co-96', orderNo: '96/QĐ-SNV', orderDate: '@today-36' },
      changedFields: ['name'],
    }),
    ...SO.map(([id, code, name]) =>
      v({ id, code, name, unitType: 'CO_QUAN', subjectGroupId: 'sg-k4', parentId: 'u-ubnd' }),
    ),
    // huyện và xã trước sắp xếp, kết thúc 30/06/2025
    v({
      id: 'u-pb',
      code: 'TN-H-PB',
      name: 'Huyện Phú Bình',
      unitType: 'CO_QUAN',
      level: 'TINH',
      subjectGroupId: 'sg-k4',
      flows: ['NHA_NUOC', 'DANG'],
      parentId: 'u-tn',
      validTo: REORG,
    }),
    ...[
      ['u-pb-ks', 'Xã Kha Sơn'],
      ['u-pb-lp', 'Xã Lương Phú'],
      ['u-pb-td', 'Xã Tân Đức'],
    ].map(([id, name], i) =>
      v({
        id,
        code: `TN-H-PB-X${i + 1}`,
        name,
        unitType: 'XA_PHUONG',
        level: 'XA',
        subjectGroupId: 'sg-k8',
        flows: ['NHA_NUOC', 'DANG'],
        parentId: 'u-pb',
        validTo: REORG,
      }),
    ),
    v({
      id: 'u-dux-pb',
      code: 'TN-DUX-PB',
      name: 'Đảng uỷ xã Phú Bình',
      unitType: 'TO_CHUC_DANG',
      level: 'XA',
      subjectGroupId: 'sg-k8',
      flows: ['DANG'],
      parentId: 'u-tn',
      predecessorIds: ['u-pb-ks', 'u-pb-lp', 'u-pb-td'],
      validFrom: REORG,
      recordedFrom: '2025-06-27T10:05:00+07:00',
      changeType: 'HOP_NHAT',
      changeOrder: { id: 'co-1650', orderNo: '1650/NQ-UBTVQH15', orderDate: '2025-06-16' },
    }),
  ];
  // trường THPT thuộc Sở GD&ĐT, dữ liệu đồng bộ từ CSDL CBCCVC (không sửa trên nền tảng)
  for (let i = 1; i <= 36; i++) {
    units.push(
      v({
        id: `u-thpt-${i}`,
        code: `TN-GD-THPT${String(i).padStart(2, '0')}`,
        nationalCode: `000.20.33.H56.${String(i).padStart(3, '0')}`,
        name: `Trường THPT ${XA_A[i % XA_A.length]} ${XA_B[(i * 5) % XA_B.length]}`,
        unitType: 'DON_VI_SU_NGHIEP',
        subjectGroupId: 'sg-k5',
        parentId: 'so-gd',
        syncedFrom: 'CBCCVC',
      }),
    );
  }
  // UBND xã, phường mới từ 01/07/2025
  xaNames().forEach((n, i) =>
    units.push(
      v({
        id: `u-xa-${i + 1}`,
        code: `TN-X-${String(i + 1).padStart(3, '0')}`,
        name: `${i % 7 === 0 ? 'UBND phường' : 'UBND xã'} ${n}`,
        unitType: 'XA_PHUONG',
        level: 'XA',
        subjectGroupId: 'sg-k8',
        parentId: 'u-ubnd',
        validFrom: REORG,
        recordedFrom: '2025-06-27T10:05:00+07:00',
      }),
    ),
  );
  return units;
}

function seedAssignments(): AssignmentRow[] {
  const a = (
    id: string,
    personName: string,
    jobTitleName: string,
    kind: AssignmentKind,
    validFrom: string,
    validTo: string | null = null,
  ): AssignmentRow => ({
    id,
    unitId: 'u-snv',
    personId: `p-${id}`,
    personName,
    jobTitleName,
    kind,
    branch: 'CHINH_QUYEN',
    validFrom,
    validTo,
    recordedFrom: '2025-02-28T16:20:00+07:00',
  });
  return [
    a('as-1', 'Đỗ Quang Hưng', 'Giám đốc', 'CHINH', MERGE),
    a('as-2', 'Đỗ Quang Hưng', 'Bí thư Chi bộ Sở', 'KIEM_NHIEM', '2025-03-15'),
    {
      ...a('as-3', 'Nguyễn Thị Lan', 'Phó Giám đốc', 'CHINH', '@today-7'),
      recordedFrom: '@today-7T10:30:00+07:00',
    },
    a('as-4', 'Vũ Văn Toàn', 'Chuyên viên P. Công chức', 'DIEU_DONG', MERGE, '@today+13'),
    a('as-5', 'Bùi Thanh Mai', 'Chuyên viên P. Tổ chức', 'BIET_PHAI', '2026-08-01', '2027-01-01'),
  ];
}

export function seedOrgTree() {
  return resolveRelativeDates({
    units: seedUnits(),
    assignments: seedAssignments(),
  });
}

const HO = ['Nguyễn', 'Trần', 'Lê', 'Phạm', 'Hoàng', 'Vũ', 'Đặng', 'Bùi', 'Đỗ', 'Ngô', 'Dương'];
const DEM = ['Văn', 'Thị', 'Minh', 'Quang', 'Thu', 'Đức', 'Thanh', 'Hồng', 'Ngọc'];
const TEN = ['An', 'Bình', 'Châu', 'Dũng', 'Giang', 'Hà', 'Hải', 'Hoa', 'Khánh', 'Linh', 'Nam'];
const CHUC_DANH = ['Chuyên viên', 'Chuyên viên chính', 'Cán sự', 'Phó Trưởng phòng'];

/** Số phân công còn hiệu lực sinh xác định theo mã đơn vị (đơn vị không có trong fixture chi tiết). */
export function generatedCount(id: string): number {
  let h = 0;
  for (const ch of id) h = (h * 31 + ch.charCodeAt(0)) % 997;
  return 8 + (h % 60);
}

export function generatedAssignments(id: string, from: string): AssignmentRow[] {
  const n = generatedCount(id);
  return Array.from({ length: n }, (_, i) => ({
    id: `${id}-as-${i + 1}`,
    unitId: id,
    personId: `${id}-p-${i + 1}`,
    personName: `${HO[(i * 7) % HO.length]} ${DEM[(i * 5) % DEM.length]} ${TEN[(i * 3 + n) % TEN.length]}`,
    jobTitleName: i === 0 ? 'Thủ trưởng đơn vị' : CHUC_DANH[i % CHUC_DANH.length],
    kind: 'CHINH' as AssignmentKind,
    branch: 'CHINH_QUYEN' as const,
    validFrom: from,
    validTo: null,
    recordedFrom: BASE_REC,
  }));
}
