package vn.viettel.okrkpi.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.viettel.okrkpi.workflow.infrastructure.TemplateReadinessIndicator;

/**
 * Kiểm thử tích hợp khởi động ứng dụng mẫu, xác minh cấu hình cổng quản trị 8081 (liveness,
 * readiness, prometheus) và graceful shutdown 25s (NEN-019 · TL-04 WI-20, WI-23).
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureObservability
@ActiveProfiles("test")
class ActuatorProbesTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private JdbcClient jdbcClient;

  @Autowired private Environment environment;

  @Test
  void cauHinhCongQuanTriVaGracefulShutdownTheoTl04() {
    YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
    yamlFactory.setResources(new ClassPathResource("application.yaml"));
    Properties appYaml = yamlFactory.getObject();
    assertThat(appYaml).isNotNull();
    assertThat(appYaml.getProperty("server.port")).isEqualTo("${SERVER_PORT:8080}");
    assertThat(appYaml.getProperty("management.server.port")).isEqualTo("${MANAGEMENT_PORT:8081}");
    assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
        .isEqualTo("25s");
  }

  @Test
  void livenessProbeChiKiemNoiTaiJvm() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.path("status").asText()).isEqualTo("UP");
    assertThat(body.path("components").has("livenessState")).isTrue();
    assertThat(body.path("components").has("db")).isFalse();
  }

  @Test
  void readinessProbeKiemPoolCsdlVaKafkaProducer() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.path("status").asText()).isEqualTo("UP");
    assertThat(body.path("components").path("db").path("status").asText()).isEqualTo("UP");
    assertThat(body.path("components").path("templateReadiness").path("status").asText())
        .isEqualTo("UP");
    assertThat(
            body.path("components")
                .path("templateReadiness")
                .path("details")
                .path("kafkaProducer")
                .asText())
        .isEqualTo("READY");

    TemplateReadinessIndicator notReadyIndicator =
        new TemplateReadinessIndicator(jdbcClient, false);
    assertThat(notReadyIndicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void prometheusEndpointXuatChiSoUngDung() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn();
    String appName = environment.getProperty("spring.application.name");
    assertThat(result.getResponse().getContentAsString())
        .contains("application=\"" + appName + "\"");
  }
}
