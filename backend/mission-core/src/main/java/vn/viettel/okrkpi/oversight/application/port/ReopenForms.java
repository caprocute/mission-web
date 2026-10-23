package vn.viettel.okrkpi.oversight.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ và mã biểu mẫu của một bản biểu mẫu hồ sơ số ({@code dossier.form_instance}), để gắn EVT-17
 * và EVT-18 với hồ sơ đánh giá; MOD-06 cung cấp qua {@code appraisal :: api}.
 */
@FunctionalInterface
public interface ReopenForms {

  record FormRef(UUID profileId, String formCode) {}

  Optional<FormRef> find(TenantId tenant, UUID formInstanceId);
}
