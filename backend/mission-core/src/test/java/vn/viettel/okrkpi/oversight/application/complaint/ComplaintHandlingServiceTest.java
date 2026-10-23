package vn.viettel.okrkpi.oversight.application.complaint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi.Participants;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintHandlingService.ComplaintAuthz;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService.NotFoundException;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintServiceTest.FakeComplaints;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintServiceTest.FakePeriods;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintServiceTest.FakeProfiles;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintServiceTest.FakeSealer;
import vn.viettel.okrkpi.oversight.application.port.ComplaintEvents;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlerDirectory;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintCloseReason;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStage;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintTarget;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm thử đơn vị CORE-07-03: chọn người xử lý, tách vai trò, tiếp nhận, không thụ lý, rút và phạm
 * vi đọc của người xử lý.
 */
class ComplaintHandlingServiceTest {

  private static final TenantId T = TenantId.of(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");
  private static final String REJECT_REASON =
      "Nội dung trùng khiếu nại đã được trả lời bằng văn bản, không có căn cứ mới.";

  private final UUID unit = UUID.randomUUID();
  private final UUID parent = UUID.randomUUID();
  private final UUID child = UUID.randomUUID();
  private final UUID complainant = UUID.randomUUID();
  private final UUID handler = UUID.randomUUID();
  private final UUID reviewer = UUID.randomUUID();
  private final UUID acceptor = UUID.randomUUID();
  private final UUID upper = UUID.randomUUID();

  private FakeComplaints repo;
  private FakeProfiles profiles;
  private FakeDirectory directory;
  private final List<String> events = new ArrayList<>();
  private final List<Collection<UUID>> recipients = new ArrayList<>();
  private final List<String> audits = new ArrayList<>();
  private ComplaintHandlingService service;
  private UUID profileId;

  @BeforeEach
  void setUp() {
    repo = new FakeComplaints();
    profiles = new FakeProfiles(unit);
    directory = new FakeDirectory();
    directory.profiles = profiles;
    directory.appraisers.put(unit, List.of(handler, reviewer));
    directory.appraisers.put(parent, List.of(upper));
    directory.ancestors.put(unit, List.of(parent));
    directory.subunits.put(parent, List.of(unit, child));
    profileId = profiles.add(complainant, "KHIEU_NAI", NOW.minusSeconds(86_400));
    profiles.participants.put(
        profileId, new Participants(Set.of(), Set.of(reviewer), Set.of(UUID.randomUUID())));
    directory.acceptors.add(acceptor);
    ComplaintEvents sink =
        new ComplaintEvents() {
          @Override
          public void filed(TenantId tenant, Complaint c) {
            events.add("EVT-25");
          }

          @Override
          public void notFiled(TenantId tenant, Complaint c) {
            events.add("KHONG_GUI");
          }

          @Override
          public void stateChanged(
              TenantId tenant, Complaint c, String template, Collection<UUID> to) {
            events.add(template + ":" + c.status());
            recipients.add(List.copyOf(to));
          }
        };
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    ComplaintService filing =
        new ComplaintService(
            repo,
            profiles,
            new FakePeriods(),
            null,
            new FakeSealer(),
            null,
            sink,
            (tenant, actor, action, type, id, purpose, role) -> audits.add(action),
            TransactionOperations.withoutTransaction(),
            clock);
    service =
        new ComplaintHandlingService(
            repo,
            filing,
            profiles,
            directory,
            sink,
            (tenant, actor, action, type, id, purpose, role) -> audits.add(action),
            clock);
  }

  // ---------------------------------------------------------------- tiếp nhận

  @Test
  void ungVienHopLeTiepNhan() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    ComplaintService.View v = service.accept(T, appraiser(handler), c.id(), etag(c), "Đã nhận");
    Complaint saved = repo.rows.get(c.id());
    assertThat(saved.status()).isEqualTo(ComplaintStatus.DA_TIEP_NHAN);
    assertThat(saved.handling().handlerId()).isEqualTo(handler);
    assertThat(saved.handling().acceptedAt()).isEqualTo(NOW);
    assertThat(saved.rowVersion()).isEqualTo(c.rowVersion() + 1);
    assertThat(v.content()).isNull();
    assertThat(events).containsExactly("KHIEU_NAI_DA_TIEP_NHAN:DA_TIEP_NHAN");
    assertThat(recipients.get(0)).containsExactly(complainant);
    assertThat(audits).containsExactly("TIEP_NHAN_KHIEU_NAI");
  }

