import { components } from '../generated/mission-core';

/** Trạng thái tác vụ dài `/api/v1/jobs/{id}` (giả định WEB-007, xem tools/openapi). */
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
