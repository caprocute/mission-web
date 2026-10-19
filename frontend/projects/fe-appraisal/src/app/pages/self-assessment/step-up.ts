import { InjectionToken, Injector, inject } from '@angular/core';
import { RUNTIME_CONFIG, mockStepUp } from '@vt/api-client';
import { SessionService } from '@vt/auth';

/** Cửa sổ MFA trước khi ký số: 15 phút (BR-00-02). */
const MAX_AGE_SEC = 900;
const SELF_REASON = 'Nộp phiếu tự đánh giá là mốc ký số; vui lòng xác thực nhiều lớp trước khi ký.';

/**
 * Xác thực bước lên (step-up MFA, RFC 9470) trước "Ký số và nộp" của SCR-0901 và "Ký số và chuyển đề
 * xuất" của SCR-0902 (wiki/15a ràng buộc 14, AAL2); `reason` là câu hiện trong hộp MFA: gọi `requireStepUp` của `@vt/auth`, shell mở hộp MFA khi phiên chưa đạt `aal2` trong 15 phút.
 * Trả `false` khi người dùng huỷ. Chạy riêng remote (không có shell) thì bỏ qua hộp thoại; với dữ liệu
 * giả lập, báo cho máy chủ giả lập biết phiên vừa MFA. Cùng cách làm với `PLAN_STEP_UP` của fe-task.
 */
export const APPRAISAL_STEP_UP = new InjectionToken<(reason?: string) => Promise<boolean>>(
  'vt.fe-appraisal.step-up',
  {
    providedIn: 'root',
    factory: () => {
      const cfg = inject(RUNTIME_CONFIG);
      const injector = inject(Injector);
      return async (reason = SELF_REASON) => {
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
        if (cfg.useMockApi) mockStepUp();
        return true;
      };
    },
  },
);
