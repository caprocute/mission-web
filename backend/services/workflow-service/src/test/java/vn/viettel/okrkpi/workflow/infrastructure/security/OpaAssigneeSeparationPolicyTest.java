package vn.viettel.okrkpi.workflow.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.monitor.MonitoredTask;

/**
 * Đầu vào tách vai trò khi chuyển người xử lý (SCR-1506) chạy với chính sách thật {@code
 * backend/opa/okrkpi/authz.rego}: người khởi tạo nhận bước duyệt thì OPA trả {@code
 * separation_of_duties} (ràng buộc 4.1), người khác thì không. Bỏ qua khi máy không có {@code opa}.
 */
class OpaAssigneeSeparationPolicyTest {

  private final ObjectMapper json = new ObjectMapper();
  private final UUID tenant = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID initiator = UUID.randomUUID();

  @Test
  void nguoiKhoiTaoBiChinhSachThatTuChoiTachVaiTro() throws Exception {
    Optional<String> opa = opaBinary();
    Path policy = Path.of("..", "..", "opa").toAbsolutePath().normalize();
    assumeTrue(
        opa.isPresent() && Files.isDirectory(policy), "không có opa hoặc thư mục chính sách");
    MonitoredTask task =
        new MonitoredTask(
            "t-1",
            tenant,
            "p-1",
            "review",
            null,
            List.of(new CandidateGroup(tenant, "appraiser", unit)),
            1,
            null,
            new BusinessKey("APPRAISAL_PROFILE", UUID.randomUUID()),
            initiator,
            "duyet-ho-so-danh-gia");
    long now = Instant.now().getEpochSecond();

    assertThat(deny(opa.get(), policy, OpaAssigneeSeparation.input(task, initiator, now)))
        .contains("separation_of_duties");
    assertThat(deny(opa.get(), policy, OpaAssigneeSeparation.input(task, UUID.randomUUID(), now)))
        .doesNotContain("separation_of_duties", "four_eyes_required");
  }

  private List<String> deny(String opa, Path policy, Object input) throws Exception {
    Path file = Files.createTempFile("opa-input", ".json");
    try {
      Files.writeString(file, json.writeValueAsString(input));
      Process p =
          new ProcessBuilder(
                  opa,
                  "eval",
                  "-f",
                  "json",
                  "-d",
                  policy.toString(),
                  "-i",
                  file.toString(),
                  "data.okrkpi.authz.decision")
              .redirectErrorStream(true)
              .start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
      assertThat(p.exitValue()).as(out).isZero();
      JsonNode value = json.readTree(out).at("/result/0/expressions/0/value/deny");
      List<String> deny = new ArrayList<>();
      value.forEach(d -> deny.add(d.asText()));
      return deny;
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static Optional<String> opaBinary() {
    String path = System.getenv("PATH");
    if (path == null) {
      return Optional.empty();
    }
    for (String dir : path.split(File.pathSeparator)) {
      Path candidate = Path.of(dir, "opa");
      if (Files.isExecutable(candidate)) {
        return Optional.of(candidate.toString());
      }
    }
    return Optional.empty();
  }
}
