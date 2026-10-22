package vn.viettel.okrkpi.platform.tenanttest.search;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Kiểm thử tự kiểm của NEN-035 trên OpenSearch 2 thật, theo mô hình cell pool của TL-03: chỉ mục
 * chung {@code pool_task_v1} định tuyến theo {@code tenant_id}, mỗi tenant một alias có bộ lọc.
 * Kiểm thử âm: alias thiếu bộ lọc làm lớp cơ sở thất bại.
 */
class SampleOpenSearchTenantLeakIT extends AbstractOpenSearchTenantLeakTest {

  private static final String A = "01927a00-0000-7000-8000-00000000010a";
  private static final String B = "01927a00-0000-7000-8000-00000000010b";
  private static final Map<String, List<String>> DOCS =
      Map.of(A, List.of("a-1", "a-2", "a-3"), B, List.of("b-1", "b-2"));

  @SuppressWarnings("resource")
  private static final GenericContainer<?> OPENSEARCH =
      new GenericContainer<>(
              DockerImageName.parse("mirror.gcr.io/opensearchproject/opensearch:2.19.1"))
          .withEnv("discovery.type", "single-node")
          .withEnv("DISABLE_SECURITY_PLUGIN", "true")
          .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
          .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
          .withExposedPorts(9200)
          .waitingFor(
              Wait.forHttp("/_cluster/health?wait_for_status=yellow")
                  .forPort(9200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  private static URI endpoint;

  @BeforeAll
  static void seed() {
    OPENSEARCH.start();
    endpoint = URI.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
    OpenSearchTenantLeakProbe admin = new OpenSearchTenantLeakProbe(endpoint);
    admin.request(
        "PUT",
        "/pool_task_v1",
        """
        {"settings":{"number_of_shards":2,"number_of_replicas":0},
         "mappings":{"_routing":{"required":true},
           "properties":{"tenant_id":{"type":"keyword"},"title":{"type":"text"}}}}
        """);
    StringBuilder bulk = new StringBuilder();
    DOCS.forEach(
        (tenant, ids) ->
            ids.forEach(
                id ->
                    bulk.append("{\"index\":{\"_id\":\"")
                        .append(id)
                        .append("\",\"routing\":\"")
                        .append(tenant)
                        .append("\"}}\n{\"tenant_id\":\"")
                        .append(tenant)
                        .append("\",\"title\":\"Nhiệm vụ mẫu ")
                        .append(id)
                        .append("\"}\n")));
    admin.request("POST", "/pool_task_v1/_bulk?refresh=true", bulk.toString());
    for (String tenant : DOCS.keySet()) {
      admin.request(
          "POST",
          "/_aliases",
          """
          {"actions":[
            {"add":{"index":"pool_task_v1","alias":"%1$s","routing":"%2$s",
                    "filter":{"term":{"tenant_id":"%2$s"}}}},
            {"add":{"index":"pool_task_v1","alias":"%1$s_khong_loc"}}]}
          """
              .formatted(aliasFor(tenant), tenant));
    }
  }

  @AfterAll
  static void stop() {
    OPENSEARCH.stop();
  }

  private static String aliasFor(String tenant) {
    return "t_" + tenant.replace("-", "") + "_task";
  }

  @Override
  protected URI openSearchEndpoint() {
    return endpoint;
  }

  @Override
  protected String aliasOf(String tenant) {
    return aliasFor(tenant);
  }

  @Override
  protected String tenantA() {
    return A;
  }

  @Override
  protected String tenantB() {
    return B;
  }

  @Override
  protected List<String> documentIdsOf(String tenant) {
    return DOCS.get(tenant);
  }

  @Test
  void aliasThieuBoLocLamKiemThuThatBai() {
    assertThatThrownBy(
            () -> probe().assertAliasIsolated(aliasFor(A) + "_khong_loc", A, B, DOCS.get(B)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("RÒ RỈ TENANT")
        .hasMessageContaining("b-1");
  }
}
