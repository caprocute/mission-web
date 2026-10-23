package vn.viettel.okrkpi.appraisal.domain.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalRuleViolation.Code;

/** Bảng chuyển trạng thái TL-02b QT-09 và BR-09-12 (CORE-04-03): mỗi dòng ít nhất một ca. */
class AppraisalProfileTest {

  private static final UUID PERSON = UUID.randomUUID();
  private static final UUID APPRAISER = UUID.randomUUID();
  private static final UUID MANAGER = UUID.randomUUID();
  private static final UUID DECIDER = UUID.randomUUID();
  private static final UUID SYSTEM = UUID.randomUUID();

  // ---------------------------------------------------------------- dòng bảng chuyển

  @Test
  void chuaLapMoTuDanhGiaKhiCoAnhChupDiem() {
    AppraisalProfile p = AppraisalProfile.open(UUID.randomUUID(), PERSON);
    assertThatThrownBy(() -> p.openSelfAssessment(null, RatingLevel.HTTNV, SYSTEM))
        .satisfies(code(Code.SNAPSHOT_REQUIRED));
    StepCompleted s = p.openSelfAssessment(UUID.randomUUID(), RatingLevel.HTTNV, SYSTEM);
    assertThat(s.from()).isEqualTo(AppraisalStatus.CHUA_LAP);
    assertThat(s.to()).isEqualTo(AppraisalStatus.TU_DANH_GIA);
    assertThat(s.stage()).isEqualTo(LevelKind.TU_DE_XUAT);
    assertThat(p.status()).isEqualTo(AppraisalStatus.TU_DANH_GIA);
  }

  @Test
  void nopPhieuTuDanhGiaCanGiaiTrinhKhiKhacMucThamKhao() {
    AppraisalProfile p = opened();
    RatingRecorded r =
        p.recordRating(id(), LevelKind.TU_DE_XUAT, RatingLevel.HTXSNV, PERSON, "staff", true);
    assertThat(r.differsFromPrevious()).isTrue();
    assertThat(r.rating().explanationRequired()).isTrue();
    assertThatThrownBy(() -> p.markSubmitted(PERSON)).satisfies(code(Code.EXPLANATION_REQUIRED));
    p.recordExplanation(LevelKind.TU_DE_XUAT);
    assertThat(p.markSubmitted(PERSON).to()).isEqualTo(AppraisalStatus.CHO_THAM_DINH);
  }

  @Test
  void nopPhieuKhongTuDeXuatVanDuocVaChiNguoiDuocDanhGiaNop() {
    AppraisalProfile p = opened();
    assertThatThrownBy(() -> p.markSubmitted(APPRAISER)).satisfies(code(Code.SEPARATION_OF_DUTIES));
    assertThat(p.markSubmitted(PERSON).to()).isEqualTo(AppraisalStatus.CHO_THAM_DINH);
  }

  @Test
  void traLaiBoSungCoLyDoToiDaHaiLan() {
    AppraisalProfile p = submitted();
    assertThatThrownBy(() -> p.returnForRevision(APPRAISER, " ", 2))
        .satisfies(code(Code.REASON_REQUIRED));
    for (int i = 0; i < 2; i++) {
      assertThat(p.returnForRevision(APPRAISER, "Bổ sung minh chứng", 2).to())
          .isEqualTo(AppraisalStatus.TU_DANH_GIA);
      p.markSubmitted(PERSON);
    }
    assertThat(p.returnCount()).isEqualTo(2);
    assertThatThrownBy(() -> p.returnForRevision(APPRAISER, "Lần ba", 2))
        .satisfies(code(Code.RETURN_LIMIT_REACHED));
  }

  @Test
  void thamDinhCanMucVaGiaiTrinhVaTachVaiTro() {
    AppraisalProfile p = submitted();
    assertThatThrownBy(() -> p.markAppraised(APPRAISER)).satisfies(code(Code.RATING_REQUIRED));
    assertThatThrownBy(
            () ->
                p.recordRating(
                    id(), LevelKind.THAM_DINH, RatingLevel.HTNV, PERSON, "appraiser", true))
        .satisfies(code(Code.SEPARATION_OF_DUTIES));
    assertThatThrownBy(
            () ->
                p.recordRating(
                    id(), LevelKind.THAM_DINH, RatingLevel.HTNV, APPRAISER, "staff", true))
        .satisfies(code(Code.ROLE_NOT_ALLOWED));
    p.recordRating(id(), LevelKind.THAM_DINH, RatingLevel.HTNV, APPRAISER, "appraiser", false);
    assertThatThrownBy(() -> p.markAppraised(APPRAISER)).satisfies(code(Code.EXPLANATION_REQUIRED));
    p.recordExplanation(LevelKind.THAM_DINH);
    // Có giải trình nhưng thiếu lý do vẫn bị chặn (BR-09-12).
    assertThatThrownBy(() -> p.markAppraised(APPRAISER)).satisfies(code(Code.EXPLANATION_REQUIRED));
    RatingRecorded again =
        p.recordRating(id(), LevelKind.THAM_DINH, RatingLevel.HTTNV, APPRAISER, "appraiser", false);
    assertThat(again.supersededId()).isNotNull();
    assertThat(again.differsFromPrevious()).isFalse();
    StepCompleted s = p.markAppraised(APPRAISER);
    assertThat(s.to()).isEqualTo(AppraisalStatus.DA_THAM_DINH);
    assertThat(p.appraiserId()).isEqualTo(APPRAISER);
  }

