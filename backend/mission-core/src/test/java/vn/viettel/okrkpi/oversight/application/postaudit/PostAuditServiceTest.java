package vn.viettel.okrkpi.oversight.application.postaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.application.port.PostAuditRepository;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.postaudit.PostAudit;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-07-09: khởi tạo, xem, danh sách, đóng đợt hậu kiểm với phát hiện C2 và bằng chứng. */
class PostAuditServiceTest {

  static final TenantId T = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID PARENT = UUID.randomUUID();
  static final UUID CHILD = UUID.randomUUID();
  static final UUID ELSEWHERE = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2027-02-06T01:00:00Z");

  final Map<UUID, PostAudit> rows = new LinkedHashMap<>();
  final List<String> audit = new ArrayList<>();
  final List<UUID> reopenOfPeriod = new ArrayList<>();

  final PostAuditRepository repo =
      new PostAuditRepository() {
        @Override
        public void insert(TenantId tenant, PostAudit a, UUID actorId) {
          rows.put(a.id(), a);
        }

        @Override
        public Optional<PostAudit> find(TenantId tenant, UUID id) {
          return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<PostAudit> lock(TenantId tenant, UUID id) {
          return find(tenant, id);
        }

        @Override
        public boolean close(TenantId tenant, PostAudit c, int expected, UUID actorId) {
          if (rows.get(c.id()).rowVersion() != expected || stale) {
            return false;
          }
          rows.put(c.id(), c);
          return true;
        }

        @Override
        public List<PostAudit> list(TenantId tenant, Filter f, After after, int limit) {
          lastFilter = f;
          return rows.values().stream()
              .filter(a -> f.open() == null || a.open() == f.open())
              .sorted(Comparator.comparing(PostAudit::openedAt).reversed())
              .limit(limit)
              .toList();
        }

        @Override
        public boolean reopenRequestOfPeriod(TenantId tenant, UUID id, UUID periodId) {
          return reopenOfPeriod.contains(id) && PERIOD.equals(periodId);
        }
      };

  boolean stale;
  PostAuditRepository.Filter lastFilter;

  final ComplaintSealer sealer =
      new ComplaintSealer() {
        @Override
        public Sealed seal(TenantId tenant, String column, UUID rowId, String plain) {
          return new Sealed(
              new StringBuilder(plain).reverse().toString().getBytes(StandardCharsets.UTF_8),
              UUID.nameUUIDFromBytes(column.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public String open(TenantId tenant, String column, UUID rowId, byte[] cipher) {
          return new StringBuilder(new String(cipher, StandardCharsets.UTF_8)).reverse().toString();
        }
      };

  PostAuditService service(ComplaintSealer s) {
    PeriodApi periods = mock(PeriodApi.class);
    when(periods.findById(any(), any())).thenReturn(Optional.empty());
    when(periods.findById(T, PeriodId.of(PERIOD)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(PERIOD),
                    T,
                    "2026",
                    "Năm 2026",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 12, 31),
                    PeriodStatus.DA_KHOA)));
    AccessDirectory directory = mock(AccessDirectory.class);
    when(directory.subunitsOf(T, PARENT)).thenReturn(List.of(CHILD));
    return new PostAuditService(
        repo,
        periods,
        directory,
        s,
        (tenant, actor, action, type, id, purpose, role) -> audit.add(action + "|" + id),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  static Actor inspector() {
    return new Actor(UUID.randomUUID(), List.of("inspector"), null);
  }

  static Actor appraiser() {
    return new Actor(UUID.randomUUID(), List.of("appraiser"), PARENT);
  }

  static PostAuditService.Draft draft(String target, UUID id) {
    return new PostAuditService.Draft(PERIOD, "DOT_XUAT", target, id, "  QĐ 12-QĐ/UBKT  ");
  }

  @Test
  void lapDotHauKiemVaGhiSoCai() {
    PostAuditService s = service(sealer);
    PostAuditService.View v = s.create(T, inspector(), draft("HO_SO", UUID.randomUUID()));
    assertThat(v.audit().open()).isTrue();
    assertThat(v.audit().basis()).isEqualTo("QĐ 12-QĐ/UBKT");
    assertThat(v.audit().periodBucket()).isEqualTo(20260);
    assertThat(audit).containsExactly("TAO_DOT_HAU_KIEM|" + v.audit().id());
  }

  @Test
  void dauVaoSaiVaKyKhongCoLa422() {
    PostAuditService s = service(sealer);
    assertThatThrownBy(
            () ->
                s.create(
                    T,
                    inspector(),
                    new PostAuditService.Draft(null, "KHAC", null, null, "x".repeat(201))))
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errors()).hasSize(5));
    assertThatThrownBy(
            () ->
                s.create(
                    T,
                    inspector(),
                    new PostAuditService.Draft(
                        UUID.randomUUID(), "DOT_XUAT", "DON_VI", CHILD, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.detail()).isEqualTo("Không tìm thấy kỳ đánh giá."));
  }

  @Test
  void nguoiThamDinhChiLapVaXemDonViTrongCay() {
    PostAuditService s = service(sealer);
    Actor a = appraiser();
    PostAudit mine = s.create(T, a, draft("DON_VI", CHILD)).audit();
    assertThatThrownBy(() -> s.create(T, a, draft("DON_VI", ELSEWHERE)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> s.create(T, a, draft("HO_SO", UUID.randomUUID())))
        .isInstanceOf(BusinessException.class);
    PostAudit other = s.create(T, inspector(), draft("DON_VI", ELSEWHERE)).audit();
    PostAudit inTree = s.create(T, inspector(), draft("DON_VI", PARENT)).audit();
    Actor peer = appraiser();
    assertThat(s.get(T, peer, mine.id()).audit().id()).isEqualTo(mine.id());
    assertThat(s.get(T, peer, inTree.id()).findings()).isNull();
    assertThatThrownBy(() -> s.get(T, peer, other.id()))
        .isInstanceOf(PostAuditService.NotFoundException.class);
    assertThatThrownBy(() -> s.get(T, peer, UUID.randomUUID()))
        .isInstanceOf(PostAuditService.NotFoundException.class);
    assertThatThrownBy(() -> s.get(T, new Actor(UUID.randomUUID(), List.of(), null), mine.id()))
        .isInstanceOf(PostAuditService.NotFoundException.class);
  }

  @Test
  void danhSachTheoPhamVi() {
    PostAuditService s = service(sealer);
    s.create(T, inspector(), draft("DON_VI", ELSEWHERE));
    Actor a = appraiser();
    assertThat(s.list(T, inspector(), PERIOD, true, null, 10)).hasSize(1);
    assertThat(lastFilter.inspectorId()).isNull();
    s.list(T, a, null, null, null, 10);
    assertThat(lastFilter.inspectorId()).isEqualTo(a.userId());
    assertThat(lastFilter.units()).containsExactlyInAnyOrder(PARENT, CHILD);
    s.list(T, new Actor(UUID.randomUUID(), List.of("appraiser"), null), null, false, null, 10);
    assertThat(lastFilter.units()).isEmpty();
  }

  @Test
  void dongDotGhiKetLuanPhatHienMaHoaVaBangChung() {
    PostAuditService s = service(sealer);
    PostAudit a = s.create(T, inspector(), draft("HO_SO", UUID.randomUUID())).audit();
    UUID reopen = UUID.randomUUID();
    reopenOfPeriod.add(reopen);
    UUID e1 = UUID.randomUUID();
    UUID e2 = UUID.randomUUID();
    PostAuditService.View v =
        s.close(
            T,
            inspector(),
            a.id(),
            "\"0\"",
            new PostAuditService.Closing(
                "DANH_GIA_LAI", "  Hồ sơ chấm sai hệ số.  ", reopen, List.of(e1, e2)));
    assertThat(v.findings()).isEqualTo("Hồ sơ chấm sai hệ số.");
    PostAudit stored = rows.get(a.id());
    assertThat(stored.closedAt()).isEqualTo(NOW);
    assertThat(stored.rowVersion()).isEqualTo(1);
    assertThat(new String(stored.findingsCipher(), StandardCharsets.UTF_8))
        .doesNotContain("Hồ sơ chấm sai");
    assertThat(stored.evidenceObjectIds()).containsExactly(e1, e2);
    assertThat(stored.reopenRequestId()).isEqualTo(reopen);
    assertThat(s.get(T, inspector(), a.id()).findings()).isEqualTo("Hồ sơ chấm sai hệ số.");
    assertThat(audit)
        .contains(
            "DONG_DOT_HAU_KIEM|" + a.id(),
            "GAN_BANG_CHUNG_SO_CAI|" + a.id() + ":" + e1,
            "GAN_BANG_CHUNG_SO_CAI|" + a.id() + ":" + e2);
    // Đã đóng: 409.
    assertThatThrownBy(
            () ->
                s.close(
                    T,
                    inspector(),
                    a.id(),
                    "\"1\"",
                    new PostAuditService.Closing("KHONG_SAI_LECH", "x", null, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("OVERSIGHT-409-INVALID-STATE"));
  }

  @Test
  void dongDotKiemDieuKien() {
    PostAuditService s = service(sealer);
    PostAudit a = s.create(T, inspector(), draft("HO_SO", UUID.randomUUID())).audit();
    PostAuditService.Closing ok = new PostAuditService.Closing("KHONG_SAI_LECH", "Ổn", null, null);
    assertThatThrownBy(() -> s.close(T, appraiser(), a.id(), "\"0\"", ok))
        .isInstanceOf(PostAuditService.NotFoundException.class);
    assertThatThrownBy(() -> s.close(T, inspector(), UUID.randomUUID(), "\"0\"", ok))
        .isInstanceOf(PostAuditService.NotFoundException.class);
    assertThatThrownBy(() -> s.close(T, inspector(), a.id(), null, ok))
        .isInstanceOf(PreconditionRequiredException.class);
    UUID dup = UUID.randomUUID();
    List<UUID> many = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      many.add(UUID.randomUUID());
    }
    assertThatThrownBy(
            () ->
                s.close(
                    T,
                    inspector(),
                    a.id(),
                    "\"0\"",
                    new PostAuditService.Closing(null, " ", UUID.randomUUID(), List.of(dup, dup))))
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errors()).hasSize(4));
    assertThatThrownBy(
            () ->
                s.close(
                    T,
                    inspector(),
                    a.id(),
                    "\"0\"",
                    new PostAuditService.Closing("HUY_KET_QUA", "x".repeat(16_001), null, many)))
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errors()).hasSize(2));
    stale = true;
    assertThatThrownBy(() -> s.close(T, inspector(), a.id(), "\"0\"", ok))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("OVERSIGHT-409-VERSION-CONFLICT"));
  }

