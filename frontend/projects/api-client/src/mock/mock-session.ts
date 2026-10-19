/**
 * Trạng thái phiên giả lập dùng chung cho các mock cần biết người dùng hiện tại (BR-04-08), thời điểm
 * MFA gần nhất (step-up, BR-00-02) và tình trạng dịch vụ ký số (MSG-04-13). Không chứa dữ liệu mẫu nên
 * được xuất ra ngoài mà không kéo fixture vào gói chính.
 */
export const MOCK_ME = { id: 'dev-user', name: 'Cán bộ thử nghiệm' } as const;

/** Cửa sổ MFA cho thao tác nhạy cảm: 15 phút (TL-02 SCR-0403, BR-00-02). */
export const STEP_UP_WINDOW_MS = 15 * 60_000;

const state = { lastMfaAt: null as number | null, esignDown: false };

/** Giả lập xác thực bước lên thành công: lần MFA gần nhất là bây giờ. */
export function mockStepUp(): void {
  state.lastMfaAt = Date.now();
}

/** Bật hoặc tắt sự cố của esign-service giả lập. */
export function setMockEsignOutage(down: boolean): void {
  state.esignDown = down;
}

export const mockSession = {
  recentMfa: () => state.lastMfaAt !== null && Date.now() - state.lastMfaAt <= STEP_UP_WINDOW_MS,
  esignDown: () => state.esignDown,
  reset: () => {
    state.lastMfaAt = null;
    state.esignDown = false;
  },
};

/** Đưa toàn bộ dữ liệu giả lập về fixture ban đầu (dùng trong kiểm thử của các remote); nạp lười. */
export async function resetMockApi(): Promise<void> {
  (await import('./mock-registry')).resetMockData();
}
