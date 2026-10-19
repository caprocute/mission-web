/**
 * Trạng thái phiên giả lập dùng chung cho các mock cần biết người dùng hiện tại (BR-04-08), thời điểm
 * MFA gần nhất (step-up, BR-00-02) và tình trạng dịch vụ ký số (MSG-04-13). Không chứa dữ liệu mẫu nên
 * được xuất ra ngoài mà không kéo fixture vào gói chính.
 */
export const MOCK_ME = { id: 'dev-user', name: 'Cán bộ thử nghiệm' } as const;

/** Cửa sổ MFA cho thao tác nhạy cảm: 15 phút (TL-02 SCR-0403, BR-00-02). */
export const STEP_UP_WINDOW_MS = 15 * 60_000;

const state = {
  lastMfaAt: null as number | null,
  esignDown: false,
  failUploadPart: null as number | null,
};

/** Giả lập xác thực bước lên thành công: lần MFA gần nhất là bây giờ. */
export function mockStepUp(): void {
  state.lastMfaAt = Date.now();
}

/** Bật hoặc tắt sự cố của esign-service giả lập. */
export function setMockEsignOutage(down: boolean): void {
  state.esignDown = down;
}

/**
 * Giả lập mất kết nối khi tải lên (QT-07): lần gửi kế tiếp của phần `partNo` trả 503 một lần, để kiểm
 * chứng tự gửi lại và nối tiếp tải lên.
 */
export function failMockUploadPartOnce(partNo = 1): void {
  state.failUploadPart = partNo;
}

export const mockSession = {
  recentMfa: () => state.lastMfaAt !== null && Date.now() - state.lastMfaAt <= STEP_UP_WINDOW_MS,
  esignDown: () => state.esignDown,
  takeUploadFailure: (partNo: number) => {
    if (state.failUploadPart !== partNo) return false;
    state.failUploadPart = null;
    return true;
  },
  reset: () => {
    state.lastMfaAt = null;
    state.esignDown = false;
    state.failUploadPart = null;
  },
};

/** Đưa toàn bộ dữ liệu giả lập về fixture ban đầu (dùng trong kiểm thử của các remote); nạp lười. */
export async function resetMockApi(): Promise<void> {
  (await import('./mock-registry')).resetMockData();
}