  @Test
  void deXuatHoacBoQuaChuyenChoQuyetDinh() {
    AppraisalProfile p = appraised();
    p.recordRating(id(), LevelKind.DE_XUAT, RatingLevel.HTTNV, MANAGER, "manager", false);
    assertThat(p.propose(MANAGER).to()).isEqualTo(AppraisalStatus.CHO_QUYET_DINH);

    AppraisalProfile q = appraised();
    q.recordRating(id(), LevelKind.DE_XUAT, RatingLevel.HTXSNV, MANAGER, "manager", true);
    assertThat(q.skipProposal(SYSTEM).to()).isEqualTo(AppraisalStatus.CHO_QUYET_DINH);
    // Bỏ qua thì mức liền kề của quyết định là mức thẩm định.
    assertThat(q.previousRating(LevelKind.QUYET_DINH)).contains(RatingLevel.HTTNV);
  }

  @Test
  void yeuCauXemLaiCoLyDoQuayVeDaThamDinh() {
    AppraisalProfile p = waitingDecision();
    assertThatThrownBy(() -> p.requestReview(DECIDER, null)).satisfies(code(Code.REASON_REQUIRED));
    assertThat(p.requestReview(DECIDER, "Xem lại tỷ lệ vượt mục tiêu").to())
        .isEqualTo(AppraisalStatus.DA_THAM_DINH);
  }

  @Test
  void quyetDinhKhacNguoiThamDinhVaGhiMucCuoi() {
    AppraisalProfile p = waitingDecision();
    assertThatThrownBy(
            () ->
                p.recordRating(
                    id(), LevelKind.QUYET_DINH, RatingLevel.HTTNV, APPRAISER, "manager", false))
        .satisfies(code(Code.ROLE_NOT_ALLOWED));
    p.recordRating(id(), LevelKind.QUYET_DINH, RatingLevel.HTTNV, APPRAISER, "decider", false);
    assertThatThrownBy(() -> p.decide(APPRAISER)).satisfies(code(Code.SEPARATION_OF_DUTIES));
    p.recordRating(id(), LevelKind.QUYET_DINH, RatingLevel.HTTNV, DECIDER, "decider", false);
    assertThat(p.decide(DECIDER).to()).isEqualTo(AppraisalStatus.DA_QUYET_DINH);
    assertThat(p.finalRating()).contains(RatingLevel.HTTNV);
    assertThat(p.deciderId()).isEqualTo(DECIDER);
  }

  @Test
  void kySoKhieuNaiKetLuanVaKhoa() {
    AppraisalProfile keep = signed();
    assertThat(keep.fileComplaint(PERSON).to()).isEqualTo(AppraisalStatus.KHIEU_NAI);
    assertThatThrownBy(() -> keep.lock(SYSTEM)).satisfies(code(Code.TRANSITION_NOT_ALLOWED));
    assertThat(keep.concludeComplaint(DECIDER, false, "Giữ nguyên").to())
        .isEqualTo(AppraisalStatus.DA_KY_SO);
    assertThat(keep.lock(SYSTEM).to()).isEqualTo(AppraisalStatus.DA_KHOA);

    AppraisalProfile adjust = signed();
    adjust.fileComplaint(PERSON);
    assertThatThrownBy(() -> adjust.concludeComplaint(DECIDER, true, ""))
        .satisfies(code(Code.REASON_REQUIRED));
    assertThat(adjust.concludeComplaint(DECIDER, true, "Điều chỉnh mức").to())
        .isEqualTo(AppraisalStatus.DA_KHOA);
  }

  @Test
  void lenhSaiTrangThaiBiTuChoi() {
    AppraisalProfile p = opened();
    assertThatThrownBy(() -> p.decide(DECIDER)).satisfies(code(Code.TRANSITION_NOT_ALLOWED));
    assertThatThrownBy(() -> p.markSigned(SYSTEM)).satisfies(code(Code.TRANSITION_NOT_ALLOWED));
    assertThatThrownBy(
            () ->
                p.recordRating(
                    id(), LevelKind.THAM_DINH, RatingLevel.HTNV, APPRAISER, "appraiser", true))
        .satisfies(code(Code.TRANSITION_NOT_ALLOWED));
  }

  // ---------------------------------------------------------------- thuộc tính DA_KHOA

