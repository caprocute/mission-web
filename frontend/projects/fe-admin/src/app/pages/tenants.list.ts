import { FilterCodec, ListColumnDef, TENANCY_MODELS, TENANT_STATUS_LABEL, TenancyModel, TenantStatus } from '@vt/api-client/domains';

/** Mã màn hình dùng cho tuỳ chọn cột và bộ lọc đã lưu (CNH-04). */
export const TENANTS_SCREEN = 'SCR-0101';

/** Danh mục cột của "Danh sách đơn vị triển khai"; cột tên đơn vị bắt buộc. */
export const TENANTS_COLUMNS: ListColumnDef[] = [
  { key: 'name', label: 'Đơn vị triển khai', required: true },
  { key: 'cellCode', label: 'Cụm máy chủ' },
  { key: 'tenancyModel', label: 'Mức cô lập' },
  { key: 'accountCount', label: 'Tài khoản' },
  { key: 'policyPacks', label: 'Bộ quy định đánh giá' },
  { key: 'uiPack', label: 'Bộ giao diện' },
  { key: 'status', label: 'Trạng thái' },
];

/** Trạng thái lọc lưu được; từ khoá tìm kiếm (văn bản tự do) không lưu. */
export interface TenantsFilterState {
  cell: string | null;
  model: TenancyModel | null;
  status: TenantStatus | null;
}

const CELL_CODE = /^[A-Za-z0-9-]{1,58}$/;

/**
 * Trạng thái → `statusCodes`; mức cô lập → cờ `model_<mã>`; cụm máy chủ → cờ `cell_<mã>` với `-`
 * đổi thành `_` (mã cụm chỉ gồm chữ, số, gạch nối, suy luận từ dữ liệu hiện có).
 */
export const TENANTS_CODEC: FilterCodec<TenantsFilterState> = {
  toCriteria: (s) => {
    const flags: Record<string, boolean> = {};
    if (s.model) flags[`model_${s.model}`] = true;
    if (s.cell && CELL_CODE.test(s.cell)) flags[`cell_${s.cell.replace(/-/g, '_')}`] = true;
    return {
      ...(s.status ? { statusCodes: [s.status] } : {}),
      ...(Object.keys(flags).length ? { flags } : {}),
    };
  },
  fromCriteria: (c) => {
    const state: TenantsFilterState = { cell: null, model: null, status: null };
    let dropped = 0;
    for (const code of c.statusCodes ?? []) {
      if (!state.status && code in TENANT_STATUS_LABEL) state.status = code as TenantStatus;
      else dropped++;
    }
    for (const [k, on] of Object.entries(c.flags ?? {})) {
      if (!on) continue;
      if (k.startsWith('model_') && TENANCY_MODELS.includes(k.slice(6) as TenancyModel)) {
        state.model = k.slice(6) as TenancyModel;
      } else if (k.startsWith('cell_') && k.length > 5) {
        state.cell = k.slice(5).replace(/_/g, '-');
      } else dropped++;
    }
    for (const [k, v] of Object.entries(c)) {
      if (k !== 'flags' && k !== 'statusCodes' && v !== undefined) dropped++;
    }
    return { state, dropped };
  },
};
