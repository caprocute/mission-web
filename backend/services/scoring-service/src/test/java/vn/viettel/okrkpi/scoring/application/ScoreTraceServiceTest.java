package vn.viettel.okrkpi.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ScoreTrace;
import vn.viettel.okrkpi.scoring.domain.TraceStep;
import vn.viettel.okrkpi.shared.authz.AuthzResource;

/** Đọc vết tính: kiểm quyền trước khi trả, lọc theo nhiệm vụ, 404 khi chưa có kết quả. */
class ScoreTraceServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID PROFILE = UUID.randomUUID();
  private static final UUID PERSON = UUID.randomUUID();
  private static final UUID TASK = UUID.randomUUID();

  @Test
  void kiemQuyenRoiLocTheoNhiemVu() {
    AtomicReference<ScoreTraceService.ProfileTrace> checked = new AtomicReference<>();
    ScoreTraceService.Guard guard =
        new ScoreTraceService.Guard() {
          @Override
          public void checkRead(ScoreTraceService.ProfileTrace trace) {
            checked.set(trace);
          }
        };
    ScoreTraceService service = new ScoreTraceService((p, f) -> Optional.of(sample()), guard);

    assertThat(service.find(PROFILE, "DANG", null).steps()).hasSize(3);
    AuthzResource resource = checked.get().toAuthzResource();
    assertThat(resource.type()).isEqualTo("appraisal_profile");
    assertThat(resource.id()).isEqualTo(PROFILE.toString());
    assertThat(resource.tenantId()).isEqualTo(TENANT.toString());
    assertThat(resource.ownerIds()).containsExactly(PERSON.toString());

    ScoreTrace one = service.find(PROFILE, "DANG", TASK);
    assertThat(one.steps()).extracting(TraceStep::stepNo).containsExactly(1, 2);
    assertThat(one.resultId()).isEqualTo(sample().resultId());
    assertThat(service.find(PROFILE, null, UUID.randomUUID()).steps()).isEmpty();
  }

  @Test
  void chuaCoKetQuaThiKhongTimThay() {
    ScoreTraceService service =
        new ScoreTraceService((p, f) -> Optional.empty(), new ScoreTraceService.Guard());
    assertThatThrownBy(() -> service.find(PROFILE, null, null))
        .isInstanceOf(ScoreTraceService.TraceNotFound.class);
  }

  @Test
  void docMaNhiemVuTuGiaTriVao() {
    assertThat(ScoreTraceService.taskOf(step(1, Map.of("task_id", "abc", "x", 1))))
        .isEqualTo("abc");
    assertThat(ScoreTraceService.taskOf(step(1, Map.of("x", 1)))).isNull();
  }

  private static ScoreTrace sample() {
    return new ScoreTrace(
        UUID.fromString("0192f1a2-0000-7000-8000-0000000000f1"),
        TENANT,
        PROFILE,
        PERSON,
        "DANG",
        "2.1.0",
        "scoring-service:0.1.0",
        List.of(
            step(1, Map.of("task_id", TASK.toString())),
            step(2, Map.of("task_id", TASK.toString())),
            step(3, Map.of("diem_ket_qua", 90))));
  }

  private static TraceStep step(int no, Map<String, Object> inputs) {
    return new TraceStep(
        no, "kpi_A", null, CanonicalDoc.of(inputs), CanonicalDoc.of(Map.of("v", 1)), null);
  }
}
