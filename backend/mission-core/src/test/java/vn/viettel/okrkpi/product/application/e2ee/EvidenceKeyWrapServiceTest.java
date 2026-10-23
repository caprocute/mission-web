package vn.viettel.okrkpi.product.application.e2ee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.product.application.e2ee.EvidenceKeyWrapService.WrapInput;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.RecoveryKeys.RecoveryKey;
import vn.viettel.okrkpi.product.application.evidence.EvidenceActor;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.VersionDetail;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskSummary;

/** Bổ sung khoá bọc cho minh chứng C3 (CORE-07-06). */
class EvidenceKeyWrapServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-00000000a001");
  static final UUID ACCEPTOR = UUID.fromString("01927a02-0000-7000-8000-00000000a002");
  static final UUID LEAD = UUID.fromString("01927a02-0000-7000-8000-00000000a003");
  static final UUID OUTSIDER = UUID.fromString("01927a02-0000-7000-8000-00000000a004");
  static final UUID RECOVERY = UUID.fromString("01927a02-0000-7000-8000-00000000a005");
  static final String RECOVERY_FP = "ab".repeat(32);
  static final Instant NOW = Instant.parse("2027-02-01T01:00:00Z");

  final UUID evidenceId = UUID.randomUUID();
  final UUID versionId = UUID.randomUUID();
  final UUID taskId = UUID.randomUUID();
  final EvidenceSubmissionService versions = mock(EvidenceSubmissionService.class);
  final TaskApi tasks = mock(TaskApi.class);
  final FakeWraps wraps = new FakeWraps();
  final List<AuditEvent> audits = new ArrayList<>();
  Optional<RecoveryKey> recovery = Optional.of(new RecoveryKey(List.of(RECOVERY_FP), RECOVERY));
  EvidenceKeyWrapService service;

  @BeforeEach
  void setUp() {
    service =
        new EvidenceKeyWrapService(
            versions, wraps, t -> recovery, tasks, audits::add, Clock.fixed(NOW, ZoneOffset.UTC));
    when(tasks.find(TENANT, taskId))
        .thenReturn(
            Optional.of(
                new TaskSummary(
                    taskId,
                    UUID.randomUUID(),
                    20271,
                    "NV-1",
                    "Nhiệm vụ hạn chế",
                    "DANG_THUC_HIEN",
                    false,
                    false,
                    ASSIGNER,
                    ACCEPTOR,
                    LEAD,
                    LocalDate.of(2027, 3, 31),
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    1)));
    version(true);
  }

  void version(boolean e2ee) {
    VersionView v =
        new VersionView(
            versionId,
            evidenceId,
            taskId,
            UUID.randomUUID(),
            20271,
            1,
            "NHAP",
            "TAI_LEN",
            null,
            "SACH",
            false,
            e2ee,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0);
    when(versions.getVersion(TENANT, evidenceId, 1)).thenReturn(new VersionDetail(v, List.of()));
  }

  static WrapInput w(UUID who) {
    return new WrapInput(
        who, Base64.getEncoder().encodeToString(new byte[] {1, 2}), "RSA-OAEP-256");
  }

  static EvidenceActor as(UUID who) {
    return new EvidenceActor(who, java.util.Set.of("member"));
  }

  static String code(Throwable t) {
    return ((BusinessException) t).getMessage();
  }

  @Test
  void nguoiNopCapKhoaChoChinhMinhVaKhoaPhucHoi() {
    service.add(TENANT, evidenceId, 1, List.of(w(LEAD), w(RECOVERY)), as(LEAD));
    assertThat(wraps.rows).hasSize(2);
    assertThat(HexFormat.of().formatHex(wraps.rows.get(1).fingerprint())).isEqualTo(RECOVERY_FP);
    assertThat(wraps.rows.get(0).fingerprint())
        .isEqualTo(EvidenceKeyWrapService.personFingerprint(LEAD));
    assertThat(audits).hasSize(1);
    assertThat(service.mine(TENANT, versions.getVersion(TENANT, evidenceId, 1).version(), LEAD))
        .isPresent();
  }

  @Test
  void thieuKhoaPhucHoiHoacChuaCauHinh422() {
    assertThatThrownBy(() -> service.add(TENANT, evidenceId, 1, List.of(w(LEAD)), as(LEAD)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    recovery = Optional.empty();
    assertThatThrownBy(
            () -> service.add(TENANT, evidenceId, 1, List.of(w(LEAD), w(RECOVERY)), as(LEAD)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void nguoiNhanNgoaiNhiemVuVaNguoiKhongGiuKhoa403() {
    assertThatThrownBy(
            () -> service.add(TENANT, evidenceId, 1, List.of(w(OUTSIDER), w(RECOVERY)), as(LEAD)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    service.add(TENANT, evidenceId, 1, List.of(w(LEAD), w(RECOVERY)), as(LEAD));
    assertThatThrownBy(() -> service.add(TENANT, evidenceId, 1, List.of(w(ACCEPTOR)), as(ACCEPTOR)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    service.add(TENANT, evidenceId, 1, List.of(w(ACCEPTOR)), as(LEAD));
    assertThat(wraps.rows).hasSize(3);
  }

  @Test
  void phienBanKhongMaHoa409() {
    version(false);
    assertThatThrownBy(() -> service.add(TENANT, evidenceId, 1, List.of(w(LEAD)), as(LEAD)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("PRODUCT-409-INVALID-STATE"));
    assertThat(service.mine(TENANT, versions.getVersion(TENANT, evidenceId, 1).version(), LEAD))
        .isEmpty();
  }

  @Test
  void nguoiNhanLapVaThuatToanLa422() {
    assertThatThrownBy(
            () -> service.add(TENANT, evidenceId, 1, List.of(w(LEAD), w(LEAD)), as(LEAD)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.add(
                    TENANT,
                    evidenceId,
                    1,
                    List.of(new WrapInput(LEAD, "AQI=", "A128KW"), w(RECOVERY)),
                    as(LEAD)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
  }

  /** Kho bản bọc trong bộ nhớ, thay theo người nhận như vân tay suy ra. */
  static final class FakeWraps implements EvidenceKeyWraps {

    final List<StoredWrap> rows = new ArrayList<>();

    @Override
    public List<StoredWrap> of(TenantId tenant, UUID versionId) {
      return List.copyOf(rows);
    }

    @Override
    public Optional<StoredWrap> activeFor(TenantId tenant, UUID versionId, UUID personId) {
      return rows.stream().filter(r -> r.active() && r.recipientId().equals(personId)).findFirst();
    }

    @Override
    public void upsert(
        TenantId tenant, UUID versionId, List<NewWrap> wraps, UUID actor, Instant at) {
      for (NewWrap n : wraps) {
        rows.removeIf(r -> java.util.Arrays.equals(r.fingerprint(), n.fingerprint()));
        rows.add(
            new StoredWrap(
                n.recipientId(), n.fingerprint(), n.algorithm(), n.wrappedKey(), actor, at, null));
      }
    }
  }
}
