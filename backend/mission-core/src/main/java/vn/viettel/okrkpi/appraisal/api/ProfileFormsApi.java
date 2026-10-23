package vn.viettel.okrkpi.appraisal.api;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ đánh giá và mã biểu mẫu của một bản biểu mẫu hồ sơ số ({@code dossier.form_instance}).
 * MOD-06 hiện thực (đảo phụ thuộc, vì MOD-07 không được phụ thuộc {@code dossier}) để MOD-07 gắn
 * EVT-17, EVT-18 của Mẫu 2, Mẫu 3 sinh lại với hồ sơ trong phạm vi hậu kiểm (CORE-07-09).
 */
public interface ProfileFormsApi {

  record ProfileForm(UUID profileId, String formCode) {}

  Optional<ProfileForm> form(TenantId tenant, UUID formInstanceId);
}
