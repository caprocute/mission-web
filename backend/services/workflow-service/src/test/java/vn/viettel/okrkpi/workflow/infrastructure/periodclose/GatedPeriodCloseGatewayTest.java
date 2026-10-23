package vn.viettel.okrkpi.workflow.infrastructure.periodclose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseRequest;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway;
import vn.viettel.okrkpi.workflow.infrastructure.periodclose.GatedPeriodCloseGateway.Mode;

/** DV-06-06: cổng bên tham gia chưa có API nội bộ. */
class GatedPeriodCloseGatewayTest {

  private final UUID period = UUID.randomUUID();

  @Test
  void congChuaNoiLamLenhThatBaiGiaLapCoiNhuXong() {
    GatedPeriodCloseGateway gated =
        new GatedPeriodCloseGateway(Map.of(PeriodCloseGateway.Participant.SVC_07, Mode.GIA_LAP));
    assertThat(gated.mode(PeriodCloseGateway.Participant.MOD_08)).isEqualTo(Mode.CHUA_NOI);
    PeriodCloseRequest request =
        new PeriodCloseRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            period,
            UUID.randomUUID(),
            List.of(UUID.randomUUID()),
            UUID.randomUUID());
    UUID job = UUID.randomUUID();
    gated.submit(PeriodCloseGateway.Command.TINH_DIEM, request, job);
    assertThat(gated.state(PeriodCloseGateway.Command.TINH_DIEM, request, job).status())
        .isEqualTo(PeriodCloseGateway.JobState.Status.HOAN_TAT);
    gated.undo(PeriodCloseGateway.Command.TINH_DIEM, request, "k");
    assertThatThrownBy(() -> gated.checkPreconditions(request))
        .hasMessageContaining("Chưa nối MOD_08")
        .hasMessageContaining("CORE-08-05");
    assertThatThrownBy(() -> gated.submit(PeriodCloseGateway.Command.KY_SO_LO, request, job))
        .hasMessageContaining("DV-10-06");
    assertThat(request.unitIds()).hasSize(1);
    assertThat(request.periodId()).isEqualTo(period);
  }
}
