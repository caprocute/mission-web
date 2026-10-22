package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code RENDER} của {@code createDossierMeetingMinutes}: chưa có EVT-17 thì {@code
 * DANG_CHAY}, có rồi thì {@code HOAN_TAT} với {@code resultLocation} là hội nghị. Trạng thái đọc từ
 * {@code dossier.review_meeting} nên đúng trên mọi pod.
 */
public class MinutesJobs implements JobSource {

  static final String TYPE = "RENDER";
  static final String WAITING = "Biên bản hội nghị đang được lập.";
  static final String DONE = "Đã lập biên bản hội nghị.";

  private final MeetingProceedingsService service;

  public MinutesJobs(MeetingProceedingsService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public String name() {
    return "dossier.review_meeting.minutes";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    UUID id;
    try {
      id = UUID.fromString(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return service
        .minutesJob(tenant, id)
        .map(j -> new TrackedJob(job(jobId, j), ActorId.of(j.initiator()), false));
  }

  /** Bản {@code DANG_CHAY} trả trong thân 202. */
  static Job pending(UUID jobId) {
    return new Job(
        jobId.toString(),
        TYPE,
        JobStatus.DANG_CHAY,
        0,
        WAITING,
        null,
        null,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static Job job(String jobId, MeetingProceedingsService.MinutesJob j) {
    if (!j.done()) {
      return new Job(jobId, TYPE, JobStatus.DANG_CHAY, 0, WAITING, null, null, null);
    }
    return new Job(
        jobId,
        TYPE,
        JobStatus.HOAN_TAT,
        100,
        DONE,
        "/api/v1/dossiers/meetings/" + j.meetingId(),
        null,
        null);
  }
}
