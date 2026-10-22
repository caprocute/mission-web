package vn.viettel.okrkpi.oversight.application.complaint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.oversight.application.port.ComplaintEvents;
import vn.viettel.okrkpi.oversight.application.port.ComplaintRepository;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRules;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStage;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintTarget;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị CORE-07-02: thời hạn, nháp mã hoá, kiểm khi gửi, EVT-18 và tài liệu ký. */
class ComplaintServiceTest {

  private static final TenantId T = TenantId.of(UUID.randomUUID());

  /** Thứ Tư 21/10/2026, 10 giờ giờ Việt Nam. */
  private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");

  private static final String CONTENT =
      "Nhiệm vụ then chốt 3 bị trừ hệ số chất lượng do chậm sáu ngày vì hồ sơ đầu vào gửi muộn.";
  private static final String REQUEST = "Đề nghị không trừ hệ số chất lượng.";

  private final UUID me = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private FakeComplaints repo;
  private FakeProfiles profiles;
  private FakeSealer sealer;
  private final List<String> events = new ArrayList<>();
  private final List<String> audits = new ArrayList<>();
  private final List<UUID> signed = new ArrayList<>();
  private ComplaintService service;

  @BeforeEach
  void setUp() {
    repo = new FakeComplaints();
    profiles = new FakeProfiles(unit);
    sealer = new FakeSealer();
    service = service(null, sealer, true);
  }

  private ComplaintService service(
      WorkingCalendarApi calendar, ComplaintSealer s, boolean withSigner) {
    return new ComplaintService(
        repo,
        profiles,
        new FakePeriods(),
        calendar,
        s,
        withSigner
            ? (tenant, id, key, auth) -> {
              UUID sign = UUID.randomUUID();
              signed.add(sign);
              return sign;
            }
            : null,
        new ComplaintEvents() {
          @Override
          public void filed(TenantId tenant, Complaint c) {
            events.add("EVT-25:" + c.code());
          }

          @Override
          public void notFiled(TenantId tenant, Complaint c) {
            events.add("KHONG_GUI:" + c.code());
          }

          @Override
          public void stateChanged(
              TenantId tenant, Complaint c, String template, Collection<UUID> recipients) {
            events.add(template + ":" + c.code());
          }
        },
        (tenant, actor, action, type, id, purpose, role) -> audits.add(action),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  // ---------------------------------------------------------------- thời hạn

  @Test
  void conHanDuBaHinhThucQuaHanChiConBaoLuu() {
    UUID fresh = profiles.add(me, "DA_KY_SO", NOW.minus(1, ChronoUnit.DAYS));
    UUID late = profiles.add(me, "DA_KY_SO", NOW.minus(40, ChronoUnit.DAYS));
    List<ComplaintService.Eligible> list = service.eligible(T, member());
    ComplaintService.Eligible f = byId(list, fresh);
    ComplaintService.Eligible l = byId(list, late);
    assertThat(f.allowedKinds()).containsExactly(ComplaintKind.values());
    // Công bố thứ Ba 20/10: 10 ngày làm việc là thứ Ba 03/11, hôm nay thứ Tư 21/10 còn 9 ngày.
    assertThat(f.deadline()).isEqualTo(LocalDate.of(2026, 11, 3));
    assertThat(f.workingDaysLeft()).isEqualTo(9);
    assertThat(f.period().name()).isEqualTo("Quý IV/2026");
    assertThat(l.allowedKinds()).containsExactly(ComplaintKind.BAO_LUU);
    assertThat(l.workingDaysLeft()).isNegative();
  }

  @Test
  void lichLamViecCuaTenantDuocDung() {
    UUID fresh = profiles.add(me, "DA_KY_SO", NOW.minus(1, ChronoUnit.DAYS));
    WorkingCalendarApi everyDay =
        new WorkingCalendarApi() {
          @Override
          public boolean isWorkingDay(TenantId tenantId, LocalDate date) {
            return true;
          }

          @Override
          public LocalDate addWorkingDays(TenantId tenantId, LocalDate from, int n) {
            return from.plusDays(n);
          }

          @Override
          public long countWorkingDays(TenantId tenantId, LocalDate from, LocalDate to) {
            return Math.max(0, ChronoUnit.DAYS.between(from, to));
          }

          @Override
          public vn.viettel.okrkpi.cel.domain.WorkingCalendar celCalendar(TenantId tenantId) {
            return null;
          }
        };
    ComplaintService.Eligible e =
        byId(service(everyDay, sealer, true).eligible(T, member()), fresh);
    assertThat(e.deadline()).isEqualTo(LocalDate.of(2026, 10, 30));
    assertThat(e.workingDaysLeft()).isEqualTo(9);
  }

  @Test
  void hoSoChuaCongBoTinhTuHomNay() {
    UUID p = profiles.add(me, "DA_KY_SO", null);
    assertThat(byId(service.eligible(T, member()), p).workingDaysLeft()).isEqualTo(10);
  }

  // ---------------------------------------------------------------- nháp

  @Test
  void luuNhapMaHoaVaSinhMa() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW.minus(1, ChronoUnit.DAYS));
    repo.codes.add("KN-2026-0007");
    repo.codes.add("MLK-2026-001");
    ComplaintService.View v = service.create(T, member(), draft(p, ComplaintKind.KHIEU_NAI));
    Complaint c = v.complaint();
    assertThat(c.code()).isEqualTo("KN-2026-0008");
    assertThat(c.draft()).isTrue();
    assertThat(c.subjectLine()).isEqualTo("Khiếu nại kết quả xếp loại Quý IV/2026");
    assertThat(new String(c.contentEnc(), StandardCharsets.UTF_8)).doesNotContain("then chốt");
    assertThat(c.dekId()).isEqualTo(sealer.dek);
    assertThat(v.content()).isEqualTo(CONTENT);
    assertThat(audits).containsExactly(ComplaintService.ACTION_CREATE);
    assertThat(repo.lockedYears).containsExactly(2026);
  }

