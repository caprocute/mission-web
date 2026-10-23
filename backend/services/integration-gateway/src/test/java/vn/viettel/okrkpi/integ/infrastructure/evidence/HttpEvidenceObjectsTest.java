package vn.viettel.okrkpi.integ.infrastructure.evidence;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.port.EvidenceObjects;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Máy khách cổng nhận tệp nội bộ của SVC-08 (CORE-03-04) với máy chủ giả. */
class HttpEvidenceObjectsTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final byte[] CONTENT = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

  private WireMockServer server;
  private HttpEvidenceObjects client;

  @BeforeEach
  void start() {
    server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
    client =
        new HttpEvidenceObjects(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            server.baseUrl() + "/",
            Duration.ofSeconds(5));
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  @Test
  void guiNoiDungKemKhoaVaDocKetQua() throws IOException {
    UUID owner = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    UUID object = UUID.randomUUID();
    server.stubFor(
        post(urlPathEqualTo("/internal/v1/ingested-objects"))
            .withQueryParam("tenantId", equalTo(TENANT.value().toString()))
            .withQueryParam("ownerRefId", equalTo(owner.toString()))
            .withQueryParam("requestedBy", equalTo(person.toString()))
            .withQueryParam("fileName", equalTo("47-BC đã ký.pdf"))
            .withHeader("Idempotency-Key", equalTo("k-1"))
            .withHeader("Content-Type", equalTo("application/pdf"))
            .withRequestBody(binaryEqualTo(CONTENT))
            .willReturn(
                okJson(
                    "{\"objectId\":\""
                        + object
                        + "\",\"objectVersion\":1,\"sha256\":\""
                        + "AB".repeat(32)
                        + "\",\"sizeBytes\":15,\"mediaType\":\"application/pdf\"}")));
    EvidenceObjects.Stored s =
        client.ingest(TENANT, "k-1", owner, person, "47-BC đã ký.pdf", "application/pdf", CONTENT);
    assertThat(s.objectId()).isEqualTo(object);
    assertThat(s.objectVersion()).isEqualTo(1);
    assertThat(s.sha256()).hasSize(32);
    assertThat(s.sizeBytes()).isEqualTo(15);
  }

  @Test
  void loiHoacThanSaiLaLoiVaoRa() {
    server.stubFor(
        post(urlPathEqualTo("/internal/v1/ingested-objects"))
            .willReturn(aResponse().withStatus(500)));
    assertThatThrownBy(
            () ->
                client.ingest(
                    TENANT,
                    "k",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    "application/pdf",
                    CONTENT))
        .isInstanceOf(IOException.class);
    server.stubFor(
        post(urlPathEqualTo("/internal/v1/ingested-objects"))
            .willReturn(okJson("{\"objectId\":\"x\"}")));
    assertThatThrownBy(
            () ->
                client.ingest(
                    TENANT,
                    "k",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    "application/pdf",
                    CONTENT))
        .isInstanceOf(IOException.class);
  }
}
