package vn.viettel.okrkpi.shared.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import vn.vtokr.registry.v1.TenantChanged;

class TenantDirectoryTest {

  private static final UUID TENANT_A_ID = UUID.fromString("0195f3a0-0000-7000-8000-000000000101");
  private static final UUID TENANT_B_ID = UUID.fromString("0195f3a0-0000-7000-8000-000000000202");

  @Test
  void tenancyModelParsesAndInfersDefaultDatabaseName() {
    assertThat(TenancyModel.fromString(null)).isEqualTo(TenancyModel.POOL);
    assertThat(TenancyModel.fromString("  ")).isEqualTo(TenancyModel.POOL);
    assertThat(TenancyModel.fromString("pool")).isEqualTo(TenancyModel.POOL);
    assertThat(TenancyModel.fromString("BRIDGE")).isEqualTo(TenancyModel.BRIDGE);
    assertThat(TenancyModel.fromString("silo")).isEqualTo(TenancyModel.SILO);
    assertThat(TenancyModel.fromString("AIRGAP")).isEqualTo(TenancyModel.AIRGAP);
    assertThatThrownBy(() -> TenancyModel.fromString("UNKNOWN"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(TenancyModel.POOL.defaultDatabaseName("any")).isEqualTo("td_pool");
    assertThat(TenancyModel.BRIDGE.defaultDatabaseName("HCM-A")).isEqualTo("td_hcm_a");
    assertThat(TenancyModel.SILO.defaultDatabaseName("Bo_X")).isEqualTo("td_bo_x");
    assertThatThrownBy(() -> TenancyModel.BRIDGE.defaultDatabaseName(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cachesRoutesAndRefreshesWhenExpiredOrOnKafkaEvent() {
    AtomicReference<Instant> currentInstant =
        new AtomicReference<>(Instant.parse("2026-04-20T00:00:00Z"));
    Clock mutableClock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return currentInstant.get();
          }
        };

    AtomicInteger resolveCalls = new AtomicInteger();
    TenantRegistryClient stubClient =
        new TenantRegistryClient() {
          @Override
          public Optional<TenantCellRoute> resolveByTenantId(UUID tenantId) {
            resolveCalls.incrementAndGet();
            if (TENANT_A_ID.equals(tenantId)) {
              return Optional.of(
                  new TenantCellRoute(
                      TENANT_A_ID,
                      "hcm_a",
                      "cell-hcm-01",
                      "https://cell-hcm-01.internal",
                      TenancyModel.POOL,
                      null,
                      "DANG_VAN_HANH",
                      List.of("hcm-a.okrkpi.vn"),
                      1L,
                      mutableClock.instant().plusSeconds(60)));
            }
            return Optional.empty();
          }

          @Override
          public Optional<TenantCellRoute> resolveByCode(String tenantCode) {
            resolveCalls.incrementAndGet();
            if ("hcm_b".equals(tenantCode)) {
              return Optional.of(
                  new TenantCellRoute(
                      TENANT_B_ID,
                      "hcm_b",
                      "cell-hcm-01",
                      null,
                      TenancyModel.BRIDGE,
                      "td_hcm_b",
                      "DANG_VAN_HANH",
                      List.of(),
                      1L,
                      mutableClock.instant().plusSeconds(60)));
            }
            return Optional.empty();
          }
        };

    TenantDirectory directory =
        new TenantDirectory(stubClient, mutableClock, Duration.ofMinutes(5));

    Optional<TenantCellRoute> first = directory.findByTenantId(TENANT_A_ID);
    assertThat(first).isPresent();
    assertThat(first.get().databaseName()).isEqualTo("td_pool");
    assertThat(resolveCalls.get()).isEqualTo(1);

    // Second lookup within TTL hits memory cache without calling client
    Optional<TenantCellRoute> second = directory.resolve(TENANT_A_ID, "hcm_a");
    assertThat(second).isPresent();
    assertThat(resolveCalls.get()).isEqualTo(1);

    // Receive Kafka TenantChanged event upgrading hcm_a from POOL to BRIDGE (version 2)
    TenantChanged upgradeEvent =
        TenantChanged.newBuilder()
            .setTenantCode("hcm_a")
            .setStatus("DANG_VAN_HANH")
            .setTenancyModel("BRIDGE")
            .setCellCode("cell-hcm-01")
            .setDatabaseName("td_hcm_a")
            .setDomains(List.of("hcm-a.okrkpi.vn"))
            .setFlows(List.of("FLOW_OKR"))
            .setSourceVersion(2L)
            .build();
    directory.onTenantChanged(upgradeEvent);

    TenantCellRoute upgraded = directory.findByTenantId(TENANT_A_ID).orElseThrow();
    assertThat(upgraded.tenancyModel()).isEqualTo(TenancyModel.BRIDGE);
    assertThat(upgraded.databaseName()).isEqualTo("td_hcm_a");
    assertThat(upgraded.sourceVersion()).isEqualTo(2L);
    assertThat(upgraded.tenantId()).isEqualTo(TENANT_A_ID);

    // Stale event with sourceVersion 1 must be ignored
    TenantChanged staleEvent =
        TenantChanged.newBuilder()
            .setTenantCode("hcm_a")
            .setStatus("DANG_VAN_HANH")
            .setTenancyModel("POOL")
            .setCellCode("cell-hcm-01")
            .setDatabaseName("td_pool")
            .setDomains(List.of())
            .setFlows(List.of())
            .setSourceVersion(1L)
            .build();
    directory.onTenantChanged(staleEvent);
    assertThat(directory.findByCode("hcm_a").orElseThrow().databaseName()).isEqualTo("td_hcm_a");

    // Lookup by code for hcm_b
    assertThat(directory.findByCode("HCM_B")).isPresent();
    assertThat(directory.snapshotByCode()).containsKeys("hcm_a", "hcm_b");

    // Evict removes from both maps
    directory.evict(TENANT_B_ID, "hcm_b");
    assertThat(directory.snapshotByCode()).doesNotContainKey("hcm_b");
  }

  @Test
  void inMemoryLookupLatencyIsUnderTenMicroseconds() {
    TenantDirectory directory =
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

    int tenantCount = 1_000;
    UUID[] tenantIds = new UUID[tenantCount];
    for (int i = 0; i < tenantCount; i++) {
      UUID id = new UUID(0x0195f3a000007000L, 0x8000000000000000L + i);
      tenantIds[i] = id;
      String code = "don_vi_" + i;
      directory.put(
          new TenantCellRoute(
              id,
              code,
              "cell-hcm-01",
              null,
              (i % 10 == 0) ? TenancyModel.BRIDGE : TenancyModel.POOL,
              null,
              "DANG_VAN_HANH",
              List.of(),
              1L,
              Instant.now().plusSeconds(3600)));
    }

    // Warm up JIT
    for (int i = 0; i < 10_000; i++) {
      directory.resolve(tenantIds[i % tenantCount], null);
    }

    int iterations = 100_000;
    long startNanos = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      Optional<TenantCellRoute> r = directory.resolve(tenantIds[i % tenantCount], null);
      if (r.isEmpty()) {
        throw new AssertionError("Expected route to be present");
      }
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    double avgMicros = (elapsedNanos / 1000.0) / iterations;
    // Requirement NEN-036: < 10 µs per request
    assertThat(avgMicros)
        .as("Average in-memory tenant routing lookup latency (µs)")
        .isLessThan(10.0);
  }

  @Test
  void httpTenantRegistryClientResolvesOverHttp() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/registry/resolve",
        exchange -> {
          String query = exchange.getRequestURI().getQuery();
          if (query != null && query.contains("code=not_found")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
          }
          if (query != null && query.contains("code=server_error")) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
          }
          String body =
              """
              {
                "tenantId": "0195f3a0-0000-7000-8000-000000000101",
                "tenantCode": "hcm_a",
                "tenantName": "UBND Quan A",
                "status": "DANG_VAN_HANH",
                "tenancyModel": "BRIDGE",
                "cell": {
                  "id": "0195f3a0-0000-7000-8000-000000000999",
                  "code": "cell-hcm-01",
                  "apiBaseUrl": "https://cell-hcm-01.okrkpi.vn"
                },
                "domains": ["hcm-a.okrkpi.vn"],
                "cacheTtlSeconds": 120,
                "sourceVersion": 5
              }
              """;
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });
    server.start();
    try {
      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
      HttpTenantRegistryClient client =
          new HttpTenantRegistryClient(baseUrl, Duration.ofSeconds(2), new ObjectMapper());

      Optional<TenantCellRoute> byId = client.resolveByTenantId(TENANT_A_ID);
      assertThat(byId).isPresent();
      assertThat(byId.get().tenantCode()).isEqualTo("hcm_a");
      assertThat(byId.get().cellCode()).isEqualTo("cell-hcm-01");
      assertThat(byId.get().tenancyModel()).isEqualTo(TenancyModel.BRIDGE);
      assertThat(byId.get().databaseName()).isEqualTo("td_hcm_a");
      assertThat(byId.get().sourceVersion()).isEqualTo(5L);

      assertThat(client.resolveByCode("hcm_a")).isPresent();
      assertThat(client.resolveByDomain("hcm-a.okrkpi.vn")).isPresent();
      assertThat(client.resolveByCode("not_found")).isEmpty();
      assertThat(client.resolveByCode("server_error")).isEmpty();
    } finally {
      server.stop(0);
    }
  }
}
