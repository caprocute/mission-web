package vn.viettel.okrkpi.config.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.infrastructure.persistence.JdbcRoleBindingRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Nạp cây tổ chức và đơn vị của vai trò vào {@code data.org.<tenant>} của OPA qua Data API
 * (CORE-00-03, {@code backend/opa/README.md} mục "Dữ liệu cây tổ chức"): {@code subtree} lấy từ bao
 * đóng {@code org_replica.unit_closure_v} (gộp hai cây), {@code managed_units} và {@code
 * role_units} lấy từ {@code config.role_binding} đang hiệu lực. Ghi lại toàn bộ khi khởi động và
 * theo lịch; cơ chế đẩy theo EVT-14, EVT-15 để giai đoạn sau (suy luận, wiki 14 mục QA-06).
 */
public class OpaOrgDataPublisher {

  private static final Logger log = LoggerFactory.getLogger(OpaOrgDataPublisher.class);

  static final String CLOSURE_SQL =
      "SELECT ancestor_id, descendant_id FROM org_replica.unit_closure_v WHERE tenant_id = :tenant"
          + " ORDER BY ancestor_id, depth, descendant_id";

  /** Vai trò đang hiệu lực: đã duyệt (vai trò nhạy cảm), chưa kết thúc, trong thời hạn. */
  static final String BINDING_SQL =
      "SELECT person_id, role_code, org_unit_id, scope FROM config.role_binding"
          + " WHERE tenant_id = :tenant AND end_kind IS NULL AND valid_from <= :now"
          + " AND :now < valid_to AND (approved_by IS NOT NULL OR role_code NOT IN "
          + JdbcRoleBindingRepository.SENSITIVE
          + ")"
          + " ORDER BY person_id, role_code, org_unit_id";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;
  private final List<UUID> fallbackTenants;
  private final URI base;
  private final ObjectMapper json;
  private final Clock clock;
  private final HttpClient http;
  private final Duration timeout = Duration.ofSeconds(5);

  public OpaOrgDataPublisher(
      JdbcClient jdbc,
      PlatformTransactionManager txManager,
      ObjectProvider<TenantDirectory> directory,
      String cell,
      List<UUID> fallbackTenants,
      URI opaBaseUrl,
      ObjectMapper json,
      Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = new TransactionTemplate(Objects.requireNonNull(txManager, "txManager"));
    this.tx.setReadOnly(true);
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
    this.fallbackTenants = fallbackTenants == null ? List.of() : List.copyOf(fallbackTenants);
    this.base = Objects.requireNonNull(opaBaseUrl, "opaBaseUrl");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    run();
  }

