import { TASK_STATUS_LABEL } from '@vt/api-client';
import { COMPLAINT_RESOLUTION_LABEL, PERIOD_STATUS_LABEL, RATING_LEVEL_LABEL, REOPEN_STATUS_LABEL } from '@vt/api-client/domains';

/**
 * Tên nghiệp vụ của các sự kiện hay tra ở SCR-1302 (danh sách gợi ý theo bảng EVT của baseline, suy luận).
 * Giao diện chỉ hiện tên tiếng Việt; mã sự kiện giữ ở thuộc tính `data-event-type` cho kiểm thử (YC-PT-10).
 */
export const EVENT_LABEL: Record<string, string> = {
  'task.task.assigned.v1': 'Giao nhiệm vụ',
  'task.task.accepted.v1': 'Nhận nhiệm vụ',
  'task.task.progress.v1': 'Cập nhật tiến độ',
  'product.evidence.submitted.v1': 'Nộp minh chứng',
  'product.evidence.accepted.v1': 'Nghiệm thu sản phẩm',
  'appraisal.self_assessment.submitted.v1': 'Gửi phiếu tự đánh giá',
  'appraisal.score.computed.v1': 'Tính điểm đánh giá',
  'appraisal.appraisal.completed.v1': 'Hoàn thành thẩm định',
  'appraisal.decision.signed.v1': 'Ký quyết định xếp loại',
  'config.period.locked.v1': 'Khoá kỳ đánh giá',
  'oversight.complaint.submitted.v1': 'Gửi khiếu nại',
  'oversight.complaint.concluded.v1': 'Kết luận khiếu nại',
  'oversight.reopen_request.created.v1': 'Lập yêu cầu mở lại kỳ',
  'oversight.reopen_request.approved.v1': 'Phê duyệt yêu cầu mở lại kỳ',
  'ledger.seal.created.v1': 'Niêm phong dữ liệu định kỳ',
};

export const EVENT_TYPES = Object.keys(EVENT_LABEL);

export const eventLabel = (t: string) => EVENT_LABEL[t] ?? 'Sự kiện khác';

/** Tên trường trong giá trị trước, sau; trường không có tên nghiệp vụ thì không hiện. */
const FIELD_LABEL: Record<string, string> = {
  status: 'Trạng thái',
  task: 'Nhiệm vụ',
  progress: 'Tiến độ (%)',
  evidence: 'Minh chứng',
  version: 'Lần nộp',
  c: 'Hệ số C',
  c_factor: 'Hệ số C',
  quality: 'Mức chất lượng',
  selfLevel: 'Tự xếp loại',
  score: 'Điểm',
  level: 'Xếp loại',
  signature: 'Chữ ký số',
  complaint: 'Khiếu nại',
  content: 'Nội dung',
  conclusion: 'Kết luận',
  code: 'Số yêu cầu',
  round: 'Lượt duyệt',
};

const QUALITY_LABEL: Record<string, string> = {
  TOT: 'Tốt',
  KHA: 'Khá',
  DAT: 'Đạt',
  KHONG_DAT: 'Không đạt',
};

const SIGNATURE_LABEL: Record<string, string> = { MySign: 'Đã ký' };

function valueLabel(key: string, v: unknown): string {
  if (v === null) return 'chưa có';
  if (typeof v === 'number') return v.toLocaleString('vi-VN');
  const s = String(v);
  const maps: Record<string, string>[] =
    key === 'quality'
      ? [QUALITY_LABEL]
      : key === 'level' || key === 'selfLevel'
        ? [RATING_LEVEL_LABEL]
        : key === 'conclusion'
          ? [COMPLAINT_RESOLUTION_LABEL]
          : key === 'signature'
            ? [SIGNATURE_LABEL]
            : key === 'status'
              ? [TASK_STATUS_LABEL, PERIOD_STATUS_LABEL, REOPEN_STATUS_LABEL]
              : [];
  for (const m of maps) if (m[s]) return m[s];
  return s;
}

/** Giá trị trước, sau trong `metadata` thành câu ngắn: "Hệ số C: 0,85; Mức chất lượng: Khá". */
export function values(v: unknown): string {
  if (v === undefined) return '—';
  if (v === null) return 'chưa có';
  if (typeof v !== 'object') return valueLabel('', v);
  const parts = Object.entries(v as Record<string, unknown>)
    .filter(([k]) => FIELD_LABEL[k])
    .map(([k, x]) => `${FIELD_LABEL[k]}: ${valueLabel(k, x)}`);
  return parts.length ? parts.join('; ') : '—';
}
