package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 cho vòng duyệt UI Pack, ghi outbox trong giao dịch của bên gọi. */
public interface UiPackNotices {

  /** Phiên bản đạt kiểm tra tự động, chờ người duyệt. */
  void reviewRequested(TenantId tenantId, String packName, UiPackVersion version, List<UUID> to);

  /** Người duyệt từ chối, gửi người soạn và người gửi duyệt. */
  void rejected(
      TenantId tenantId, String packName, UiPackVersion version, String comment, List<UUID> to);
}
