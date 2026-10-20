import { InjectionToken, Injector, inject } from '@angular/core';
import { RUNTIME_CONFIG, mockStepUp } from '@vt/api-client';
import { SessionService } from '@vt/auth';

/**
 * Xác thực bước lên (AAL2, RFC 9470) trước khi ký phê duyệt phát hành Policy Pack (SCR-1503, BR-15-09):
 * gọi `requireStepUp` của `@vt/auth`, shell mở hộp MFA khi phiên chưa đạt `aal2`. Trả `false` khi
 * người dùng huỷ. Chạy riêng remote thì bỏ qua hộp thoại; với dữ liệu giả lập, báo máy chủ giả lập là
 * phiên vừa MFA. Máy chủ vẫn kiểm `acr` và trả 401 nếu thiếu.
 */
export const STUDIO_STEP_UP = new InjectionToken<(reason: string) => Promise<boolean>>(
  'vt.fe-config-studio.step-up',
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
            await session.requireStepUp('aal2', 900, reason);
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
