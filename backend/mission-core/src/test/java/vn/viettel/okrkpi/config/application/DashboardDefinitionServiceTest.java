package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.application.port.DashboardDefinitionRepository;
import vn.viettel.okrkpi.config.domain.DashboardDefinition;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-17: quy tắc trường SCR-1602, chủ sở hữu, khoá lạc quan và kiểm toán chia sẻ. */
class DashboardDefinitionServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-16T02:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID owner = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final Repo repo = new Repo();
  private final List<String> audits = new ArrayList<>();
  private final DashboardDefinitionService service =
      new DashboardDefinitionService(
          repo,
          (t, a, action, type, id, agg, v) -> audits.add(action + ":" + v),
          JSON,
          Clock.fixed(NOW));

  private JsonNode body(String s) throws Exception {
    return JSON.readTree(s);
  }

  private static String widget(String id, String measure, String dims) {
    return """
        {"id":"%s","x":0,"y":1,"w":2,"h":2,"measure":"%s","dimensions":%s,"chartType":"BANG",
         "filters":[{"member":"don_vi.cap","operator":"equals","values":[1]}]}
        """
        .formatted(id, measure, dims);
  }

  private static String def(String title, String scope, String units, String... widgets) {
    return """
        {"title":"%s","layout":[%s],"shareScope":"%s","sharedUnitIds":%s}
        """
        .formatted(title, String.join(",", widgets), scope, units);
  }

  @Test
  void createDerivesMeasuresDimensionsAndDefaultsDrillDown() throws Exception {
    DashboardDefinition d =
        service.create(
            tenant,
            owner,
            body(
                def(
                    "  Bảng tỉnh  ",
                    "RIENG",
                    "[]",
                    widget("a", "okr.tien_do_o", "[\"don_vi.ten\",\"ky.quy\"]"),
                    widget("b", "okr.tien_do_o", "[\"don_vi.ten\"]"))));

    assertThat(d.title()).isEqualTo("Bảng tỉnh");
    assertThat(d.rowVersion()).isZero();
    assertThat(d.measures()).containsExactly("okr.tien_do_o");
    assertThat(d.dimensions()).containsExactly("don_vi.ten", "ky.quy");
    JsonNode layout = service.layout(d);
    assertThat(layout.get("widgets").get(0).get("drillDown").asBoolean()).isTrue();
    assertThat(layout.has("semanticModelVersion")).isFalse();
    assertThat(audits).isEmpty();
    assertThat(service.view(d).layout().get("widgets")).hasSize(2);
  }

  @Test
  void collectsEveryFieldErrorInOne422() throws Exception {
    String bad =
        """
        {"title":"","shareScope":"DON_VI","sharedUnitIds":["khong-phai-uuid"],
         "semanticModelVersion":5,
         "layout":[{"id":"a","x":-1,"y":0,"w":0,"h":1,"measure":"X","dimensions":"a",
                    "chartType":"TRON","filters":[{"member":"a.b","operator":"like","values":[]}],
                    "drillDown":"co"},
                   {"id":"a","x":0,"y":0,"w":1,"h":1,"measure":"a.b","chartType":"COT",
                    "dimensions":["Sai"],"filters":"x"},
                   "khong"]}
        """;
    assertThatThrownBy(() -> service.create(tenant, owner, body(bad)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors().stream().map(ProblemFieldError::field))
                    .contains(
                        "title",
                        "layout[0].x",
                        "layout[0].w",
                        "layout[0].measure",
                        "layout[0].dimensions",
                        "layout[0].chartType",
                        "layout[0].filters[0]",
                        "layout[0].drillDown",
                        "layout[1].id",
                        "layout[1].dimensions",
                        "layout[1].filters",
                        "layout[2]",
                        "sharedUnitIds",
                        "semanticModelVersion"));
    assertThatThrownBy(() -> service.create(tenant, owner, body("[]")))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void replaceChecksOwnerVersionAndAuditsShareChange() throws Exception {
    DashboardDefinition d =
        service.create(tenant, owner, body(def("Bảng", "TENANT", "[]", widget("a", "a.b", "[]"))));
    assertThat(audits).containsExactly("CHIA_SE_DASHBOARD:0");
    String shared = def("Bảng", "DON_VI", "[\"" + unit + "\"]", widget("a", "a.b", "[]"));

    assertThatThrownBy(() -> service.replace(tenant, other, d.id(), 0L, body(shared)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("CONFIG-403-NOT-OWNER");
    assertThatThrownBy(() -> service.replace(tenant, owner, d.id(), null, body(shared)))
        .isInstanceOf(PreconditionRequiredException.class);
    assertThatThrownBy(() -> service.replace(tenant, owner, d.id(), 3L, body(shared)))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.replace(tenant, owner, UUID.randomUUID(), 0L, body(shared)))
        .isInstanceOf(NoSuchElementException.class);

    DashboardDefinition next = service.replace(tenant, owner, d.id(), 0L, body(shared));
    assertThat(next.rowVersion()).isEqualTo(1);
    assertThat(audits).containsExactly("CHIA_SE_DASHBOARD:0", "CHIA_SE_DASHBOARD:1");
    service.replace(tenant, owner, d.id(), 1L, body(shared.replace("Bảng", "Bảng mới")));
    assertThat(audits).hasSize(2);

    assertThat(service.find(tenant, other, List.of(unit), d.id())).isPresent();
    assertThat(service.find(tenant, other, List.of(), d.id())).isEmpty();
    assertThatThrownBy(() -> service.get(tenant, other, List.of(), d.id()))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(service.get(tenant, owner, List.of(), d.id()).title()).isEqualTo("Bảng mới");
    assertThat(service.visible(tenant, other, List.of(unit), false, null, 10)).hasSize(1);
    assertThat(service.visible(tenant, other, List.of(unit), true, null, 10)).isEmpty();
  }

  @Test
  void duplicateTitleOfSameOwnerIs409() throws Exception {
    service.create(tenant, owner, body(def("Bảng", "RIENG", "[]", widget("a", "a.b", "[]"))));
    assertThatThrownBy(
            () ->
                service.create(
                    tenant, owner, body(def("BẢNG", "RIENG", "[]", widget("a", "a.b", "[]")))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("CONFIG-409-DUPLICATE-TITLE");
    assertThat(
            service.create(
                tenant, other, body(def("Bảng", "RIENG", "[]", widget("a", "a.b", "[]")))))
        .isNotNull();
  }

  @Test
  void domainRejectsUnitsThatDoNotMatchScope() {
    assertThatThrownBy(
            () ->
                new DashboardDefinition(
                    UUID.randomUUID(),
                    owner,
                    "x",
                    List.of(),
                    List.of(),
                    "{}",
                    DashboardDefinition.ShareScope.DON_VI,
                    List.of(),
                    NOW,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Kho trong bộ nhớ, một tenant. */
  static final class Repo implements DashboardDefinitionRepository {
    final Map<UUID, DashboardDefinition> rows = new LinkedHashMap<>();

    @Override
    public Optional<DashboardDefinition> find(TenantId tenantId, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<DashboardDefinition> visible(
        TenantId tenantId,
        UUID userId,
        List<UUID> viewerUnits,
        boolean ownOnly,
        UUID beforeId,
        int limit) {
      return rows.values().stream()
          .filter(d -> ownOnly ? d.ownerUserId().equals(userId) : d.visibleTo(userId, viewerUnits))
          .sorted(Comparator.comparing(DashboardDefinition::id).reversed())
          .limit(limit)
          .toList();
    }

    @Override
    public boolean titleTaken(TenantId tenantId, UUID ownerUserId, String title, UUID exceptId) {
      return rows.values().stream()
          .anyMatch(
              d ->
                  d.ownerUserId().equals(ownerUserId)
                      && d.title().equalsIgnoreCase(title)
                      && !d.id().equals(exceptId));
    }

    @Override
    public void lockOwner(TenantId tenantId, UUID ownerUserId) {}

    @Override
    public void insert(TenantId tenantId, DashboardDefinition definition, UUID actorId) {
      rows.put(definition.id(), definition);
    }

    @Override
    public boolean update(
        TenantId tenantId, DashboardDefinition next, int expectedVersion, UUID actorId) {
      DashboardDefinition cur = rows.get(next.id());
      if (cur == null || cur.rowVersion() != expectedVersion) {
        return false;
      }
      rows.put(next.id(), next);
      return true;
    }
  }
}