  @Scheduled(
      initialDelayString = "${mission.config.opa-org-data.interval:PT5M}",
      fixedDelayString = "${mission.config.opa-org-data.interval:PT5M}")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      // Dev một cell không bật định tuyến nên không có danh bạ tenant: dùng danh sách cấu hình.
      if (fallbackTenants.isEmpty()) {
        log.debug("Bỏ qua nạp cây tổ chức sang OPA vì chưa có danh bạ tenant");
      }
      fallbackTenants.stream().map(TenantId::of).forEach(this::publishQuietly);
      return;
    }
    tenants.snapshotByCode().values().stream()
        .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
        .map(TenantCellRoute::tenantId)
        .distinct()
        .map(TenantId::of)
        .forEach(this::publishQuietly);
  }

  private void publishQuietly(TenantId tenant) {
    try {
      publish(tenant);
    } catch (RuntimeException e) {
      log.warn("Nạp cây tổ chức sang OPA lỗi ở tenant {}", tenant, e);
    }
  }

  /** Đọc dữ liệu của {@code tenant} và ghi {@code PUT /v1/data/org/<tenant>}. */
  public void publish(TenantId tenant) {
    List<Map<String, Object>> holder = new ArrayList<>(1);
    TenantContext.run(
        tenant,
        () ->
            holder.add(
                tx.execute(
                    s -> {
                      List<UUID[]> closure =
                          jdbc.sql(CLOSURE_SQL)
                              .param("tenant", tenant.value())
                              .query(
                                  (rs, i) ->
                                      new UUID[] {
                                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)
                                      })
                              .list();
                      List<Binding> bindings =
                          jdbc.sql(BINDING_SQL)
                              .param("tenant", tenant.value())
                              .param("now", Timestamp.from(clock.instant()))
                              .query(
                                  (rs, i) ->
                                      new Binding(
                                          rs.getObject(1, UUID.class),
                                          rs.getString(2),
                                          rs.getObject(3, UUID.class),
                                          "DON_VI_VA_CAP_DUOI".equals(rs.getString(4))))
                              .list();
                      return document(closure, bindings);
                    })));
    put(tenant, holder.get(0));
  }

  /** Một dòng {@code config.role_binding}: vai trò {@code role} tại {@code unit} (kèm cấp dưới). */
  record Binding(UUID person, String role, UUID unit, boolean subunits) {}

  /**
   * Tài liệu {@code data.org.<tenant>}: {@code subtree[u]} gồm chính {@code u} và mọi cấp dưới;
   * {@code managed_units[người]} là các đơn vị người đó có vai trò; {@code role_units[người][vai
   * trò]} giữ {@code unit} (mọi đơn vị gắn vai trò) và {@code subtree} (đơn vị gắn vai trò kèm cấp
   * dưới) để chính sách xét phạm vi theo đơn vị của vai trò.
   */
  static Map<String, Object> document(List<UUID[]> closure, List<Binding> bindings) {
    Map<String, Set<String>> subtree = new LinkedHashMap<>();
    for (UUID[] row : closure) {
      subtree
          .computeIfAbsent(row[0].toString(), k -> new LinkedHashSet<>(List.of(k)))
          .add(row[1].toString());
    }
    Map<String, Set<String>> managed = new LinkedHashMap<>();
    Map<String, Map<String, Map<String, Set<String>>>> roleUnits = new LinkedHashMap<>();
    for (Binding b : bindings) {
      String unit = b.unit().toString();
      managed.computeIfAbsent(b.person().toString(), k -> new LinkedHashSet<>()).add(unit);
      Map<String, Set<String>> role =
          roleUnits
              .computeIfAbsent(b.person().toString(), k -> new LinkedHashMap<>())
              .computeIfAbsent(
                  b.role(),
                  k -> {
                    Map<String, Set<String>> m = new LinkedHashMap<>();
                    m.put("unit", new LinkedHashSet<>());
                    m.put("subtree", new LinkedHashSet<>());
                    return m;
                  });
      role.get("unit").add(unit);
      if (b.subunits()) {
        role.get("subtree").add(unit);
      }
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("subtree", lists(subtree));
    doc.put("managed_units", lists(managed));
    Map<String, Object> roles = new LinkedHashMap<>();
    roleUnits.forEach(
        (person, byRole) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          byRole.forEach((code, units) -> r.put(code, lists(units)));
          roles.put(person, r);
        });
    doc.put("role_units", roles);
    return doc;
  }

  private static Map<String, List<String>> lists(Map<String, Set<String>> m) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    m.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
    return out;
  }

  private void put(TenantId tenant, Map<String, Object> doc) {
    String body;
    try {
      body = json.writeValueAsString(doc);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    HttpRequest req =
        HttpRequest.newBuilder(base.resolve("/v1/data/org/" + tenant.value()))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
      if (res.statusCode() / 100 != 2) {
        throw new IllegalStateException("OPA trả mã " + res.statusCode() + " khi ghi cây tổ chức");
      }
    } catch (IOException e) {
      throw new IllegalStateException("Không kết nối được OPA", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bị ngắt khi ghi cây tổ chức sang OPA", e);
    }
  }
}
