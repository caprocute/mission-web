package vn.viettel.okrkpi.okr.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Người được giao check-in thay chủ sở hữu KR (QT-03 "Chủ sở hữu KR vắng mặt dài ngày"). CORE-01-12
 * cắm bean hiện thực; chưa có bean thì chỉ chủ sở hữu KR được check-in.
 */
public interface OkrCheckinDelegatePort {

  boolean isDelegate(TenantId tenant, UUID keyResultId, UUID personId);
}
