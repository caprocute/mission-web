package vn.viettel.okrkpi.shared.infrastructure.esign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Client SVC-10: thân {@code EsignRequestCreate}, header chuyển tiếp, ánh xạ lỗi Problem. */
class EsignRequestClientTest {

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID source = UUID.randomUUID();

  @Test
  void moYeuCauKyVaChuyenTiepHeader() {
    RestClient.Builder b = RestClient.builder().baseUrl("http://esign");
    MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
    UUID id = UUID.randomUUID();
    server
        .expect(requestTo("http://esign/api/v1/esign/requests"))
        .andExpect(header("Idempotency-Key", "k-1"))
        .andExpect(header("Authorization", "Bearer t"))
        .andExpect(header(EsignRequestClient.TENANT_HEADER, tenant.value().toString()))
        .andExpect(jsonPath("$.purpose").value("TU_DANH_GIA"))
        .andExpect(jsonPath("$.sourceRefId").value(source.toString()))
        .andExpect(jsonPath("$.provider").value("MYSIGN"))
        .andRespond(withSuccess("{\"id\":\"" + id + "\"}", MediaType.APPLICATION_JSON));
    EsignRequestClient client = new EsignRequestClient(b.build(), new ObjectMapper());
    assertThat(
            client.create(tenant, "TU_DANH_GIA", "appraisal", source, "MYSIGN", "k-1", "Bearer t"))
        .isEqualTo(id);
    server.verify();
  }

  @Test
  void anhXaLoi() {
    RestClient.Builder b = RestClient.builder().baseUrl("http://esign");
    MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
    server
        .expect(requestTo("http://esign/api/v1/esign/requests"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"code\":\"ESIGN-403-NOT-SIGNER\",\"detail\":\"Không phải người ký\"}"));
    server
        .expect(requestTo("http://esign/api/v1/esign/requests"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    server
        .expect(requestTo("http://esign/api/v1/esign/requests"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("lỗi"));
    server
        .expect(requestTo("http://esign/api/v1/esign/requests"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    EsignRequestClient client = new EsignRequestClient(b.build(), new ObjectMapper());
    assertThatThrownBy(
            () -> client.create(tenant, "TU_DANH_GIA", "appraisal", source, null, "k", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("ESIGN-403-NOT-SIGNER"));
    assertThatThrownBy(
            () -> client.create(tenant, "TU_DANH_GIA", "appraisal", source, null, "k", null))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () -> client.create(tenant, "TU_DANH_GIA", "appraisal", source, null, "k", null))
        .isInstanceOf(RestClientResponseException.class);
    assertThatThrownBy(
            () -> client.create(tenant, "TU_DANH_GIA", "appraisal", source, null, "k", null))
        .isInstanceOf(IllegalStateException.class);
  }
}
