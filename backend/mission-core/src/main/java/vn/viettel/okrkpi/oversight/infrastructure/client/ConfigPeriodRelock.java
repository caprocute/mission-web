package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.config.api.PeriodReopenApi;
import vn.viettel.okrkpi.oversight.application.port.PeriodRelock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đề nghị khoá lại sớm qua {@code config :: api}. */
public class ConfigPeriodRelock implements PeriodRelock {

  private final PeriodReopenApi reopens;

  public ConfigPeriodRelock(PeriodReopenApi reopens) {
    this.reopens = Objects.requireNonNull(reopens, "reopens");
  }

  @Override
  public boolean finishEarly(TenantId tenant, UUID reopenRequestId, UUID actorId) {
    return reopens.finishEarly(tenant, reopenRequestId, actorId);
  }
}
