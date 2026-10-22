package vn.viettel.okrkpi.oversight.infrastructure.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.oversight.application.alert.AnomalyAlertIntake;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.analytics.v1.DauHieuBatThuong;

/** CORE-07-10: thân EVT-20 sai hợp đồng chuyển thư chết, không thử lại. */
class AnomalyDetectedListenerTest {

  private final AnomalyDetectedListener listener =
      new AnomalyDetectedListener(
          new AnomalyAlertIntake(
              Mockito.mock(AnomalyAlertRepository.class),
              Mockito.mock(PeriodApi.class),
              null,
              null,
              java.time.Clock.systemUTC()),
          new ObjectMapper());

  @Test
  void evidenceChainMustBeJsonArray() {
    assertThatThrownBy(() -> listener.on(event("CAO", "khong-phai-json"), envelope()))
        .isInstanceOf(PermanentInboxException.class);
    assertThatThrownBy(() -> listener.on(event("CAO", "{\"a\":1}"), envelope()))
        .isInstanceOf(PermanentInboxException.class);
  }

  @Test
  void unknownCodesArePermanent() {
    assertThatThrownBy(() -> listener.on(event("HIGH", "[]"), envelope()))
        .isInstanceOf(PermanentInboxException.class)
        .hasMessageStartingWith("EVT-20");
  }

  private static DauHieuBatThuong event(String severity, String chain) {
    return DauHieuBatThuong.newBuilder()
        .setAlertKey("k")
        .setSignalCode("CHE_NHO")
        .setSeverity(severity)
        .setSubjectType("CA_NHAN")
        .setSubjectId(UUID.randomUUID())
        .setEvidenceChain(chain)
        .setModelVersion("2026.10.3")
        .build();
  }

  private static InboxEnvelope<DauHieuBatThuong> envelope() {
    DauHieuBatThuong payload = event("CAO", "[]");
    return new InboxEnvelope<>(
        UUID.randomUUID(),
        "analytics.anomaly.detected.v1",
        "k",
        TenantId.of(UUID.randomUUID()),
        null,
        0L,
        null,
        Classification.C1,
        null,
        null,
        null,
        Instant.now(),
        0,
        0L,
        payload);
  }
}