  @Test
  void khongChuoiLenhNaoDuaHoSoRaKhoiDaKhoa() {
    List<Consumer<AppraisalProfile>> commands =
        List.of(
            p -> p.openSelfAssessment(UUID.randomUUID(), RatingLevel.HTNV, SYSTEM),
            p -> p.replaceSnapshot(UUID.randomUUID(), RatingLevel.HTNV),
            p -> p.markSubmitted(PERSON),
            p -> p.returnForRevision(APPRAISER, "Lý do", 5),
            p ->
                p.recordRating(
                    id(), LevelKind.THAM_DINH, RatingLevel.HTNV, APPRAISER, "appraiser", true),
            p -> p.recordExplanation(LevelKind.THAM_DINH),
            p -> p.markAppraised(APPRAISER),
            p -> p.propose(MANAGER),
            p -> p.skipProposal(SYSTEM),
            p -> p.requestReview(DECIDER, "Lý do"),
            p ->
                p.recordRating(
                    id(), LevelKind.QUYET_DINH, RatingLevel.HTNV, DECIDER, "decider", true),
            p -> p.decide(DECIDER),
            p -> p.markSigned(SYSTEM),
            p -> p.fileComplaint(PERSON),
            p -> p.concludeComplaint(DECIDER, false, "Giữ nguyên"),
            p -> p.concludeComplaint(DECIDER, true, "Điều chỉnh"),
            p -> p.lock(SYSTEM));
    Random random = new Random(366);
    int lockedRuns = 0;
    for (int run = 0; run < 1_000; run++) {
      AppraisalProfile p = AppraisalProfile.open(UUID.randomUUID(), PERSON);
      boolean locked = false;
      for (int i = 0; i < 400; i++) {
        try {
          commands.get(random.nextInt(commands.size())).accept(p);
        } catch (AppraisalRuleViolation e) {
          if (locked) {
            assertThat(e.code()).isEqualTo(Code.PROFILE_LOCKED);
          }
        }
        if (locked) {
          assertThat(p.status()).isEqualTo(AppraisalStatus.DA_KHOA);
        }
        locked = p.status() == AppraisalStatus.DA_KHOA;
      }
      if (locked) {
        lockedRuns++;
      }
    }
    assertThat(lockedRuns).as("phải có chuỗi lệnh đi tới DA_KHOA").isPositive();
  }

  // ---------------------------------------------------------------- dựng sẵn

  /** CORE-04-10: hồ sơ năm đã ký về bước quyết định, mức cũ không còn hiện hành. */
  @Test
  void duaVeBuocQuyetDinhSauHauKiem() {
    AppraisalProfile p = signed();
    assertThatThrownBy(() -> p.reopenDecision(SYSTEM, " ")).satisfies(code(Code.REASON_REQUIRED));
    StepCompleted step = p.reopenDecision(SYSTEM, "Thi hành kết luận hậu kiểm");
    assertThat(step.to()).isEqualTo(AppraisalStatus.CHO_QUYET_DINH);
    assertThat(p.finalRating()).isEmpty();
    assertThat(p.currentRating(LevelKind.QUYET_DINH)).isEmpty();
    p.recordRating(id(), LevelKind.QUYET_DINH, RatingLevel.HTNV, DECIDER, "decider", false);
    assertThatThrownBy(() -> appraised().reopenDecision(SYSTEM, "lý do"))
        .satisfies(code(Code.TRANSITION_NOT_ALLOWED));
  }

  private static AppraisalProfile opened() {
    AppraisalProfile p = AppraisalProfile.open(UUID.randomUUID(), PERSON);
    p.openSelfAssessment(UUID.randomUUID(), RatingLevel.HTTNV, SYSTEM);
    return p;
  }

  private static AppraisalProfile submitted() {
    AppraisalProfile p = opened();
    p.recordRating(id(), LevelKind.TU_DE_XUAT, RatingLevel.HTTNV, PERSON, "staff", false);
    p.markSubmitted(PERSON);
    return p;
  }

  private static AppraisalProfile appraised() {
    AppraisalProfile p = submitted();
    p.recordRating(id(), LevelKind.THAM_DINH, RatingLevel.HTTNV, APPRAISER, "appraiser", false);
    p.markAppraised(APPRAISER);
    return p;
  }

  private static AppraisalProfile waitingDecision() {
    AppraisalProfile p = appraised();
    p.skipProposal(SYSTEM);
    return p;
  }

  private static AppraisalProfile signed() {
    AppraisalProfile p = waitingDecision();
    p.recordRating(id(), LevelKind.QUYET_DINH, RatingLevel.HTTNV, DECIDER, "decider", false);
    p.decide(DECIDER);
    p.markSigned(SYSTEM);
    return p;
  }

  private static UUID id() {
    return UUID.randomUUID();
  }

  private static Consumer<Throwable> code(Code expected) {
    return e ->
        assertThat(e)
            .isInstanceOfSatisfying(
                AppraisalRuleViolation.class, v -> assertThat(v.code()).isEqualTo(expected));
  }
}
