package vn.viettel.okrkpi.appraisal.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService.Transitioned;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.AppraisalDraft;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.Prepared;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewNotices;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.AppraisalSign;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueFilter;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueRow;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.CurrentRating;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.ProfileCommand;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.profile.StepCompleted;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Dịch vụ thẩm định, đề xuất với cổng giả (CORE-04-05). */
class ReviewServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2027-04-01T00:00:00Z"), ZoneOffset.UTC);
  private static final String REASON = "Kết quả nổi bật của quý được đơn vị ghi nhận rõ ràng.";

  private final UUID id = UUID.randomUUID();
  private final UUID person = UUID.randomUUID();
  private final UUID acceptor = UUID.randomUUID();
  private final UUID appraiser = UUID.randomUUID();
  private final UUID manager = UUID.randomUUID();
  private final AppraisalProfileRepository profiles = mock(AppraisalProfileRepository.class);
  private final SelfAssessmentRepository forms = mock(SelfAssessmentRepository.class);
  private final ReviewRepository reviews = mock(ReviewRepository.class);
  private final AppraisalProfileService steps = mock(AppraisalProfileService.class);
  private final ReviewNotices notices = mock(ReviewNotices.class);
  private final AtomicBoolean skip = new AtomicBoolean();
  private ReviewService service;

  @BeforeEach
  void setUp() {
    service =
        new ReviewService(
            profiles,
            forms,
            reviews,
            steps,
            new PlainSealer(),
            (t, period, who) -> Set.of(acceptor),
            notices,
            (t, row) -> skip.get(),
            CLOCK);
    when(forms.summary(TENANT, id)).thenReturn(Optional.of(summary(AppraisalStatus.CHO_THAM_DINH)));
    when(profiles.lock(TENANT, id))
        .thenReturn(Optional.of(row(AppraisalStatus.CHO_THAM_DINH, 4L, List.of())));
    when(forms.touchProfile(any(), any(), any(), any())).thenReturn(Optional.of(5L));
    when(steps.transition(any(), any(), any(), any(), any()))
        .thenReturn(
            new Transitioned(
                new StepCompleted(
                    ProfileCommand.APPRAISE,
                    AppraisalStatus.CHO_THAM_DINH,
                    AppraisalStatus.DA_THAM_DINH,
                    LevelKind.THAM_DINH,
                    appraiser,
                    null),
                5L));
  }

  @Test
  void hangDoiGanMucHienHanh() {
    QueueFilter filter =
        new QueueFilter(null, null, null, true, Set.of("CHO_THAM_DINH"), null, null);
    when(reviews.queue(TENANT, filter, Scope.NONE, null, 10)).thenReturn(List.of());
    assertThat(service.queue(TENANT, filter, Scope.NONE, null, 10)).isEmpty();
    Scope scope = new Scope("p.org_unit_id = ?", List.of(UUID.randomUUID()));
    when(reviews.queue(TENANT, filter, scope, null, 10))
        .thenReturn(List.of(new QueueRow(summary(AppraisalStatus.CHO_THAM_DINH), "A", "B")));
    when(reviews.currentLevels(TENANT, Set.of(id)))
        .thenReturn(Map.of(id, Map.of(LevelKind.TU_DE_XUAT, RatingLevel.HTNV)));
    assertThat(service.queue(TENANT, filter, scope, null, 10))
        .singleElement()
        .satisfies(
            i -> assertThat(i.levels()).containsEntry(LevelKind.TU_DE_XUAT, RatingLevel.HTNV));
  }

  @Test
  void nguoiThamGiaGomNguoiNghiemThu() {
    ReviewParticipants p = service.participants(TENANT, id).orElseThrow();
    assertThat(p.acceptors()).containsExactly(acceptor);
    assertThat(p.mayAppraise(appraiser)).isTrue();
    assertThat(p.mayAppraise(acceptor)).isFalse();
    assertThat(p.mayAppraise(person)).isFalse();
    assertThat(p.mayPropose(manager)).isTrue();
    assertThat(p.toAuthzResource().participants())
        .containsEntry("acceptor", List.of(acceptor.toString()))
        .containsEntry("appraiser", List.of());
    ReviewService noAcceptors =
        new ReviewService(
            profiles, forms, reviews, steps, new PlainSealer(), null, notices, null, CLOCK);
    assertThat(noAcceptors.participants(TENANT, id).orElseThrow().acceptors()).isEmpty();
  }

  @Test
  void traLaiKiemTachVaiTroVaBaoNguoiDuocDanhGia() {
    assertThatThrownBy(() -> service.returnProfile(TENANT, id, 4L, acceptor, REASON))
        .hasMessage("APPRAISAL-403-SEPARATION-OF-DUTIES");
    when(steps.returnForRevision(TENANT, id, 4L, appraiser, REASON))
        .thenReturn(new Transitioned(null, 5L));
    service.returnProfile(TENANT, id, 4L, appraiser, REASON);
    verify(notices).returned(TENANT, id, person, 1, 5L);
    when(forms.summary(TENANT, id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.returnProfile(TENANT, id, 4L, appraiser, REASON))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void ghiKetLuanThamDinhChoKy() {
    UUID proposal = UUID.randomUUID();
    when(steps.recordRating(
            eq(TENANT),
            eq(id),
            isNull(),
            eq(LevelKind.THAM_DINH),
            eq(RatingLevel.HTXSNV),
            eq(appraiser),
            eq("appraiser"),
            any(),
            any(),
            any()))
        .thenReturn(current(proposal, LevelKind.THAM_DINH, RatingLevel.HTXSNV, true));
    when(reviews.selfCriteria(TENANT, 20271, id))
        .thenReturn(List.of(new SelfAssessmentForm.Criterion("TCC-01", true)));
    Prepared p =
        service.prepareAppraisal(
            TENANT, id, 4L, appraiser, new AppraisalDraft(RatingLevel.HTXSNV, "Tốt", REASON, null));
    assertThat(p.signRequestId()).isNull();
    verify(steps)
        .recordExplanation(
            eq(TENANT),
            eq(id),
            eq(LevelKind.THAM_DINH),
            eq("KHAC_MUC_TRUOC"),
            any(),
            any(),
            any(),
            eq(appraiser));
    verify(reviews).replaceAppraisalCriteria(eq(TENANT), any(), any(), any(), any(), any(), any());

    // Không có tiêu chí nào thì không ghi tiêu chí; khác mức gợi ý nhưng có lý do vẫn được.
    when(reviews.selfCriteria(TENANT, 20271, id)).thenReturn(List.of());
    when(steps.recordRating(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(current(proposal, LevelKind.THAM_DINH, RatingLevel.HTTNV, false));
    service.prepareAppraisal(
        TENANT, id, 4L, appraiser, new AppraisalDraft(RatingLevel.HTTNV, "Tốt", null, null));
    verify(reviews, times(1))
        .replaceAppraisalCriteria(any(), any(), any(), any(), any(), any(), any());

    UUID sign = UUID.randomUUID();
    service.attachSignRequest(TENANT, p, sign);
    verify(reviews).attachSignRequest(eq(TENANT), eq(20271), eq(p.proposalId()), eq(sign), any());

    when(forms.touchProfile(any(), any(), any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.prepareAppraisal(
                    TENANT,
                    id,
                    4L,
                    appraiser,
                    new AppraisalDraft(RatingLevel.HTTNV, "Tốt", null, null)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void ketLuanThamDinhBiTuChoi() {
    AppraisalDraft ok = new AppraisalDraft(RatingLevel.HTTNV, "Tốt", null, null);
    assertThatThrownBy(() -> service.prepareAppraisal(TENANT, id, 3L, appraiser, ok))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.prepareAppraisal(TENANT, id, 4L, acceptor, ok))
        .hasMessage("APPRAISAL-403-SEPARATION-OF-DUTIES");
    assertThatThrownBy(
            () ->
                service.prepareAppraisal(
                    TENANT, id, 4L, appraiser, new AppraisalDraft(null, "Tốt", null, null)))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () ->
                service.prepareAppraisal(
                    TENANT,
                    id,
                    4L,
                    appraiser,
                    new AppraisalDraft(RatingLevel.HTTNV, " ", null, null)))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () ->
                service.prepareAppraisal(
                    TENANT,
                    id,
                    4L,
                    appraiser,
                    new AppraisalDraft(RatingLevel.HTNV, "Tốt", null, null)))
        .hasMessage("APPRAISAL-422-REASON-REQUIRED");
    verify(steps, never())
        .recordRating(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    when(profiles.lock(TENANT, id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.prepareAppraisal(TENANT, id, 4L, appraiser, ok))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void guiLaiKhiDangChoKyTraYeuCauCu() {
    UUID sign = UUID.randomUUID();
    AppraisalSign pending = sign(sign, null, appraiser, true);
    when(reviews.currentAppraisal(TENANT, 20271, id)).thenReturn(Optional.of(pending));
    assertThat(
            service
                .prepareAppraisal(
                    TENANT,
                    id,
                    0L,
                    appraiser,
                    new AppraisalDraft(RatingLevel.HTTNV, "x", null, null))
                .signRequestId())
        .isEqualTo(sign);
    // Người khác thì phải qua kiểm phiên bản.
    assertThatThrownBy(
            () ->
                service.prepareAppraisal(
                    TENANT,
                    id,
                    0L,
                    manager,
                    new AppraisalDraft(RatingLevel.HTTNV, "x", null, null)))
        .isInstanceOf(VersionConflictException.class);

    ReviewService.SignSource source = service.signSource(TENANT, id).orElseThrow();
    assertThat(source.ready()).isTrue();
    assertThat(source.signer()).isEqualTo(appraiser);
    assertThat(source.contentHash()).hasSize(32);
    when(reviews.currentAppraisal(TENANT, 20271, id)).thenReturn(Optional.empty());
    assertThat(service.signSource(TENANT, id).orElseThrow().ready()).isFalse();

    when(reviews.appraisalBySignRequest(TENANT, sign)).thenReturn(Optional.of(pending));
    assertThat(service.signJob(TENANT, sign).orElseThrow().signed()).isFalse();
  }

  @Test
  void nhanEvt18ChuyenDaThamDinhVaBoQuaDeXuat() {
    UUID sign = UUID.randomUUID();
    Instant at = CLOCK.instant();
    when(reviews.appraisalBySignRequest(TENANT, sign)).thenReturn(Optional.empty());
    assertThat(service.onAppraisalSigned(TENANT, sign, at)).isFalse();
    when(reviews.appraisalBySignRequest(TENANT, sign))
        .thenReturn(Optional.of(sign(sign, at, appraiser, true)));
    assertThat(service.onAppraisalSigned(TENANT, sign, at)).isFalse();

    when(reviews.appraisalBySignRequest(TENANT, sign))
        .thenReturn(Optional.of(sign(sign, null, appraiser, true)));
    assertThat(service.onAppraisalSigned(TENANT, sign, at)).isTrue();
    verify(steps, times(1)).transition(eq(TENANT), eq(id), isNull(), eq(appraiser), any());
    skip.set(true);
    assertThat(service.onAppraisalSigned(TENANT, sign, at)).isTrue();
    verify(steps, times(3)).transition(eq(TENANT), eq(id), isNull(), eq(appraiser), any());

    when(reviews.appraisalBySignRequest(TENANT, sign))
        .thenReturn(Optional.of(sign(sign, null, appraiser, false)));
    assertThat(service.onAppraisalSigned(TENANT, sign, at)).isFalse();
    when(profiles.lock(TENANT, id))
        .thenReturn(Optional.of(row(AppraisalStatus.DA_THAM_DINH, 5L, List.of())));
    when(reviews.appraisalBySignRequest(TENANT, sign))
        .thenReturn(Optional.of(sign(sign, null, appraiser, true)));
    assertThat(service.onAppraisalSigned(TENANT, sign, at)).isFalse();
  }

  @Test
  void deXuatKhacMucThamDinhPhaiCoLyDo() {
    UUID p = UUID.randomUUID();
    when(profiles.lock(TENANT, id))
        .thenReturn(
            Optional.of(
                row(
                    AppraisalStatus.DA_THAM_DINH,
                    6L,
                    List.of(current(p, LevelKind.THAM_DINH, RatingLevel.HTTNV, false)))));
    when(forms.summary(TENANT, id)).thenReturn(Optional.of(summary(AppraisalStatus.DA_THAM_DINH)));
    assertThatThrownBy(() -> service.propose(TENANT, id, 5L, manager, RatingLevel.HTNV, null, null))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () -> service.propose(TENANT, id, 6L, appraiser, RatingLevel.HTNV, null, null))
        .hasMessage("APPRAISAL-403-SEPARATION-OF-DUTIES");
    assertThatThrownBy(() -> service.propose(TENANT, id, 6L, manager, null, null, null))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.propose(TENANT, id, 6L, manager, RatingLevel.HTNV, null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessage("APPRAISAL-422-REASON-REQUIRED")
        .satisfies(e -> assertThat(((BusinessException) e).msg()).isEqualTo("MSG-09-12"));

    when(steps.recordRating(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(current(UUID.randomUUID(), LevelKind.DE_XUAT, RatingLevel.HTNV, true));
    service.propose(TENANT, id, 6L, manager, RatingLevel.HTNV, REASON, "Đồng ý");
    verify(steps)
        .recordExplanation(
            eq(TENANT),
            eq(id),
            eq(LevelKind.DE_XUAT),
            eq("HA_MUC"),
            any(),
            any(),
            any(),
            eq(manager));

    when(steps.recordRating(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(current(UUID.randomUUID(), LevelKind.DE_XUAT, RatingLevel.HTTNV, false));
    service.propose(TENANT, id, 6L, manager, RatingLevel.HTTNV, null, null);
    verify(steps, times(2)).transition(eq(TENANT), eq(id), isNull(), eq(manager), any());
  }

  @Test
  void lyDoTheoMucGoiYVaLoaiGiaiTrinh() {
    assertThatThrownBy(
            () ->
                ReviewService.requireReasons(
                    RatingLevel.HTXSNV, null, null, RatingLevel.HTTNV, "m", "MSG-09-06"))
        .hasMessage("APPRAISAL-422-SUGGESTED-REASON-REQUIRED");
    ReviewService.requireReasons(RatingLevel.HTTNV, null, null, null, "m", "MSG-09-06");
    ReviewService.requireReasons(RatingLevel.HTXSNV, RatingLevel.HTNV, REASON, null, "m", null);
    assertThat(ReviewService.explanationType(RatingLevel.KHTNV, RatingLevel.HTNV))
        .isEqualTo("HA_MUC");
    assertThat(ReviewService.explanationType(RatingLevel.HTXSNV, RatingLevel.HTNV))
        .isEqualTo("KHAC_MUC_TRUOC");
    assertThat(ReviewService.explanationType(RatingLevel.HTXSNV, null)).isEqualTo("KHAC_MUC_TRUOC");
  }

  @Test
  void dongMucChoKy() {
    UUID sign = UUID.randomUUID();
    assertThat(sign(sign, null, appraiser, true).pending()).isTrue();
    assertThat(sign(sign, CLOCK.instant(), appraiser, true).pending()).isFalse();
    assertThat(sign(null, null, appraiser, true).pending()).isFalse();
    assertThat(new Scope("1 = 1", List.of()).params()).isEmpty();
  }

  // ---------------------------------------------------------------- hỗ trợ

  private ProfileSummary summary(AppraisalStatus status) {
    return new ProfileSummary(
        id,
        UUID.randomUUID(),
        20271,
        person,
        UUID.randomUUID(),
        "DANG",
        status,
        false,
        false,
        UUID.randomUUID(),
        RatingLevel.HTTNV,
        null,
        status == AppraisalStatus.DA_THAM_DINH ? appraiser : null,
        null,
        0,
        false,
        null,
        null,
        null,
        4L);
  }

  private ProfileRow row(AppraisalStatus status, long version, List<CurrentRating> ratings) {
    return new ProfileRow(
        id,
        UUID.randomUUID(),
        20271,
        person,
        "DANG",
        status,
        null,
        null,
        UUID.randomUUID(),
        RatingLevel.HTTNV,
        null,
        0,
        UUID.randomUUID(),
        version,
        ratings);
  }

  private CurrentRating current(UUID proposal, LevelKind kind, RatingLevel r, boolean differs) {
    return new CurrentRating(proposal, kind, r, appraiser, differs, differs, false);
  }

  private AppraisalSign sign(UUID signRequest, Instant signedAt, UUID actor, boolean current) {
    return new AppraisalSign(
        UUID.randomUUID(),
        id,
        20271,
        RatingLevel.HTTNV,
        actor,
        current,
        null,
        new SealedText("Tốt".getBytes(StandardCharsets.UTF_8), UUID.randomUUID()),
        signRequest,
        signedAt);
  }

  /** Niêm phong giả: giữ nguyên byte để kiểm băm. */
  static final class PlainSealer implements TextSealer {
    @Override
    public SealedText seal(TenantId tenant, String column, UUID rowId, String plain) {
      return plain == null ? null : new SealedText(plain.getBytes(StandardCharsets.UTF_8), rowId);
    }

    @Override
    public String open(TenantId tenant, String column, UUID rowId, SealedText sealed) {
      return sealed == null ? null : new String(sealed.cipher(), StandardCharsets.UTF_8);
    }
  }
}
