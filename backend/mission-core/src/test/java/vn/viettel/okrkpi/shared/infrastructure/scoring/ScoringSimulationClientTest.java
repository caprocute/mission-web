package vn.viettel.okrkpi.shared.infrastructure.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

class ScoringSimulationClientTest {

  static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  static final TenantId T = TenantId.of(UUID.randomUUID());
  static final UUID PROFILE = UUID.randomUUID();
  static final UUID TASK = UUID.randomUUID();
  static final List<ScoringSimulationClient.Adjustment> ADJ =
      List.of(
          new ScoringSimulationClient.Adjustment(
              "C", TASK, new BigDecimal("0.85"), BigDecimal.ONE, "Miễn trừ"),
          new ScoringSimulationClient.Adjustment("A", null, null, new BigDecimal("90"), null));

  @Test
  void guiDieuChinhChuyenTiepNguoiGoiVaDocKetQua() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://scoring");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://scoring/api/v1/appraisals/profiles/" + PROFILE + "/simulate"))
        .andExpect(header("Authorization", "Bearer t"))
        .andExpect(header(ScoringSimulationClient.TENANT_HEADER, T.value().toString()))
        .andExpect(r -> assertThat(r.getHeaders().getFirst("Idempotency-Key")).isNotBlank())
        .andExpect(
            r ->
                assertThat(
                        JSON.readTree(
                            ((org.springframework.mock.http.client.MockClientHttpRequest) r)
                                .getBodyAsString()))
                    .isEqualTo(
                        JSON.readTree(
                            "{\"adjustments\":[{\"component\":\"C\",\"taskId\":\""
                                + TASK
                                + "\",\"oldValue\":0.85,\"newValue\":1,\"reason\":\"Miễn trừ\"},"
                                + "{\"component\":\"A\",\"newValue\":90}]}")))
        .andRespond(
            withSuccess(
                "{\"policyVersion\":\"KHOI_DANG v2.0\",\"before\":{\"totalScore\":91.2,"
                    + "\"ratingCode\":\"HTTNV\"},\"after\":{\"totalScore\":92.1}}",
                MediaType.APPLICATION_JSON));
    ScoringSimulationClient.Result r =
        new ScoringSimulationClient(builder.build()).simulate(T, PROFILE, ADJ, "Bearer t");
    assertThat(r.policyVersion()).isEqualTo("KHOI_DANG v2.0");
    assertThat(r.before())
        .isEqualTo(new ScoringSimulationClient.Outcome(new BigDecimal("91.2"), "HTTNV"));
    assertThat(r.after())
        .isEqualTo(new ScoringSimulationClient.Outcome(new BigDecimal("92.1"), null));
    server.verify();
  }

  @Test
  void khongCoKetQuaGocTraNullVaKhongGuiAuthorizationRong() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://scoring");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(headerDoesNotExist("Authorization")).andRespond(withStatus(HttpStatus.NOT_FOUND));
    assertThat(new ScoringSimulationClient(builder.build()).simulate(T, PROFILE, ADJ, null))
        .isNull();
  }

  @Test
  void tuChoiLaLoiNgoaiPhamViConLoiKhacLaPhuThuocKhongSanSang() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://scoring");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo(org.hamcrest.Matchers.any(String.class)))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));
    server
        .expect(requestTo(org.hamcrest.Matchers.any(String.class)))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    server.expect(requestTo(org.hamcrest.Matchers.any(String.class))).andRespond(withServerError());
    server
        .expect(requestTo(org.hamcrest.Matchers.any(String.class)))
        .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
    ScoringSimulationClient client = new ScoringSimulationClient(builder.build());
    assertThatThrownBy(() -> client.simulate(T, PROFILE, ADJ, "Bearer t"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> client.simulate(T, PROFILE, ADJ, "Bearer t"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> client.simulate(T, PROFILE, ADJ, "Bearer t"))
        .isInstanceOf(DependencyUnavailableException.class);
    assertThatThrownBy(() -> client.simulate(T, PROFILE, ADJ, "Bearer t"))
        .isInstanceOf(DependencyUnavailableException.class);
    assertThat(new ScoringSimulationClient("http://localhost:1", Duration.ofMillis(100)))
        .isNotNull();
  }
}