  @Test
  void nguoiDaThamDinhBiTuChoiVoiMaTachVaiTro() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    assertThatThrownBy(() -> service.accept(T, appraiser(reviewer), c.id(), etag(c), null))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES"))
        .satisfies(e -> assertThat(((BusinessException) e).detail()).contains("thẩm định"));
    assertThat(repo.rows.get(c.id()).status()).isEqualTo(ComplaintStatus.MOI);
    assertThat(events).isEmpty();
  }

  @Test
  void nguoiDaNghiemThuBiTuChoi() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    directory.appraisers.put(unit, List.of(handler, acceptor));
    assertThatThrownBy(() -> service.accept(T, appraiser(acceptor), c.id(), etag(c), null))
        .satisfies(e -> assertThat(((BusinessException) e).detail()).contains("nghiệm thu"));
  }

  @Test
  void khongPhaiUngVienHoacKhongDungVaiTroLaNgoaiPhamVi() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    // Người ở cấp trên không được chọn khi đơn vị xử lý còn ứng viên.
    assertThatThrownBy(() -> service.accept(T, appraiser(upper), c.id(), etag(c), null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    Actor member = new Actor(handler, List.of("member"), unit, "aal2", null);
    assertThatThrownBy(() -> service.accept(T, member, c.id(), etag(c), null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
  }

  @Test
  void donViNhoKhongConAiThiNangCapTren() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    directory.appraisers.put(unit, List.of(reviewer));
    ComplaintAuthz authz = service.authz(T, c.id());
    assertThat(authz.unitId()).isEqualTo(parent);
    assertThat(authz.candidates()).containsExactly(upper);
    service.accept(T, appraiser(upper), c.id(), etag(c), null);
    assertThat(repo.rows.get(c.id()).handling().handlerId()).isEqualTo(upper);
  }

  @Test
  void taiNguyenOpaMangNguoiThamGiaVaUngVien() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    AuthzResource r = service.authz(T, c.id()).toAuthzResource();
    assertThat(r.toOpaResource(T.value().toString(), "C1")).isNotNull();
    ComplaintAuthz a = service.authz(T, c.id());
    assertThat(a.participants().get("appraiser")).containsExactly(reviewer);
    assertThat(a.participants().get("acceptor")).containsExactly(acceptor);
    assertThat(a.candidates()).containsExactly(handler);
  }

  @Test
  void tiepNhanSaiTrangThaiHoacPhienBan() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    assertThatThrownBy(() -> service.accept(T, appraiser(handler), c.id(), "\"99\"", null))
        .isInstanceOf(VersionConflictException.class);
    service.accept(T, appraiser(handler), c.id(), etag(c), null);
    Complaint now = repo.rows.get(c.id());
    assertThatThrownBy(() -> service.accept(T, appraiser(handler), c.id(), etag(now), null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
  }

  @Test
  void ghiChuQuaDaiBi422() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    assertThatThrownBy(
            () -> service.accept(T, appraiser(handler), c.id(), etag(c), "x".repeat(2001)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void nhapKhongTiepNhanDuoc() {
    Complaint d = draft(ComplaintKind.KHIEU_NAI);
    assertThatThrownBy(() -> service.accept(T, appraiser(handler), d.id(), etag(d), null))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.authz(T, d.id())).isInstanceOf(NotFoundException.class);
  }

  // ---------------------------------------------------------------- không thụ lý

  @Test
  void khongThuLyThieuLyDoBi422() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    assertThatThrownBy(
            () ->
                service.reject(
                    T, appraiser(handler), c.id(), etag(c), ComplaintCloseReason.QUA_HAN, "  "))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.reject(T, appraiser(handler), c.id(), etag(c), null, REJECT_REASON))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.reject(
                    T,
                    appraiser(handler),
                    c.id(),
                    etag(c),
                    ComplaintCloseReason.RUT,
                    REJECT_REASON))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThat(repo.rows.get(c.id()).status()).isEqualTo(ComplaintStatus.MOI);
    assertThat(profiles.returned).isEmpty();
  }

  @Test
  void khongThuLyDongNgayVaTraHoSo() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    service.reject(
        T,
        appraiser(handler),
        c.id(),
        etag(c),
        ComplaintCloseReason.TRUNG_NOI_DUNG,
        "  " + REJECT_REASON + " ");
    Complaint saved = repo.rows.get(c.id());
    assertThat(saved.status()).isEqualTo(ComplaintStatus.DONG);
    assertThat(saved.rowVersion()).isEqualTo(c.rowVersion() + 2);
    assertThat(saved.handling().closeReasonCode()).isEqualTo(ComplaintCloseReason.TRUNG_NOI_DUNG);
    assertThat(saved.handling().closeReason()).isEqualTo(REJECT_REASON);
    assertThat(saved.handling().handlerId()).isEqualTo(handler);
    assertThat(saved.handling().closedAt()).isEqualTo(NOW);
    assertThat(events).containsExactly("KHIEU_NAI_KHONG_THU_LY:KHONG_THU_LY");
    assertThat(profiles.returned).containsExactly(profileId);
    assertThat(profiles.rows.get(profileId).status()).isEqualTo("DA_KY_SO");
    assertThat(audits).containsExactly("KHONG_THU_LY_KHIEU_NAI");
  }

  @Test
  void khongThuLyCuaNguoiDaThamDinhBi403() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    assertThatThrownBy(
            () ->
                service.reject(
                    T,
                    appraiser(reviewer),
                    c.id(),
                    etag(c),
                    ComplaintCloseReason.QUA_HAN,
                    REJECT_REASON))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES"));
  }

  @Test
  void baoLuuKhongDoiTrangThaiHoSo() {
    Complaint c = filed(ComplaintKind.BAO_LUU);
    service.reject(
        T, appraiser(handler), c.id(), etag(c), ComplaintCloseReason.SAI_THAM_QUYEN, REJECT_REASON);
    assertThat(profiles.returned).isEmpty();
  }

  // ---------------------------------------------------------------- rút

  @Test
  void rutTruocKetLuanDongVaBaoNguoiXuLy() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    service.accept(T, appraiser(handler), c.id(), etag(c), null);
    Complaint accepted = repo.rows.get(c.id());
    service.withdraw(
        T, owner(), c.id(), etag(accepted), "Đã trao đổi và thống nhất với người nghiệm thu.");
    Complaint saved = repo.rows.get(c.id());
    assertThat(saved.status()).isEqualTo(ComplaintStatus.DONG);
    assertThat(saved.handling().closeReasonCode()).isEqualTo(ComplaintCloseReason.RUT);
    assertThat(saved.contentEnc()).isEqualTo(c.contentEnc());
    assertThat(events)
        .containsExactly("KHIEU_NAI_DA_TIEP_NHAN:DA_TIEP_NHAN", "KHIEU_NAI_DA_RUT:RUT");
    assertThat(recipients.get(1)).containsExactly(handler);
    assertThat(profiles.rows.get(profileId).status()).isEqualTo("DA_KY_SO");
    assertThat(audits).containsExactly("TIEP_NHAN_KHIEU_NAI", "RUT_KHIEU_NAI");
  }

  @Test
  void rutKhiMoiChuaCoNguoiXuLyThiKhongBaoAi() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    service.withdraw(T, owner(), c.id(), etag(c), "Đồng chí tự nguyện rút khiếu nại.");
    assertThat(recipients.get(0)).isEmpty();
  }

  @Test
  void rutSauKhiDaTraLoiBi409() {
    Complaint c = withStatus(filed(ComplaintKind.KHIEU_NAI), ComplaintStatus.DA_TRA_LOI);
    assertThatThrownBy(
            () -> service.withdraw(T, owner(), c.id(), etag(c), "Đồng chí muốn rút khiếu nại."))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    assertThat(profiles.returned).isEmpty();
  }

  @Test
  void rutCanLyDoVaChiChuKhieuNai() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    assertThatThrownBy(() -> service.withdraw(T, owner(), c.id(), etag(c), "ngắn"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.withdraw(
                    T, appraiser(handler), c.id(), etag(c), "Không phải người khiếu nại."))
        .isInstanceOf(NotFoundException.class);
    Complaint d = draft(ComplaintKind.KHIEU_NAI);
    assertThatThrownBy(() -> service.withdraw(T, owner(), d.id(), etag(d), "Rút bản nháp này."))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
  }

  @Test
  void conKhieuNaiKhacMoThiGiuHoSo() {
    Complaint a = filed(ComplaintKind.KHIEU_NAI);
    filed(ComplaintKind.GIAI_TRINH);
    service.withdraw(T, owner(), a.id(), etag(a), "Rút một trong hai khiếu nại đang mở.");
    assertThat(profiles.returned).isEmpty();
  }

  // ---------------------------------------------------------------- đọc

  @Test
  void danhSachTheoDonViVaCapDuoiCuaNguoiXuLy() {
    Complaint mine = filed(ComplaintKind.KHIEU_NAI);
    Complaint other = filed(ComplaintKind.KHIEU_NAI, UUID.randomUUID());
    draft(ComplaintKind.KHIEU_NAI);
    Actor atParent = new Actor(upper, List.of("appraiser"), parent, "aal2", null);
    assertThat(service.list(T, atParent, null, Set.of(), null, false, null, 50))
        .extracting(Complaint::id)
        .containsExactly(mine.id());
    assertThat(service.list(T, atParent, null, Set.of(), null, true, null, 50)).isEmpty();
    assertThat(other.id()).isNotEqualTo(mine.id());
  }

  @Test
  void chiTietMoNoiDungVaGhiNhatKy() {
    Complaint c = filed(ComplaintKind.KHIEU_NAI);
    ComplaintService.View v = service.get(T, appraiser(handler), c.id());
    assertThat(v.content()).isEqualTo("Nội dung khiếu nại đủ dài để gửi đi.");
    assertThat(audits).containsExactly("XEM_NOI_DUNG_KHIEU_NAI");
    Actor outside = new Actor(UUID.randomUUID(), List.of("appraiser"), child, "aal2", null);
    assertThatThrownBy(() -> service.get(T, outside, c.id())).isInstanceOf(NotFoundException.class);
    assertThat(service.get(T, owner(), c.id()).content()).isNotNull();
    Complaint d = draft(ComplaintKind.KHIEU_NAI);
    assertThatThrownBy(() -> service.get(T, owner(), d.id())).isInstanceOf(NotFoundException.class);
    assertThat(ComplaintHandlingService.handlerRole(owner())).isFalse();
    assertThat(
            ComplaintHandlingService.handlerRole(
                new Actor(handler, List.of("decider"), unit, null, null)))
        .isTrue();
  }

  // ---------------------------------------------------------------- hỗ trợ

  private Actor appraiser(UUID id) {
    return new Actor(id, List.of("appraiser"), unit, "aal2", NOW.getEpochSecond());
  }

  private Actor owner() {
    return new Actor(complainant, List.of("member"), unit, "aal2", NOW.getEpochSecond());
  }

  private static String etag(Complaint c) {
    return ETags.of(c.rowVersion());
  }

  private Complaint draft(ComplaintKind kind) {
    Complaint c =
        new Complaint(
            UUID.randomUUID(),
            "KN-2026-" + String.format("%04d", repo.rows.size() + 1),
            UUID.randomUUID(),
            20264,
            complainant,
            profileId,
            kind,
            ComplaintStage.THAM_DINH,
            "Khiếu nại kết quả xếp loại Quý IV/2026",
            List.of(new ComplaintTarget("TONG_DIEM", ComplaintStage.THAM_DINH, "Tổng điểm")),
            seal("oversight.complaint.content_enc", "Nội dung khiếu nại đủ dài để gửi đi."),
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
            null);
    repo.insert(T, c, complainant);
    return c;
  }

  private Complaint filed(ComplaintKind kind) {
    return filed(kind, unit);
  }

  private Complaint filed(ComplaintKind kind, UUID handlerUnit) {
    Complaint c =
        draft(kind).filed(NOW, LocalDate.of(2026, 11, 11), handlerUnit, LocalDate.of(2026, 10, 23));
    repo.rows.put(c.id(), c);
    return c;
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

  /** Cùng phép mã hoá giả của {@link FakeSealer}. */
  private static byte[] seal(String column, String plain) {
    byte[] b = (column + "|" + plain).getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[b.length];
    for (int i = 0; i < b.length; i++) {
      out[i] = (byte) ~b[i];
    }
    return out;
  }

  private static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  private static final class FakeDirectory implements ComplaintHandlerDirectory {
    final Map<UUID, List<UUID>> appraisers = new HashMap<>();
    final Map<UUID, List<UUID>> ancestors = new HashMap<>();
    final Map<UUID, List<UUID>> subunits = new HashMap<>();
    final List<UUID> acceptors = new ArrayList<>();
    FakeProfiles profiles;

    @Override
    public List<UUID> appraisersAt(TenantId tenant, UUID orgUnitId, Instant at) {
      return appraisers.getOrDefault(orgUnitId, List.of());
    }

    @Override
    public List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId) {
      return ancestors.getOrDefault(orgUnitId, List.of());
    }

    @Override
    public List<UUID> subunitsOf(TenantId tenant, UUID orgUnitId) {
      return subunits.getOrDefault(orgUnitId, List.of());
    }

    @Override
    public Map<String, Set<UUID>> participants(TenantId tenant, Complaint c) {
      Participants p = profiles.participants(tenant, c.profileId());
      Map<String, Set<UUID>> out = new LinkedHashMap<>();
      out.put("acceptor", Set.copyOf(acceptors));
      out.put("appraiser", p.appraisers());
      out.put("proposer", p.proposers());
      out.put("decider", p.deciders());
      return out;
    }
  }
}
