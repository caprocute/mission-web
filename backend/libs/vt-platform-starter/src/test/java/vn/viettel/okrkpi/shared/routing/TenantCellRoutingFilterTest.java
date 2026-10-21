package vn.viettel.okrkpi.shared.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

class TenantCellRoutingFilterTest {

  private static final UUID TENANT_LOCAL = UUID.fromString("0195f3a0-0000-7000-8000-000000000010");
  private static final UUID TENANT_MOVED = UUID.fromString("0195f3a0-0000-7000-8000-000000000020");
  private static final UUID TENANT_MIGRATING =
      UUID.fromString("0195f3a0-0000-7000-8000-000000000030");

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private TenantDirectory directory;
  private TenantCellRoutingFilter filter;

  @BeforeEach
  void setUp() {
    directory =
        new TenantDirectory(
            new TenantRegistryClient() {
              @Override
              public Optional<TenantCellRoute> resolveByTenantId(UUID tenantId) {
                return Optional.empty();
              }

              @Override
              public Optional<TenantCellRoute> resolveByCode(String tenantCode) {
                return Optional.empty();
              }
            });
    Instant expiry = Instant.now().plusSeconds(600);
    directory.put(
        new TenantCellRoute(
            TENANT_LOCAL,
            "hcm_a",
            "cell-hcm-01",
            "https://cell-hcm-01.okrkpi.vn",
            TenancyModel.BRIDGE,
            "td_hcm_a",
            "DANG_VAN_HANH",
            List.of(),
            1L,
            expiry));
    directory.put(
        new TenantCellRoute(
            TENANT_MOVED,
            "hn_a",
            "cell-hn-01",
            "https://cell-hn-01.okrkpi.vn",
            TenancyModel.BRIDGE,
            "td_hn_a",
            "DANG_VAN_HANH",
            List.of(),
            2L,
            expiry));
    directory.put(
        new TenantCellRoute(
            TENANT_MIGRATING,
            "hcm_moving",
            "cell-hcm-01",
            "https://cell-hcm-02.okrkpi.vn",
            TenancyModel.BRIDGE,
            "td_hcm_moving",
            "DANG_CHUYEN_CELL",
            List.of(),
            3L,
            expiry));

    ProblemFactory problemFactory =
        new ProblemFactory(req -> "test-trace-id", ErrorCatalog.standard());
    filter = new TenantCellRoutingFilter(directory, "cell-hcm-01", problemFactory, objectMapper);
  }

  @Test
  void allowsRequestWhenTenantBelongsToLocalCell() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    TenantContext.run(
        TenantId.of(TENANT_LOCAL),
        () -> {
          try {
            filter.doFilter(
                request,
                response,
                (req, res) -> {
                  chainCalled.set(true);
                });
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    assertThat(chainCalled.get()).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void returns421MisdirectedRequestWhenTenantBelongsToDifferentCell() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    TenantContext.run(
        TenantId.of(TENANT_MOVED),
        () -> {
          try {
            filter.doFilter(request, response, chain);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    assertThat(response.getStatus()).isEqualTo(421);
    assertThat(response.getHeader(TenantCellRoutingFilter.TARGET_CELL_HEADER))
        .isEqualTo("cell-hn-01");
    JsonNode json = objectMapper.readTree(response.getContentAsByteArray());
    assertThat(json.get("status").asInt()).isEqualTo(421);
    assertThat(json.get("code").asText()).isEqualTo("REGISTRY-421-TENANT-MOVED");
    assertThat(json.get("expectedCell").asText()).isEqualTo("cell-hn-01");
    assertThat(json.get("actualCell").asText()).isEqualTo("cell-hcm-01");
    assertThat(json.get("targetCellUrl").asText()).isEqualTo("https://cell-hn-01.okrkpi.vn");
  }

  @Test
  void returns503ServiceUnavailableWhenTenantIsMigratingCell() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    TenantContext.run(
        TenantId.of(TENANT_MIGRATING),
        () -> {
          try {
            filter.doFilter(request, response, chain);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("30");
    JsonNode json = objectMapper.readTree(response.getContentAsByteArray());
    assertThat(json.get("status").asInt()).isEqualTo(503);
    assertThat(json.get("tenantCode").asText()).isEqualTo("hcm_moving");
    assertThat(json.get("tenantStatus").asText()).isEqualTo("DANG_CHUYEN_CELL");
  }
}
