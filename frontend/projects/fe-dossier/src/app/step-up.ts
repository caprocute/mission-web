import { InjectionToken, Injector, inject } from '@angular/core';
import { RUNTIME_CONFIG, mockStepUp } from '@vt/api-client';
import { SessionService } from '@vt/auth';

/** Cửa sổ MFA cho thao tác lập biên bản: 15 phút (TL-01 PL C, BR-00-02). */
const MAX_AGE_SEC = 900;

/**
 * Xác thực bước lên (step-up MFA, RFC 9470) trước khi thư ký lập biên bản hội nghị (SCR-1101, phiên AAL2):
 * gọi `requireStepUp` của `@vt/auth`, shell mở hộp MFA khi phiên chưa đạt `aal2` trong 15 phút. Trả `false`
 * khi người dùng huỷ. Chạy riêng remote thì bỏ qua hộp thoại; với dữ liệu giả lập, báo máy chủ giả lập là
 * phiên vừa MFA.
 */
export const DOSSIER_STEP_UP = new InjectionToken<(reason: string) => Promise<boolean>>(
  'vt.fe-dossier.step-up',
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
        if (cfg.useMockApi) mockStepUp();
        return true;
      };
    },
  },
);
