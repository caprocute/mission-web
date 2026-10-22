package vn.viettel.okrkpi.config.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Người gọi lệnh: mã người dùng, mức xác thực ({@code acr}) và thời điểm xác thực ({@code
 * auth_time}, giây epoch) để kiểm MFA bước lên trong tầng ứng dụng (BR-08-02).
 */
public record Actor(UUID userId, String acr, Long authTime) {
  public Actor {
    Objects.requireNonNull(userId, "userId");
  }
}
