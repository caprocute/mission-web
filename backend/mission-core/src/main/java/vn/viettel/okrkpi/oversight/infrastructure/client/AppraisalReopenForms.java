package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.api.ProfileFormsApi;
import vn.viettel.okrkpi.oversight.application.port.ReopenForms;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Biểu mẫu hồ sơ số qua {@code appraisal :: api} (MOD-06 hiện thực); không có bean thì rỗng. */
public class AppraisalReopenForms implements ReopenForms {

  private final ProfileFormsApi forms;

  public AppraisalReopenForms(ProfileFormsApi forms) {
    this.forms = forms;
  }

  @Override
  public Optional<FormRef> find(TenantId tenant, UUID formInstanceId) {
    if (forms == null) {
      return Optional.empty();
    }
    return forms.form(tenant, formInstanceId).map(f -> new FormRef(f.profileId(), f.formCode()));
  }
}
