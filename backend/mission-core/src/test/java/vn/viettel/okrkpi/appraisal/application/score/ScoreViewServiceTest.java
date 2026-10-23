package vn.viettel.okrkpi.appraisal.application.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader.Snapshot;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class ScoreViewServiceTest {

  private static final TenantId TENANT = new TenantId(UUID.randomUUID());
  private static final UUID PERIOD = UUID.randomUUID();
  private static final UUID PERSON = UUID.randomUUID();

  private final SelfAssessmentRepository profiles = mock(SelfAssessmentRepository.class);
  private final ScoreSnapshotReader snapshots = mock(ScoreSnapshotReader.class);
  private final ScoreViewService service = new ScoreViewService(profiles, snapshots);

  @Test
  void chuHoSoDocAnhChupHienHanh() {
    ProfileSummary p = profile(UUID.randomUUID(), "DANG");
    Snapshot s = snapshot(p.id(), "DANG");
    when(profiles.summary(TENANT, p.id())).thenReturn(Optional.of(p));
    when(snapshots.current(TENANT, 20271, p.id())).thenReturn(Optional.of(s));

    assertThat(service.view(TENANT, p.id(), null, PERSON, x -> false))
        .hasValueSatisfying(v -> assertThat(v.snapshot()).isSameAs(s));
    assertThat(service.view(TENANT, p.id(), "DANG", PERSON, x -> false)).isPresent();
  }

  @Test
  void luongKhacDocHoSoCungNguoiCungKy() {
    ProfileSummary dang = profile(UUID.randomUUID(), "DANG");
    ProfileSummary nn = profile(UUID.randomUUID(), "NHA_NUOC");
    Snapshot s = snapshot(nn.id(), "NHA_NUOC");
    when(profiles.summary(TENANT, dang.id())).thenReturn(Optional.of(dang));
    when(profiles.summary(TENANT, nn.id())).thenReturn(Optional.of(nn));
    when(snapshots.sibling(TENANT, 20271, PERIOD, PERSON, "NHA_NUOC"))
        .thenReturn(Optional.of(nn.id()));
    when(snapshots.current(TENANT, 20271, nn.id())).thenReturn(Optional.of(s));

    assertThat(service.view(TENANT, dang.id(), "NHA_NUOC", PERSON, x -> false))
        .hasValueSatisfying(v -> assertThat(v.profile().id()).isEqualTo(nn.id()));
  }

  @Test
  void khongCoHoSoLuongKiaThiRong() {
    ProfileSummary dang = profile(UUID.randomUUID(), "DANG");
    when(profiles.summary(TENANT, dang.id())).thenReturn(Optional.of(dang));
    when(snapshots.sibling(TENANT, 20271, PERIOD, PERSON, "NHA_NUOC")).thenReturn(Optional.empty());

    assertThat(service.view(TENANT, dang.id(), "NHA_NUOC", PERSON, x -> false)).isEmpty();
    assertThat(service.view(TENANT, UUID.randomUUID(), null, PERSON, x -> true)).isEmpty();
  }

  @Test
  void ngoaiPhamViBiTuChoi() {
    ProfileSummary p = profile(UUID.randomUUID(), "DANG");
    when(profiles.summary(TENANT, p.id())).thenReturn(Optional.of(p));
    when(snapshots.current(TENANT, 20271, p.id()))
        .thenReturn(Optional.of(snapshot(p.id(), "DANG")));

    assertThatThrownBy(() -> service.view(TENANT, p.id(), null, UUID.randomUUID(), x -> false))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThat(service.view(TENANT, p.id(), null, UUID.randomUUID(), x -> true)).isPresent();
  }

  @Test
  void chuaTinhThi409() {
    ProfileSummary p = profile(UUID.randomUUID(), "DANG");
    when(profiles.summary(TENANT, p.id())).thenReturn(Optional.of(p));
    when(snapshots.current(TENANT, 20271, p.id())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.view(TENANT, p.id(), null, PERSON, x -> false))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("APPRAISAL-409-NOT-COMPUTED"));
  }

  static ProfileSummary profile(UUID id, String flow) {
    return new ProfileSummary(
        id,
        PERIOD,
        20271,
        PERSON,
        UUID.randomUUID(),
        flow,
        AppraisalStatus.CHO_THAM_DINH,
        false,
        false,
        null,
        RatingLevel.HTTNV,
        null,
        null,
        null,
        0,
        false,
        null,
        null,
        null,
        1L);
  }

  static Snapshot snapshot(UUID profileId, String flow) {
    return new Snapshot(
        UUID.randomUUID(),
        profileId,
        flow,
        "CHOT_KY",
        UUID.randomUUID(),
        null,
        "1.0.0",
        null,
        new byte[] {1, 2},
        new byte[] {3, 4},
        new BigDecimal("85.50"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "HTTNV",
        "[]",
        "[]",
        "{}",
        null,
        Instant.parse("2027-04-05T00:00:00Z"),
        null);
  }
}
