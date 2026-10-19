import { Delegation, RoleBinding, RoleCode } from '../../lib/models/role-binding';
import { MOCK_ME } from '../mock-session';
import { ORG_PERSONS } from '../org/org.mock';

/**
 * Dữ liệu mẫu quản trị vai trò và uỷ quyền (SCR-0105 đến SCR-0107, WEB-058). Người lấy từ fixture
 * `org` (Ban Tổ chức, Văn phòng), đơn vị lấy từ cây tổ chức có lịch sử (`org-tree`), nên chọn
 * "Ban Tổ chức Tỉnh uỷ" trên cây thấy ngay các bản ghi. Ngày tính tương đối so với hôm nay.
 */
const DAY = 86_400_000;
export const at = (days: number, hour = 0) => {
  const d = new Date(Date.now() + days * DAY);
  d.setHours(hour, 0, 0, 0);
  return d.toISOString();
};

export const ref = (id: string) => {
  if (id === MOCK_ME.id) return { id, name: MOCK_ME.name };
  const p = ORG_PERSONS.find((x) => x.id === id);
  return { id, name: p?.name ?? id };
};
export const titleOf = (id: string) => ORG_PERSONS.find((x) => x.id === id)?.title ?? null;

const BTC = { id: 'u-btc', name: 'Ban Tổ chức Tỉnh uỷ' };
const VPTU = { id: 'u-vptu', name: 'Văn phòng Tỉnh uỷ' };
const TN = { id: 'u-tn', name: 'Đảng bộ tỉnh Thái Nguyên' };
const SNV = { id: 'u-snv', name: 'Sở Nội vụ' };

export const SENSITIVE: readonly RoleCode[] = ['decider', 'second_approver', 'policy_author'];

/** Bản ghi lưu như cột của `config.role_binding`; `status` do máy chủ suy ra khi trả về. */
export type StoredBinding = Omit<RoleBinding, 'status'>;
export type StoredDelegation = Omit<Delegation, 'status'>;

function b(
  id: string,
  personId: string,
  roleCode: RoleCode,
  orgUnit: { id: string; name: string },
  from: number,
  to: number,
  grantedById: string,
  extra: Partial<StoredBinding> = {},
): StoredBinding {
  return {
    id,
    person: ref(personId),
    personTitle: titleOf(personId),
    roleCode,
    orgUnit,
    scope: 'DON_VI_VA_CAP_DUOI',
    flow: null,
    validFrom: at(from),
    validTo: at(to, 23),
    sensitive: SENSITIVE.includes(roleCode),
    grantedBy: ref(grantedById),
    grantedAt: at(Math.min(from, 0) - 1, 9),
    reason: 'Quyết định phân công công tác của Ban Thường vụ Tỉnh uỷ',
    approvedBy: null,
    approvedAt: null,
    endedBy: null,
    endedAt: null,
    endKind: null,
    endReason: null,
    rowVersion: 1,
    ...extra,
  };
}

export function seedBindings(): StoredBinding[] {
  return [
    b('rb-01', 'p-dung', 'leader', BTC, -400, 330, 'p-lan'),
    b('rb-02', 'p-binh', 'appraiser', BTC, -200, 20, 'p-lan', {
      flow: 'DANG',
      reason: 'Quyết định 87-QĐ/TU giao thẩm định hồ sơ khối Đảng năm 2026',
    }),
    b('rb-03', 'p-chau', 'decider', BTC, 12, 377, 'p-lan', {
      flow: 'DANG',
      grantedAt: at(-1, 9),
      reason: 'Quyết định 152-QĐ/TU ngày 15/10/2026 giao quyết định xếp loại khối Đảng',
    }),
    b('rb-04', 'p-an', 'second_approver', BTC, 0, 365, MOCK_ME.id, {
      grantedAt: at(0, 8),
      reason: 'Bổ sung người duyệt thứ hai cho lệnh khoá kỳ quý IV/2026',
    }),
    b('rb-05', 'p-ha', 'manager', BTC, -150, 215, 'p-lan', { scope: 'DON_VI' }),
    b('rb-06', 'p-tuan', 'inspector', BTC, -90, 275, 'p-lan', {
      endedBy: ref('p-lan'),
      endedAt: at(-10, 15),
      endKind: 'THU_HOI',
      endReason: 'Đồng chí chuyển công tác sang Uỷ ban Kiểm tra Tỉnh uỷ',
      rowVersion: 2,
    }),
    b('rb-07', 'p-hung', 'policy_author', VPTU, -60, 305, 'p-lan', {
      approvedBy: ref('p-dung'),
      approvedAt: at(-60, 14),
      rowVersion: 2,
    }),
    b('rb-08', 'p-an', 'manager', BTC, -500, -135, 'p-lan', { scope: 'DON_VI' }),
    b('rb-09', 'p-lan', 'tenant_admin', TN, -700, 395, 'p-dung'),
    b('rb-10', 'p-tuan', 'appraiser', SNV, 5, 370, 'p-lan', {
      reason: 'Điều động hỗ trợ thẩm định khối chính quyền',
    }),
  ];
}

export function seedDelegations(): StoredDelegation[] {
  const d = (
    id: string,
    kind: StoredDelegation['kind'],
    from: string,
    to: string,
    orgUnit: { id: string; name: string },
    start: number,
    end: number,
    reason: string,
    extra: Partial<StoredDelegation> = {},
  ): StoredDelegation => ({
    id,
    kind,
    delegator: ref(from),
    delegate: ref(to),
    orgUnit,
    subject: null,
    validFrom: at(start),
    validTo: at(end, 23),
    reason,
    createdBy: ref('p-dung'),
    createdAt: at(Math.min(start, 0) - 1, 10),
    endedBy: null,
    endedAt: null,
    endReason: null,
    rowVersion: 1,
    ...extra,
  });
  return [
    d(
      'dl-01',
      'DUYET_THAY',
      'p-dung',
      'p-chau',
      BTC,
      -2,
      10,
      'Trưởng ban dự lớp bồi dưỡng kiến thức quốc phòng',
    ),
    d('dl-02', 'CHECK_IN_THAY', 'p-ha', 'p-an', BTC, 3, 45, 'Đồng chí Hà nghỉ chế độ thai sản', {
      subject: { id: 'kr-btc-02', name: 'KR2: 100% hồ sơ đánh giá quý được số hoá' },
    }),
    d(
      'dl-03',
      'NGHIEM_THU_THAY',
      'p-lan',
      'p-hung',
      VPTU,
      -40,
      -20,
      'Chánh Văn phòng đi công tác nước ngoài',
      {
        createdBy: ref('p-lan'),
      },
    ),
  ];
}
