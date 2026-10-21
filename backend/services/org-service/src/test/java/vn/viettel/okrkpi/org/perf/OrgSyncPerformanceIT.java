package vn.viettel.okrkpi.org.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;
import vn.viettel.okrkpi.org.application.port.IntegrationGatewayPort;
import vn.viettel.okrkpi.org.support.AesGcmTestFieldEncryptor;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * DV-04-07: nhận 10.000 bản ghi mới rồi 10.000 bản ghi đổi đơn vị chính qua cổng nội bộ, lô 1.000 bản ghi. Ngoại suy
 * tuyến tính sang 100.000 bản ghi phải dưới một giờ (tiêu chí của task); số đo ghi vào nhật ký kiểm thử.
 */
@AutoConfigureMockMvc(printOnlyOnFailure = false, print = MockMvcPrint.NONE)
@Import(OrgSyncPerformanceIT.Crypto.class)
class OrgSyncPerformanceIT extends IntegrationTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(OrgSyncPerformanceIT.class);
  private static final int RECORDS = 10_000;
  private static final int BATCH = 1_000;

  @TestConfiguration
  static class Crypto {
    @Bean
    @Primary
    FieldEncryptor testFieldEncryptor() {
      return new AesGcmTestFieldEncryptor();
    }
  }

  @Autowired
  MockMvc mvc;
  @Autowired
  ObjectMapper json;
  @MockitoBean
  IntegrationGatewayPort gateway;

  private final Fixtures fx = new Fixtures();

  @Test
  void ingest100kExtrapolatesUnderOneHour() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID group = fx.subjectGroup(tenant, "K3");
    UUID unitA = UUID.randomUUID();
    UUID unitB = UUID.randomUUID();
    fx.unit(tenant, unitA, "CUC-A", "Cục Công nghệ thông tin", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    fx.unit(tenant, unitB, "CUC-B", "Cục Pháp chế", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    UUID title = fx.jobTitle(tenant);
    String token = "Bearer " + TestJwt.token(tenant, UUID.randomUUID(), List.of("svc_integration_gateway"), "aal1",
        null);

    long created = run(tenant, token, unitA, title, "2025-01-01");
    long compared = run(tenant, token, unitB, title, "2026-06-01");
    Integer open = fx.admin().queryForObject("SELECT count(*) FROM org.sync_discrepancy WHERE tenant_id = ?",
        Integer.class, tenant);
    assertThat(open).isEqualTo(RECORDS);

    long worst = Math.max(created, compared);
    Duration projected = Duration.ofMillis(worst * 100_000 / RECORDS);
    LOG.info("org-sync perf records={} createMs={} compareMs={} projected100k={}s", RECORDS, created, compared,
        projected.toSeconds());
    assertThat(projected).isLessThan(Duration.ofHours(1));
  }

  private long run(UUID tenant, String token, UUID unit, UUID title, String from) throws Exception {
    UUID runId = UUID.randomUUID();
    long start = System.nanoTime();
    for (int b = 0; b < RECORDS / BATCH; b++) {
      List<Map<String, Object>> records = new ArrayList<>(BATCH);
      for (int i = b * BATCH; i < (b + 1) * BATCH; i++) {
        Map<String, Object> post = new LinkedHashMap<>();
        post.put("orgUnitId", unit);
        post.put("jobTitleId", title);
        post.put("kind", "CHINH");
        post.put("head", false);
        post.put("validFrom", from);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("externalId", "P-" + i);
        r.put("fullName", "Cán bộ " + i);
        r.put("nationalId", String.format("0010%08d", i));
        r.put("birthDate", "1980-01-01");
        r.put("employmentStatus", "DANG_CONG_TAC");
        r.put("partyMember", false);
        r.put("posts", List.of(post));
        records.add(r);
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("tenantId", tenant);
      body.put("connector", "CBCCVC");
      body.put("records", records);
      body.put("last", b == RECORDS / BATCH - 1);
      body.put("deadLetters", 0);
      String out = mvc.perform(post("/internal/v1/org/sync-runs/" + runId + "/batches")
              .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(body)))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      JsonNode result = json.readTree(out);
      assertThat(result.get("errors").asInt()).isZero();
    }
    return (System.nanoTime() - start) / 1_000_000;
  }
}
