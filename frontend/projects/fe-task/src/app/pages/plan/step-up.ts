import { InjectionToken, inject } from '@angular/core';
import { RUNTIME_CONFIG, mockStepUp } from '@vt/api-client';

/**
 * Xác thực bước lên (step-up MFA, RFC 9470) trước thao tác ký số khi máy chủ trả 401
 * `insufficient_user_authentication` (BR-00-02, wiki/07). Trả `true` khi đã xác thực xong và có thể
 * gửi lại yêu cầu. Với dữ liệu giả lập, đánh dấu phiên vừa MFA; với Keycloak thật, `@vt/auth` cần
 * chuyển hướng đăng nhập lại với `acr_values=aal2` và `max_age` (chưa có, xem wiki/14), nên trả `false`
 * để màn hình hướng dẫn đăng nhập lại.
 */
export const PLAN_STEP_UP = new InjectionToken<() => Promise<boolean>>('vt.fe-task.step-up', {
  providedIn: 'root',
  factory: () => {
    const cfg = inject(RUNTIME_CONFIG);
    return async () => {
      if (!cfg.useMockApi) return false;
      mockStepUp();
      return true;
    };
  },
});
