package vn.viettel.okrkpi.appraisal.application.selfassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalProfile;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.CurrentRating;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingRecorded;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.CriteriaLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.config.api.FormValidationApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dịch vụ phiếu tự đánh giá với kho giả trong bộ nhớ (CORE-04-04): lưu nháp và xung đột phiên bản,
 * chốt phiếu để ký, nộp lại không mở yêu cầu ký thứ hai, chỉ chuyển bước khi nhận EVT-18.
 */
class SelfAssessmentServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2027-04-01T00:00:00Z"), ZoneOffset.UTC);
  private static final String REASON = "Đơn vị ghi nhận giải pháp số hoá báo cáo tuần của tôi.";

  private final UUID person = UUID.randomUUID();
  private final UUID appraiser = UUID.randomUUID();
  private final UUID decider = UUID.randomUUID();
  private final Profiles profiles = new Profiles();
  private final Forms forms = new Forms();
  private final List<String> notices = new ArrayList<>();
  private final List<UUID> signCalls = new ArrayList<>();
  private Set<UUID> confirmed = Set.of();
  private FormValidationApi validator;
  private SelfAssessmentService service;

  @BeforeEach
  void setUp() {
    profiles.row = row(AppraisalStatus.TU_DANH_GIA, 3L, List.of());
    service = service();
  }

  private SelfAssessmentService service() {
    return new SelfAssessmentService(
        profiles,
        forms,
        new AppraisalProfileService(profiles, (t, id, step, v) -> {}, null, CLOCK),
        new ReverseSealer(),
        validator,
        (t, person, ids) -> confirmed,
        (t, profileId, personId, signedAt, version) -> notices.add(profileId + "@" + version),
        new ObjectMapper(),
        CLOCK);
  }

  @Test
  void hoSoChuaCoTieuChiTraDanhMucMacDinhChuaDanhDau() {
    List<SelfAssessmentRepository.CriteriaRow> rows =
        SelfAssessmentService.withDefaultCriteria(List.of());
    assertThat(rows).hasSize(9);
    assertThat(rows).allMatch(r -> "TU_DANH_GIA".equals(r.stage()) && !r.met());
    assertThat(rows.get(0).code()).isEqualTo("TCC-01");
    assertThat(rows.get(0).level()).isEqualTo(CriteriaLevel.KHONG_DAM_BAO);

    List<SelfAssessmentRepository.CriteriaRow> stored =
        List.of(
            new SelfAssessmentRepository.CriteriaRow(
                "TU_DANH_GIA", "TCC-01", BigDecimal.ONE, CriteriaLevel.THUC_HIEN_TOT, true));
    assertThat(SelfAssessmentService.withDefaultCriteria(stored)).isEqualTo(stored);
  }

  @Test
  void luuNhapMaHoaVaQuyDoiTieuChi() {
    SelfAssessmentService.View v = service.save(TENANT, id(), 3L, person, form(RatingLevel.HTNV));
    assertThat(v.form().content()).isEqualTo("Hoàn thành nhiệm vụ trọng tâm.");
    assertThat(v.form().reason()).isEqualTo(REASON);
    assertThat(v.form().criteria()).hasSize(2);
    assertThat(v.form().claims()).hasSize(1);
    SelfAssessmentRepository.StoredSelfAssessment s = forms.stored;
    assertThat(new String(s.content().cipher(), StandardCharsets.UTF_8))
        .doesNotContain("Hoàn thành");
    assertThat(s.formCode()).isEqualTo(SelfAssessmentService.FORM_KEY);
    assertThat(s.formVersion()).isEqualTo("1.0");
    assertThat(forms.level).isEqualTo(CriteriaLevel.KHONG_DAM_BAO);
    assertThat(forms.ratio).isEqualByComparingTo("0.5");
    assertThat(profiles.row.rowVersion()).isEqualTo(4L);

    // Lưu lần hai với phiên bản cũ: xung đột; với phiên bản mới: ghi đè cùng dòng.
    assertThatThrownBy(() -> service.save(TENANT, id(), 3L, person, form(RatingLevel.HTNV)))
        .isInstanceOf(VersionConflictException.class);
    service.save(TENANT, id(), 4L, person, form(RatingLevel.HTTNV));
    assertThat(forms.inserts).isEqualTo(1);
    assertThat(forms.stored.versionNo()).isEqualTo(1);
  }

  @Test
  void luuNhapBiTuChoiKhiKhongPhaiChuHoSoHoacMauLoi() {
    assertThatThrownBy(() -> service.save(TENANT, id(), 3L, appraiser, form(RatingLevel.HTNV)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.save(TENANT, UUID.randomUUID(), 3L, person, form(null)))
        .isInstanceOf(NoSuchElementException.class);
    SelfAssessmentForm tooLong =
        new SelfAssessmentForm(
            null, null, null, null, null, "x".repeat(2001), null, null, null, null);
    assertThatThrownBy(() -> service.save(TENANT, id(), 3L, person, tooLong))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void bieuMauDongCoCauHinhThiKiemVaGhiPhienBan() {
    validator =
        (t, key, version, payload) ->
            payload.containsKey("content")
                ? new FormValidationApi.Result(List.of(), Set.of(), "2.1")
                : new FormValidationApi.Result(
                    List.of(new FormValidationApi.Violation("content", "Bắt buộc", "x-cel")),
                    Set.of(),
                    "2.1");
    service = service();
    service.save(TENANT, id(), 3L, person, form(RatingLevel.HTNV));
    assertThat(forms.stored.formVersion()).isEqualTo("2.1");
    assertThatThrownBy(() -> service.save(TENANT, id(), 4L, person, SelfAssessmentForm.empty()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("COMMON-422-VALIDATION"));
    validator =
        (t, key, version, payload) -> new FormValidationApi.Result(List.of(), Set.of(), null);
    service = service();
    service.save(TENANT, id(), 4L, person, form(RatingLevel.HTNV));
    assertThat(forms.stored.formVersion()).isEqualTo("1.0");
  }

  @Test
  void nopChiChuyenBuocSauEvt18VaNopLaiKhongMoYeuCauThuHai() {
    service.save(TENANT, id(), 3L, person, form(RatingLevel.HTNV));
    SelfAssessmentSubmission submission =
        new SelfAssessmentSubmission(
            service,
            (t, profileId, provider, key, auth) -> {
              UUID sr = UUID.randomUUID();
              signCalls.add(sr);
              return sr;
            });
    UUID first = submission.submit(TENANT, id(), 4L, person, null, null, "k1", "Bearer x");
    assertThat(profiles.row.status()).isEqualTo(AppraisalStatus.TU_DANH_GIA);
    assertThat(forms.stored.pending()).isTrue();
    // Gửi lại (kể cả phiên bản cũ): trả yêu cầu cũ, không gọi SVC-10 lần nữa.
    assertThat(submission.submit(TENANT, id(), 4L, person, null, null, "k2", null))
        .isEqualTo(first);
    assertThat(signCalls).containsExactly(first);
    // Đang chờ ký thì không sửa được.
    assertThatThrownBy(() -> service.save(TENANT, id(), 5L, person, form(RatingLevel.HTNV)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("APPRAISAL-409-INVALID-STATUS"));
    assertThat(service.signSource(TENANT, id()).orElseThrow().ready()).isTrue();
    assertThat(service.signJob(TENANT, first).orElseThrow().signed()).isFalse();

    Instant signedAt = Instant.parse("2027-04-01T01:00:00Z");
    assertThat(service.onSigned(TENANT, first, signedAt)).isTrue();
    assertThat(profiles.row.status()).isEqualTo(AppraisalStatus.CHO_THAM_DINH);
    assertThat(profiles.inserted)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.rating().levelKind()).isEqualTo(LevelKind.TU_DE_XUAT);
              assertThat(r.rating().rating()).isEqualTo(RatingLevel.HTNV);
            });
    assertThat(profiles.explanationTypes).containsExactly("HA_MUC");
    assertThat(profiles.reasons).hasSize(1);
    assertThat(notices).hasSize(1);
    assertThat(service.signJob(TENANT, first).orElseThrow().signed()).isTrue();
    // EVT-18 lặp: bỏ qua.
    assertThat(service.onSigned(TENANT, first, signedAt)).isFalse();
    assertThat(service.onSigned(TENANT, UUID.randomUUID(), signedAt)).isFalse();
    assertThat(service.signSource(TENANT, id()).orElseThrow().ready()).isFalse();
  }

  @Test
  void moYeuCauKyLoiThiGiuDuLieuVaBaoMsg0905() {
    service.save(TENANT, id(), 3L, person, form(RatingLevel.HTNV));
    SelfAssessmentSubmission failing =
        new SelfAssessmentSubmission(
            service,
            (t, profileId, provider, key, auth) -> {
              throw new IllegalStateException("mất kết nối");
            });
    assertThatThrownBy(() -> failing.submit(TENANT, id(), 4L, person, "MYSIGN", null, "k", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("ESIGN-422-PROVIDER-UNAVAILABLE"));
    assertThat(forms.stored.pending()).isFalse();
    assertThat(forms.stored.content()).isNotNull();

    SelfAssessmentSubmission stepUp =
        new SelfAssessmentSubmission(
            service,
            (t, profileId, provider, key, auth) -> {
              throw new BusinessException("ESIGN-403-NOT-SIGNER");
            });
    long version = profiles.row.rowVersion();
    assertThatThrownBy(() -> stepUp.submit(TENANT, id(), version, person, null, null, "k", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("ESIGN-403-NOT-SIGNER"));
    assertThat(forms.stored.pending()).isFalse();
    SelfAssessmentSubmission relayed =
        new SelfAssessmentSubmission(
            service,
            (t, profileId, provider, key, auth) -> {
              throw new SignRequests.Relayed(new IllegalArgumentException("401"));
            });
    long v2 = profiles.row.rowVersion();
    assertThatThrownBy(() -> relayed.submit(TENANT, id(), v2, person, null, null, "k", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(forms.stored.pending()).isFalse();
  }

  @Test
  void nopKiemQuyTacVaBamCuaClient() {
    assertThatThrownBy(() -> service.prepareSubmit(TENANT, id(), 3L, person, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("COMMON-422-VALIDATION"));
    SelfAssessmentForm noReason =
        new SelfAssessmentForm(
            criteria(), RatingLevel.HTNV, null, null, null, "Nội dung", null, null, null, null);
    service.save(TENANT, id(), 3L, person, noReason);
    assertThatThrownBy(() -> service.prepareSubmit(TENANT, id(), 4L, person, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("APPRAISAL-422-REASON-REQUIRED"));
    UUID evidence = UUID.randomUUID();
    SelfAssessmentForm excellent =
        new SelfAssessmentForm(
            criteria(),
            RatingLevel.HTXSNV,
            "Số hoá báo cáo",
            List.of(evidence),
            REASON,
            "Nội dung",
            null,
            null,
            null,
            null);
    service.save(TENANT, id(), 4L, person, excellent);
    assertThatThrownBy(() -> service.prepareSubmit(TENANT, id(), 5L, person, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("APPRAISAL-422-BREAKTHROUGH-REQUIRED"));
    confirmed = Set.of(evidence);
    service = service();
    assertThatThrownBy(() -> service.prepareSubmit(TENANT, id(), 5L, person, new byte[32]))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("APPRAISAL-409-INVALID-STATUS"));
    assertThatThrownBy(() -> service.prepareSubmit(TENANT, id(), 9L, person, null))
        .isInstanceOf(VersionConflictException.class);
    SelfAssessmentService.Prepared p = service.prepareSubmit(TENANT, id(), 5L, person, null);
    assertThat(p.signRequestId()).isNull();
    assertThat(SelfAssessmentService.explanationType(RatingLevel.HTXSNV, RatingLevel.HTTNV))
        .isEqualTo("KHAC_MUC_TRUOC");
  }

  @Test
  void docHoSoTheoNguoiDuocXem() {
    assertThat(service.view(TENANT, id(), person)).isPresent();
    assertThat(service.view(TENANT, id(), decider).orElseThrow().stored()).isNull();
    assertThatThrownBy(() -> service.view(TENANT, id(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThat(service.view(TENANT, UUID.randomUUID(), person)).isEmpty();
    assertThat(service.mine(TENANT, person, null, null, null, 20)).hasSize(1);
    assertThat(service.signSource(TENANT, id()).orElseThrow().ready()).isFalse();
  }

  @Test
  void quaHanChuyenThamDinhVoiCoKhongTuDanhGia() {
    service.skipOverdue(TENANT, id(), appraiser);
    assertThat(profiles.row.status()).isEqualTo(AppraisalStatus.CHO_THAM_DINH);
    assertThat(forms.missing).isTrue();
    assertThat(profiles.inserted).isEmpty();
    // Đã rời bước tự đánh giá: không làm gì.
    service.skipOverdue(TENANT, id(), appraiser);
    assertThatThrownBy(() -> service.skipOverdue(TENANT, UUID.randomUUID(), appraiser))
        .isInstanceOf(NoSuchElementException.class);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private UUID id() {
    return profiles.row.id();
  }

  private static List<SelfAssessmentForm.Criterion> criteria() {
    return List.of(
        new SelfAssessmentForm.Criterion("TCC-01", true),
        new SelfAssessmentForm.Criterion("TCC-02", false));
  }

  private static SelfAssessmentForm form(RatingLevel rating) {
    return new SelfAssessmentForm(
        criteria(),
        rating,
        null,
        null,
        REASON,
        "Hoàn thành nhiệm vụ trọng tâm.",
        "Chủ động",
        "Còn chậm một báo cáo",
        "Lập kế hoạch tuần",
        List.of(Map.of("taskId", UUID.randomUUID().toString())));
  }

  private ProfileRow row(AppraisalStatus status, long version, List<CurrentRating> ratings) {
    UUID id = profiles.row == null ? UUID.randomUUID() : profiles.row.id();
    return new ProfileRow(
        id,
        UUID.randomUUID(),
        20271,
        person,
        "DANG",
        status,
        appraiser,
        decider,
        UUID.randomUUID(),
        RatingLevel.HTTNV,
        null,
        0,
        UUID.randomUUID(),
        version,
        ratings);
  }

  /** "Mã hoá" đảo ngược byte, đủ để kiểm cột không chứa văn bản rõ. */
  private static final class ReverseSealer implements TextSealer {
    private final UUID dek = UUID.randomUUID();

    @Override
    public SealedText seal(TenantId tenant, String column, UUID rowId, String plain) {
      if (plain == null || plain.isEmpty()) {
        return null;
      }
      byte[] b = plain.getBytes(StandardCharsets.UTF_8);
      byte[] r = new byte[b.length];
      for (int i = 0; i < b.length; i++) {
        r[i] = (byte) ~b[b.length - 1 - i];
      }
      return new SealedText(r, dek);
    }

    @Override
    public String open(TenantId tenant, String column, UUID rowId, SealedText sealed) {
      if (sealed == null) {
        return null;
      }
      byte[] r = sealed.cipher();
      byte[] b = new byte[r.length];
      for (int i = 0; i < r.length; i++) {
        b[i] = (byte) ~r[r.length - 1 - i];
      }
      return new String(b, StandardCharsets.UTF_8);
    }
  }

  /** Kho hồ sơ giả: một dòng, cập nhật trạng thái, phiên bản và mức hiện hành. */
  private final class Profiles implements AppraisalProfileRepository {
    ProfileRow row;
    final List<RatingRecorded> inserted = new ArrayList<>();
    final List<SealedText> reasons = new ArrayList<>();
    final List<String> explanationTypes = new ArrayList<>();

    @Override
    public Optional<ProfileRow> lock(TenantId tenant, UUID profileId) {
      return row.id().equals(profileId) ? Optional.of(row) : Optional.empty();
    }

    @Override
    public Optional<Long> save(
        TenantId tenant, ProfileRow r, AppraisalProfile p, UUID actorId, Instant at) {
      row = with(p.status(), r.rowVersion() + 1, r.currentRatings());
      return Optional.of(row.rowVersion());
    }

    @Override
    public void supersedeRating(
        TenantId tenant, int periodBucket, UUID proposalId, UUID actorId, Instant at) {}

    @Override
    public void insertRating(
        TenantId tenant,
        ProfileRow r,
        UUID snapshotId,
        RatingRecorded recorded,
        SealedText reason,
        SealedText comment,
        Instant at) {
      inserted.add(recorded);
      if (reason != null) {
        reasons.add(reason);
      }
      List<CurrentRating> ratings = new ArrayList<>(row.currentRatings());
      ratings.removeIf(c -> c.levelKind() == recorded.rating().levelKind());
      ratings.add(recorded.rating());
      row = with(row.status(), row.rowVersion(), ratings);
    }

    @Override
    public void insertExplanation(
        TenantId tenant,
        ProfileRow r,
        UUID explanationId,
        UUID proposalId,
        String explanationType,
        String autoText,
        String legalRef,
        SealedText explanation,
        UUID actorId,
        Instant at) {
      explanationTypes.add(explanationType);
      List<CurrentRating> ratings = new ArrayList<>();
      for (CurrentRating c : row.currentRatings()) {
        ratings.add(
            c.proposalId().equals(proposalId)
                ? new CurrentRating(
                    c.proposalId(),
                    c.levelKind(),
                    c.rating(),
                    c.actorId(),
                    c.explanationRequired(),
                    c.hasReason(),
                    true)
                : c);
      }
      row = with(row.status(), row.rowVersion(), ratings);
    }

    private ProfileRow with(AppraisalStatus status, long version, List<CurrentRating> ratings) {
      ProfileRow r = row;
      return new ProfileRow(
          r.id(),
          r.periodId(),
          r.periodBucket(),
          r.personId(),
          r.flow(),
          status,
          r.appraiserId(),
          r.deciderId(),
          r.currentSnapshotId(),
          r.autoRating(),
          r.finalRating(),
          r.returnCount(),
          r.policyPackVersionId(),
          version,
          ratings);
    }
  }

  /** Kho phiếu giả: một phiếu hiện hành của hồ sơ. */
  private final class Forms implements SelfAssessmentRepository {
    StoredSelfAssessment stored;
    int inserts;
    BigDecimal ratio;
    CriteriaLevel level;
    boolean missing;

    @Override
    public Optional<StoredSelfAssessment> current(TenantId tenant, int bucket, UUID profileId) {
      return Optional.ofNullable(stored);
    }

    @Override
    public void insert(
        TenantId tenant, ProfileRow profile, StoredSelfAssessment d, UUID actor, Instant at) {
      inserts++;
      stored = copy(d, null, null, null, null, at);
    }

    @Override
    public void update(
        TenantId tenant, int bucket, StoredSelfAssessment d, UUID actor, Instant at) {
      stored = copy(d, null, null, null, null, at);
    }

    @Override
    public void replaceCriteria(
        TenantId tenant,
        ProfileRow profile,
        List<SelfAssessmentForm.Criterion> criteria,
        BigDecimal ratio,
        CriteriaLevel level,
        UUID actor,
        Instant at) {
      this.ratio = ratio;
      this.level = level;
    }

    @Override
    public Optional<Long> touchProfile(
        TenantId tenant, ProfileRow profile, UUID actor, Instant at) {
      if (profiles.row.rowVersion() != profile.rowVersion()) {
        return Optional.empty();
      }
      profiles.row =
          profiles.with(
              profiles.row.status(), profiles.row.rowVersion() + 1, profiles.row.currentRatings());
      return Optional.of(profiles.row.rowVersion());
    }

    @Override
    public void flagMissing(TenantId tenant, ProfileRow profile) {
      missing = true;
    }

    @Override
    public void markPending(TenantId tenant, int bucket, UUID id, byte[] contentHash, Instant at) {
      stored = copy(stored, contentHash, at, null, null, at);
    }

    @Override
    public void attachSignRequest(
        TenantId tenant, int bucket, UUID id, UUID signRequestId, Instant at) {
      stored = copy(stored, stored.contentHash(), stored.submittedAt(), signRequestId, null, at);
    }

    @Override
    public void clearPending(TenantId tenant, int bucket, UUID id, Instant at) {
      stored = copy(stored, null, null, null, null, at);
    }

    @Override
    public void markSigned(TenantId tenant, int bucket, UUID id, Instant signedAt, Instant at) {
      stored =
          copy(
              stored,
              stored.contentHash(),
              stored.submittedAt(),
              stored.signRequestId(),
              signedAt,
              at);
    }

    @Override
    public Optional<SignTarget> findBySignRequest(TenantId tenant, UUID signRequestId) {
      return stored != null && signRequestId.equals(stored.signRequestId())
          ? Optional.of(new SignTarget(stored.id(), profiles.row.id(), 20271, stored.signedAt()))
          : Optional.empty();
    }

    @Override
    public Optional<ProfileSummary> summary(TenantId tenant, UUID profileId) {
      ProfileRow r = profiles.row;
      if (!r.id().equals(profileId)) {
        return Optional.empty();
      }
      return Optional.of(
          new ProfileSummary(
              r.id(),
              r.periodId(),
              r.periodBucket(),
              r.personId(),
              UUID.randomUUID(),
              r.flow(),
              r.status(),
              false,
              false,
              r.currentSnapshotId(),
              r.autoRating(),
              r.finalRating(),
              r.appraiserId(),
              r.deciderId(),
              r.returnCount(),
              missing,
              null,
              null,
              null,
              r.rowVersion()));
    }

    @Override
    public List<ProfileSummary> mine(
        TenantId tenant, UUID personId, UUID periodId, String flow, UUID after, int limit) {
      return summary(tenant, profiles.row.id()).stream()
          .filter(s -> s.personId().equals(personId))
          .toList();
    }

    @Override
    public List<CriteriaRow> criteria(TenantId tenant, int bucket, UUID profileId) {
      return List.of();
    }

    @Override
    public List<TrailRow> trail(TenantId tenant, int bucket, UUID profileId) {
      return List.of();
    }

    private StoredSelfAssessment copy(
        StoredSelfAssessment d,
        byte[] hash,
        Instant submittedAt,
        UUID signRequestId,
        Instant signedAt,
        Instant at) {
      return new StoredSelfAssessment(
          d.id(),
          d.profileId(),
          d.versionNo(),
          d.formCode(),
          d.formVersion(),
          d.structuredJson(),
          d.claimsJson(),
          d.content(),
          d.strengths(),
          d.weaknesses(),
          d.remedialPlan(),
          d.breakthrough(),
          d.reason(),
          d.dekId(),
          hash == null ? null : Arrays.copyOf(hash, hash.length),
          submittedAt,
          signRequestId,
          signedAt,
          at);
    }
  }
}
