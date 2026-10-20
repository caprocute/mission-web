package vn.viettel.okrkpi.shared.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class JobQueryServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final ActorId OWNER = ActorId.of(UUID.randomUUID());
  private static final ActorId OTHER = ActorId.of(UUID.randomUUID());
  private static final OffsetDateTime AT = OffsetDateTime.parse("2026-12-31T17:05:00+07:00");

  private static JobSource source(String id, JobStatus status, boolean cancellable, boolean stops) {
    return new JobSource() {
      @Override
      public String name() {
        return "thu." + id;
      }

      @Override
      public Optional<TrackedJob> find(TenantId tenant, String jobId) {
        if (!jobId.equals(id)) {
          return Optional.empty();
        }
        return Optional.of(
            new TrackedJob(
                new Job(id, "PERIOD_LOCK", status, 45, "Đang chốt", null, null, AT),
                OWNER,
                cancellable));
      }

      @Override
      public boolean requestCancel(TenantId tenant, String jobId) {
        return stops;
      }
    };
  }

  private final JobQueryService service =
      new JobQueryService(
          List.of(
              source("a", JobStatus.DANG_CHAY, true, true),
              source("b", JobStatus.HOAN_TAT, true, true),
              source("c", JobStatus.DANG_CHAY, false, true),
              source("d", JobStatus.CHO, true, false),
              new JobSource() {
                @Override
                public String name() {
                  return "mac-dinh";
                }

                @Override
                public Optional<TrackedJob> find(TenantId tenant, String jobId) {
                  return jobId.equals("e")
                      ? Optional.of(new TrackedJob(Job.accepted("e", "EXPORT", AT), OWNER, true))
                      : Optional.empty();
                }
              }));

  @Test
  void chiNguoiKhoiTaoXemDuoc() {
    assertThat(service.get(TENANT, OWNER, "a").progress()).isEqualTo(45);
    assertThat(service.find(TENANT, OTHER, "a")).isEmpty();
    assertThatThrownBy(() -> service.get(TENANT, OTHER, "a"))
        .isInstanceOf(JobNotFoundException.class);
    assertThatThrownBy(() -> service.get(TENANT, OWNER, "khong-co"))
        .isInstanceOf(JobNotFoundException.class);
    assertThat(service.find(TENANT, OWNER, " ")).isEmpty();
    assertThat(service.find(TENANT, OWNER, null)).isEmpty();
  }

  @Test
  void huyTacVu() {
    assertThat(service.cancel(TENANT, OWNER, "a").id()).isEqualTo("a");
    for (String id : List.of("b", "c", "d", "e")) {
      assertThatThrownBy(() -> service.cancel(TENANT, OWNER, id))
          .as(id)
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.errorCode().code()).isEqualTo("JOB-409-NOT-CANCELLABLE"));
    }
    assertThatThrownBy(() -> service.cancel(TENANT, OTHER, "a"))
        .isInstanceOf(JobNotFoundException.class);
  }

  @Test
  void phanHoi202VaTrangThai() {
    Job job = Job.accepted("01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77", "EXPORT", AT);
    ResponseEntity<Job> r = Jobs.accepted(job);
    assertThat(r.getStatusCode().value()).isEqualTo(202);
    assertThat(r.getHeaders().getLocation())
        .hasToString("/api/v1/jobs/01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77");
    assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("2");
    assertThat(Jobs.accepted(job, -5).getHeaders().getFirst("Retry-After")).isEqualTo("0");
    assertThat(Jobs.location("a b/c")).hasToString("/api/v1/jobs/a%20b%2Fc");
    assertThat(Jobs.status(job).getHeaders().getFirst("Retry-After")).isEqualTo("2");
    Job done = new Job("x", "EXPORT", JobStatus.HOAN_TAT, 100, null, "/api/v1/files/1", null, AT);
    assertThat(Jobs.status(done).getHeaders().containsKey("Retry-After")).isFalse();
    assertThat(JobStatus.THAT_BAI.isTerminal()).isTrue();
    assertThat(JobStatus.CHO.isTerminal()).isFalse();
  }

  @Test
  void jobHopLe() {
    assertThatThrownBy(() -> new Job(" ", "EXPORT", JobStatus.CHO, 0, null, null, null, AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Job("a", "EXPORT", JobStatus.CHO, 101, null, null, null, AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Job("a", "EXPORT", JobStatus.CHO, -1, null, null, null, AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new Job("a", "EXPORT", JobStatus.CHO, null, null, null, null, null).progress())
        .isNull();
  }
}
