package vn.viettel.okrkpi.search.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.search.application.IndexFailure;
import vn.viettel.okrkpi.search.application.RebuildScope;
import vn.viettel.okrkpi.search.application.RebuildTarget;
import vn.viettel.okrkpi.search.application.SearchIndexPort;
import vn.viettel.okrkpi.search.application.TransientIndexException;
import vn.viettel.okrkpi.search.domain.FieldPolicy;
import vn.viettel.okrkpi.search.domain.IndexCommand;
import vn.viettel.okrkpi.search.domain.IndexNames;
import vn.viettel.okrkpi.search.domain.TenancyModel;

/**
 * Hiện thực {@link SearchIndexPort} trên OpenSearch 2.x.
 *
 * <p>Ghi trực tuyến luôn đi qua bí danh {@code t_<tenant>_task} với {@code require_alias=true}, nên
 * không bao giờ tự sinh chỉ mục lạ khi bí danh chưa có; ở pool, bí danh mang định tuyến và bộ lọc
 * theo tenant nên tài liệu tự rơi vào đúng shard. Ghi khi dựng lại đi thẳng vào chỉ mục mới với
 * định tuyến tường minh.
 */
public final class OpenSearchIndex implements SearchIndexPort {

  static final String TEMPLATE_NAME = "mission-search-task";
  private static final Logger LOG = LoggerFactory.getLogger(OpenSearchIndex.class);
  private static final Pattern TENANT_ALIAS = Pattern.compile("^t_([0-9a-f-]{36})_task$");

  private final OpenSearchHttp http;
  private final ObjectMapper json;
  private final SearchProperties props;
  private final String mergeScript;
  private final Set<String> known = ConcurrentHashMap.newKeySet();

  public OpenSearchIndex(OpenSearchHttp http, ObjectMapper json, SearchProperties props) {
    this.http = http;
    this.json = json;
    this.props = props;
    this.mergeScript = resource("opensearch/merge.painless");
  }

  /** Nạp (hoặc cập nhật) mẫu chỉ mục; chạy lúc khởi động, gọi lặp an toàn. */
  public void installTemplate() {
    ObjectNode template;
    try {
      template = (ObjectNode) json.readTree(resource("opensearch/index-template.json"));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Mẫu chỉ mục không hợp lệ", e);
    }
    ObjectNode settings = (ObjectNode) template.path("template").path("settings");
    ObjectNode index = (ObjectNode) settings.path("index");
    index.put("number_of_shards", props.shards());
    index.put("number_of_replicas", props.replicas());
    ArrayNode synonyms = json.createArrayNode();
    resource("opensearch/synonyms.txt")
        .lines()
        .map(String::strip)
        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
        .forEach(synonyms::add);
    ((ObjectNode) settings.path("analysis").path("filter").path("vi_synonyms"))
        .set("synonyms", synonyms);
    expectOk(http.send("PUT", "/_index_template/" + TEMPLATE_NAME, template), "nạp mẫu chỉ mục");
  }

  @Override
  public void ensureTenant(String tenantId, TenancyModel model) {
    String tenant = IndexNames.tenant(tenantId);
    if (known.contains(tenant)) {
      return;
    }
    String alias = IndexNames.tenantAlias(tenant);
    if (http.send("GET", "/_alias/" + alias, null).ok()) {
      known.add(tenant);
      return;
    }
    if (model.sharedIndex()) {
      String pool = poolIndex();
      Map<String, Object> add = poolAlias(pool, tenant);
      expectOk(
          http.send("POST", "/_aliases", Map.of("actions", List.of(Map.of("add", add)))),
          "tạo bí danh tenant");
    } else {
      String index = IndexNames.tenantIndex(tenant, 1);
      OpenSearchHttp.Response r =
          http.send("PUT", "/" + index, Map.of("aliases", Map.of(alias, Map.of())));
      if (!r.ok() && !alreadyExists(r)) {
        expectOk(r, "tạo chỉ mục tenant");
      }
    }
    LOG.info("Đã tạo bí danh tìm kiếm cho tenant {} theo mô hình {}", tenant, model);
    known.add(tenant);
  }

  /** Chỉ mục chung hiện hành của pool; tạo {@code pool_task_v1} khi cell chưa có. */
  private String poolIndex() {
    OpenSearchHttp.Response r = http.send("GET", "/_alias/" + IndexNames.POOL_ALIAS, null);
    if (r.ok()) {
      return r.body().fieldNames().next();
    }
    String first = IndexNames.poolIndex(1);
    OpenSearchHttp.Response c =
        http.send("PUT", "/" + first, Map.of("aliases", Map.of(IndexNames.POOL_ALIAS, Map.of())));
    if (!c.ok() && !alreadyExists(c)) {
      expectOk(c, "tạo chỉ mục chung");
    }
    return first;
  }

