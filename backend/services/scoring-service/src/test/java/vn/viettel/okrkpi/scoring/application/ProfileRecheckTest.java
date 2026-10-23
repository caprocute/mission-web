package vn.viettel.okrkpi.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;

/** Mở tác vụ đối chiếu theo đúng dạng {recheckOf, results} của DL-12 R4 (DV-07-06). */
class ProfileRecheckTest {

  final StoredScores stored = mock(StoredScores.class);
  final ScoreJobStore jobs = mock(ScoreJobStore.class);
  final ProfileRecheck.Guard guard = mock(ProfileRecheck.Guard.class);
  final ProfileRecheck recheck = new ProfileRecheck(stored, jobs, guard);

  @Test
  void moTacVuKiemTraTaiLapChoKetQuaMoiNhat() {
    UUID profile = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    StoredScores.Stored s =
        new StoredScores.Stored(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            profile,
            UUID.randomUUID(),
            "DANG",
            UUID.randomUUID(),
            "h",
            "2.1.0",
            "{}",
            20271,
            UUID.randomUUID());
    when(stored.latest(profile)).thenReturn(Optional.of(s));
    UUID id = UUID.randomUUID();
    when(jobs.open(org.mockito.ArgumentMatchers.any())).thenReturn(id);

    assertThat(recheck.request(profile, actor)).isEqualTo(id);
    ArgumentCaptor<ScoreJob> job = ArgumentCaptor.forClass(ScoreJob.class);
    verify(jobs).open(job.capture());
    assertThat(job.getValue().kind()).isEqualTo(ScoreJobKind.KIEM_TRA_TAI_LAP);
    assertThat(job.getValue().totalItems()).isEqualTo(1);
    assertThat(job.getValue().actorId()).isEqualTo(actor);
    assertThat(job.getValue().scope().json())
        .isEqualTo(
            "{\"recheckOf\":\"" + s.scoreJobId() + "\",\"results\":[\"" + s.resultId() + "\"]}");
    ArgumentCaptor<ProfileRecheck.Resource> res =
        ArgumentCaptor.forClass(ProfileRecheck.Resource.class);
    verify(guard).checkRecompute(res.capture());
    var authz = res.getValue().toAuthzResource();
    assertThat(authz.type()).isEqualTo("appraisal_profile");
    assertThat(authz.ownerIds()).isEqualTo(List.of(s.personId().toString()));
  }

  @Test
  void hoSoChuaCoKetQuaLa404() {
    UUID profile = UUID.randomUUID();
    when(stored.latest(profile)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> recheck.request(profile, UUID.randomUUID()))
        .isInstanceOf(ScoreTraceService.TraceNotFound.class);
  }
}
