package vn.viettel.okrkpi.shared.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class OperationPolicyCatalogTest {

  // 7 thao tác quản trị vai trò/uỷ quyền được WEB-058 bổ sung vào config.yaml sau khi NEN-014 viết
  // đề bài.
  private static final List<String> WEB_058_AAL2_OPS =
      List.of(
          "approveRoleBinding",
          "createDelegation",
          "exportAccessHistory",
          "grantRoleBinding",
          "rejectRoleBinding",
          "revokeDelegation",
          "revokeRoleBinding");

  private static final List<String> WEB_058_FOUR_EYES_OPS =
      List.of("approveRoleBinding", "rejectRoleBinding");

  @Test
  void defaultCatalogMatchesOpenApiContractCounts() {
    OperationPolicyCatalog catalog = OperationPolicyCatalog.getDefault();

    assertThat(catalog.size()).isEqualTo(372);
    assertThat(catalog.all()).hasSize(372);

    List<OperationPolicy> aal2 = catalog.aal2Operations();
    // 54 thao tác AAL2 gốc của hợp đồng + 7 thao tác của WEB-058 trong config.yaml = 61 thao tác.
    assertThat(aal2).hasSize(61);
    long baselineAal2 =
        aal2.stream().filter(p -> !WEB_058_AAL2_OPS.contains(p.operationId())).count();
    assertThat(baselineAal2).isEqualTo(54);

    List<OperationPolicy> fourEyes = catalog.fourEyesOperations();
    // 15 thao tác bốn mắt gốc của hợp đồng + 2 thao tác của WEB-058 trong config.yaml = 17 thao
    // tác.
    assertThat(fourEyes).hasSize(17);
    long baselineFourEyes =
        fourEyes.stream().filter(p -> !WEB_058_FOUR_EYES_OPS.contains(p.operationId())).count();
    assertThat(baselineFourEyes).isEqualTo(15);
  }

  @Test
  void lookupByOperationIdAndMethodPath() {
    OperationPolicyCatalog catalog = OperationPolicyCatalog.getDefault();

    OperationPolicy approvePlan = catalog.require("approvePlanVersion");
    assertThat(approvePlan.requiresStepUp()).isTrue();
    assertThat(approvePlan.aal()).isEqualTo(2);
    assertThat(approvePlan.module()).isEqualTo("plans");
    assertThat(approvePlan.errorModule()).isEqualTo("PLAN");
    assertThat(approvePlan.method()).isEqualTo("POST");
    assertThat(approvePlan.roles()).contains("leader");

    assertThat(catalog.findByMethodAndPath("post", "/tasks"))
        .isPresent()
        .get()
        .extracting(OperationPolicy::operationId)
        .isEqualTo("createTaskDraft");

    assertThat(catalog.find(null)).isEmpty();
    assertThat(catalog.find("   ")).isEmpty();
    assertThat(catalog.findByMethodAndPath(null, "/tasks")).isEmpty();
    assertThatThrownBy(() -> catalog.require("nonExistentOperation"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void generatedJsonIsInSyncWithOpenApiYamlFiles() throws Exception {
    Path script = Path.of("tools/extract-x-mission.mjs").toAbsolutePath().normalize();
    if (!Files.exists(script)) {
      script =
          Path.of("backend/libs/vt-platform-starter/tools/extract-x-mission.mjs")
              .toAbsolutePath()
              .normalize();
    }
    assertThat(script).exists();
    String nodeBin = "node";
    for (String candidate :
        List.of(
            System.getProperty("user.home") + "/.local/bin/node",
            "/opt/homebrew/bin/node",
            "/usr/local/bin/node")) {
      if (Files.isExecutable(Path.of(candidate))) {
        nodeBin = candidate;
        break;
      }
    }
    Process proc =
        new ProcessBuilder(nodeBin, script.toString(), "--check").redirectErrorStream(true).start();
    String output = new String(proc.getInputStream().readAllBytes());
    int exit = proc.waitFor();
    assertThat(exit).as("extract-x-mission.mjs --check output: %s", output).isZero();
  }
}
