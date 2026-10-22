package vn.viettel.okrkpi.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.search.application.IndexFailure;
import vn.viettel.okrkpi.search.application.RebuildScope;
import vn.viettel.okrkpi.search.application.RebuildTarget;
import vn.viettel.okrkpi.search.application.TransientIndexException;
import vn.viettel.okrkpi.search.domain.DocType;
import vn.viettel.okrkpi.search.domain.IndexCommand;
import vn.viettel.okrkpi.search.domain.TenancyModel;
import vn.viettel.okrkpi.search.infrastructure.OpenSearchHttp;
import vn.viettel.okrkpi.search.infrastructure.OpenSearchIndex;
import vn.viettel.okrkpi.search.infrastructure.SearchProperties;

/** Nhánh lỗi của cổng OpenSearch trên máy chủ HTTP giả (không cần Docker). */
class OpenSearchIndexTest {

  private static final String T = "0192f000-0000-7000-8000-0000000000a1";

  private record Reply(int status, String body) {}

  private HttpServer server;
  private final Map<String, Deque<Reply>> replies = new HashMap<>();
  private final List<String> seen = new ArrayList<>();
  private final List<String> auth = new ArrayList<>();
  private OpenSearchIndex index;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
          seen.add(key);
          auth.add(ex.getRequestHeaders().getFirst("Authorization"));
          Deque<Reply> q = replies.get(key);
          Reply r =
              q == null || q.isEmpty() ? new Reply(404, "{}") : q.size() == 1 ? q.peek() : q.poll();
          byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(r.status(), b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    SearchProperties p =
        new SearchProperties(
            url,
            "indexer",
            "khong-phai-mat-khau-that",
            Duration.ofSeconds(2),
            null,
            null,
            null,
            0,
            0,
            0,
            null,
            null);
    index =
        new OpenSearchIndex(
            new OpenSearchHttp(
                p.opensearchUrl(),
                p.opensearchUsername(),
                p.opensearchPassword(),
                p.timeout(),
                new ObjectMapper()),
            new ObjectMapper(),
            p);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void on(String key, int status, String body) {
    replies.computeIfAbsent(key, k -> new ArrayDeque<>()).add(new Reply(status, body));
  }

  private static IndexCommand cmd(int seq, IndexCommand.Mode mode) {
    return new IndexCommand(
        seq,
        T,
        mode == IndexCommand.Mode.MERGE ? DocType.TASK : DocType.SOURCE_DOC,
        "e" + seq,
        mode,
        mode == IndexCommand.Mode.MERGE ? "assign" : null,
        1,
        Map.of("title", "x"),
        Instant.now());
  }

  private static String items(int... statuses) {
    StringBuilder b = new StringBuilder("{\"items\":[");
    for (int i = 0; i < statuses.length; i++) {
      b.append(i == 0 ? "" : ",")
          .append("{\"index\":{\"status\":")
          .append(statuses[i])
          .append(",\"error\":{\"type\":\"mapper_parsing_exception\"}}}");
    }
    return b.append("]}").toString();
  }

  @Test
  void mauChiMucVaBiDanhTenant() {
    on("PUT /_index_template/mission-search-task", 200, "{}");
    index.installTemplate();
    assertThat(auth.get(0)).startsWith("Basic ");
    // Pool: chưa có chỉ mục chung thì tạo v1 (gặp tranh chấp đã tồn tại vẫn đi tiếp), rồi gắn bí
    // danh.
    on("PUT /pool_task_v1", 400, "{\"error\":{\"type\":\"resource_already_exists_exception\"}}");
    on("POST /_aliases", 200, "{}");
    index.ensureTenant(T, TenancyModel.POOL);
    index.ensureTenant(T, TenancyModel.POOL);
    assertThat(seen)
        .containsSubsequence(
            "GET /_alias/t_" + T + "_task",
            "GET /_alias/pool_task",
            "PUT /pool_task_v1",
            "POST /_aliases");
    assertThat(seen.stream().filter(s -> s.startsWith("GET /_alias/t_")).count()).isEqualTo(1);
    String silo = "0192f000-0000-7000-8000-0000000000b2";
    on("PUT /t_" + silo + "_task_v1", 500, "{}");
    assertThatThrownBy(() -> index.ensureTenant(silo, TenancyModel.SILO))
        .isInstanceOf(TransientIndexException.class);
    replies.remove("PUT /t_" + silo + "_task_v1");
    on("PUT /t_" + silo + "_task_v1", 403, "{\"error\":{\"type\":\"security_exception\"}}");
    assertThatThrownBy(() -> index.ensureTenant(silo, TenancyModel.SILO))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void ketQuaTungMucCuaLoGhi() {
    on("POST /_bulk", 200, items(201, 409, 400));
    List<IndexFailure> f =
        index.write(
            List.of(
                cmd(0, IndexCommand.Mode.MERGE),
                cmd(1, IndexCommand.Mode.REPLACE),
                cmd(2, IndexCommand.Mode.REPLACE)));
    assertThat(f)
        .containsExactly(new IndexFailure(2, "SEARCH_INDEX_400", "mapper_parsing_exception"));
    for (String body : List.of(items(201, 404), items(429), items(409))) {
      replies.remove("POST /_bulk");
      on("POST /_bulk", 200, body);
      int n = body.split("status").length - 1;
      List<IndexCommand> cmds = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        cmds.add(cmd(i, IndexCommand.Mode.MERGE));
      }
      assertThatThrownBy(() -> index.write(cmds)).isInstanceOf(TransientIndexException.class);
    }
    replies.remove("POST /_bulk");
    on("POST /_bulk", 503, "");
    assertThatThrownBy(() -> index.write(List.of(cmd(0, IndexCommand.Mode.MERGE))))
        .isInstanceOf(TransientIndexException.class);
    replies.remove("POST /_bulk");
    on("POST /_bulk", 200, "{\"items\":[]}");
    assertThatThrownBy(() -> index.write(List.of(cmd(0, IndexCommand.Mode.MERGE))))
        .isInstanceOf(TransientIndexException.class);
  }

  @Test
  void dungLaiTenantRiengVaChongNhamPool() {
    on("GET /_alias/t_" + T + "_task", 200, "{\"pool_task_v3\":{}}");
    assertThatThrownBy(() -> index.startRebuild(new RebuildScope(T)))
        .isInstanceOf(IllegalStateException.class);
    replies.clear();
    on("GET /_alias/t_" + T + "_task", 200, "{\"t_" + T + "_task_v2\":{}}");
    on(
        "PUT /t_" + T + "_task_v3",
        400,
        "{\"error\":{\"type\":\"resource_already_exists_exception\"}}");
    on("PUT /t_" + T + "_task_v3", 200, "{}");
    on("DELETE /t_" + T + "_task_v3", 200, "{}");
    RebuildTarget t = index.startRebuild(new RebuildScope(T));
    assertThat(t.newIndex()).isEqualTo("t_" + T + "_task_v3");
    assertThat(seen)
        .containsSubsequence(
            "PUT /t_" + T + "_task_v3", "DELETE /t_" + T + "_task_v3", "PUT /t_" + T + "_task_v3");
    on("POST /t_" + T + "_task_v3/_refresh", 200, "{}");
    on("POST /_aliases", 200, "{}");
    on("POST /_bulk", 200, items(200));
    assertThat(index.write(t, List.of(cmd(0, IndexCommand.Mode.REPLACE)))).isEmpty();
    assertThat(index.swap(t)).isEqualTo(Set.of());
  }

  @Test
  void loiMangLaLoiTamThoi() {
    server.stop(0);
    assertThatThrownBy(() -> index.ensureTenant(T, TenancyModel.POOL))
        .isInstanceOf(TransientIndexException.class);
  }
}
