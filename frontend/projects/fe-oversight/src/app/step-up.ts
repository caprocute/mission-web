import { InjectionToken, Injector, inject } from '@angular/core';
import { RUNTIME_CONFIG, mockApiActive, mockStepUp } from '@vt/api-client';
import { SessionService } from '@vt/auth';

/** Cửa sổ MFA cho phê duyệt mở lại kỳ: 15 phút (BR-13-04, BR-00-02). */
const MAX_AGE_SEC = 900;

/**
 * Xác thực bước lên (step-up MFA, RFC 9470) trước khi tạo, phê duyệt hoặc từ chối yêu cầu mở lại kỳ
 * (SCR-1301, BR-13-04, MSG-13-02): gọi `requireStepUp` của `@vt/auth`, shell mở hộp MFA khi phiên chưa
 * đạt `aal2` trong 15 phút. Trả `false` khi người dùng huỷ. Chạy riêng remote thì bỏ qua hộp thoại; với
 * dữ liệu giả lập, báo máy chủ giả lập là phiên vừa MFA.
 */
export const OVERSIGHT_STEP_UP = new InjectionToken<(reason: string) => Promise<boolean>>(
  'vt.fe-oversight.step-up',
  {
    providedIn: 'root',
    factory: () => {
      const cfg = inject(RUNTIME_CONFIG);
      const injector = inject(Injector);
      return async (reason: string) => {
        let session: SessionService | null = null;
        try {
          session = injector.get(SessionService);
        } catch {
          session = null;
        }
        if (session) {
          try {
            await session.requireStepUp('aal2', MAX_AGE_SEC, reason);
          } catch {
            return false;
          }
        }
        if (mockApiActive(cfg)) mockStepUp();
        return true;
      };
    },
  },
);

/** Chu kỳ đọc lại lần kiểm tra toàn vẹn đang chạy (dự phòng cho SSE `/notifications/stream`). */
export const VERIFY_POLL_MS = new InjectionToken<number>('vt.fe-oversight.verify-poll-ms', {
  providedIn: 'root',
  factory: () => 2000,
});
