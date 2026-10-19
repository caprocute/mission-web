import { InjectionToken, Injector, inject } from '@angular/core';
import { RUNTIME_CONFIG, mockStepUp } from '@vt/api-client';
import { SessionService } from '@vt/auth';

/** Cửa sổ MFA trước khi ký số kế hoạch: 15 phút (BR-00-02, TL-02 SCR-0403). */
const MAX_AGE_SEC = 900;

/**
 * Xác thực bước lên (step-up MFA, RFC 9470) trước "Duyệt và ký số": gọi `requireStepUp` của `@vt/auth`
 * (WEB-006), shell mở hộp MFA khi phiên chưa đạt `aal2` trong 15 phút. Trả `true` khi đã xác thực,
 * `false` khi người dùng huỷ. Chạy riêng remote (không có shell, không cấu hình OIDC) thì bỏ qua bước
 * hộp thoại; với dữ liệu giả lập, báo cho máy chủ giả lập biết phiên vừa MFA.
 */
export const PLAN_STEP_UP = new InjectionToken<() => Promise<boolean>>('vt.fe-task.step-up', {
  providedIn: 'root',
  factory: () => {
    const cfg = inject(RUNTIME_CONFIG);
    const injector = inject(Injector);
    return async () => {
      let session: SessionService | null = null;
      try {
        session = injector.get(SessionService);
      } catch {
        session = null;
      }
      if (session) {
        try {
          await session.requireStepUp(
            'aal2',
            MAX_AGE_SEC,
            'Duyệt kế hoạch là mốc ký số pháp lý; vui lòng xác thực nhiều lớp (MFA) trước khi ký.',
          );
        } catch {
          return false;
        }
      }
      if (cfg.useMockApi) mockStepUp();
      return true;
    };
  },
});
