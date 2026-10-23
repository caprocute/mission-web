import { JOB_TERMINAL_STATUSES, Job } from '../lib/models/job';
import { MockResponse, ok } from './mock-router';

/**
 * Bộ tác vụ nền dùng chung của giả lập (`GET /api/v1/jobs/{id}`, `jobs.yaml`): thao tác trả
 * `202 Accepted` + `Location: /api/v1/jobs/{id}` của bất kỳ module nào đăng ký job ở đây để
 * `JobTracker` thăm dò được như với máy chủ thật (WEB-072). Sau `after` lần đọc, job kết thúc và
 * `finish` chạy: đổi trạng thái tài nguyên rồi trả phần job cần ghi đè (ví dụ `THAT_BAI` kèm
 * `problem`); không trả gì thì job `HOAN_TAT`.
 */
interface MockJobEntry {
  job: Job;
  polls: number;
  after: number;
  finish?: () => Partial<Job> | void;
}

let store = new Map<string, MockJobEntry>();

/**
 * Nguồn job riêng của một bộ giả lập (ví dụ saga cấp phát tenant tính tiến độ theo thời gian):
 * `readMockJob` hỏi lần lượt khi id không có trong bộ chung. Không bị xoá khi đặt lại dữ liệu.
 */
const sources: ((id: string) => MockResponse | null)[] = [];
export function addMockJobSource(read: (id: string) => MockResponse | null): void {
  if (!sources.includes(read)) sources.push(read);
}

export function startMockJob(
  type: string,
  finish?: () => Partial<Job> | void,
  after = 1,
  initial: Partial<Job> = {},
): MockResponse {
  const id = crypto.randomUUID();
  const job: Job = { status: 'CHO', progress: 0, ...initial, id, type };
  store.set(id, { job, polls: 0, after, finish });
  return { status: 202, body: { ...job }, headers: { Location: `/api/v1/jobs/${id}` } };
}

/** Trạng thái job đã đăng ký; `null` khi không có (để bộ giả lập khác trả 404). */
export function readMockJob(id: string): MockResponse | null {
  const e = store.get(id);
  if (!e) {
    for (const read of sources) {
      const res = read(id);
      if (res) return res;
    }
    return null;
  }
  if (!JOB_TERMINAL_STATUSES.includes(e.job.status)) {
    e.polls++;
    if (e.polls >= e.after) {
      e.job = {
        id: e.job.id,
        type: e.job.type,
        status: 'HOAN_TAT',
        progress: 100,
        ...(e.finish?.() ?? {}),
        updatedAt: new Date().toISOString(),
      };
    } else {
      e.job = { ...e.job, status: 'DANG_CHAY', progress: Math.max(e.job.progress ?? 0, 50) };
    }
  }
  return ok({ ...e.job });
}

export function resetMockJobs(): void {
  store = new Map();
}
