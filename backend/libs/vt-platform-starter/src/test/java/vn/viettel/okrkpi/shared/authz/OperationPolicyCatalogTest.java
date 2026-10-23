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

    // 372 thao tác trước NEN-042/043 + 42 thao tác bổ sung trong NEN-042/043 + 17 thao tác SCIM của
    // DV-04-06 (scim.yaml) + 1 thao tác listAppraisalBatchDecisions của WEB-063 + 20 thao tác của
    // NEN-044 (BS-03: 13 hồ sơ tập khách hàng, 4 đánh giá, 3 mốc KR) + 7 thao tác tuỳ chọn cá nhân
    // của CNH-02 + 4 thao tác dịch vụ định nghĩa dashboard của CORE-08-17
    // + searchAccessLogs của CORE-07-12 + các thao tác task của CORE-02-14/18/26 + 8 thao tác danh
    // bạ khoá công khai, khoá phục hồi và nhật ký phục hồi của CORE-07-06 + 3 thao tác khôi phục
    // khoá (getE2eeRecovery, restoreTaskSecretKeyWrap, restoreEvidenceKeyWrap) = 477 thao tác.
    assertThat(catalog.size()).isEqualTo(477);
    assertThat(catalog.all()).hasSize(477);

    List<OperationPolicy> aal2 = catalog.aal2Operations();
    // 54 thao tác AAL2 gốc + 7 thao tác của WEB-058 + 12 thao tác của NEN-042/043 + 1 thao tác
    // approveCustomerProfileVersion của NEN-044 + 1 thao tác searchAccessLogs của CORE-07-12 + 5
    // thao tác ghi khoá và đọc nhật ký phục hồi của CORE-07-06 + 2 thao tác nạp khoá bọc khôi phục
    // = 82 thao tác.
    assertThat(aal2).hasSize(82);
    long nonWeb058Aal2 =
        aal2.stream().filter(p -> !WEB_058_AAL2_OPS.contains(p.operationId())).count();
    assertThat(nonWeb058Aal2).isEqualTo(75);

    List<OperationPolicy> fourEyes = catalog.fourEyesOperations();
    // 15 thao tác bốn mắt gốc + 2 thao tác của WEB-058 + 1 thao tác của NEN-043 + 1 thao tác
    // approveCustomerProfileVersion của NEN-044 = 19 thao tác.
    assertThat(fourEyes).hasSize(19);
    long nonWeb058FourEyes =
        fourEyes.stream().filter(p -> !WEB_058_FOUR_EYES_OPS.contains(p.operationId())).count();
    assertThat(nonWeb058FourEyes).isEqualTo(17);
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
