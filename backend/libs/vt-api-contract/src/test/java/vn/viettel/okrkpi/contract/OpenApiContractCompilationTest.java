package vn.viettel.okrkpi.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.bind.annotation.RequestHeader;
import vn.viettel.okrkpi.contract.application.OpenApiContractRegistry;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.jobs.api.JobsApi;
import vn.viettel.okrkpi.contract.jobs.model.FieldError;
import vn.viettel.okrkpi.contract.jobs.model.Job;
import vn.viettel.okrkpi.contract.jobs.model.JobStatus;
import vn.viettel.okrkpi.contract.jobs.model.ProblemDetails;
import vn.viettel.okrkpi.contract.tasks.api.TasksApi;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetail;
import vn.viettel.okrkpi.contract.tasks.model.TaskStatus;

class OpenApiContractCompilationTest {

  @Test
  void shouldLoadAllElevenModulesAndTwentyFourGeneratedApiInterfaces() {
    assertThat(ContractModule.values()).hasSize(11);
    int totalOps =
        Arrays.stream(ContractModule.values()).mapToInt(ContractModule::operationCount).sum();
    assertThat(totalOps).isEqualTo(ContractModule.TOTAL_OPERATIONS);

    List<Class<?>> allInterfaces = OpenApiContractRegistry.loadAllApiInterfaces();
    assertThat(allInterfaces).hasSize(24);
    assertThat(allInterfaces).allMatch(Class::isInterface);

    for (ContractModule module : ContractModule.values()) {
      List<Class<?>> interfaces = OpenApiContractRegistry.loadApiInterfaces(module);
      assertThat(interfaces).isNotEmpty();
      assertThat(module.apiPackage()).startsWith(ContractModule.BASE_PACKAGE + ".");
      assertThat(module.modelPackage()).endsWith(".model");
      assertThat(module.specFileName()).isEqualTo(module.moduleId() + ".yaml");
      Path specPath = OpenApiContractRegistry.resolveContractSpecPath(module, Path.of("."));
      assertThat(specPath).exists();
    }
  }

  @Test
  void shouldResolveModulesByIdAndOwnerServiceCode() {
    assertThat(ContractModule.fromModuleId("jobs")).contains(ContractModule.JOBS);
    assertThat(ContractModule.fromModuleId("TASKS.YAML")).contains(ContractModule.TASKS);
    assertThat(ContractModule.fromModuleId(null)).isEmpty();
    assertThat(ContractModule.fromModuleId("   ")).isEmpty();
    assertThat(ContractModule.fromModuleId("unknown")).isEmpty();

    assertThat(ContractModule.missionCoreModules()).hasSize(10);
    assertThat(ContractModule.forOwner("SVC-07")).containsExactly(ContractModule.DOSSIERS);
    assertThat(ContractModule.forOwner("SVC-13")).containsExactly(ContractModule.OVERSIGHT);
    assertThat(ContractModule.forOwner("SVC-10"))
        .containsExactlyInAnyOrder(ContractModule.PLATFORM, ContractModule.POLICIES);
    assertThat(ContractModule.forOwner(null)).isEmpty();
    assertThat(ContractModule.forOwner("  ")).isEmpty();
    assertThat(ContractModule.JOBS.isOwnedBy(null)).isFalse();
    assertThat(ContractModule.JOBS.isOwnedBy(" ")).isFalse();
    assertThat(ContractModule.JOBS.isOwnedBy("svc-05")).isTrue();
  }

  @Test
  void shouldFailGracefullyWhenContractSpecCannotBeResolved(@TempDir Path tempDir) {
    assertThatThrownBy(
            () -> OpenApiContractRegistry.resolveContractSpecPath(ContractModule.JOBS, tempDir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jobs");
  }

  @Test
  void shouldGenerateJobsApiAndCommonDtosAccurately() throws NoSuchMethodException {
    Method getJob = JobsApi.class.getMethod("getJob", String.class);
    assertThat(getJob.getReturnType().getSimpleName()).isEqualTo("ResponseEntity");

    Method cancelJob = JobsApi.class.getMethod("cancelJob", String.class, String.class);
    Parameter idempotencyParam = cancelJob.getParameters()[0];
    RequestHeader header = idempotencyParam.getAnnotation(RequestHeader.class);
    assertThat(header).isNotNull();
    assertThat(header.value()).isEqualTo("Idempotency-Key");

    assertThat(JobStatus.values())
        .extracting(JobStatus::getValue)
        .containsExactly("CHO", "DANG_CHAY", "HOAN_TAT", "THAT_BAI", "DA_HUY");

    OffsetDateTime now = OffsetDateTime.parse("2026-12-31T17:05:00+07:00");
    ProblemDetails problem =
        new ProblemDetails("https://okrkpi.vn/problems/conflict", "Xung đột", 409)
            .code("JOB-409-NOT-CANCELLABLE")
            .errors(List.of(new FieldError("status", "Tác vụ đã kết thúc")));
    Job job =
        new Job("01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77", "PERIOD_LOCK", JobStatus.DANG_CHAY)
            .progress(45)
            .message("Đang chốt điểm đơn vị 9/20")
            .updatedAt(now)
            .problem(problem);

    assertThat(job.getId()).isEqualTo("01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77");
    assertThat(job.getStatus()).isEqualTo(JobStatus.DANG_CHAY);
    assertThat(job.getUpdatedAt()).isEqualTo(now);
    assertThat(job.getProblem().getCode()).isEqualTo("JOB-409-NOT-CANCELLABLE");
  }

  @Test
  void shouldMapUuidLocalDateAndVietnameseTaskStatusesCorrectly() throws NoSuchMethodException {
    assertThat(TaskStatus.values())
        .extracting(TaskStatus::getValue)
        .contains(
            "NHAP", "DA_GIAO", "DANG_THUC_HIEN", "CHO_NGHIEM_THU", "HOAN_THANH", "TAM_DUNG", "HUY");

    Method getId = TaskDetail.class.getMethod("getId");
    assertThat(getId.getReturnType()).isEqualTo(UUID.class);

    Method getDueDate = TaskDetail.class.getMethod("getDueDate");
    assertThat(getDueDate.getReturnType()).isEqualTo(LocalDate.class);

    boolean hasIfMatchHeader =
        Arrays.stream(TasksApi.class.getMethods())
            .flatMap(m -> Arrays.stream(m.getParameters()))
            .map(p -> p.getAnnotation(RequestHeader.class))
            .filter(h -> h != null)
            .anyMatch(h -> "If-Match".equals(h.value()));
    assertThat(hasIfMatchHeader).isTrue();
  }
}
