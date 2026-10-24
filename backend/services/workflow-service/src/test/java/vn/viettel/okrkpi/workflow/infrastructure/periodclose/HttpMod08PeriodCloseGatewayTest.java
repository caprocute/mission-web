package vn.viettel.okrkpi.workflow.infrastructure.periodclose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseRequest;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway.Command;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway.JobState;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway.Participant;
import vn.viettel.okrkpi.workflow.infrastructure.periodclose.GatedPeriodCloseGateway.Mode;

/** QA-06: MOD-08 là bên tham gia thật của saga chốt kỳ qua cổng nội bộ của mission-core. */
class HttpMod08PeriodCloseGatewayTest {

  private final UUID tenant = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();
  private final UUID saga = UUID.randomUUID();
  private final UUID order = UUID.randomUUID();
  private final PeriodCloseRequest request =
      new PeriodCloseRequest(saga, tenant, period, order, List.of(), UUID.randomUUID());
  private final String base = "http://core/internal/v1/config/periods/" + period + "/close";

  private final RestClient.Builder builder = RestClient.builder().baseUrl("http://core");
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final HttpMod08PeriodCloseGateway gateway =
      new HttpMod08PeriodCloseGateway(builder.build(), () -> "tok");

  @Test
  void goiTienDieuKienLenhTrangThaiVaBuTruKemTokenVaTenant() {
    UUID job = UUID.randomUUID();
    server
        .expect(requestTo(base + "/preconditions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer tok"))
        .andExpect(header("X-Tenant-Id", tenant.toString()))
        .andRespond(
            withSuccess(
                "{\"violations\":[\"Kỳ đánh giá không còn mở.\"],\"warnings\":[]}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(base + "/jobs/" + job))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(
            content()
                .json(
                    "{\"command\":\"KHOA_KY\",\"sagaId\":\""
                        + saga
                        + "\",\"lockOrderId\":\""
                        + order
                        + "\"}"))
        .andRespond(withStatus(HttpStatus.ACCEPTED));
    server
        .expect(requestTo(base + "/jobs/" + job + "?command=KHOA_KY"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"status\":\"HOAN_TAT\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(base + "/jobs/" + job + "?command=HOAN_TAT_KY"))
        .andRespond(withSuccess("{\"status\":\"CHUA_CO\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(base + "/undo"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("{\"command\":\"KHOA_KY\",\"undoKey\":\"k1\"}"))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    assertThat(gateway.checkPreconditions(request).violations())
        .containsExactly("Kỳ đánh giá không còn mở.");
    gateway.submit(Command.KHOA_KY, request, job);
    assertThat(gateway.state(Command.KHOA_KY, request, job).status())
        .isEqualTo(JobState.Status.HOAN_TAT);
    assertThat(gateway.state(Command.HOAN_TAT_KY, request, job).status())
        .isEqualTo(JobState.Status.CHUA_CO);
    gateway.undo(Command.KHOA_KY, request, "k1");
    server.verify();
  }

  @Test
  void loiCuaLoiNemRaDeSagaThuLaiLenhKhongThuocMod08BiTuChoi() {
    UUID job = UUID.randomUUID();
    server.expect(requestTo(base + "/jobs/" + job)).andRespond(withStatus(HttpStatus.CONFLICT));
    assertThatThrownBy(() -> gateway.submit(Command.HOAN_TAT_KY, request, job))
        .isInstanceOf(RestClientResponseException.class);
    assertThatThrownBy(() -> gateway.submit(Command.TINH_DIEM, request, job))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cheDoNoiChuyenLenhChoAdapterConLaiGiuNguyen() {
    GatedPeriodCloseGateway gated =
        new GatedPeriodCloseGateway(
            Map.of(Participant.MOD_08, Mode.NOI, Participant.SVC_07, Mode.GIA_LAP),
            Map.of(Participant.MOD_08, gateway));
    UUID job = UUID.randomUUID();
    server.expect(requestTo(base + "/jobs/" + job)).andRespond(withStatus(HttpStatus.ACCEPTED));
    gated.submit(Command.KHOA_KY, request, job);
    gated.submit(Command.TINH_DIEM, request, job);
    server.verify();
    // NOI mà thiếu adapter thì coi như chưa nối.
    GatedPeriodCloseGateway missing =
        new GatedPeriodCloseGateway(Map.of(Participant.MOD_08, Mode.NOI));
    assertThatThrownBy(() -> missing.submit(Command.KHOA_KY, request, job))
        .hasMessageContaining("Chưa nối MOD_08");
    assertThat(PeriodCloseGateway.Command.HOAN_TAT_KY.participant()).isEqualTo(Participant.MOD_08);
  }
}
