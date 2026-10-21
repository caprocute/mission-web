package vn.viettel.okrkpi.shared.platformtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.observability.ObservabilityAutoConfiguration;

/**
 * Nhật ký JSON ECS có {@code trace.id}, {@code tenant.id}, đã che JWT và số định danh; 503 kèm
 * {@code Retry-After} khi phụ thuộc không dùng được; kênh kiểm toán tách riêng; metric chung.
 */
@SpringBootTest(classes = PlatformTestApplication.class)
@AutoConfigureMockMvc
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityWebTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private AuditLogger audit;
  @Autowired private MeterRegistry meters;

  @Test
  void moiDongNhatKyTrongYeuCauCoTraceVaTenantVaDaCheDuLieu(CapturedOutput output)
      throws Exception {
    mvc.perform(get("/log-probe").header("traceparent", TRACEPARENT)).andReturn();

    JsonNode line = awaitLine(output, "cccd=");
    assertThat(line.get("trace.id").asText()).isEqualTo(TRACE_ID);
    assertThat(line.get("tenant.id").asText()).isEqualTo(PlatformTestApplication.TENANT.toString());
    assertThat(line.get("event.dataset").asText()).isEqualTo("mission.app");
    assertThat(line.get("log.level").asText()).isEqualTo("INFO");
    assertThat(line.get("@timestamp").asText()).isNotBlank();
    String message = line.get("message").asText();
    assertThat(message)
        .contains("[REDACTED:JWT]", "[REDACTED:ID]", "[REDACTED:EMAIL]")
        .doesNotContain("eyJhbGciOiJSUzI1NiJ9", "001203004567", "nguyen.van.a@example.vn");
    assertThat(output.getAll()).doesNotContain("001203004567", "eyJhbGciOiJSUzI1NiJ9.");
  }

  @Test
  void phuThuocKhongDungDuocTra503KemRetryAfter() throws Exception {
    MvcResult r = mvc.perform(get("/dependency-down")).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(503);
    assertThat(r.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("20");
    assertThat(MediaType.parseMediaType(r.getResponse().getContentType()))
        .matches(t -> t.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    JsonNode p = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    assertThat(p.get("status").asInt()).isEqualTo(503);
    assertThat(p.get("type").asText()).startsWith("https://okrkpi.vn/problems/");
    assertThat(p.toString()).doesNotContain("vneid", "CIRCUIT_OPEN");

    for (String path : List.of("/circuit-open", "/bulkhead-full")) {
      MvcResult x = mvc.perform(get(path)).andReturn();
      assertThat(x.getResponse().getStatus()).as(path).isEqualTo(503);
      assertThat(x.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).as(path).isEqualTo("1");
    }
  }

  @Test
  void suKienKiemToanDiKenhRieng(CapturedOutput output) throws Exception {
    audit.record(
        AuditEvent.builder(
                AuditEventType.SENSITIVE_DATA_ACCESS,
                ActorId.of(Ids.newUuidV7()),
                "VIEW_APPRAISAL_SCORE")
            .tenant(PlatformTestApplication.TENANT)
            .object("appraisal", "0192f5a0-7b1c-7cde-8f00-00000000beef")
            .classification(Classification.C2)
            .attribute("ghiChu", "liên hệ 0912345678")
            .build());

    JsonNode line = awaitLine(output, "VIEW_APPRAISAL_SCORE");
    assertThat(line.get("event.dataset").asText()).isEqualTo("mission.audit");
    assertThat(line.get("log.logger").asText()).isEqualTo("OKRKPI_AUDIT");
    JsonNode event = json.readTree(line.get("message").asText());
    assertThat(event.get("topic").asText()).isEqualTo("sys.audit.v1");
    assertThat(event.get("classification").asText()).isEqualTo("C2");
    assertThat(event.get("attributes").get("ghiChu").asText()).contains("[REDACTED:PHONE]");
    assertThat(Ids.isUuidV7(java.util.UUID.fromString(event.get("eventId").asText()))).isTrue();
  }

  @Test
  void metricChungVaSoDongBiBo() {
    assertThat(meters.find(ObservabilityAutoConfiguration.DISCARDED_METRIC).functionCounter())
        .isNotNull();
    assertThat(
            meters
                .find(ObservabilityAutoConfiguration.DISCARDED_METRIC)
                .tag("service", "unknown")
                .tag("cell", "unknown")
                .functionCounter())
        .isNotNull();
  }

  private JsonNode awaitLine(CapturedOutput output, String marker) {
    List<JsonNode> found = new ArrayList<>();
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              for (String raw : output.getOut().split("\\R")) {
                if (raw.startsWith("{") && raw.contains(marker)) {
                  found.add(json.readTree(raw));
                  return true;
                }
              }
              return false;
            });
    return found.get(0);
  }
}
