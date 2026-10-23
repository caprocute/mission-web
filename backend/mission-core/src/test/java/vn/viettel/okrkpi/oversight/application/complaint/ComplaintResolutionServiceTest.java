package vn.viettel.okrkpi.oversight.application.complaint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi.Participants;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintResolutionService.ConclusionInput;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintResolutionService.Record;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintResolutionService.Resolved;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService.NotFoundException;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintServiceTest.FakeComplaints;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintServiceTest.FakeProfiles;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintServiceTest.FakeSealer;
import vn.viettel.okrkpi.oversight.application.port.ComplaintEvents;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlerDirectory;
import vn.viettel.okrkpi.oversight.application.port.ComplaintProcessRepository;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintAdjustment;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintConclusion;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRecordKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResolution;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResponse;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStage;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintTarget;
import vn.viettel.okrkpi.oversight.domain.complaint.ExplanationParty;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm thử đơn vị CORE-07-04: yêu cầu và nộp giải trình, chuyển chờ kết luận khi quá hạn, ghi kết
 * luận (mở lại kỳ đã khoá), ký văn bản trả lời và EVT-18.
 */
class ComplaintResolutionServiceTest {

  private static final TenantId T = TenantId.of(UUID.randomUUID());
  // Thứ Tư 21/10/2026 10:00 giờ Việt Nam.
  private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");
  private static final String TEXT = "Văn bản trả lời khiếu nại theo kết quả xác minh.";

  private final UUID unit = UUID.randomUUID();
  private final UUID otherUnit = UUID.randomUUID();
  private final UUID complainant = UUID.randomUUID();
  private final UUID handler = UUID.randomUUID();
  private final UUID reviewer = UUID.randomUUID();
  private final UUID decider = UUID.randomUUID();
  private final UUID witness = UUID.randomUUID();
  private final UUID manager = UUID.randomUUID();

  private FakeComplaints repo;
  private FakeProcess process;
  private FakeProfiles profiles;
  private final MutablePeriods periods = new MutablePeriods();
  private final List<String> events = new ArrayList<>();
  private final List<Collection<UUID>> recipients = new ArrayList<>();
  private final List<Map<String, String>> params = new ArrayList<>();
  private final List<String> audits = new ArrayList<>();
  private final List<UUID> reopened = new ArrayList<>();
  private final List<UUID> signed = new ArrayList<>();
  private Clock clock;
  private FakeSealer sealer = new FakeSealer();
  private ComplaintService filing;
  private ComplaintResolutionService service;
  private java.util.function.Consumer<UUID> duringSign = id -> {};
  private UUID profileId;

  @BeforeEach
  void setUp() {
    repo = new FakeComplaints();
    process = new FakeProcess();
    process.complaints = repo;
    profiles = new FakeProfiles(unit);
    profileId = profiles.add(complainant, "KHIEU_NAI", NOW.minusSeconds(86_400));
    profiles.participants.put(
        profileId, new Participants(Set.of(), Set.of(reviewer), Set.of(UUID.randomUUID())));
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    build(true, true);
  }

  private void build(boolean withSigner, boolean withReopener) {
    ComplaintEvents sink =
        new ComplaintEvents() {
          @Override
          public void filed(TenantId tenant, Complaint c) {}

          @Override
          public void notFiled(TenantId tenant, Complaint c) {}

          @Override
          public void stateChanged(
              TenantId tenant, Complaint c, String template, Collection<UUID> to) {
            stateChanged(tenant, c, template, to, Map.of());
          }

          @Override
          public void stateChanged(
              TenantId tenant,
              Complaint c,
              String template,
              Collection<UUID> to,
              Map<String, String> p) {
            events.add(template + ":" + c.status());
            recipients.add(List.copyOf(to));
            params.add(p);
          }
        };
    filing =
        new ComplaintService(
            repo,
            profiles,
            periods,
            null,
            sealer,
            null,
            sink,
            (tenant, actor, action, type, id, purpose, role) -> audits.add(action),
            TransactionOperations.withoutTransaction(),
            clock);
    service =
        new ComplaintResolutionService(
            repo,
            process,
            filing,
            profiles,
            new Directory(),
            (tenant, role, unitId, at) ->
                "manager".equals(role) && unit.equals(unitId) ? List.of(manager) : List.of(),
            null,
            sealer,
            withSigner
                ? (tenant, id, key, auth) -> {
                  duringSign.accept(id);
                  UUID s = UUID.randomUUID();
                  signed.add(s);
                  return s;
                }
                : null,
            withReopener
                ? (tenant, requester, c, k) -> {
                  UUID r = UUID.randomUUID();
                  reopened.add(r);
                  return r;
                }
                : null,
            sink,
            (tenant, actor, action, type, id, purpose, role) -> audits.add(action),
            TransactionOperations.withoutTransaction(),
            clock);
  }

  // ---------------------------------------------------------------- giải trình

  @Test
  void yeuCauGiaiTrinhChuyenDangGiaiTrinhVaBaoTungBen() {
    Complaint c = accepted();
    Resolved r =
        service.requestExplanations(
            T,
            appraiser(handler),
            c.id(),
            etag(c),
            List.of(person(witness), unitParty(unit)),
            "Đề nghị giải trình căn cứ chấm điểm.",
            null);
    Complaint saved = repo.rows.get(c.id());
    assertThat(saved.status()).isEqualTo(ComplaintStatus.DANG_GIAI_TRINH);
    assertThat(r.view().complaint().status()).isEqualTo(ComplaintStatus.DANG_GIAI_TRINH);
    assertThat(r.conclusion()).isNull();
    List<ComplaintResponse> requests = process.of(c.id(), ComplaintRecordKind.YEU_CAU);
    assertThat(requests).hasSize(2);
    // Mặc định 3 ngày làm việc từ thứ Tư: thứ Hai 26/10.
    assertThat(requests).allMatch(q -> q.dueDate().equals(LocalDate.of(2026, 10, 26)));
    assertThat(events)
        .containsExactly(
            "KHIEU_NAI_YEU_CAU_GIAI_TRINH:DANG_GIAI_TRINH",
            "KHIEU_NAI_YEU_CAU_GIAI_TRINH:DANG_GIAI_TRINH");
    assertThat(recipients).containsExactly(List.of(witness), List.of(manager));
    assertThat(params.get(0)).containsEntry("han", "2026-10-26");
    assertThat(audits).containsExactly("YEU_CAU_GIAI_TRINH_KHIEU_NAI");

    // Đang giải trình vẫn được yêu cầu thêm, không đổi trạng thái.
    Complaint now = repo.rows.get(c.id());
    service.requestExplanations(
        T,
        appraiser(handler),
        c.id(),
        etag(now),
        List.of(person(reviewer)),
        "Bổ sung giải trình.",
        LocalDate.of(2026, 10, 30));
    assertThat(repo.rows.get(c.id()).status()).isEqualTo(ComplaintStatus.DANG_GIAI_TRINH);
    assertThat(repo.rows.get(c.id()).rowVersion()).isEqualTo(now.rowVersion() + 1);
    assertThat(process.of(c.id(), ComplaintRecordKind.YEU_CAU)).hasSize(3);
  }