  @Test
  void nhapTrongKhongCanKhoa() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    ComplaintService.Draft d =
        new ComplaintService.Draft(
            p,
            ComplaintKind.GIAI_TRINH,
            ComplaintStage.DE_XUAT,
            null,
            " Tiêu đề ",
            " ",
            null,
            null,
            true);
    Complaint c = service(null, null, true).create(T, member(), d).complaint();
    assertThat(c.contentEnc()).isNull();
    assertThat(c.dekId()).isNull();
    assertThat(c.subjectLine()).isEqualTo("Tiêu đề");
    assertThat(c.reserveOpinion()).isTrue();
  }

  @Test
  void khongCoBoMaHoaThiTuChoiGhiNoiDung() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    ComplaintService noSealer = service(null, null, true);
    assertThatThrownBy(() -> noSealer.create(T, member(), draft(p, ComplaintKind.KHIEU_NAI)))
        .isInstanceOf(ComplaintService.SealingUnavailableException.class);
  }

  @Test
  void dekXoayGiuaHaiLanMaHoaThiMaLaiNoiDung() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    sealer.rotateAfter = 1;
    Complaint c = service.create(T, member(), draft(p, ComplaintKind.KHIEU_NAI)).complaint();
    assertThat(sealer.sealed).isEqualTo(3);
    assertThat(sealer.dekOf(c.contentEnc())).isEqualTo(c.dekId());
    assertThat(sealer.dekOf(c.requestTextEnc())).isEqualTo(c.dekId());
  }

  @Test
  void quaHanKhongLuuDuocKhieuNai() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW.minus(40, ChronoUnit.DAYS));
    assertThatThrownBy(() -> service.create(T, member(), draft(p, ComplaintKind.KHIEU_NAI)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-422-DEADLINE-PASSED"));
    assertThat(service.create(T, member(), draft(p, ComplaintKind.BAO_LUU)).complaint().kind())
        .isEqualTo(ComplaintKind.BAO_LUU);
  }

  @Test
  void hoSoCuaNguoiKhacHoacChuaKySo() {
    UUID other = profiles.add(UUID.randomUUID(), "DA_KY_SO", NOW);
    UUID notSigned = profiles.add(me, "DA_QUYET_DINH", NOW);
    assertThatThrownBy(() -> service.create(T, member(), draft(other, ComplaintKind.KHIEU_NAI)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> service.create(T, member(), draft(notSigned, ComplaintKind.KHIEU_NAI)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
  }

  @Test
  void doDaiToiDaKiemNgayKhiLuuNhap() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    List<UUID> files = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      files.add(UUID.randomUUID());
    }
    ComplaintService.Draft bad =
        new ComplaintService.Draft(
            null,
            null,
            null,
            List.of(new ComplaintTarget("x".repeat(101), ComplaintStage.THAM_DINH, null)),
            "t".repeat(256),
            "n".repeat(8001),
            "r".repeat(501),
            files,
            false);
    assertThatThrownBy(() -> service.create(T, member(), bad))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors())
                    .extracting(f -> f.field())
                    .containsExactly(
                        "profileId",
                        "kind",
                        "targetStage",
                        "targets",
                        "subjectLine",
                        "content",
                        "requestText",
                        "attachmentObjectIds"));
    ComplaintService.Draft longLabel =
        new ComplaintService.Draft(
            p,
            ComplaintKind.KHIEU_NAI,
            ComplaintStage.THAM_DINH,
            List.of(new ComplaintTarget("K", ComplaintStage.THAM_DINH, "l".repeat(201))),
            null,
            null,
            null,
            null,
            false);
    assertThatThrownBy(() -> service.create(T, member(), longLabel))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void suaNhapVaCacTruongHopTuChoi() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    UUID p2 = profiles.add(me, "DA_KY_SO", NOW);
    Complaint c = service.create(T, member(), draft(p, ComplaintKind.KHIEU_NAI)).complaint();
    String id = c.id().toString();
    assertThatThrownBy(
            () -> service.update(T, member(), c.id(), null, draft(p, ComplaintKind.KHIEU_NAI)))
        .isInstanceOf(PreconditionRequiredException.class);
    assertThatThrownBy(
            () -> service.update(T, member(), c.id(), "\"0\"", draft(p2, ComplaintKind.KHIEU_NAI)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.update(T, other(), c.id(), "\"0\"", draft(p, ComplaintKind.KHIEU_NAI)))
        .isInstanceOf(ComplaintService.NotFoundException.class);
    ComplaintService.View v =
        service.update(T, member(), c.id(), "\"0\"", withTarget(p, ComplaintKind.GIAI_TRINH));
    assertThat(v.complaint().rowVersion()).isEqualTo(1);
    assertThat(v.complaint().kind()).isEqualTo(ComplaintKind.GIAI_TRINH);
    assertThat(v.complaint().targets()).hasSize(1);
    repo.failUpdate = true;
    assertThatThrownBy(
            () -> service.update(T, member(), c.id(), "\"1\"", draft(p, ComplaintKind.KHIEU_NAI)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-VERSION-CONFLICT"));
    repo.failUpdate = false;
    repo.rows.put(c.id(), filed(repo.rows.get(c.id())));
    assertThatThrownBy(
            () -> service.update(T, member(), c.id(), "\"2\"", draft(p, ComplaintKind.KHIEU_NAI)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    assertThat(id).isNotBlank();
  }

  // ---------------------------------------------------------------- gửi

  @Test
  void guiCanAal2VaDuTruong() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    Complaint c = service.create(T, member(), draft(p, ComplaintKind.KHIEU_NAI)).complaint();
    assertThatThrownBy(() -> service.submit(T, aal1(), c.id(), "\"0\"", "k", null))
        .isInstanceOf(StepUpRequiredException.class);
    Actor stale = new Actor(me, List.of("member"), null, "aal2", NOW.getEpochSecond() - 3600);
    assertThatThrownBy(() -> service.submit(T, stale, c.id(), "\"0\"", "k", null))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(() -> service.submit(T, member(), c.id(), "\"0\"", "k", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-422-NO-TARGET"));
    assertThatThrownBy(() -> service.submit(T, other(), c.id(), "\"0\"", "k", null))
        .isInstanceOf(ComplaintService.NotFoundException.class);
  }

  @Test
  void cauPhanLaNoiDungNganVaThieuDeNghi() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    ComplaintService.Draft unknown =
        new ComplaintService.Draft(
            p,
            ComplaintKind.KHIEU_NAI,
            ComplaintStage.THAM_DINH,
            List.of(new ComplaintTarget("LA", ComplaintStage.THAM_DINH, null)),
            null,
            CONTENT,
            REQUEST,
            null,
            false);
    Complaint a = service.create(T, member(), unknown).complaint();
    assertThatThrownBy(() -> service.submit(T, member(), a.id(), "\"0\"", "k", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    ComplaintService.Draft shortText =
        new ComplaintService.Draft(
            p,
            ComplaintKind.KHIEU_NAI,
            ComplaintStage.THAM_DINH,
            List.of(new ComplaintTarget("TONG_DIEM", ComplaintStage.THAM_DINH, null)),
            null,
            "Ngắn.",
            null,
            null,
            false);
    Complaint b = service.create(T, member(), shortText).complaint();
    assertThatThrownBy(() -> service.submit(T, member(), b.id(), "\"0\"", "k", null))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors())
                    .extracting(f -> f.field())
                    .containsExactly("content", "requestText"));
  }

  @Test
  void guiKhiHoSoDoiTrangThaiHoacQuaHan() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    Complaint c = service.create(T, member(), withTarget(p, ComplaintKind.KHIEU_NAI)).complaint();
    profiles.status(p, "KHIEU_NAI");
    assertThatThrownBy(() -> service.submit(T, member(), c.id(), "\"0\"", "k", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    profiles.status(p, "DA_KY_SO");
    profiles.published(p, NOW.minus(40, ChronoUnit.DAYS));
    assertThatThrownBy(() -> service.submit(T, member(), c.id(), "\"0\"", "k", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-422-DEADLINE-PASSED"));
    profiles.published(p, NOW);
    assertThatThrownBy(
            () -> service(null, sealer, false).submit(T, member(), c.id(), "\"0\"", "k", null))
        .isInstanceOf(ComplaintService.SigningUnavailableException.class);
  }

  @Test
  void guiMoYeuCauKyRoiEvt18ChuyenHoSoMotLan() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    Complaint c = service.create(T, member(), withTarget(p, ComplaintKind.KHIEU_NAI)).complaint();
    UUID sign = service.submit(T, member(), c.id(), "\"0\"", "k", "Bearer x");
    assertThat(signed).containsExactly(sign);
    assertThat(profiles.filed).isEmpty();
    assertThat(service.signJob(T, sign)).hasValueSatisfying(j -> assertThat(j.filed()).isFalse());
    ComplaintService.SignSource src = service.signSource(T, c.id()).orElseThrow();
    assertThat(src.signer()).isEqualTo(me);
    assertThat(src.sha256()).hasSize(32);
    assertThat(src.ref()).isEqualTo(c.code());

    assertThat(service.onSigned(T, c.id(), UUID.randomUUID(), NOW)).isFalse();
    assertThat(service.onSigned(T, UUID.randomUUID(), sign, NOW)).isFalse();
    assertThat(service.onSigned(T, c.id(), sign, null)).isTrue();
    assertThat(service.onSigned(T, c.id(), sign, NOW)).isFalse();
    assertThat(profiles.filed).containsExactly(p);
    assertThat(events).containsExactly("EVT-25:" + c.code());
    Complaint f = repo.rows.get(c.id());
    assertThat(f.status()).isEqualTo(ComplaintStatus.MOI);
    assertThat(f.handlerUnitId()).isEqualTo(unit);
    // Gửi thứ Tư 21/10: 15 ngày làm việc là thứ Tư 11/11.
    assertThat(f.responseDeadline()).isEqualTo(LocalDate.of(2026, 11, 11));
    assertThat(service.responseDaysLeft(T, f)).isEqualTo(15);
    assertThat(service.signJob(T, sign)).hasValueSatisfying(j -> assertThat(j.filed()).isTrue());
    assertThat(service.signSource(T, c.id())).isEmpty();
    assertThat(service.get(T, member(), c.id()).requestText()).isEqualTo(REQUEST);
    assertThat(
            service.list(
                T, member(), null, Set.of(ComplaintStatus.MOI), ComplaintKind.KHIEU_NAI, null, 10))
        .hasSize(1);
  }

  @Test
  void suaNhapSauKhiMoYeuCauKyThiChuKyCuBiBo() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    Complaint c = service.create(T, member(), withTarget(p, ComplaintKind.KHIEU_NAI)).complaint();
    UUID sign = service.submit(T, member(), c.id(), "\"0\"", "k", null);
    service.update(T, member(), c.id(), "\"1\"", withTarget(p, ComplaintKind.KHIEU_NAI));
    assertThat(service.onSigned(T, c.id(), sign, NOW)).isFalse();
    assertThat(repo.rows.get(c.id()).draft()).isTrue();
  }

  @Test
  void baoLuuKhongChuyenHoSo() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    Complaint c = service.create(T, member(), withTarget(p, ComplaintKind.BAO_LUU)).complaint();
    UUID sign = service.submit(T, member(), c.id(), "\"0\"", "k", null);
    assertThat(service.onSigned(T, c.id(), sign, NOW)).isTrue();
    assertThat(profiles.filed).isEmpty();
  }

  @Test
  void hoSoKhoaTrongLucChoKyThiGiuNhapVaBao() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    Complaint c = service.create(T, member(), withTarget(p, ComplaintKind.KHIEU_NAI)).complaint();
    UUID sign = service.submit(T, member(), c.id(), "\"0\"", "k", null);
    profiles.reject = true;
    assertThat(service.onSigned(T, c.id(), sign, NOW)).isFalse();
    Complaint back = repo.rows.get(c.id());
    assertThat(back.draft()).isTrue();
    assertThat(back.signRequestId()).isNull();
    assertThat(events).containsExactly("KHONG_GUI:" + c.code());
    profiles.reject = false;
    UUID again = service.submit(T, member(), c.id(), "\"2\"", "k2", null);
    assertThat(again).isNotNull();
  }

  @Test
  void hoSoBiXoaThiKhongGuiDuoc() {
    UUID p = profiles.add(me, "DA_KY_SO", NOW);
    Complaint c = service.create(T, member(), withTarget(p, ComplaintKind.KHIEU_NAI)).complaint();
    UUID sign = service.submit(T, member(), c.id(), "\"0\"", "k", null);
    profiles.remove(p);
    assertThat(service.onSigned(T, c.id(), sign, NOW)).isFalse();
    assertThat(events).containsExactly("KHONG_GUI:" + c.code());
    assertThatThrownBy(() -> service.submit(T, member(), c.id(), "\"2\"", "k", null))
        .isInstanceOf(ComplaintService.NotFoundException.class);
    assertThatThrownBy(() -> service.get(T, other(), c.id()))
        .isInstanceOf(ComplaintService.NotFoundException.class);
  }

  @Test
  void quyTacMa() {
    assertThat(ComplaintRules.code(2027, 31)).isEqualTo("KN-2027-0031");
    assertThat(ComplaintRules.sequenceOf("KN-2027-0031", 2027)).isEqualTo(31);
    assertThat(ComplaintRules.sequenceOf("KN-2026-0031", 2027)).isZero();
    assertThat(ComplaintRules.sequenceOf(null, 2027)).isZero();
    assertThat(ComplaintRules.length(null)).isZero();
    assertThatThrownBy(() -> ComplaintRules.code(2027, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ComplaintKind.BAO_LUU.reopensProfile()).isFalse();
    assertThat(new ComplaintRepository.After(NOW, UUID.randomUUID()).createdAt()).isEqualTo(NOW);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private Actor member() {
    return new Actor(me, List.of("member"), null, "aal2", NOW.getEpochSecond() - 30);
  }

  private Actor aal1() {
    return new Actor(me, List.of("member"), null, "aal1", null);
  }

  private static Actor other() {
    return new Actor(UUID.randomUUID(), List.of("member"), null, "aal2", null);
  }

  private static ComplaintService.Draft draft(UUID profile, ComplaintKind kind) {
    return new ComplaintService.Draft(
        profile, kind, ComplaintStage.THAM_DINH, List.of(), null, CONTENT, REQUEST, null, false);
  }

  private static ComplaintService.Draft withTarget(UUID profile, ComplaintKind kind) {
    return new ComplaintService.Draft(
        profile,
        kind,
        ComplaintStage.THAM_DINH,
        List.of(new ComplaintTarget("TONG_DIEM", ComplaintStage.THAM_DINH, "Tổng điểm")),
        null,
        CONTENT,
        REQUEST,
        List.of(UUID.randomUUID()),
        true);
  }

  private static Complaint filed(Complaint c) {
    return c.filed(NOW, LocalDate.of(2026, 11, 11), UUID.randomUUID(), LocalDate.of(2026, 10, 23));
  }

  private static ComplaintService.Eligible byId(List<ComplaintService.Eligible> list, UUID id) {
    return list.stream().filter(e -> e.profile().id().equals(id)).findFirst().orElseThrow();
  }

  private static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  static final class FakeProfiles implements ComplaintProfilesApi {
    final Map<UUID, Profile> rows = new HashMap<>();
    final List<UUID> filed = new ArrayList<>();
    final List<UUID> returned = new ArrayList<>();
    final Map<UUID, Participants> participants = new HashMap<>();
    final UUID unit;
    boolean reject;

    FakeProfiles(UUID unit) {
      this.unit = unit;
    }

    UUID add(UUID person, String status, Instant published) {
      UUID id = UUID.randomUUID();
      rows.put(
          id,
          new Profile(
              id,
              person,
              UUID.randomUUID(),
              20264,
              unit,
              status,
              "HTTNV",
              new BigDecimal("91.2"),
              published,
              DEFAULT_DEADLINE_DAYS,
              List.of(new Target("TONG_DIEM", "THAM_DINH", "Tổng điểm · 91,2 điểm"))));
      return id;
    }

    void status(UUID id, String status) {
      Profile p = rows.get(id);
      rows.put(id, copy(p, status, p.publishedAt()));
    }

    void published(UUID id, Instant at) {
      Profile p = rows.get(id);
      rows.put(id, copy(p, p.status(), at));
    }

    void remove(UUID id) {
      rows.remove(id);
    }

    private Profile copy(Profile p, String status, Instant published) {
      return new Profile(
          p.id(),
          p.personId(),
          p.periodId(),
          p.periodBucket(),
          p.orgUnitId(),
          status,
          p.ratingCode(),
          p.score(),
          published,
          p.deadlineWorkingDays(),
          p.targets());
    }

    @Override
    public List<Profile> signedProfilesOf(TenantId tenant, UUID personId) {
      return rows.values().stream()
          .filter(p -> p.personId().equals(personId) && "DA_KY_SO".equals(p.status()))
          .toList();
    }

    @Override
    public Optional<Profile> find(TenantId tenant, UUID profileId) {
      return Optional.ofNullable(rows.get(profileId));
    }

    @Override
    public boolean fileComplaint(TenantId tenant, UUID profileId, UUID actorId) {
      if (reject) {
        throw new BusinessException("APPRAISAL-409-INVALID-STATUS", "Hồ sơ đã khoá");
      }
      if (!rows.containsKey(profileId)) {
        throw new NoSuchElementException();
      }
      filed.add(profileId);
      status(profileId, "KHIEU_NAI");
      return true;
    }

    @Override
    public Participants participants(TenantId tenant, UUID profileId) {
      return participants.getOrDefault(profileId, new Participants(Set.of(), Set.of(), Set.of()));
    }

    @Override
    public boolean returnToSigned(TenantId tenant, UUID profileId, UUID actorId, String reason) {
      if (!"KHIEU_NAI".equals(rows.get(profileId).status())) {
        return false;
      }
      returned.add(profileId);
      status(profileId, "DA_KY_SO");
      return true;
    }
  }

  /** Mã hoá giả: đảo byte, tiền tố 16 byte là mã DEK; có thể xoay DEK sau n lần. */
  static final class FakeSealer implements ComplaintSealer {
    UUID dek = UUID.randomUUID();
    int sealed;
    int rotateAfter = -1;
    final Map<String, UUID> dekByCipher = new HashMap<>();

    @Override
    public Sealed seal(TenantId tenant, String column, UUID rowId, String plain) {
      if (sealed == rotateAfter) {
        dek = UUID.randomUUID();
      }
      sealed++;
      byte[] b = (column + "|" + plain).getBytes(StandardCharsets.UTF_8);
      byte[] out = new byte[b.length];
      for (int i = 0; i < b.length; i++) {
        out[i] = (byte) ~b[i];
      }
      dekByCipher.put(new String(out, StandardCharsets.ISO_8859_1), dek);
      return new Sealed(out, dek);
    }

    @Override
    public String open(TenantId tenant, String column, UUID rowId, byte[] cipher) {
      byte[] out = new byte[cipher.length];
      for (int i = 0; i < cipher.length; i++) {
        out[i] = (byte) ~cipher[i];
      }
      String s = new String(out, StandardCharsets.UTF_8);
      return s.substring(s.indexOf('|') + 1);
    }

    UUID dekOf(byte[] cipher) {
      return dekByCipher.get(new String(cipher, StandardCharsets.ISO_8859_1));
    }
  }

  static final class FakeComplaints implements ComplaintRepository {
    final Map<UUID, Complaint> rows = new HashMap<>();
    final List<String> codes = new ArrayList<>();
    final List<Integer> lockedYears = new ArrayList<>();
    boolean failUpdate;

    @Override
    public void lockCodes(TenantId tenant, int year) {
      lockedYears.add(year);
    }

    @Override
    public List<String> codesOfYear(TenantId tenant, int year) {
      List<String> out = new ArrayList<>(codes);
      rows.values().forEach(c -> out.add(c.code()));
      return out;
    }

    @Override
    public void insert(TenantId tenant, Complaint c, UUID actorId) {
      rows.put(c.id(), c);
    }

    @Override
    public Optional<Complaint> find(TenantId tenant, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Complaint> lock(TenantId tenant, UUID id) {
      return find(tenant, id);
    }

    @Override
    public boolean update(TenantId tenant, Complaint changed, int expected, UUID actorId) {
      Complaint cur = rows.get(changed.id());
      if (failUpdate || cur == null || cur.rowVersion() != expected) {
        return false;
      }
      rows.put(changed.id(), changed);
      return true;
    }

    @Override
    public List<Complaint> ofComplainant(
        TenantId tenant,
        UUID personId,
        UUID periodId,
        Set<ComplaintStatus> statuses,
        boolean includeDrafts,
        ComplaintKind kind,
        After after,
        int limit) {
      return rows.values().stream()
          .filter(c -> c.complainantId().equals(personId))
          .filter(c -> statuses.isEmpty() || statuses.contains(c.status()))
          .filter(c -> kind == null || c.kind() == kind)
          .sorted(Comparator.comparing(Complaint::createdAt).reversed())
          .limit(limit)
          .toList();
    }

    @Override
    public List<Complaint> forHandler(
        TenantId tenant,
        Collection<UUID> unitIds,
        UUID handlerId,
        boolean assignedOnly,
        UUID periodId,
        Set<ComplaintStatus> statuses,
        ComplaintKind kind,
        HandlerAfter after,
        int limit) {
      return rows.values().stream()
          .filter(c -> !c.draft())
          .filter(
              c ->
                  handlerId.equals(c.handling().handlerId())
                      || (!assignedOnly && unitIds.contains(c.handlerUnitId())))
          .filter(c -> statuses.isEmpty() || statuses.contains(c.status()))
          .filter(c -> kind == null || c.kind() == kind)
          .sorted(Comparator.comparing(Complaint::responseDeadline))
          .limit(limit)
          .toList();
    }

    @Override
    public boolean otherOpenReopening(TenantId tenant, UUID profileId, UUID exceptId) {
      return rows.values().stream()
          .anyMatch(
              c ->
                  c.profileId().equals(profileId)
                      && !c.id().equals(exceptId)
                      && c.kind().reopensProfile()
                      && c.status() != null
                      && c.status() != ComplaintStatus.DONG
                      && c.status() != ComplaintStatus.RUT
                      && c.status() != ComplaintStatus.KHONG_THU_LY);
    }

    @Override
    public Optional<Complaint> bySignRequest(TenantId tenant, UUID signRequestId) {
      return rows.values().stream()
          .filter(c -> signRequestId.equals(c.signRequestId()))
          .findFirst();
    }
  }

  static final class FakePeriods implements PeriodApi {
    @Override
    public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
      return Optional.empty();
    }

    @Override
    public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId id) {
      return Optional.of(
          new PeriodSnapshot(
              id,
              tenantId,
              "2026-Q4",
              "Quý IV/2026",
              LocalDate.of(2026, 10, 1),
              LocalDate.of(2026, 12, 31),
              PeriodStatus.DA_KHOA));
    }

    @Override
    public void assertOpen(TenantId tenantId, PeriodId periodId) throws PeriodLockedException {}
  }
}
