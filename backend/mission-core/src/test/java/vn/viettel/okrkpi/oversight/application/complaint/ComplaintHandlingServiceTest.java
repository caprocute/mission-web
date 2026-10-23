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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi.Participants;
import vn.viettel.okrkpi.oversight.application.port.ComplaintDirectory;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlingRepository;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.complaint.CloseReason;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintHandling;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRecordKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResponse;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStage;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị CORE-07-03: tiếp nhận, không thụ lý, rút, tách vai trò và phạm vi. */
class ComplaintHandlingServiceTest {

  private static final TenantId T = TenantId.of(UUID.randomUUID());

  /** Thứ Sáu 23/10/2026, 10 giờ giờ Việt Nam. */
  private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");

  private static final String CONTENT = "Nhiệm vụ then chốt 3 bị trừ hệ số chất lượng.";
  private static final String LONG_REASON = "Nội dung đã được trả lời tại văn bản số 12.";

  private final UUID unit = UUID.randomUUID();
  private final UUID parent = UUID.randomUUID();
  private final UUID complainant = UUID.randomUUID();
  private final UUID appraiser = UUID.randomUUID();
  private final UUID handler = UUID.randomUUID();
  private final UUID upper = UUID.randomUUID();
  private final UUID profile = UUID.randomUUID();

  private FakeRepo repo;
  private FakeDirectory directory;
  private FakeProfiles profiles;
  private final List<String> notices = new ArrayList<>();
  private final List<String> audits = new ArrayList<>();
  private ComplaintHandlingService service;

  @BeforeEach
  void setUp() {
    repo = new FakeRepo();
    directory = new FakeDirectory();
    profiles = new FakeProfiles();
    directory.holders.put(unit, List.of(appraiser, handler));
    directory.holders.put(parent, List.of(upper));
    directory.ancestors.put(unit, List.of(parent));
    directory.participants =
        new Participants(
            profile,
            complainant,
            unit,
            UUID.randomUUID(),
            Set.of(),
            Set.of(appraiser),
            Set.of(),
            Set.of());
    service =
        new ComplaintHandlingService(
            repo,
            directory,
            profiles,
            null,
            null,
            new FakeSealer(),
            (tenant, c, to, template, params) ->
                notices.add(template + ":" + params.get("ma") + ":" + to),
            (tenant, actor, action, type, id, purpose, role) -> audits.add(action + ":" + role),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  // ---------------------------------------------------------------- tiếp nhận

  @Test
  void ungVienTiepNhanThanhNguoiXuLyVaBaoNguoiKhieuNai() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    var v = service.accept(T, staff(handler), id, "\"1\"", "Đã nhận đủ hồ sơ");
    assertThat(v.complaint().status()).isEqualTo(ComplaintStatus.DA_TIEP_NHAN);
    assertThat(v.complaint().rowVersion()).isEqualTo(2);
    assertThat(v.handling().handlerPersonId()).isEqualTo(handler);
    assertThat(v.handling().acceptedAt()).isEqualTo(NOW);
    assertThat(v.content()).isEqualTo(CONTENT);
    assertThat(v.workingDaysLeft()).isNotNull();
    assertThat(v.periodLocked()).isFalse();
    assertThat(notices)
        .containsExactly("KHIEU_NAI_DA_TIEP_NHAN:KN-2026-000001:[" + complainant + "]");
    assertThat(audits).containsExactly("TIEP_NHAN_KHIEU_NAI:appraiser");
    assertThat(repo.rows.get(id).handling().handlerPersonId()).isEqualTo(handler);
  }

  @Test
  void nguoiDaThamDinhHoSoGoiTiepNhanBi403TachVaiTro() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    assertThatThrownBy(() -> service.accept(T, staff(appraiser), id, "\"1\"", null))
        .satisfies(
            e -> {
              BusinessException b = (BusinessException) e;
              assertThat(b.errorCode().code()).isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES");
            });
    assertThat(repo.rows.get(id).complaint().status()).isEqualTo(ComplaintStatus.MOI);
    assertThat(notices).isEmpty();
  }