  @Test
  void yeuCauGiaiTrinhKiemDauVaoVaQuyen() {
    Complaint c = accepted();
    assertThatThrownBy(
            () ->
                service.requestExplanations(
                    T,
                    appraiser(handler),
                    c.id(),
                    etag(c),
                    List.of(),
                    " ",
                    LocalDate.of(2026, 10, 21)))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors())
                    .extracting(x -> x.field())
                    .containsExactly("parties", "content", "dueDate"));
    assertThatThrownBy(
            () ->
                service.requestExplanations(
                    T,
                    appraiser(handler),
                    c.id(),
                    etag(c),
                    List.of(person(complainant)),
                    "Nội dung",
                    LocalDate.of(2026, 12, 31)))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors())
                    .extracting(x -> x.field())
                    .containsExactly("parties", "dueDate"));
    // Không phải người đã tiếp nhận.
    assertThatThrownBy(
            () ->
                service.requestExplanations(
                    T,
                    appraiser(witness),
                    c.id(),
                    etag(c),
                    List.of(person(manager)),
                    "Nội dung",
                    null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    // Người tiếp nhận nay là người tham gia hồ sơ: kiểm lại tách vai trò.
    profiles.participants.put(
        profileId, new Participants(Set.of(), Set.of(reviewer, handler), Set.of()));
    assertThatThrownBy(
            () ->
                service.requestExplanations(
                    T,
                    appraiser(handler),
                    c.id(),
                    etag(c),
                    List.of(person(manager)),
                    "Nội dung",
                    null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES"));
    // Sai trạng thái.
    Complaint fresh = filed();
    assertThatThrownBy(
            () ->
                service.requestExplanations(
                    T,
                    appraiser(handler),
                    fresh.id(),
                    etag(fresh),
                    List.of(person(manager)),
                    "Nội dung",
                    null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    assertThatThrownBy(
            () ->
                service.requestExplanations(
                    T, appraiser(handler), UUID.randomUUID(), "\"0\"", null, null, null))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void giaiTrinhDuThiChuyenChoKetLuan() {
    Complaint c = requested(List.of(person(witness), unitParty(unit)), null);
    List<ComplaintResponse> requests = process.of(c.id(), ComplaintRecordKind.YEU_CAU);
    UUID first = requests.get(0).id();
    UUID second = requests.get(1).id();

    Record r =
        service.submitExplanation(
            T, member(witness), c.id(), etag(repo.rows.get(c.id())), first, "Giải trình", null);
    assertThat(r.content()).isEqualTo("Giải trình");
    assertThat(r.response().party()).isEqualTo(person(witness));
    assertThat(repo.rows.get(c.id()).status()).isEqualTo(ComplaintStatus.DANG_GIAI_TRINH);

    // Trả lời lần hai cùng yêu cầu bị từ chối.
    assertThatThrownBy(
            () ->
                service.submitExplanation(
                    T,
                    member(witness),
                    c.id(),
                    etag(repo.rows.get(c.id())),
                    first,
                    "Lần hai",
                    null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    // Người không phải bên được yêu cầu: không thấy khiếu nại.
    assertThatThrownBy(
            () ->
                service.submitExplanation(
                    T, member(reviewer), c.id(), etag(repo.rows.get(c.id())), second, "X", null))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                service.submitExplanation(
                    T, member(witness), c.id(), etag(repo.rows.get(c.id())), null, "X", null))
        .isInstanceOf(NotFoundException.class);

    events.clear();
    service.submitExplanation(
        T,
        managerOf(unit),
        c.id(),
        etag(repo.rows.get(c.id())),
        second,
        "Đơn vị giải trình",
        List.of(UUID.randomUUID()));
    assertThat(repo.rows.get(c.id()).status()).isEqualTo(ComplaintStatus.CHO_KET_LUAN);
    assertThat(events).containsExactly("KHIEU_NAI_CHO_KET_LUAN:CHO_KET_LUAN");
    assertThat(recipients.get(recipients.size() - 1)).containsExactly(handler);
    assertThat(audits).contains("GIAI_TRINH_KHIEU_NAI");
  }

  @Test
  void giaiTrinhQuaHanBiTuChoiNhungVanChuyenChoKetLuanBangTacVu() {
    Complaint c = requested(List.of(person(witness), person(reviewer)), LocalDate.of(2026, 10, 22));
    UUID first = process.of(c.id(), ComplaintRecordKind.YEU_CAU).get(0).id();
    service.submitExplanation(
        T, member(witness), c.id(), etag(repo.rows.get(c.id())), first, "Đúng hạn", null);
    assertThat(service.settleExplanations(T, 50)).isZero();

    // Sang ngày 23/10: bên thứ hai quá hạn.
    clock = Clock.fixed(NOW.plusSeconds(2 * 86_400), ZoneOffset.UTC);
    build(true, true);
    UUID second = process.of(c.id(), ComplaintRecordKind.YEU_CAU).get(1).id();
    assertThatThrownBy(
            () ->
                service.submitExplanation(
                    T, member(reviewer), c.id(), etag(repo.rows.get(c.id())), second, "Muộn", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    assertThat(service.settleExplanations(T, 50)).isEqualTo(1);
    assertThat(repo.rows.get(c.id()).status()).isEqualTo(ComplaintStatus.CHO_KET_LUAN);
    assertThat(service.settleExplanations(T, 50)).isZero();
  }

  @Test
  void giaiTrinhKiemNoiDungVaTrangThai() {
    Complaint c = requested(List.of(person(witness)), null);
    UUID q = process.of(c.id(), ComplaintRecordKind.YEU_CAU).get(0).id();
    assertThatThrownBy(
            () -> service.submitExplanation(T, member(witness), c.id(), etag(c(c)), q, " ", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    List<UUID> many = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      many.add(UUID.randomUUID());
    }
    assertThatThrownBy(
            () -> service.submitExplanation(T, member(witness), c.id(), etag(c(c)), q, "Có", many))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors().get(0).field())
                    .isEqualTo("attachmentObjectIds"));
    // Một người khác cùng đơn vị nhưng không giữ vai trò quản lý không trả lời thay đơn vị.
    Complaint u = requested(List.of(unitParty(unit)), null);
    UUID uq = process.of(u.id(), ComplaintRecordKind.YEU_CAU).get(0).id();
    assertThatThrownBy(
            () ->
                service.submitExplanation(
                    T,
                    new Actor(witness, List.of("manager"), unit, null, null),
                    u.id(),
                    etag(c(u)),
                    uq,
                    "X",
                    null))
        .isInstanceOf(NotFoundException.class);
    // Khiếu nại đã sang chờ kết luận.
    Complaint moved = withStatus(c(c), ComplaintStatus.CHO_KET_LUAN);
    assertThatThrownBy(
            () -> service.submitExplanation(T, member(witness), c.id(), etag(moved), q, "Có", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
  }

  @Test
  void phamViXemBanGhi() {
    Complaint c = requested(List.of(person(witness), unitParty(otherUnit)), null);
    UUID q = process.of(c.id(), ComplaintRecordKind.YEU_CAU).get(0).id();
    service.submitExplanation(T, member(witness), c.id(), etag(c(c)), q, "Giải trình", null);
    audits.clear();

    // Người xử lý thấy tất cả, đọc nội dung có ghi nhật ký.
    List<Record> all = service.responses(T, appraiser(handler), c.id(), null, null, 50);
    assertThat(all).hasSize(3);
    assertThat(all).allMatch(r -> r.content() != null);
    assertThat(audits).containsExactly("XEM_TRA_LOI_KHIEU_NAI");

    // Bên được yêu cầu chỉ thấy bản ghi của mình.
    List<Record> mine = service.responses(T, member(witness), c.id(), null, null, 50);
    assertThat(mine)
        .extracting(r -> r.response().kind())
        .containsExactly(ComplaintRecordKind.YEU_CAU, ComplaintRecordKind.GIAI_TRINH);

    // Người khiếu nại chưa thấy gì khi chưa có kết luận.
    assertThat(service.responses(T, owner(), c.id(), null, null, 50)).isEmpty();

    // Cán bộ cùng đơn vị nhưng đã tham gia hồ sơ: thấy bản ghi nhưng không đọc nội dung.
    List<Record> blind = service.responses(T, appraiser(reviewer), c.id(), null, null, 50);
    assertThat(blind).hasSize(3).allMatch(r -> r.content() == null);

    // Người ngoài.
    assertThatThrownBy(() -> service.responses(T, member(manager), c.id(), null, null, 50))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.responses(T, owner(), UUID.randomUUID(), null, null, 50))
        .isInstanceOf(NotFoundException.class);
  }

  // ---------------------------------------------------------------- kết luận

  @Test
  void ketLuanKhongQuaGiaiTrinhCanGhiChu() {
    Complaint c = accepted();
    assertThatThrownBy(() -> service.saveConclusion(T, appraiser(handler), c.id(), etag(c), keep()))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors().get(0).field())
                    .isEqualTo("noExplanationNote"));
    Resolved r =
        service.saveConclusion(
            T,
            appraiser(handler),
            c.id(),
            etag(c),
            new ConclusionInput(
                ComplaintResolution.GIU_NGUYEN, List.of(), TEXT, "123/TB", "Hồ sơ đã rõ."));
    assertThat(r.view().complaint().status()).isEqualTo(ComplaintStatus.CHO_KET_LUAN);
    assertThat(r.conclusion().noExplanationNote()).isEqualTo("Hồ sơ đã rõ.");
    assertThat(r.conclusion().docRef()).isEqualTo("123/TB");
    assertThat(reopened).isEmpty();
    assertThat(service.conclusionOf(T, c(c))).isPresent();

    // Ghi lại ở chờ kết luận: không đổi trạng thái, bỏ ghi chú.
    Resolved again = service.saveConclusion(T, appraiser(handler), c.id(), etag(c(c)), keep());
    assertThat(again.conclusion().noExplanationNote()).isNull();
    assertThat(c(c).status()).isEqualTo(ComplaintStatus.CHO_KET_LUAN);
    assertThat(audits).containsExactly("KET_LUAN_KHIEU_NAI", "KET_LUAN_KHIEU_NAI");
  }

  @Test
  void ketLuanDieuChinhTrenKyDaKhoaTaoYeuCauMoLaiMotLan() {
    Complaint c = waiting();
    Resolved r =
        service.saveConclusion(T, appraiser(handler), c.id(), etag(c), adjust("TONG_DIEM", 93));
    assertThat(r.periodLocked()).isTrue();
    assertThat(reopened).hasSize(1);
    assertThat(r.conclusion().reopenRequestId()).isEqualTo(reopened.get(0));
    // Dữ liệu kỳ không bị sửa: hồ sơ vẫn ở nhánh khiếu nại, chưa trả về.
    assertThat(profiles.returned).isEmpty();
    assertThat(profiles.rows.get(profileId).status()).isEqualTo("KHIEU_NAI");

    Resolved again =
        service.saveConclusion(T, appraiser(handler), c.id(), etag(c(c)), adjust("TONG_DIEM", 94));
    assertThat(reopened).hasSize(1);
    assertThat(again.conclusion().reopenRequestId()).isEqualTo(reopened.get(0));
    assertThat(again.conclusion().adjustments().get(0).newValue()).isEqualTo(new BigDecimal("94"));
  }

  @Test
  void ketLuanDieuChinhKyChuaKhoaKhongMoLai() {
    periods.status = PeriodStatus.MO;
    Complaint c = waiting();
    Resolved r =
        service.saveConclusion(T, appraiser(handler), c.id(), etag(c), adjust("TONG_DIEM", 93));
    assertThat(r.periodLocked()).isFalse();
    assertThat(reopened).isEmpty();
  }

  @Test
  void ketLuanDieuChinhKhongCoCongMoLaiThi503() {
    build(true, false);
    Complaint c = waiting();
    assertThatThrownBy(
            () ->
                service.saveConclusion(
                    T, appraiser(handler), c.id(), etag(c), adjust("TONG_DIEM", 93)))
        .isInstanceOf(ComplaintService.SigningUnavailableException.class);
  }

  @Test
  void ketLuanKiemDieuChinh() {
    Complaint c = waiting();
    ConclusionInput bad =
        new ConclusionInput(
            ComplaintResolution.CHAP_NHAN_MOT_PHAN,
            List.of(
                new ComplaintAdjustment("KHAC", null, 1, "Căn cứ"),
                new ComplaintAdjustment("TONG_DIEM", 91, 91, ""),
                new ComplaintAdjustment("TONG_DIEM", null, -1, "Căn cứ"),
                new ComplaintAdjustment("TONG_DIEM.A", null, " ", "Căn cứ")),
            "",
            "x".repeat(201),
            "x".repeat(2001));
    assertThatThrownBy(() -> service.saveConclusion(T, appraiser(handler), c.id(), etag(c), bad))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors())
                    .extracting(x -> x.field())
                    .containsExactly(
                        "responseText",
                        "responseDocRef",
                        "noExplanationNote",
                        "adjustments[0].targetKey",
                        "adjustments[1].newValue",
                        "adjustments[1].reason",
                        "adjustments[2].targetKey",
                        "adjustments[2].newValue",
                        "adjustments[3].newValue"));
    assertThatThrownBy(
            () ->
                service.saveConclusion(
                    T,
                    appraiser(handler),
                    c.id(),
                    etag(c),
                    new ConclusionInput(ComplaintResolution.CHAP_NHAN, null, TEXT, null, null)))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors().get(0).field())
                    .isEqualTo("adjustments"));
    assertThatThrownBy(
            () ->
                service.saveConclusion(
                    T,
                    appraiser(handler),
                    c.id(),
                    etag(c),
                    new ConclusionInput(null, null, TEXT, null, null)))
        .satisfies(
            e -> assertThat(((BusinessException) e).errors().get(0).field()).isEqualTo("outcome"));
    assertThatThrownBy(() -> service.saveConclusion(T, appraiser(handler), c.id(), etag(c), null))
        .isInstanceOf(BusinessException.class);
    Complaint fresh = filed();
    assertThatThrownBy(
            () -> service.saveConclusion(T, appraiser(handler), fresh.id(), etag(fresh), keep()))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
  }

  // ---------------------------------------------------------------- ký trả lời

  @Test
  void kyTraLoiGiuNguyenTraHoSoVeDaKySo() {
    Complaint c = waiting();
    service.saveConclusion(T, appraiser(handler), c.id(), etag(c), keep());
    UUID sign =
        service.respond(T, decider(decider), c.id(), etag(c(c)), "k1", "Bearer x", "Đồng ý");
    assertThat(signed).containsExactly(sign);
    assertThat(process.conclusions.get(c.id()).signRequestId()).isEqualTo(sign);
    assertThat(process.of(c.id(), ComplaintRecordKind.KET_LUAN)).hasSize(1);
    assertThat(audits).contains("KY_TRA_LOI_KHIEU_NAI");
    // Gọi lại khi đang chờ ký trả cùng yêu cầu ký.
    assertThat(service.respond(T, decider(decider), c.id(), etag(c(c)), "k2", null, null))
        .isEqualTo(sign);
    assertThat(signed).hasSize(1);
    // Đang chờ ký thì không sửa kết luận được.
    assertThatThrownBy(
            () -> service.saveConclusion(T, appraiser(handler), c.id(), etag(c(c)), keep()))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));

    assertThat(service.responseJob(T, sign))
        .get()
        .satisfies(
            j -> {
              assertThat(j.responded()).isFalse();
              assertThat(j.responderId()).isEqualTo(decider);
            });
    assertThat(service.responseSource(T, c.id()))
        .get()
        .satisfies(
            s -> {
              assertThat(s.signer()).isEqualTo(decider);
              assertThat(s.sha256()).hasSize(32);
              assertThat(s.ref()).isEqualTo(c.code());
            });

    events.clear();
    assertThat(service.onResponseSigned(T, sign, NOW.plusSeconds(60))).isTrue();
    assertThat(c(c).status()).isEqualTo(ComplaintStatus.DA_TRA_LOI);
    assertThat(events).containsExactly("KHIEU_NAI_DA_TRA_LOI:DA_TRA_LOI");
    assertThat(recipients.get(recipients.size() - 1)).containsExactly(complainant);
    // Hồ sơ còn ở nhánh khiếu nại tới khi khiếu nại đóng (bảo lưu hoặc hết hạn xác nhận).
    assertThat(profiles.returned).isEmpty();
    assertThat(service.onResponseSigned(T, sign, null)).isFalse();
    assertThat(service.onResponseSigned(T, UUID.randomUUID(), null)).isFalse();
    assertThat(service.responseJob(T, sign))
        .get()
        .satisfies(
            j -> {
              assertThat(j.responded()).isTrue();
              assertThat(j.respondedAt()).isEqualTo(NOW.plusSeconds(60));
            });
    assertThat(service.responseSource(T, c.id())).isEmpty();
    assertThat(service.responseJob(T, UUID.randomUUID())).isEmpty();

    // Người khiếu nại thấy văn bản kết luận sau khi trả lời.
    assertThat(service.responses(T, owner(), c.id(), null, null, 50))
        .extracting(r -> r.response().kind())
        .containsExactly(ComplaintRecordKind.KET_LUAN);
  }

  @Test
  void kyTraLoiCoDieuChinhChuyenDangThiHanh() {
    Complaint c = waiting();
    service.saveConclusion(T, appraiser(handler), c.id(), etag(c), adjust("TONG_DIEM", 93));
    UUID sign = service.respond(T, decider(decider), c.id(), etag(c(c)), "k1", null, null);
    assertThat(service.onResponseSigned(T, sign, null)).isTrue();
    assertThat(c(c).status()).isEqualTo(ComplaintStatus.DANG_THI_HANH);
    assertThat(profiles.returned).isEmpty();
    assertThat(process.conclusions.get(c.id()).respondedAt()).isEqualTo(NOW);
  }

  @Test
  void taiLieuCanKyVanBanTraLoiSanSangTrongLoiGoiSvc10() {
    Complaint c = waiting();
    service.saveConclusion(T, appraiser(handler), c.id(), etag(c), adjust("TONG_DIEM", 93));
    assertThat(service.responseSource(T, c.id())).isEmpty();
    List<ComplaintResolutionService.ResponseSource> seen = new ArrayList<>();
    duringSign = id -> seen.add(service.responseSource(T, id).orElseThrow());
    UUID sign = service.respond(T, decider(decider), c.id(), etag(c(c)), "k1", null, null);
    assertThat(seen).singleElement().satisfies(x -> assertThat(x.signer()).isEqualTo(decider));
    assertThat(repo.responseSigners).isEmpty();
    assertThat(service.responseSource(T, c.id()).orElseThrow().sha256())
        .isEqualTo(seen.get(0).sha256());
    assertThat(process.conclusions.get(c.id()).signRequestId()).isEqualTo(sign);
  }

  @Test
  void svc10LoiThiGoNguoiKyDangMoVanBanTraLoi() {
    Complaint c = waiting();
    service.saveConclusion(T, appraiser(handler), c.id(), etag(c), adjust("TONG_DIEM", 93));
    duringSign =
        id -> {
          throw new IllegalStateException("SVC-10 lỗi");
        };
    assertThatThrownBy(
            () -> service.respond(T, decider(decider), c.id(), etag(c(c)), "k1", null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repo.responseSigners).isEmpty();
    assertThat(service.responseSource(T, c.id())).isEmpty();
    assertThat(process.conclusions.get(c.id()).signing()).isFalse();
    duringSign = id -> {};
    assertThat(service.respond(T, decider(decider), c.id(), etag(c(c)), "k2", null, null))
        .isNotNull();
  }

  @Test
  void khieuNaiDoiTrongLucGoiSvc10ThiKhongGhiKetLuan() {
    Complaint c = waiting();
    service.saveConclusion(T, appraiser(handler), c.id(), etag(c), adjust("TONG_DIEM", 93));
    duringSign = id -> repo.rows.put(id, repo.rows.get(id).touched());
    assertThatThrownBy(
            () -> service.respond(T, decider(decider), c.id(), etag(c(c)), "k1", null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-VERSION-CONFLICT"));
    assertThat(process.conclusions.get(c.id()).signing()).isFalse();
    duringSign = id -> {};
    repo.failMark = true;
    assertThatThrownBy(
            () -> service.respond(T, decider(decider), c.id(), etag(c(c)), "k2", null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-VERSION-CONFLICT"));
  }

  @Test
  void kyTraLoiKiemQuyen() {
    Complaint c = waiting();
    Actor weak = new Actor(decider, List.of("decider"), unit, "aal1", NOW.getEpochSecond());
    assertThatThrownBy(() -> service.respond(T, weak, c.id(), etag(c), "k", null, null))
        .isInstanceOf(StepUpRequiredException.class);
    Actor stale =
        new Actor(decider, List.of("decider"), unit, "aal2", NOW.getEpochSecond() - 3_600);
    assertThatThrownBy(() -> service.respond(T, stale, c.id(), etag(c), "k", null, null))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                service.respond(
                    T,
                    new Actor(witness, List.of("appraiser"), unit, "aal2", NOW.getEpochSecond()),
                    c.id(),
                    etag(c),
                    "k",
                    null,
                    null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    Actor far = new Actor(decider, List.of("decider"), otherUnit, "aal2", NOW.getEpochSecond());
    assertThatThrownBy(() -> service.respond(T, far, c.id(), etag(c), "k", null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    Actor self =
        new Actor(handler, List.of("decider", "appraiser"), unit, "aal2", NOW.getEpochSecond());
    assertThatThrownBy(() -> service.respond(T, self, c.id(), etag(c), "k", null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES"));
    assertThatThrownBy(
            () -> service.respond(T, decider(reviewer), c.id(), etag(c), "k", null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES"));
    assertThatThrownBy(() -> service.respond(T, decider(decider), c.id(), etag(c), "k", null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-422-CONCLUSION-MISSING"));
    assertThatThrownBy(
            () ->
                service.respond(T, decider(decider), c.id(), etag(c), "k", null, "x".repeat(2001)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    Complaint other = accepted();
    assertThatThrownBy(
            () -> service.respond(T, decider(decider), other.id(), etag(other), "k", null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));

    build(false, true);
    service.saveConclusion(T, appraiser(handler), c.id(), etag(c), keep());
    assertThatThrownBy(
            () -> service.respond(T, decider(decider), c.id(), etag(c(c)), "k", null, null))
        .isInstanceOf(ComplaintService.SigningUnavailableException.class);
  }

  @Test
  void khongCoBoMaHoaThiTuChoi() {
    service =
        new ComplaintResolutionService(
            repo,
            process,
            filing,
            profiles,
            new Directory(),
            (t, r, u, a) -> List.of(),
            null,
            null,
            null,
            null,
            new ComplaintEvents() {
              @Override
              public void filed(TenantId tenant, Complaint c) {}

              @Override
              public void notFiled(TenantId tenant, Complaint c) {}

              @Override
              public void stateChanged(
                  TenantId tenant, Complaint c, String t, Collection<UUID> to) {}
            },
            (tenant, actor, action, type, id, purpose, role) -> {},
            TransactionOperations.withoutTransaction(),
            clock);
    Complaint c = accepted();
    assertThatThrownBy(
            () ->
                service.requestExplanations(
                    T,
                    appraiser(handler),
                    c.id(),
                    etag(c),
                    List.of(person(witness)),
                    "Nội dung",
                    null))
        .isInstanceOf(ComplaintService.SealingUnavailableException.class);
  }

  // ---------------------------------------------------------------- bảo lưu, đóng, trùng

  @Test
  void baoLuuTrongHanDongKhieuNaiVaTraHoSo() {
    Complaint c = answered(ComplaintResolution.GIU_NGUYEN, NOW);
    audits.clear();
    Resolved r = service.reserve(T, owner(), c.id(), etag(c(c)), "  Tôi vẫn giữ ý kiến.  ");
    assertThat(c(c).status()).isEqualTo(ComplaintStatus.DONG);
    assertThat(c(c).handling().closedAt()).isEqualTo(NOW);
    assertThat(r.conclusion().reservedAt()).isEqualTo(NOW);
    assertThat(
            sealer.open(
                T, "oversight.complaint.reserve_opinion_enc", c.id(), process.reserved.get(c.id())))
        .isEqualTo("Tôi vẫn giữ ý kiến.");
    assertThat(process.of(c.id(), ComplaintRecordKind.BAO_LUU)).hasSize(1);
    assertThat(profiles.returned).containsExactly(profileId);
    assertThat(audits).containsExactly("BAO_LUU_Y_KIEN_KHIEU_NAI", "DONG_KHIEU_NAI");
    // Người khiếu nại thấy văn bản trả lời và ý kiến bảo lưu của mình.
    assertThat(service.responses(T, owner(), c.id(), null, null, 50))
        .extracting(x -> x.response().kind())
        .containsExactly(ComplaintRecordKind.KET_LUAN, ComplaintRecordKind.BAO_LUU);
    // Đã đóng: bảo lưu lần hai bị từ chối.
    assertThatThrownBy(() -> service.reserve(T, owner(), c.id(), etag(c(c)), "Lần hai"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
  }

  @Test
  void baoLuuQuaNamNgayLamViecBi422() {
    // Ký thứ Tư 21/10; hạn là thứ Tư 28/10; thứ Năm 29/10 đã quá hạn.
    Complaint c = answered(ComplaintResolution.GIU_NGUYEN, NOW);
    clock = Clock.fixed(Instant.parse("2026-10-28T09:00:00Z"), ZoneOffset.UTC);
    build(true, true);
    service.responses(T, owner(), c.id(), null, null, 1);
    clock = Clock.fixed(Instant.parse("2026-10-29T03:00:00Z"), ZoneOffset.UTC);
    build(true, true);
    assertThatThrownBy(() -> service.reserve(T, owner(), c.id(), etag(c(c)), "Muộn"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-422-DEADLINE-PASSED"));
    assertThat(c(c).status()).isEqualTo(ComplaintStatus.DA_TRA_LOI);
  }

  @Test
  void baoLuuKiemNguoiGoiTrangThaiVaNoiDung() {
    Complaint c = answered(ComplaintResolution.GIU_NGUYEN, NOW);
    assertThatThrownBy(() -> service.reserve(T, member(witness), c.id(), etag(c(c)), "X"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.reserve(T, owner(), c.id(), etag(c(c)), " "))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    Complaint waiting = waiting();
    assertThatThrownBy(() -> service.reserve(T, owner(), waiting.id(), etag(waiting), "X"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    // DA_TRA_LOI nhưng không có văn bản đã ký (dữ liệu lệch): 409.
    Complaint odd = withStatus(accepted(), ComplaintStatus.DA_TRA_LOI);
    assertThatThrownBy(() -> service.reserve(T, owner(), odd.id(), etag(odd), "X"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
  }

  @Test
  void baoLuuKhiDekDaXoayThiMaLaiCaDong() {
    Complaint c = answered(ComplaintResolution.GIU_NGUYEN, NOW);
    Complaint base = withSealed(c(c));
    sealer.rotateAfter = sealer.sealed;
    service.reserve(T, owner(), c.id(), etag(base), "Bảo lưu");
    Complaint saved = c(c);
    assertThat(saved.dekId()).isNotEqualTo(base.dekId()).isEqualTo(sealer.dek);
    assertThat(sealer.dekOf(saved.contentEnc())).isEqualTo(saved.dekId());
    assertThat(sealer.open(T, "oversight.complaint.content_enc", c.id(), saved.contentEnc()))
        .isEqualTo("Nội dung khiếu nại");
  }

  @Test
  void tuDongDongChayHaiLanChiGhiMotLan() {
    Complaint expired = answered(ComplaintResolution.GIU_NGUYEN, NOW);
    Complaint fresh = answered(ComplaintResolution.GIU_NGUYEN, NOW.plusSeconds(6 * 86_400));
    Complaint executing = answered(ComplaintResolution.CHAP_NHAN_MOT_PHAN, NOW);
    Complaint pending = answered(ComplaintResolution.CHAP_NHAN_MOT_PHAN, NOW);
    Complaint rejected = withStatus(filed(), ComplaintStatus.KHONG_THU_LY);
    process.executed.add(process.conclusions.get(executing.id()).reopenRequestId());
    // 30/10 (thứ Sáu): đã qua hạn bảo lưu của khiếu nại ký 21/10, chưa qua hạn của khiếu nại ký
    // 27/10.
    clock = Clock.fixed(Instant.parse("2026-10-30T03:00:00Z"), ZoneOffset.UTC);
    build(true, true);
    audits.clear();
    events.clear();
    assertThat(service.closeDue(T, 50)).isEqualTo(3);
    assertThat(c(expired).status()).isEqualTo(ComplaintStatus.DONG);
    assertThat(c(executing).status()).isEqualTo(ComplaintStatus.DONG);
    assertThat(c(rejected).status()).isEqualTo(ComplaintStatus.DONG);
    assertThat(c(fresh).status()).isEqualTo(ComplaintStatus.DA_TRA_LOI);
    assertThat(c(pending).status()).isEqualTo(ComplaintStatus.DANG_THI_HANH);
    assertThat(audits).containsExactly("DONG_KHIEU_NAI", "DONG_KHIEU_NAI", "DONG_KHIEU_NAI");
    assertThat(events).allMatch(e -> e.equals("KHIEU_NAI_DA_DONG:DONG")).hasSize(3);
    assertThat(service.closeDue(T, 50)).isZero();
    assertThat(audits).hasSize(3);
  }

  @Test
  void goiYKhieuNaiTrungDaTraLoi() {
    Complaint old = answered(ComplaintResolution.GIU_NGUYEN, NOW.minusSeconds(86_400));
    Complaint other = withTargets(filed(), List.of("KHAC"));
    Complaint current = filed();
    List<ComplaintResolutionService.Duplicate> d =
        service.duplicates(T, appraiser(handler), current.id());
    assertThat(d).extracting(x -> x.complaint().id()).containsExactly(old.id());
    assertThat(d.get(0).similarity()).isEqualTo(1.0);
    assertThat(d.get(0).matchedTargets()).containsExactly("TONG_DIEM");
    assertThat(d.get(0).summary()).isEqualTo("Giữ nguyên kết quả");
    assertThat(other.status()).isEqualTo(ComplaintStatus.MOI);
    assertThatThrownBy(() -> service.duplicates(T, member(witness), current.id()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.duplicates(T, appraiser(handler), UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
  }

  /** Khiếu nại đã ký văn bản trả lời lúc {@code at}. */
  private Complaint answered(ComplaintResolution resolution, Instant at) {
    Complaint c = waiting();
    ConclusionInput in = resolution.adjusts() ? adjust("TONG_DIEM", 93) : keep();
    service.saveConclusion(T, appraiser(handler), c.id(), etag(c), in);
    UUID sign = service.respond(T, decider(decider), c.id(), etag(c(c)), "k", null, null);
    service.onResponseSigned(T, sign, at);
    return c(c);
  }

  private Complaint withSealed(Complaint c) {
    ComplaintSealerSealed s =
        new ComplaintSealerSealed(
            sealer.seal(T, "oversight.complaint.content_enc", c.id(), "Nội dung khiếu nại"));
    Complaint changed = c.resealed(s.cipher(), null, s.dek());
    repo.rows.put(c.id(), changed);
    return changed;
  }

  private record ComplaintSealerSealed(byte[] cipher, UUID dek) {
    ComplaintSealerSealed(vn.viettel.okrkpi.oversight.application.port.ComplaintSealer.Sealed s) {
      this(s.cipher(), s.dekId());
    }
  }

  private Complaint withTargets(Complaint c, List<String> keys) {
    Complaint changed =
        new Complaint(
            c.id(),
            c.code(),
            c.periodId(),
            c.periodBucket(),
            c.complainantId(),
            c.profileId(),
            c.kind(),
            c.targetStage(),
            c.subjectLine(),
            keys.stream().map(k -> new ComplaintTarget(k, ComplaintStage.THAM_DINH, k)).toList(),
            c.contentEnc(),
            c.requestTextEnc(),
            c.dekId(),
            c.attachmentIds(),
            c.reserveOpinion(),
            c.status(),
            c.filedAt(),
            c.responseDeadline(),
            c.handlerUnitId(),
            c.signRequestId(),
            c.createdAt(),
            c.rowVersion(),
            c.handling());
    repo.rows.put(c.id(), changed);
    return changed;
  }

  // ---------------------------------------------------------------- hỗ trợ

  private ConclusionInput keep() {
    return new ConclusionInput(ComplaintResolution.GIU_NGUYEN, List.of(), TEXT, null, null);
  }

  private ConclusionInput adjust(String key, int value) {
    return new ConclusionInput(
        ComplaintResolution.CHAP_NHAN_MOT_PHAN,
        List.of(new ComplaintAdjustment(key, new BigDecimal("91.2"), value, "Bổ sung minh chứng")),
        TEXT,
        "45/TB",
        null);
  }

  private Complaint c(Complaint c) {
    return repo.rows.get(c.id());
  }

  private Actor appraiser(UUID id) {
    return new Actor(id, List.of("appraiser"), unit, "aal2", NOW.getEpochSecond());
  }

  /** Người ký mặc định: {@code decider}, AAL2 vừa xác thực, cùng đơn vị. */
  private Actor decider(UUID id) {
    return new Actor(id, List.of("decider"), unit, "aal2", NOW.getEpochSecond());
  }

  private Actor member(UUID id) {
    return new Actor(id, List.of("member"), otherUnit, null, null);
  }

  private Actor managerOf(UUID u) {
    return new Actor(manager, List.of("manager"), u, null, null);
  }

  private Actor owner() {
    return new Actor(complainant, List.of("member"), unit, null, null);
  }

  private static ExplanationParty person(UUID id) {
    return new ExplanationParty(ExplanationParty.Kind.NGUOI, id);
  }

  private static ExplanationParty unitParty(UUID id) {
    return new ExplanationParty(ExplanationParty.Kind.DON_VI, id);
  }

  private static String etag(Complaint c) {
    return ETags.of(c.rowVersion());
  }

  private static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  private Complaint filed() {
    Complaint c =
        new Complaint(
                UUID.randomUUID(),
                "KN-2026-" + String.format("%04d", repo.rows.size() + 1),
                UUID.randomUUID(),
                20264,
                complainant,
                profileId,
                ComplaintKind.KHIEU_NAI,
                ComplaintStage.THAM_DINH,
                "Khiếu nại kết quả xếp loại Quý IV/2026",
                List.of(new ComplaintTarget("TONG_DIEM", ComplaintStage.THAM_DINH, "Tổng điểm")),
                new byte[] {1},
                null,
                UUID.randomUUID(),
                List.of(),
                false,
                null,
                null,
                null,
                null,
                null,
                NOW,
                0,
                null)
            .filed(NOW, LocalDate.of(2026, 11, 11), unit, LocalDate.of(2026, 10, 23));
    repo.rows.put(c.id(), c);
    return c;
  }

  private Complaint accepted() {
    Complaint c = filed().accepted(handler, NOW);
    repo.rows.put(c.id(), c);
    return c;
  }

  private Complaint waiting() {
    return withStatus(accepted(), ComplaintStatus.CHO_KET_LUAN);
  }

  private Complaint requested(List<ExplanationParty> parties, LocalDate due) {
    Complaint c = accepted();
    service.requestExplanations(
        T, appraiser(handler), c.id(), etag(c), parties, "Đề nghị giải trình.", due);
    events.clear();
    recipients.clear();
    audits.clear();
    return repo.rows.get(c.id());
  }

  private Complaint withStatus(Complaint c, ComplaintStatus status) {
    Complaint changed =
        new Complaint(
            c.id(),
            c.code(),
            c.periodId(),
            c.periodBucket(),
            c.complainantId(),
            c.profileId(),
            c.kind(),
            c.targetStage(),
            c.subjectLine(),
            c.targets(),
            c.contentEnc(),
            c.requestTextEnc(),
            c.dekId(),
            c.attachmentIds(),
            c.reserveOpinion(),
            status,
            c.filedAt(),
            c.responseDeadline(),
            c.handlerUnitId(),
            c.signRequestId(),
            c.createdAt(),
            c.rowVersion(),
            c.handling());
    repo.rows.put(c.id(), changed);
    return changed;
  }

  private final class Directory implements ComplaintHandlerDirectory {
    @Override
    public List<UUID> appraisersAt(TenantId tenant, UUID orgUnitId, Instant at) {
      return List.of(handler);
    }

    @Override
    public List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId) {
      return List.of();
    }

    @Override
    public List<UUID> subunitsOf(TenantId tenant, UUID orgUnitId) {
      return List.of();
    }

    @Override
    public Map<String, Set<UUID>> participants(TenantId tenant, Complaint c) {
      Participants p = profiles.participants(tenant, c.profileId());
      Map<String, Set<UUID>> out = new LinkedHashMap<>();
      out.put("appraiser", p.appraisers());
      out.put("proposer", p.proposers());
      out.put("decider", p.deciders());
      return out;
    }
  }

  private static final class MutablePeriods implements PeriodApi {
    PeriodStatus status = PeriodStatus.DA_KHOA;

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
              status));
    }

    @Override
    public void assertOpen(TenantId tenantId, PeriodId periodId) throws PeriodLockedException {}
  }

  static final class FakeProcess implements ComplaintProcessRepository {
    final Map<UUID, ComplaintConclusion> conclusions = new HashMap<>();
    final List<ComplaintResponse> rows = new ArrayList<>();
    final Map<UUID, byte[]> reserved = new HashMap<>();
    final Set<UUID> executed = new java.util.HashSet<>();
    FakeComplaints complaints;

    List<ComplaintResponse> of(UUID complaintId, ComplaintRecordKind kind) {
      return rows.stream()
          .filter(r -> r.complaintId().equals(complaintId) && r.kind() == kind)
          .toList();
    }

    @Override
    public Optional<ComplaintConclusion> conclusion(TenantId tenant, Complaint complaint) {
      ComplaintConclusion k = conclusions.get(complaint.id());
      if (k == null) {
        return Optional.empty();
      }
      Instant reservedAt =
          of(complaint.id(), ComplaintRecordKind.BAO_LUU).stream()
              .map(ComplaintResponse::respondedAt)
              .max(Comparator.naturalOrder())
              .orElse(null);
      return Optional.of(
          new ComplaintConclusion(
              k.resolution(),
              k.adjustments(),
              k.textEnc(),
              k.dekId(),
              k.docRef(),
              k.noExplanationNote(),
              k.concludedBy(),
              k.concludedAt(),
              k.reopenRequestId(),
              k.signRequestId(),
              k.respondedAt(),
              reservedAt));
    }

    @Override
    public void reserve(TenantId tenant, Complaint complaint, byte[] opinionEnc) {
      reserved.put(complaint.id(), opinionEnc);
    }

    @Override
    public List<UUID> closable(TenantId tenant, Instant respondedBefore, int limit) {
      return complaints.rows.values().stream()
          .filter(
              c ->
                  (c.status() == ComplaintStatus.DA_TRA_LOI
                          && conclusions.get(c.id()).respondedAt().isBefore(respondedBefore))
                      || c.status() == ComplaintStatus.KHONG_THU_LY
                      || (c.status() == ComplaintStatus.DANG_THI_HANH
                          && executed.contains(conclusions.get(c.id()).reopenRequestId())))
          .map(Complaint::id)
          .limit(limit)
          .toList();
    }

    @Override
    public void saveConclusion(TenantId tenant, Complaint complaint, ComplaintConclusion k) {
      conclusions.put(complaint.id(), k);
    }

    @Override
    public Optional<UUID> byResponseSignRequest(TenantId tenant, UUID signRequestId) {
      return conclusions.entrySet().stream()
          .filter(e -> signRequestId.equals(e.getValue().signRequestId()))
          .map(Map.Entry::getKey)
          .findFirst();
    }

    @Override
    public void insertResponse(TenantId tenant, ComplaintResponse r, UUID actorId) {
      rows.add(r);
    }

    @Override
    public Optional<ComplaintResponse> response(TenantId tenant, Complaint complaint, UUID id) {
      return rows.stream()
          .filter(r -> r.complaintId().equals(complaint.id()) && r.id().equals(id))
          .findFirst();
    }

    @Override
    public List<ComplaintResponse> responses(
        TenantId tenant,
        Complaint complaint,
        ComplaintRecordKind kind,
        ResponseAfter after,
        int limit) {
      return rows.stream()
          .filter(r -> r.complaintId().equals(complaint.id()))
          .filter(r -> kind == null || r.kind() == kind)
          .sorted(Comparator.comparing(ComplaintResponse::respondedAt))
          .limit(limit)
          .toList();
    }

    @Override
    public List<UUID> explanationsSettled(TenantId tenant, LocalDate today, int limit) {
      List<UUID> out = new ArrayList<>();
      for (UUID id :
          rows.stream()
              .filter(r -> r.kind() == ComplaintRecordKind.YEU_CAU)
              .map(ComplaintResponse::complaintId)
              .distinct()
              .toList()) {
        boolean open =
            rows.stream()
                .filter(r -> r.complaintId().equals(id) && r.kind() == ComplaintRecordKind.YEU_CAU)
                .anyMatch(
                    q ->
                        !today.isAfter(q.dueDate())
                            && rows.stream().noneMatch(a -> q.id().equals(a.requestId())));
        if (!open) {
          out.add(id);
        }
      }
      return out;
    }
  }
}
