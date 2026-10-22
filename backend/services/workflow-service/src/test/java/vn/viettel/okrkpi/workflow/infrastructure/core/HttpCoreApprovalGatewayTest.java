package vn.viettel.okrkpi.workflow.infrastructure.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway.Outcome;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;

/** Ánh xạ lời gọi lõi theo hợp đồng {@code okr.yaml}, {@code tasks.yaml}. */
class HttpCoreApprovalGatewayTest {

  static final String BASE = "http://core.test/api/v1";
  static final UUID TENANT = UUID.randomUUID();
  static final UUID ID = UUID.randomUUID();
  static final UUID KEY = UUID.randomUUID();

  static CoreApprovalGateway.Request request(ApprovalTemplate t, Decision d, String comment) {
    return new CoreApprovalGateway.Request(
        TENANT,
        t,
        d,
        ID,
        7,
        KEY,
        UUID.randomUUID(),
        comment,
        3,
        "MYSIGN",
        UUID.randomUUID(),
        "CHU_QUAN");
  }

  @Test
  void duyetOkrGuiIdempotencyKeyVaIfMatch(@TempDir Path dir) throws IOException {
    Path token = dir.resolve("token");
    Files.writeString(token, "tok-123\n");
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    HttpCoreApprovalGateway gateway = new HttpCoreApprovalGateway(builder, BASE, token.toString());
    server
        .expect(requestTo(BASE + "/okr/objectives/" + ID + "/approve"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", KEY.toString()))
        .andExpect(header("If-Match", "\"7\""))
        .andExpect(header("Authorization", "Bearer tok-123"))
        .andExpect(jsonPath("$.comment").value("Đồng ý"))
        .andRespond(withSuccess());
    assertThat(gateway.apply(request(ApprovalTemplate.DUYET_OKR, Decision.DUYET, "Đồng ý")))
        .isEqualTo(Outcome.APPLIED);
    server.verify();
  }

  @Test
  void keHoachVaDeNghiTheoHopDong() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    HttpCoreApprovalGateway gateway = new HttpCoreApprovalGateway(builder, BASE, "");
    server
        .expect(requestTo(BASE + "/plans/" + ID + "/versions/3/approve"))
        .andExpect(jsonPath("$.signMethod").value("MYSIGN"))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withStatus(HttpStatus.ACCEPTED));
    server
        .expect(requestTo(BASE + "/plans/" + ID + "/versions/3/return"))
        .andExpect(jsonPath("$.comment").value("Sửa lại"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    CoreApprovalGateway.Request reject =
        request(ApprovalTemplate.DUYET_DE_NGHI, Decision.TRA_LAI, "Không đủ căn cứ");
    server
        .expect(
            requestTo(BASE + "/tasks/" + reject.taskId() + "/change-requests/" + ID + "/decision"))
        .andExpect(headerDoesNotExist("If-Match"))
        .andExpect(jsonPath("$.approve").value(false))
        .andExpect(jsonPath("$.causeDecided").value("CHU_QUAN"))
        .andExpect(jsonPath("$.note").value("Không đủ căn cứ"))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
    server
        .expect(requestTo(BASE + "/okr/objectives/" + ID + "/return"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertThat(gateway.apply(request(ApprovalTemplate.DUYET_KE_HOACH_QUY, Decision.DUYET, null)))
        .isEqualTo(Outcome.APPLIED);
    assertThat(
            gateway.apply(
                request(ApprovalTemplate.DUYET_KE_HOACH_QUY, Decision.TRA_LAI, "Sửa lại")))
        .isEqualTo(Outcome.RETRY);
    assertThat(gateway.apply(reject)).isEqualTo(Outcome.REJECTED);
    assertThat(gateway.apply(request(ApprovalTemplate.DUYET_OKR, Decision.TRA_LAI, "x")))
        .isEqualTo(Outcome.RETRY);
    server.verify();
  }

  @Test
  void loiMangThiThuLaiVaTepTokenThieuThiBoQua() {
    HttpCoreApprovalGateway gateway =
        new HttpCoreApprovalGateway(
            RestClient.builder(), "http://127.0.0.1:1/api/v1", "/khong/ton/tai");
    assertThat(gateway.apply(request(ApprovalTemplate.DUYET_OKR, Decision.DUYET, null)))
        .isEqualTo(Outcome.RETRY);
  }
}