  @Test
  void khongCoBoMaHoaThiKhongDongVaKhongGiaiMa() {
    PostAuditService withSealer = service(sealer);
    PostAudit a = withSealer.create(T, inspector(), draft("HO_SO", UUID.randomUUID())).audit();
    withSealer.close(
        T,
        inspector(),
        a.id(),
        "\"0\"",
        new PostAuditService.Closing("KHONG_SAI_LECH", "Ổn", null, null));
    PostAuditService none = service(null);
    assertThatThrownBy(() -> none.get(T, inspector(), a.id()))
        .isInstanceOf(PostAuditService.SealingUnavailableException.class);
    PostAudit b = none.create(T, inspector(), draft("HO_SO", UUID.randomUUID())).audit();
    assertThatThrownBy(
            () ->
                none.close(
                    T,
                    inspector(),
                    b.id(),
                    "\"0\"",
                    new PostAuditService.Closing("KHONG_SAI_LECH", "Ổn", null, null)))
        .isInstanceOf(PostAuditService.SealingUnavailableException.class);
    assertThat(
            new PostAuditService(
                    repo,
                    mock(PeriodApi.class),
                    null,
                    null,
                    (t, u, ac, ty, i, p, r) -> {},
                    Clock.systemUTC())
                .list(T, appraiser(), null, null, null, 5))
        .isNotNull();
    assertThat(lastFilter.units()).containsExactly(PARENT);
  }
}
