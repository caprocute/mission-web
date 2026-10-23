package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;

/**
 * Chặn hồi quy lỗi 404 khi chạy thử trên dev: mọi thao tác của {@code appraisals.yaml} có {@code
 * x-mission.owner: MOD-04} phải có handler trong ngữ cảnh {@code mission-core} khởi động như hồ sơ
 * dev (không bật hồ sơ Spring nào, cùng cấu hình mặc định mà {@code infra/dev/docker-compose.yml}
 * dùng). Thao tác của SVC-07 (vết tính, tính thử, tính lại, xem trước điểm) do scoring-service phục
 * vụ qua tuyến APISIX riêng, nên mission-core không được khai báo trùng.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "management.server.port=0")
class AppraisalRouteRegistrationIT {

  private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired
  @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;

  record Operation(String id, String owner, String method, String path) {}

  @Test
  void moiThaoTacMod04CoHandlerConThaoTacSvc07KhongTrung() throws Exception {
    List<Operation> ops = operations("appraisals.yaml");
    assertThat(ops).hasSizeGreaterThan(20);

    List<String> missing = new ArrayList<>();
    List<String> shadowing = new ArrayList<>();
    for (Operation op : ops) {
      boolean handled = handled(op);
      if ("MOD-04".equals(op.owner()) && !handled)
        missing.add(op.id() + " " + op.method() + " " + op.path());
      if ("SVC-07".equals(op.owner()) && handled)
        shadowing.add(op.id() + " " + op.method() + " " + op.path());
    }
    assertThat(missing).as("thao tác MOD-04 chưa có handler trong mission-core").isEmpty();
    assertThat(shadowing).as("thao tác SVC-07 bị mission-core khai báo trùng").isEmpty();
  }

  private boolean handled(Operation op) throws Exception {
    // biến đường dẫn thay bằng UUID cố định, đủ để khớp mẫu {id}, {profileId}, {periodId}
    String uri =
        "/api/v1" + op.path().replaceAll("\\{[^}]+}", "0192e1c4-5d6e-7f80-9a1b-000000000001");
    MockHttpServletRequest request = new MockHttpServletRequest(op.method().toUpperCase(), uri);
    HandlerExecutionChain chain = mappings.getHandler(request);
    return chain != null;
  }

  @SuppressWarnings("unchecked")
  private static List<Operation> operations(String file) throws IOException {
    Path spec = repoRoot().resolve("contracts/openapi/modules").resolve(file);
    Map<String, Object> doc;
    try (Reader r = Files.newBufferedReader(spec)) {
      doc = new Yaml().load(r);
    }
    List<Operation> out = new ArrayList<>();
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");
    paths.forEach(
        (path, item) ->
            item.forEach(
                (method, raw) -> {
                  if (!METHODS.contains(method) || !(raw instanceof Map<?, ?> op)) return;
                  Map<String, Object> mission = (Map<String, Object>) op.get("x-mission");
                  String owner = mission == null ? "" : String.valueOf(mission.get("owner"));
                  out.add(
                      new Operation(String.valueOf(op.get("operationId")), owner, method, path));
                }));
    return out;
  }

  private static Path repoRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("contracts/openapi/modules")))
      p = p.getParent();
    if (p == null) throw new IllegalStateException("không tìm thấy contracts/openapi/modules");
    return p;
  }
}
