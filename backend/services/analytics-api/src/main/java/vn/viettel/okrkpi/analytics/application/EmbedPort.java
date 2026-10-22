package vn.viettel.okrkpi.analytics.application;

import java.time.Duration;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.domain.Scope;

/** Cổng cấp guest token của Superset (SVC-15, ADR-14). */
public interface EmbedPort {

  /**
   * Guest token xem đúng một dashboard nhúng, điều kiện RLS khoá theo phạm vi {@code scope}, sống
   * {@code lifetime}.
   */
  String guestToken(Scope scope, UUID embeddedId, Duration lifetime);

  /** Địa chỉ trình duyệt mở khung nhúng của dashboard. */
  String embedUrl(UUID embeddedId);
}