  @Test
  void nguoiKhieuNaiVaNguoiNgoaiDanhSachUngVienKhongTiepNhanDuoc() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    assertThatThrownBy(() -> service.accept(T, staff(complainant), id, "\"1\"", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES"));
    // Cấp trên chỉ là ứng viên khi đơn vị xử lý không còn ai hợp lệ.
    assertThatThrownBy(() -> service.accept(T, staff(upper), id, "\"1\"", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    Actor member = new Actor(handler, List.of("member"), unit, "aal2", null);
    assertThatThrownBy(() -> service.accept(T, member, id, "\"1\"", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
  }

  @Test
  void hetNguoiHopLeODonViThiNangLenCapTren() {
    directory.participants =
        new Participants(
            profile,
            complainant,
            unit,
            UUID.randomUUID(),
            Set.of(handler),
            Set.of(appraiser),
            Set.of(),
            Set.of());
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    var c = service.candidates(T, id);
    assertThat(c.people()).containsExactly(upper);
    assertThat(c.escalated()).isTrue();
    assertThat(service.accept(T, staff(upper), id, "\"1\"", null).handling().handlerPersonId())
        .isEqualTo(upper);
  }

  @Test
  void tiepNhanKiemPhienBanVaTrangThai() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    assertThatThrownBy(() -> service.accept(T, staff(handler), id, null, null))
        .isInstanceOf(PreconditionRequiredException.class);
    assertThatThrownBy(() -> service.accept(T, staff(handler), id, "\"9\"", null))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.accept(T, staff(handler), id, "\"1\"", "x".repeat(2001)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    service.accept(T, staff(handler), id, "\"1\"", null);
    assertThatThrownBy(() -> service.accept(T, staff(handler), id, "\"2\"", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    assertThatThrownBy(() -> service.accept(T, staff(handler), UUID.randomUUID(), "\"1\"", null))
        .isInstanceOf(ComplaintService.NotFoundException.class);
    repo.failUpdate = true;
    UUID other = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    assertThatThrownBy(() -> service.accept(T, staff(handler), other, "\"1\"", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-VERSION-CONFLICT"));
  }

  // ---------------------------------------------------------------- không thụ lý, rút

  @Test
  void khongThuLyThieuLyDoBi422() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    assertThatThrownBy(
            () -> service.reject(T, staff(handler), id, "\"1\"", CloseReason.QUA_HAN, "Quá hạn"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> service.reject(T, staff(handler), id, "\"1\"", null, LONG_REASON))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.reject(T, staff(handler), id, "\"1\"", CloseReason.RUT, LONG_REASON))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThat(profiles.concluded).isEmpty();
  }

  @Test
  void khongThuLyTuDongVaDuaHoSoVeDaKySo() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    var v =
        service.reject(
            T, staff(handler), id, "\"1\"", CloseReason.TRUNG_NOI_DUNG, "  " + LONG_REASON + " ");
    assertThat(v.complaint().status()).isEqualTo(ComplaintStatus.DONG);
    assertThat(v.complaint().rowVersion()).isEqualTo(3);
    assertThat(v.handling().closeReason()).isEqualTo(CloseReason.TRUNG_NOI_DUNG);
    assertThat(v.handling().closeNote()).isEqualTo(LONG_REASON);
    assertThat(v.handling().acceptedAt()).isNull();
    assertThat(profiles.concluded).containsExactly(profile + ":false");
    assertThat(notices).singleElement().asString().startsWith("KHIEU_NAI_KHONG_THU_LY:");
    assertThat(audits)
        .containsExactly("KHONG_THU_LY_KHIEU_NAI:appraiser", "DONG_KHIEU_NAI:appraiser");
  }

  @Test
  void rutTruocKetLuanDongKhieuNaiBaoLuuKhongDongHoSo() {
    UUID id = repo.add(ComplaintKind.BAO_LUU, ComplaintStatus.DANG_GIAI_TRINH);
    var v = service.withdraw(T, owner(), id, "\"1\"", "Đã thống nhất kết quả");
    assertThat(v.complaint().status()).isEqualTo(ComplaintStatus.DONG);
    assertThat(v.handling().closeReason()).isEqualTo(CloseReason.RUT);
    assertThat(profiles.concluded).isEmpty();
    assertThat(audits).containsExactly("RUT_KHIEU_NAI:member", "DONG_KHIEU_NAI:member");

    UUID kn = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    service.withdraw(T, owner(), kn, "\"1\"", "Đã thống nhất kết quả");
    assertThat(profiles.concluded).containsExactly(profile + ":false");
  }

  @Test
  void rutSauKhiDaTraLoiBi409() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.DA_TRA_LOI);
    assertThatThrownBy(() -> service.withdraw(T, owner(), id, "\"1\"", "Đã thống nhất kết quả"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    assertThatThrownBy(() -> service.withdraw(T, owner(), id, "\"1\"", "ngắn"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.withdraw(T, staff(handler), id, "\"1\"", "Đã thống nhất kết quả"))
        .isInstanceOf(ComplaintService.NotFoundException.class);
  }

  @Test
  void daCoKetLuanThiKhongRutDuoc() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.CHO_KET_LUAN);
    var h = repo.rows.get(id);
    repo.rows.put(
        id,
        new ComplaintHandlingRepository.Handled(
            h.complaint(),
            h.handling()
                .concluded(
                    new vn.viettel.okrkpi.oversight.domain.complaint.ComplaintConclusion(
                        vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResolution.GIU_NGUYEN,
                        List.of(),
                        seal("Giữ nguyên"),
                        UUID.randomUUID(),
                        null,
                        null,
                        handler,
                        NOW),
                    null)));
    assertThatThrownBy(() -> service.withdraw(T, owner(), id, "\"1\"", "Đã thống nhất kết quả"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
    var v = service.get(T, owner(), id);
    assertThat(v.conclusionText()).isEqualTo("Giữ nguyên");
  }

  // ---------------------------------------------------------------- đọc

  @Test
  void danhSachChiChoNguoiXuLyNguoiKyTrongPhamVi() {
    repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    assertThat(service.list(T, staff(handler), null, Set.of(), null, false, null, 10)).hasSize(1);
    assertThat(repo.lastUnits).contains(unit);
    assertThat(repo.lastComplainant).isEqualTo(handler);
    service.list(T, staff(handler), null, Set.of(), null, true, null, 10);
    assertThat(repo.lastHandler).isEqualTo(handler);
    assertThat(repo.lastComplainant).isNull();
    assertThatThrownBy(() -> service.list(T, owner(), null, Set.of(), null, false, null, 10))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    Actor noUnit = new Actor(handler, List.of("appraiser"), null, "aal2", null);
    assertThatThrownBy(() -> service.list(T, noUnit, null, Set.of(), null, false, null, 10))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
  }

  @Test
  void chiTietMoNoiDungChoNguoiCoQuyenVaGhiSoCai() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    assertThat(service.get(T, owner(), id).content()).isEqualTo(CONTENT);
    assertThat(audits).isEmpty();
    // Ứng viên khi còn MOI đọc được và ghi sổ cái.
    assertThat(service.get(T, staff(handler), id).content()).isEqualTo(CONTENT);
    assertThat(audits).containsExactly("XEM_KHIEU_NAI:appraiser");
    // Người đã thẩm định hồ sơ chỉ thấy thông tin chung.
    assertThat(service.get(T, staff(appraiser), id).content()).isNull();
    // Người ký đọc từ bước chờ kết luận.
    Actor decider = new Actor(UUID.randomUUID(), List.of("decider"), parent, "aal2", null);
    directory.scope.put(parent, Set.of(parent, unit));
    assertThat(service.get(T, decider, id).content()).isNull();
    UUID waiting = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.CHO_KET_LUAN);
    assertThat(service.get(T, decider, waiting).content()).isEqualTo(CONTENT);
    assertThat(audits).endsWith("XEM_KHIEU_NAI:decider");
    // Người đã tiếp nhận đọc ở mọi bước.
    service.accept(T, staff(handler), id, "\"1\"", null);
    assertThat(service.get(T, staff(handler), id).content()).isEqualTo(CONTENT);
    // Ngoài phạm vi, người không phải cán bộ xử lý, nháp: không thấy.
    Actor outside =
        new Actor(UUID.randomUUID(), List.of("appraiser"), UUID.randomUUID(), "aal2", null);
    assertThatThrownBy(() -> service.get(T, outside, id))
        .isInstanceOf(ComplaintService.NotFoundException.class);
    Actor other = new Actor(UUID.randomUUID(), List.of("member"), unit, "aal2", null);
    assertThatThrownBy(() -> service.get(T, other, id))
        .isInstanceOf(ComplaintService.NotFoundException.class);
    UUID draft = repo.add(ComplaintKind.KHIEU_NAI, null);
    assertThatThrownBy(() -> service.get(T, staff(handler), draft))
        .isInstanceOf(ComplaintService.NotFoundException.class);
    assertThat(service.get(T, owner(), draft).workingDaysLeft()).isNull();
  }

  @Test
  void khongCoBoMaHoaThiKhongMoNoiDung() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    ComplaintHandlingService plain =
        new ComplaintHandlingService(
            repo,
            directory,
            profiles,
            null,
            null,
            null,
            (tenant, c, to, template, params) -> {},
            (tenant, actor, action, type, i, purpose, role) -> {},
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> plain.get(T, owner(), id))
        .isInstanceOf(ComplaintService.SealingUnavailableException.class);
  }

  @Test
  void ngayLamViecConLaiAmKhiQuaHan() {
    UUID id = repo.add(ComplaintKind.KHIEU_NAI, ComplaintStatus.MOI);
    Complaint c = repo.rows.get(id).complaint();
    // Hạn thứ Sáu 06/11: từ thứ Sáu 23/10 còn 10 ngày làm việc.
    assertThat(service.responseDaysLeft(T, c)).isEqualTo(10);
    Complaint late = c.filed(NOW, LocalDate.of(2026, 10, 19), unit);
    assertThat(service.responseDaysLeft(T, late)).isEqualTo(-4);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private Actor staff(UUID person) {
    return new Actor(person, List.of("appraiser"), unit, "aal2", null);
  }

  private Actor owner() {
    return new Actor(complainant, List.of("member"), unit, "aal2", null);
  }

  private static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  private static byte[] seal(String plain) {
    byte[] b = plain.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[b.length];
    for (int i = 0; i < b.length; i++) {
      out[i] = (byte) ~b[i];
    }
    return out;
  }

  private final class FakeRepo implements ComplaintHandlingRepository {
    final Map<UUID, Handled> rows = new HashMap<>();
    boolean failUpdate;
    Collection<UUID> lastUnits;
    UUID lastHandler;
    UUID lastComplainant;
    int seq;

    UUID add(ComplaintKind kind, ComplaintStatus status) {
      UUID id = UUID.randomUUID();
      seq++;
      Complaint c =
          new Complaint(
              id,
              String.format("KN-2026-%06d", seq),
              UUID.randomUUID(),
              20264,
              complainant,
              profile,
              kind,
              ComplaintStage.THAM_DINH,
              "Khiếu nại kết quả xếp loại",
              List.of(),
              seal(CONTENT),
              null,
              UUID.randomUUID(),
              List.of(),
              false,
              status,
              status == null ? null : NOW,
              status == null ? null : LocalDate.of(2026, 11, 6),
              status == null ? null : unit,
              null,
              NOW,
              1);
      rows.put(id, new Handled(c, ComplaintHandling.NONE));
      return id;
    }

    @Override
    public Optional<Handled> find(TenantId tenant, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Handled> lock(TenantId tenant, UUID id) {
      return find(tenant, id);
    }

    @Override
    public boolean update(
        TenantId tenant, Complaint changed, ComplaintHandling handling, int expected, UUID actor) {
      Handled h = rows.get(changed.id());
      if (failUpdate || h == null || h.complaint().rowVersion() != expected) {
        return false;
      }
      rows.put(changed.id(), new Handled(changed, handling));
      return true;
    }

    @Override
    public Optional<Handled> byResponseSignRequest(TenantId tenant, UUID signRequestId) {
      return Optional.empty();
    }

    @Override
    public List<Complaint> inScope(
        TenantId tenant,
        Collection<UUID> units,
        UUID handler,
        UUID complainant,
        UUID periodId,
        Set<ComplaintStatus> statuses,
        vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind kind,
        DeadlineAfter after,
        int limit) {
      lastUnits = units;
      lastHandler = handler;
      lastComplainant = complainant;
      return rows.values().stream()
          .map(Handled::complaint)
          .filter(c -> c.status() != null && units.contains(c.handlerUnitId()))
          .toList();
    }

    @Override
    public void insertResponse(TenantId tenant, ComplaintResponse r, UUID actorId) {}

    @Override
    public List<ComplaintResponse> responses(
        TenantId tenant,
        Complaint complaint,
        ComplaintRecordKind kind,
        ResponseAfter after,
        int limit) {
      return List.of();
    }

    @Override
    public List<UUID> explanationsSettled(TenantId tenant, LocalDate today, int limit) {
      return List.of();
    }
  }

  private final class FakeDirectory implements ComplaintDirectory {
    final Map<UUID, List<UUID>> holders = new HashMap<>();
    final Map<UUID, List<UUID>> ancestors = new HashMap<>();
    final Map<UUID, Set<UUID>> scope = new HashMap<>();
    Participants participants;

    @Override
    public Optional<Participants> participants(TenantId tenant, UUID profileId) {
      return Optional.ofNullable(participants);
    }

    @Override
    public List<UUID> holders(TenantId tenant, String roleCode, UUID unitId, Instant at) {
      return "appraiser".equals(roleCode) ? holders.getOrDefault(unitId, List.of()) : List.of();
    }

    @Override
    public List<UUID> ancestorsOf(TenantId tenant, UUID unitId) {
      return ancestors.getOrDefault(unitId, List.of());
    }

    @Override
    public Set<UUID> scopeOf(TenantId tenant, UUID unitId) {
      return scope.getOrDefault(unitId, new LinkedHashSet<>(List.of(unitId)));
    }
  }

  private static final class FakeProfiles implements ComplaintProfilesApi {
    final List<String> concluded = new ArrayList<>();

    @Override
    public List<Profile> signedProfilesOf(TenantId tenant, UUID personId) {
      return List.of();
    }

    @Override
    public Optional<Profile> find(TenantId tenant, UUID profileId) {
      return Optional.empty();
    }

    @Override
    public boolean fileComplaint(TenantId tenant, UUID profileId, UUID actorId) {
      return true;
    }

    @Override
    public boolean concludeComplaint(
        TenantId tenant, UUID profileId, UUID actorId, boolean adjusted, String reason) {
      concluded.add(profileId + ":" + adjusted);
      return true;
    }
  }

  private static final class FakeSealer implements ComplaintSealer {
    @Override
    public Sealed seal(TenantId tenant, String column, UUID rowId, String plain) {
      return new Sealed(ComplaintHandlingServiceTest.seal(plain), UUID.randomUUID());
    }

    @Override
    public String open(TenantId tenant, String column, UUID rowId, byte[] cipher) {
      byte[] out = new byte[cipher.length];
      for (int i = 0; i < cipher.length; i++) {
        out[i] = (byte) ~cipher[i];
      }
      return new String(out, StandardCharsets.UTF_8);
    }
  }
}
