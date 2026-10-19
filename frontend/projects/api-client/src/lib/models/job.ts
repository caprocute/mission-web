import type { components } from '../generated/common';

/** Trạng thái tác vụ dài `/api/v1/jobs/{id}` (lược đồ `Job` của `contracts/openapi/common/common.yaml`, HD-06). */
export type JobStatus = components['schemas']['JobStatus'];
export type Job = components['schemas']['Job'];

export const JOB_STATUS_LABEL: Record<JobStatus, string> = {
  CHO: 'Đang chờ xử lý',
  DANG_CHAY: 'Đang xử lý',
  HOAN_TAT: 'Hoàn tất',
  THAT_BAI: 'Thất bại',
  DA_HUY: 'Đã huỷ',
};

/** Trạng thái kết thúc: dừng theo dõi. */
export const JOB_TERMINAL_STATUSES: readonly JobStatus[] = ['HOAN_TAT', 'THAT_BAI', 'DA_HUY'];
