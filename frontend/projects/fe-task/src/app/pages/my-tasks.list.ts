import { AssigneeRole, MyTasksQuickFilter, TaskGroup } from '@vt/api-client';
import { FilterCodec, ListColumnDef } from '@vt/api-client/domains';

/** Mã màn hình dùng cho tuỳ chọn cột và bộ lọc đã lưu (CNH-04). */
export const MY_TASKS_SCREEN = 'SCR-0503';

/** Danh mục cột của "Việc của tôi"; cột tên nhiệm vụ bắt buộc. */
export const MY_TASKS_COLUMNS: ListColumnDef[] = [
  { key: 'task', label: 'Nhiệm vụ', required: true },
  { key: 'taskGroup', label: 'Nhóm' },
  { key: 'role', label: 'Vai trò' },
  { key: 'dueDate', label: 'Hạn' },
  { key: 'progress', label: 'Tiến độ' },
  { key: 'situation', label: 'Tình trạng' },
  { key: 'status', label: 'Trạng thái' },
];

/** Trạng thái lọc lưu được; KR và từ khoá tìm kiếm không lưu (KR đổi theo kỳ, từ khoá là văn bản tự do). */
export interface MyTasksFilterState {
  filter: MyTasksQuickFilter;
  role: AssigneeRole | null;
  group: TaskGroup | null;
}

const QUICK: MyTasksQuickFilter[] = ['TAT_CA', 'CHAM', 'SAP_HAN', 'CHO_NGHIEM_THU', 'HOAN_THANH'];
const ROLES: AssigneeRole[] = ['CHU_TRI', 'PHOI_HOP'];
const GROUPS: TaskGroup[] = ['THEN_CHOT', 'THUONG_XUYEN'];

/** Lọc nhanh, vai trò, nhóm ghi thành cờ `quick_*`, `role_*`, `group_*` của tiêu chí. */
export const MY_TASKS_CODEC: FilterCodec<MyTasksFilterState> = {
  toCriteria: (s) => {
    const flags: Record<string, boolean> = {};
    if (s.filter !== 'TAT_CA') flags[`quick_${s.filter}`] = true;
    if (s.role) flags[`role_${s.role}`] = true;
    if (s.group) flags[`group_${s.group}`] = true;
    return Object.keys(flags).length ? { flags } : {};
  },
  fromCriteria: (c) => {
    const state: MyTasksFilterState = { filter: 'TAT_CA', role: null, group: null };
    let dropped = 0;
    for (const [k, on] of Object.entries(c.flags ?? {})) {
      if (!on) continue;
      const [kind, ...rest] = k.split('_');
      const v = rest.join('_');
      if (kind === 'quick' && QUICK.includes(v as MyTasksQuickFilter))
        state.filter = v as MyTasksQuickFilter;
      else if (kind === 'role' && ROLES.includes(v as AssigneeRole)) state.role = v as AssigneeRole;
      else if (kind === 'group' && GROUPS.includes(v as TaskGroup)) state.group = v as TaskGroup;
      else dropped++;
    }
    for (const [k, v] of Object.entries(c)) if (k !== 'flags' && v !== undefined) dropped++;
    return { state, dropped };
  },
};
