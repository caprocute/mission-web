import { InjectionToken, Injector, inject } from '@angular/core';
import { RUNTIME_CONFIG, mockApiActive, mockStepUp } from '@vt/api-client';
import { SessionService } from '@vt/auth';

/** Cửa sổ MFA trước khi xác nhận nghiệm thu: 15 phút (BR-07-11, BR-00-02). */
const MAX_AGE_SEC = 900;

/**
 * Xác thực bước lên (RFC 9470) trước "Xác nhận nghiệm thu": gọi `requireStepUp` của `@vt/auth`, shell mở
 * hộp MFA khi phiên chưa đạt `aal2` trong 15 phút. Trả `true` khi đã xác thực, `false` khi người dùng huỷ.
 * Chạy riêng remote thì bỏ qua hộp thoại; với dữ liệu giả lập, báo máy chủ giả lập là phiên vừa MFA.
 */
export const REVIEW_STEP_UP = new InjectionToken<() => Promise<boolean>>('vt.fe-evidence.step-up', {
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
            'Xác nhận nghiệm thu là căn cứ tính điểm; đồng chí vui lòng xác thực nhiều lớp trước khi xác nhận.',
          );
        } catch {
          return false;
        }
      }
      if (mockApiActive(cfg)) mockStepUp();
      return true;
    };
  },
});
