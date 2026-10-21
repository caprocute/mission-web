package vn.viettel.okrkpi.task.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import vn.viettel.okrkpi.shared.error.ProblemSchema;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.testdb.TenantDatabase;

/**
 * Kiểm thử tích hợp NEN-013 trên PostgreSQL 17 thật (bảng {@code task.idempotency_key}, {@code
 * task.outbox_event} của NEN-032, kết nối {@code svc_task}, RLS FORCE) qua HTTP thật: các kịch bản
 * mục 3 của thẻ, phản hồi lỗi khớp lược đồ {@code ProblemDetails} và mã trong hợp đồng.
 */
@SpringBootTest(
    classes = ApiConventionsTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.flyway.enabled=false", "vt.db.schema-guard.enabled=false"})
class ApiConventionsIT {

  private static final String TENANT = "01927a00-0000-7000-8000-00000000000a";
  private static final String ACTOR = "01927a00-0000-7000-8000-0000000000a1";
  private static final String OTHER_ACTOR = "01927a00-0000-7000-8000-0000000000a2";
  private static final String PROBLEM_JSON = "application/problem+json";

  private static ProblemSchema schema;

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeAll
  static void start() {
    TenantDatabase.start();
    schema = ProblemSchema.fromCommonYaml();
  }

  // ---------- Idempotency-Key ----------

  @Test
  void guiLaiCungKhoaBaLanChiMotTacDung() throws Exception {
    UUID task = UUID.randomUUID();
    String key = Ids.newUuidV7().toString();
    String body = "{\"percent\":40,\"note\":\"Đã xong phần khảo sát\"}";
    List<HttpResponse<String>> responses = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      responses.add(post("/api/v1/tasks/" + task + "/progress", key, ACTOR, body));
    }
    for (HttpResponse<String> r : responses) {
      assertThat(r.statusCode()).isEqualTo(201);
      assertThat(r.headers().firstValue("Location")).hasValue("/api/v1/tasks/" + task);
      assertThat(json.readTree(r.body())).isEqualTo(json.readTree(responses.get(0).body()));
    }
    assertThat(json.readTree(responses.get(0).body()).get("percent").asInt()).isEqualTo(40);
    assertThat(effects(task)).isEqualTo(1);
    assertThat(keyRow(key)).isEqualTo("XONG|201|true|/api/v1/tasks/" + task);
  }

  @Test
  void cungKhoaThuTuTruongKhacVanLaCungYeuCau() throws Exception {
    UUID task = UUID.randomUUID();
    String key = Ids.newUuidV7().toString();
    assertThat(
            post(
                    "/api/v1/tasks/" + task + "/progress",
                    key,
                    ACTOR,
                    "{\"percent\":10,\"note\":\"a\"}")
                .statusCode())
        .isEqualTo(201);
    HttpResponse<String> again =
        post(
            "/api/v1/tasks/" + task + "/progress",
            key,
            ACTOR,
            "{ \"note\" : \"a\", \"percent\" : 10 }");
    assertThat(again.statusCode()).isEqualTo(201);
    assertThat(effects(task)).isEqualTo(1);
  }

  @Test
  void cungKhoaKhacNoiDungTra422() throws Exception {
    UUID task = UUID.randomUUID();
    String key = Ids.newUuidV7().toString();
    post("/api/v1/tasks/" + task + "/progress", key, ACTOR, "{\"percent\":40,\"note\":\"x\"}");
    HttpResponse<String> r =
        post("/api/v1/tasks/" + task + "/progress", key, ACTOR, "{\"percent\":50,\"note\":\"x\"}");
    JsonNode p = problem(r, 422);
    assertThat(p.get("code").asText()).isEqualTo("COMMON-422-IDEMPOTENCY-MISMATCH");
    // Cùng khoá, đường dẫn khác cũng là nội dung khác.
    JsonNode p2 =
        problem(
            post(
                "/api/v1/tasks/" + UUID.randomUUID() + "/progress",
                key,
                ACTOR,
                "{\"percent\":40,\"note\":\"x\"}"),
            422);
    assertThat(p2.get("code").asText()).isEqualTo("COMMON-422-IDEMPOTENCY-MISMATCH");
    assertThat(effects(task)).isEqualTo(1);
  }

  @Test
  void thieuHoacSaiKhoaTra400() throws Exception {
    UUID task = UUID.randomUUID();
    JsonNode missing =
        problem(post("/api/v1/tasks/" + task + "/progress", null, ACTOR, "{\"percent\":1}"), 400);
    assertThat(missing.get("errors").get(0).get("field").asText()).isEqualTo("Idempotency-Key");
    problem(
        post("/api/v1/tasks/" + task + "/progress", "k".repeat(65), ACTOR, "{\"percent\":1}"), 400);
    problem(
        post("/api/v1/tasks/" + task + "/progress", "co khoang trang", ACTOR, "{\"percent\":1}"),
        400);
    assertThat(effects(task)).isZero();
  }

  @Test
  void haiYeuCauDongThoiCungKhoaMotThanhCongMotNhan409() throws Exception {
    UUID task = UUID.randomUUID();
    String key = Ids.newUuidV7().toString();
    String body = "{\"percent\":70,\"note\":\"đồng thời\"}";
    TaskProbeController.entered = new CountDownLatch(1);
    TaskProbeController.release = new CountDownLatch(1);
    CompletableFuture<HttpResponse<String>> first =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return send(
                    request("/api/v1/tasks/" + task + "/progress", key, ACTOR)
                        .header("X-Test-Hold", "1")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build());
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    assertThat(TaskProbeController.entered.await(15, TimeUnit.SECONDS)).isTrue();
    HttpResponse<String> second = post("/api/v1/tasks/" + task + "/progress", key, ACTOR, body);
    JsonNode p = problem(second, 409);
    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/conflict");
    assertThat(second.headers().firstValue("Retry-After")).hasValue("1");
    TaskProbeController.release.countDown();
    assertThat(first.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
    // Sau khi yêu cầu đầu xong, gửi lại nhận phản hồi đã lưu.
    assertThat(post("/api/v1/tasks/" + task + "/progress", key, ACTOR, body).statusCode())
        .isEqualTo(201);
    assertThat(effects(task)).isEqualTo(1);
  }

  @Test
  void khoaTheoNguoiGoiKhongDungChung() throws Exception {
    UUID task = UUID.randomUUID();
    String key = Ids.newUuidV7().toString();
    String body = "{\"percent\":5}";
    assertThat(post("/api/v1/tasks/" + task + "/progress", key, ACTOR, body).statusCode())
        .isEqualTo(201);
    assertThat(post("/api/v1/tasks/" + task + "/progress", key, OTHER_ACTOR, body).statusCode())
        .isEqualTo(201);
    assertThat(effects(task)).isEqualTo(2);
  }

  @Test
  void thanCoTruongC2KhongDuocLuu() throws Exception {
    UUID task = UUID.randomUUID();
    String key = Ids.newUuidV7().toString();
    String body = "{\"content\":\"Đồng chí cần cải thiện tác phong\"}";
    HttpResponse<String> first = post("/api/v1/tasks/" + task + "/comments", key, ACTOR, body);
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(first.body()).contains("tác phong");
    HttpResponse<String> replay = post("/api/v1/tasks/" + task + "/comments", key, ACTOR, body);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.headers().firstValue("Location"))
        .hasValue("/api/v1/tasks/" + task + "/comments/1");
    assertThat(replay.body()).isEmpty();
    assertThat(keyRow(key)).isEqualTo("XONG|201|false|/api/v1/tasks/" + task + "/comments/1");
    assertThat(effects(task)).isEqualTo(1);
  }

  @Test
  void loiNghiepVuKhongLuuKhoaVaHuyTacDung() throws Exception {
    UUID task = UUID.randomUUID();
    String key = Ids.newUuidV7().toString();
    for (int i = 0; i < 2; i++) {
      JsonNode p =
          problem(post("/api/v1/tasks/" + task + "/locked", key, ACTOR, "{\"percent\":1}"), 409);
      assertThat(p.get("code").asText()).isEqualTo("TASK-409-PERIOD-LOCKED");
    }
    assertThat(effects(task)).isZero();
    assertThat(keyRow(key)).isNull();
  }

  // ---------- ETag / If-Match ----------

  @Test
  void etagIfMatchVaIfNoneMatch() throws Exception {
    UUID id = UUID.randomUUID();
    String path = "/api/v1/tasks/" + id + "/doc";
    HttpResponse<String> read = send(request(path, null, ACTOR).GET().build());
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(read.headers().firstValue("ETag")).hasValue("\"0\"");

    HttpResponse<String> notModified =
        send(request(path, null, ACTOR).header("If-None-Match", "\"0\"").GET().build());
    assertThat(notModified.statusCode()).isEqualTo(304);
    assertThat(notModified.body()).isEmpty();

    JsonNode missing = problem(put(path, null, "{\"title\":\"Mới\"}"), 428);
    assertThat(missing.get("type").asText())
        .isEqualTo("https://okrkpi.vn/problems/precondition-required");

    JsonNode conflict = problem(put(path, "\"5\"", "{\"title\":\"Mới\"}"), 409);
    assertThat(conflict.get("code").asText()).isEqualTo("TASK-409-VERSION-CONFLICT");
    assertThat(conflict.get("type").asText())
        .isEqualTo("https://okrkpi.vn/problems/concurrent-modification");
    assertThat(conflict.get("detail").asText())
        .isEqualTo("Phiên bản bạn sửa là 5, phiên bản hiện tại là 0.");

    HttpResponse<String> ok = put(path, "\"0\"", "{\"title\":\"Mới\"}");
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(ok.headers().firstValue("ETag")).hasValue("\"1\"");
    assertThat(json.readTree(ok.body()).get("rowVersion").asLong()).isEqualTo(1);
    // Phiên bản cũ giờ lệch.
    problem(put(path, "\"0\"", "{\"title\":\"Lần hai\"}"), 409);
    assertThat(
            send(request(path, null, ACTOR).header("If-None-Match", "\"0\"").GET().build())
                .statusCode())
        .isEqualTo(200);
  }

  // ---------- Phân trang con trỏ ----------

  @Test
  void phanTrang450DongLimit200BaTrangKhongTrungKhongSot() throws Exception {
    UUID task = UUID.randomUUID();
    seedEvents(task, 450);
    Set<String> seen = new HashSet<>();
    List<Integer> sizes = new ArrayList<>();
    String cursor = null;
    String lastKey = null;
    do {
      String path =
          "/api/v1/tasks/"
              + task
              + "/events?limit=200"
              + (cursor == null
                  ? ""
                  : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
      HttpResponse<String> r = send(request(path, null, ACTOR).GET().build());
      assertThat(r.statusCode()).isEqualTo(200);
      JsonNode page = json.readTree(r.body());
      assertThat(page.has("nextCursor")).isTrue();
      sizes.add(page.get("items").size());
      for (JsonNode item : page.get("items")) {
        String k = item.get("createdAt").asText() + "|" + item.get("id").asText();
        if (lastKey != null) {
          assertThat(compareKey(k, lastKey)).isPositive();
        }
        lastKey = k;
        assertThat(seen.add(item.get("id").asText())).isTrue();
      }
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
      if (cursor != null) {
        assertThat(cursor).hasSizeLessThanOrEqualTo(512).matches("[A-Za-z0-9_-]+");
      }
    } while (cursor != null);
    assertThat(sizes).containsExactly(200, 200, 50);
    assertThat(seen).hasSize(450);

    // Mặc định 20 dòng.
    JsonNode first =
        json.readTree(
            send(request("/api/v1/tasks/" + task + "/events", null, ACTOR).GET().build()).body());
    assertThat(first.get("items").size()).isEqualTo(20);
    String firstCursor = first.get("nextCursor").asText();

    // limit ngoài 1..200, con trỏ bị sửa, con trỏ của danh sách khác: 400.
    for (String bad : List.of("limit=0", "limit=201")) {
      JsonNode p =
          problem(
              send(request("/api/v1/tasks/" + task + "/events?" + bad, null, ACTOR).GET().build()),
              400);
      assertThat(p.get("errors").get(0).get("field").asText()).isEqualTo("limit");
    }
    String tampered = (firstCursor.charAt(0) == 'A' ? "B" : "A") + firstCursor.substring(1);
    for (String path :
        List.of(
            "/api/v1/tasks/" + task + "/events?cursor=" + tampered,
            "/api/v1/tasks/" + task + "/events?cursor=khong-phai-con-tro",
            "/api/v1/tasks/" + UUID.randomUUID() + "/events?cursor=" + firstCursor)) {
      JsonNode p = problem(send(request(path, null, ACTOR).GET().build()), 400);
      assertThat(p.get("errors").get(0).get("field").asText()).isEqualTo("cursor");
    }
  }

  // ---------- Tác vụ dài 202 ----------

  @Test
  void tacVuDaiTra202KemLocationVaChiNguoiKhoiTaoXemDuoc() throws Exception {
    String key = Ids.newUuidV7().toString();
    HttpResponse<String> accepted =
        post("/api/v1/tasks/exports", key, ACTOR, "{\"format\":\"xlsx\"}");
    assertThat(accepted.statusCode()).isEqualTo(202);
    JsonNode job = json.readTree(accepted.body());
    assertThat(schema.validate(job, "Job")).isEmpty();
    assertThat(job.get("status").asText()).isEqualTo("CHO");
    String id = job.get("id").asText();
    assertThat(accepted.headers().firstValue("Location")).hasValue("/api/v1/jobs/" + id);
    assertThat(accepted.headers().firstValue("Retry-After")).isPresent();

    // Gửi lại cùng khoá: cùng tác vụ, không tạo tác vụ mới.
    HttpResponse<String> replay =
        post("/api/v1/tasks/exports", key, ACTOR, "{\"format\":\"xlsx\"}");
    assertThat(replay.statusCode()).isEqualTo(202);
    assertThat(json.readTree(replay.body()).get("id").asText()).isEqualTo(id);
    assertThat(replay.headers().firstValue("Location")).hasValue("/api/v1/jobs/" + id);

    HttpResponse<String> status = send(request("/api/v1/jobs/" + id, null, ACTOR).GET().build());
    assertThat(status.statusCode()).isEqualTo(200);
    assertThat(schema.validate(json.readTree(status.body()), "Job")).isEmpty();
    assertThat(status.headers().firstValue("Retry-After")).isPresent();

    problem(send(request("/api/v1/jobs/" + id, null, OTHER_ACTOR).GET().build()), 404);
    problem(send(request("/api/v1/jobs/" + UUID.randomUUID(), null, ACTOR).GET().build()), 404);
    problem(send(request("/api/v1/jobs/" + id, null, OTHER_ACTOR).DELETE().build()), 404);

    assertThat(send(request("/api/v1/jobs/" + id, null, ACTOR).DELETE().build()).statusCode())
        .isEqualTo(202);
    JsonNode cancelled =
        json.readTree(send(request("/api/v1/jobs/" + id, null, ACTOR).GET().build()).body());
    assertThat(cancelled.get("status").asText()).isEqualTo("DA_HUY");
    JsonNode again =
        problem(send(request("/api/v1/jobs/" + id, null, ACTOR).DELETE().build()), 409);
    assertThat(again.get("code").asText()).isEqualTo("JOB-409-NOT-CANCELLABLE");
  }

  // ---------- tiện ích ----------

  private static int compareKey(String a, String b) {
    String[] x = a.split("\\|");
    String[] y = b.split("\\|");
    int c = java.time.Instant.parse(x[0]).compareTo(java.time.Instant.parse(y[0]));
    return c != 0
        ? c
        : UUID.fromString(x[1]).toString().compareTo(UUID.fromString(y[1]).toString());
  }

  private JsonNode problem(HttpResponse<String> r, int status) throws Exception {
    assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
    assertThat(r.headers().firstValue("Content-Type"))
        .hasValueSatisfying(ct -> assertThat(ct).startsWith(PROBLEM_JSON));
    JsonNode p = json.readTree(r.body());
    assertThat(schema.validate(p, "ProblemDetails")).isEmpty();
    assertThat(p.get("status").asInt()).isEqualTo(status);
    assertThat(p.get("traceId").asText()).isNotBlank();
    assertThat(r.body()).doesNotContainIgnoringCase("exception").doesNotContain("idempotency_key");
    return p;
  }

  private HttpRequest.Builder request(String path, String key, String actor) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header(ApiConventionsTestApplication.TENANT_HEADER, TENANT)
            .header(ApiConventionsTestApplication.ACTOR_HEADER, actor);
    if (key != null) {
      b.header("Idempotency-Key", key);
    }
    return b;
  }

  private HttpResponse<String> post(String path, String key, String actor, String body)
      throws Exception {
    return send(request(path, key, actor).POST(HttpRequest.BodyPublishers.ofString(body)).build());
  }

  private HttpResponse<String> put(String path, String ifMatch, String body) throws Exception {
    HttpRequest.Builder b = request(path, null, ACTOR);
    if (ifMatch != null) {
      b.header("If-Match", ifMatch);
    }
    return send(b.PUT(HttpRequest.BodyPublishers.ofString(body)).build());
  }

  private HttpResponse<String> send(HttpRequest r) throws Exception {
    return http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** Số dòng outbox (tác dụng) của nhiệm vụ, đọc bằng svc_task trong ngữ cảnh tenant. */
  private static long effects(UUID task) throws SQLException {
    return inTenant(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT count(*) FROM task.outbox_event WHERE aggregate_id = ?")) {
            ps.setObject(1, task);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return rs.getLong(1);
            }
          }
        });
  }

  /** {@code status|response_code|có thân|resource_ref} của khoá, {@code null} khi không có. */
  private static String keyRow(String key) throws SQLException {
    return inTenant(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT status, response_code, response_body IS NOT NULL, resource_ref"
                      + " FROM task.idempotency_key WHERE idem_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                return null;
              }
              return rs.getString(1)
                  + "|"
                  + rs.getInt(2)
                  + "|"
                  + rs.getBoolean(3)
                  + "|"
                  + rs.getString(4);
            }
          }
        });
  }

  private static void seedEvents(UUID task, int n) throws SQLException {
    inTenant(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO task.outbox_event (id, tenant_id, aggregate_type, aggregate_id,"
                      + " aggregate_version, event_type, topic, event_key, payload, schema_version,"
                      + " created_at) SELECT gen_random_uuid(), ?::uuid, 'Task', ?, g, 'TienDoDaCapNhat',"
                      + " 'task.task.progressed.v1', 'k', '\\x00'::bytea, 1,"
                      + " date_trunc('second', now()) - (g % 7) * interval '1 second'"
                      + " FROM generate_series(1, ?) g")) {
            ps.setString(1, TENANT);
            ps.setObject(2, task);
            ps.setInt(3, n);
            return (long) ps.executeUpdate();
          }
        });
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection c) throws SQLException;
  }

  private static <T> T inTenant(SqlWork<T> work) throws SQLException {
    try (Connection c = TenantDatabase.connect("svc_task")) {
      c.setAutoCommit(false);
      try (PreparedStatement ps =
          c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        ps.setString(1, TENANT);
        ps.execute();
      }
      T result = work.run(c);
      c.commit();
      return result;
    }
  }
}
