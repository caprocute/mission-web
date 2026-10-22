import { inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MyTasksQuickFilter, TaskApi } from '@vt/api-client';

/** Các chỉ số chuyên môn hiển thị, lấy từ số đếm của `/api/v1/tasks/mine` (API công bố). */
export const CHI_SO: { key: MyTasksQuickFilter; label: string }[] = [
  { key: 'TAT_CA', label: 'Nhiệm vụ trong kỳ' },
  { key: 'SAP_HAN', label: 'Sắp đến hạn' },
  { key: 'CHAM', label: 'Chậm tiến độ' },
  { key: 'HOAN_THANH', label: 'Đã hoàn thành' },
];

/** Danh sách và số đếm nhiệm vụ của người dùng trong kỳ hiện tại; lỗi API trả `null`. */
export function nhiemVuCuaToi(limit: number) {
  const api = inject(TaskApi);
  return rxResource({ stream: () => api.myTasks({ limit }) });
}