  private static Map<String, Object> poolAlias(String index, String tenant) {
    Map<String, Object> add = new LinkedHashMap<>();
    add.put("index", index);
    add.put("alias", IndexNames.tenantAlias(tenant));
    add.put("filter", Map.of("term", Map.of(FieldPolicy.TENANT_ID, tenant)));
    add.put("routing", tenant);
    return add;
  }

  @Override
  public List<IndexFailure> write(List<IndexCommand> commands) {
    return bulk(commands, c -> IndexNames.tenantAlias(c.tenantId()), false, "?require_alias=true");
  }

  @Override
  public List<IndexFailure> write(RebuildTarget target, List<IndexCommand> commands) {
    return bulk(commands, c -> target.newIndex(), target.scope().pool(), "");
  }

  private List<IndexFailure> bulk(
      List<IndexCommand> commands,
      java.util.function.Function<IndexCommand, String> index,
      boolean routing,
      String query) {
    StringBuilder body = new StringBuilder(commands.size() * 512);
    String now = Instant.now().toString();
    try {
      for (IndexCommand c : commands) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("_index", index.apply(c));
        meta.put("_id", c.docId());
        if (routing) {
          meta.put("routing", c.tenantId());
        }
        Map<String, Object> base = new LinkedHashMap<>();
        base.put(FieldPolicy.TENANT_ID, c.tenantId());
        base.put(FieldPolicy.DOC_TYPE, c.type().name());
        base.put(FieldPolicy.ENTITY_ID, c.entityId());
        base.put(FieldPolicy.UPDATED_AT, now);
        if (c.mode() == IndexCommand.Mode.REPLACE) {
          meta.put("version", c.version());
          meta.put("version_type", "external");
          Map<String, Object> doc = new LinkedHashMap<>(base);
          c.fields()
              .forEach(
                  (k, v) -> {
                    if (v != null) {
                      doc.put(k, v);
                    }
                  });
          line(body, Map.of("index", meta));
          line(body, doc);
        } else {
          meta.put("retry_on_conflict", 5);
          Map<String, Object> set = new HashMap<>();
          List<String> unset = new ArrayList<>();
          c.fields()
              .forEach(
                  (k, v) -> {
                    if (v == null) {
                      unset.add(k);
                    } else {
                      set.put(k, v);
                    }
                  });
          Map<String, Object> params =
              Map.of(
                  "group",
                  c.group(),
                  "version",
                  c.version(),
                  "base",
                  base,
                  "set",
                  set,
                  "unset",
                  unset);
          line(body, Map.of("update", meta));
          line(
              body,
              Map.of(
                  "script",
                  Map.of("lang", "painless", "source", mergeScript, "params", params),
                  "scripted_upsert",
                  true,
                  "upsert",
                  Map.of()));
        }
      }
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Không tuần tự được lô ghi chỉ mục", e);
    }
    OpenSearchHttp.Response r = http.ndjson("/_bulk" + query, body.toString());
    if (r.status() == 429 || r.status() >= 500) {
      throw new TransientIndexException("OpenSearch từ chối lô ghi, mã " + r.status());
    }
    expectOk(r, "ghi lô");
    return outcomes(commands, r.body());
  }

  private List<IndexFailure> outcomes(List<IndexCommand> commands, JsonNode body) {
    List<IndexFailure> failures = new ArrayList<>();
    boolean transientError = false;
    Iterator<JsonNode> items = body.path("items").elements();
    for (IndexCommand c : commands) {
      JsonNode item = items.hasNext() ? items.next() : null;
      JsonNode result = item == null ? null : item.elements().next();
      int status = result == null ? 500 : result.path("status").asInt(500);
      String type = result == null ? "" : result.path("error").path("type").asText("");
      if (status < 300) {
        continue;
      }
      if (status == 409 && c.mode() == IndexCommand.Mode.REPLACE) {
        continue; // version_type=external: tài liệu đã có phiên bản bằng hoặc mới hơn, sự kiện đến
        // muộn bị bỏ.
      }
      if (status == 404 || status == 409 || status == 429 || status >= 500) {
        if (status == 404) {
          known.remove(c.tenantId()); // bí danh bị xoá ngoài indexer: lần thử lại tạo lại.
        }
        transientError = true;
        continue;
      }
      failures.add(new IndexFailure(c.seq(), "SEARCH_INDEX_" + status, type));
    }
    if (transientError) {
      throw new TransientIndexException("Một phần lô ghi lỗi tạm thời, thử lại cả lô");
    }
    return failures;
  }

  @Override
  public RebuildTarget startRebuild(RebuildScope scope) {
    String alias = scope.pool() ? IndexNames.POOL_ALIAS : IndexNames.tenantAlias(scope.tenantId());
    OpenSearchHttp.Response r = http.send("GET", "/_alias/" + alias, null);
    expectOk(r, "đọc bí danh " + alias);
    String old = r.body().fieldNames().next();
    if (!scope.pool() && IndexNames.isPoolIndex(old)) {
      throw new IllegalStateException(
          "Tenant " + scope.tenantId() + " dùng chỉ mục chung; dựng lại theo phạm vi pool");
    }
    String fresh = IndexNames.next(old);
    OpenSearchHttp.Response c = http.send("PUT", "/" + fresh, Map.of());
    if (alreadyExists(c)) {
      // Lần dựng lại trước dừng giữa chừng: chỉ mục chưa được bí danh nào trỏ tới, tạo lại từ đầu.
      expectOk(http.send("DELETE", "/" + fresh, null), "xoá chỉ mục dở dang");
      c = http.send("PUT", "/" + fresh, Map.of());
    }
    expectOk(c, "tạo chỉ mục " + fresh);
    Set<String> tenants = ConcurrentHashMap.newKeySet();
    tenants.addAll(scope.pool() ? tenantsOf(old) : Set.of(scope.tenantId()));
    return new RebuildTarget(scope, old, fresh, tenants);
  }

  @Override
  public Set<String> swap(RebuildTarget t) {
    Set<String> current = t.scope().pool() ? tenantsOf(t.oldIndex()) : Set.of(t.scope().tenantId());
    Set<String> late = new HashSet<>(current);
    late.removeAll(t.tenants());
    t.tenants().addAll(late);
    List<Map<String, Object>> actions = new ArrayList<>();
    for (String tenant : t.tenants()) {
      String alias = IndexNames.tenantAlias(tenant);
      actions.add(Map.of("remove", Map.of("index", t.oldIndex(), "alias", alias)));
      actions.add(
          Map.of(
              "add",
              t.scope().pool()
                  ? poolAlias(t.newIndex(), tenant)
                  : Map.of("index", t.newIndex(), "alias", alias)));
    }
    if (t.scope().pool()) {
      actions.add(Map.of("remove", Map.of("index", t.oldIndex(), "alias", IndexNames.POOL_ALIAS)));
      actions.add(Map.of("add", Map.of("index", t.newIndex(), "alias", IndexNames.POOL_ALIAS)));
    }
    // Làm mới trước khi hoán để truy vấn ngay sau khi hoán thấy đủ dữ liệu đã nạp (chu kỳ làm mới
    // là 1 giây).
    expectOk(http.send("POST", "/" + t.newIndex() + "/_refresh", null), "làm mới " + t.newIndex());
    expectOk(http.send("POST", "/_aliases", Map.of("actions", actions)), "hoán bí danh");
    return late;
  }

  private Set<String> tenantsOf(String index) {
    OpenSearchHttp.Response r = http.send("GET", "/" + index + "/_alias", null);
    expectOk(r, "đọc bí danh của " + index);
    Set<String> out = new HashSet<>();
    r.body()
        .path(index)
        .path("aliases")
        .fieldNames()
        .forEachRemaining(
            a -> {
              Matcher m = TENANT_ALIAS.matcher(a);
              if (m.matches()) {
                out.add(m.group(1));
              }
            });
    return out;
  }

  private void line(StringBuilder b, Object o) throws JsonProcessingException {
    b.append(json.writeValueAsString(o)).append('\n');
  }

  private static boolean alreadyExists(OpenSearchHttp.Response r) {
    return r.status() == 400
        && "resource_already_exists_exception".equals(r.body().path("error").path("type").asText());
  }

  private static void expectOk(OpenSearchHttp.Response r, String action) {
    if (r.status() == 429 || r.status() >= 500) {
      throw new TransientIndexException("OpenSearch lỗi khi " + action + ", mã " + r.status());
    }
    if (!r.ok()) {
      throw new IllegalStateException(
          "OpenSearch từ chối "
              + action
              + ", mã "
              + r.status()
              + ": "
              + r.body().path("error").path("type").asText(""));
    }
  }

  private static String resource(String path) {
    try (InputStream in = OpenSearchIndex.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu tài nguyên " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
