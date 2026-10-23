package vn.viettel.okrkpi.task.application.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService.Meta;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService.SaveInput;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService.WrapInput;
import vn.viettel.okrkpi.task.application.secret.port.RecoveryRecords;
import vn.viettel.okrkpi.task.application.secret.port.TaskSecretContents;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.secret.KeyWrap;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.RecoveryKey;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.Sealed;

/**
 * Dịch vụ nội dung nhiệm vụ mật C3 trên kho giả trong bộ nhớ (CORE-07-06, CORE-02-24): quyền đọc
 * theo bản bọc còn hiệu lực, lưu lần đầu và lưu tiếp theo phiên bản, chia sẻ, thu hồi, nạp khoá sau
 * khôi phục; mọi thao tác ghi kiểm toán mà không có bản mã.
 */
class TaskSecretContentServiceTest {

  static final TenantId T = TenantId.of(UUID.fromString("01927a02-0000-7000-8000-0000000007a0"));
  static final UUID TASK = UUID.fromString("01927a02-0000-7000-8000-0000000007a1");
  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-0000000007a2");
  static final UUID ACCEPTOR = UUID.fromString("01927a02-0000-7000-8000-0000000007a3");
  static final UUID DOER = UUID.fromString("01927a02-0000-7000-8000-0000000007a4");
  static final UUID OUTSIDER = UUID.fromString("01927a02-0000-7000-8000-0000000007a5");
  static final UUID RECOVERY_PERSON = UUID.fromString("01927a02-0000-7000-8000-0000000007a6");
  static final UUID RECOVERY_ID = UUID.fromString("01927a02-0000-7000-8000-0000000007a7");
  static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
  static final byte[] CIPHER = "ban-ma-noi-dung".getBytes(StandardCharsets.UTF_8);

  static String device(String label) {
    return HexFormat.of()
        .formatHex(SecretContentRules.sha256(label.getBytes(StandardCharsets.UTF_8)));
  }

  static final RecoveryKey KEY = new RecoveryKey(Set.of(device("phuc-hoi")), RECOVERY_PERSON);

  static final TaskActor A = new TaskActor(ASSIGNER, Set.of("member"));
  static final TaskActor D = new TaskActor(DOER, Set.of("member"));
  static final TaskActor MANAGER = new TaskActor(ACCEPTOR, Set.of("manager"));
  static final TaskActor OUT = new TaskActor(OUTSIDER, Set.of("member"));

  /** Kho giả: một nhiệm vụ, bản mã tuỳ chọn, danh sách bọc. */
  static final class FakeStore implements TaskSecretContents {
    TaskFacts facts =
        new TaskFacts(TASK, UUID.randomUUID(), 20264, true, ASSIGNER, ACCEPTOR, Set.of(DOER));
    Content content;
    final List<KeyWrap> wraps = new ArrayList<>();
    boolean insertOk = true;
    long updateResult = Long.MIN_VALUE;
    int revokedPersons = 1;
    final List<String> calls = new ArrayList<>();

    @Override
    public Optional<TaskFacts> task(TenantId tenant, UUID taskId) {
      return Optional.ofNullable(facts).filter(f -> f.id().equals(taskId));
    }

    @Override
    public Optional<Content> content(TenantId tenant, int bucket, UUID taskId, boolean lock) {
      return Optional.ofNullable(content);
    }

    @Override
    public boolean insert(
        TenantId tenant,
        TaskFacts task,
        UUID id,
        Sealed sealed,
        byte[] sender,
        UUID actor,
        Instant at) {
      calls.add("insert");
      if (insertOk) {
        content = new Content(id, task.id(), sealed, sender, at, 0);
      }
      return insertOk;
    }

    @Override
    public long update(
        TenantId tenant,
        int bucket,
        UUID taskId,
        long expected,
        Sealed sealed,
        byte[] sender,
        UUID actor,
        Instant at) {
      calls.add("update");
      long v = updateResult == Long.MIN_VALUE ? expected + 1 : updateResult;
      if (v >= 0) {
        content = new Content(content.id(), taskId, sealed, sender, at, v);
      }
      return v;
    }

    @Override
    public List<KeyWrap> wraps(TenantId tenant, UUID taskId) {
      return List.copyOf(wraps);
    }

    @Override
    public void upsert(TenantId tenant, UUID taskId, List<KeyWrap> in, UUID actor, Instant at) {
      calls.add("upsert:" + in.size());
      for (KeyWrap w : in) {
        wraps.removeIf(x -> x.deviceId().equals(w.deviceId()));
        wraps.add(
            new KeyWrap(
                w.recipientId(),
                w.fingerprint(),
                w.algorithm(),
                w.ephemeralPublicKey(),
                w.wrappedKey(),
                actor,
                at,
                null));
      }
    }

    @Override
    public int revokeExcept(
        TenantId tenant, UUID taskId, Set<String> keep, UUID actor, Instant at) {
      calls.add("revokeExcept:" + keep.size());
      return 0;
    }

    @Override
    public int revokePerson(TenantId tenant, UUID taskId, UUID personId, UUID actor, Instant at) {
      calls.add("revokePerson");
      return revokedPersons;
    }
  }

  private final FakeStore store = new FakeStore();
  private final List<AuditEvent> audits = new ArrayList<>();
  private final Map<UUID, RecoveryRecords.Recovery> records = new HashMap<>();
  private Optional<RecoveryKey> key = Optional.of(KEY);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private TaskSecretContentService service() {
    return new TaskSecretContentService(
        store,
        tenant -> key,
        (tenant, id) -> Optional.ofNullable(records.get(id)),
        audits::add,
        clock);
  }

  static WrapInput rsa(UUID who, String label) {
    return new WrapInput(
        who,
        device(label),
        "RSA-OAEP-256",
        null,
        Base64.getEncoder().encodeToString(new byte[] {1}));
  }

  static SaveInput input(long version, List<WrapInput> wraps) {
    String aad =
        HexFormat.of()
            .formatHex(SecretContentRules.sha256(SecretContentRules.aad(T.value(), TASK, version)));
    return new SaveInput(
        Base64.getEncoder().encodeToString(CIPHER),
        Base64.getEncoder().encodeToString(new byte[12]),
        aad,
        HexFormat.of().formatHex(SecretContentRules.sha256(CIPHER)),
        wraps);
  }

  static List<WrapInput> ownAndRecovery(UUID who, String label) {
    return List.of(rsa(who, label), rsa(RECOVERY_PERSON, "phuc-hoi"));
  }

  private Meta firstSave() {
    return service().save(T, TASK, 0, input(0, ownAndRecovery(ASSIGNER, "a")), A);
  }

  private static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  @Test
  void quyenTruocKhiLuuLaNguoiThamGiaSauDoLaNguoiGiuBanBoc() {
    var before = service().authz(T, TASK).toAuthzResource();
    assertThat(before.recipientIds())
        .containsExactly(ASSIGNER.toString(), ACCEPTOR.toString(), DOER.toString());
    assertThat(before.classification()).isEqualTo("C3");
    firstSave();
    assertThat(service().authz(T, TASK).toAuthzResource().recipientIds())
        .containsExactly(ASSIGNER.toString());
    store.facts =
        new TaskSecretContents.TaskFacts(
            TASK, UUID.randomUUID(), 20264, true, ASSIGNER, null, Set.of());
    store.content = null;
    assertThat(service().authz(T, TASK).toAuthzResource().recipientIds())
        .containsExactly(ASSIGNER.toString());
    assertThatThrownBy(() -> service().authz(T, UUID.randomUUID()))
        .isInstanceOf(java.util.NoSuchElementException.class);
  }

  @Test
  void luuLanDauGhiBanMaBanBocVaKiemToan() {
    Meta m = firstSave();
    assertThat(m.rowVersion()).isZero();
    assertThat(m.recipientCount()).isEqualTo(2);
    assertThat(m.sha256()).isEqualTo(SecretContentRules.sha256(CIPHER));
    assertThat(store.calls).containsExactly("insert", "upsert:2", "revokeExcept:2");
    assertThat(audits)
        .singleElement()
        .satisfies(e -> assertThat(e.action()).isEqualTo("LUU_NOI_DUNG_MAT"));
    assertThat(audits.get(0).attributes().get("recipients")).contains(ASSIGNER.toString());
  }

  @Test
  void luuTiepTheoPhienBan() {
    firstSave();
    Meta m = service().save(T, TASK, 0, input(1, ownAndRecovery(ASSIGNER, "a")), A);
    assertThat(m.rowVersion()).isEqualTo(1);
    assertThatThrownBy(() -> service().save(T, TASK, 0, input(1, ownAndRecovery(ASSIGNER, "a")), A))
        .isInstanceOf(VersionConflictException.class);
    store.updateResult = -1;
    assertThatThrownBy(() -> service().save(T, TASK, 1, input(2, ownAndRecovery(ASSIGNER, "a")), A))
        .isInstanceOf(VersionConflictException.class);
    // Người tham gia chưa giữ bản bọc không lưu đè được.
    assertThatThrownBy(() -> service().save(T, TASK, 1, input(2, ownAndRecovery(DOER, "d")), D))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
  }

  @Test
  void luuDongThoiLanDauLaXungDot() {
    store.insertOk = false;
    assertThatThrownBy(this::firstSave).isInstanceOf(VersionConflictException.class);
  }

  @Test
  void luuSaiDuLieuHoacSaiNguoiBiChan() {
    TaskSecretContentService s = service();
    assertThatThrownBy(
            () ->
                s.save(
                    T,
                    TASK,
                    0,
                    new SaveInput("%%", "x", "y", "z", ownAndRecovery(ASSIGNER, "a")),
                    A))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () -> s.save(T, TASK, 0, input(0, List.of(rsa(RECOVERY_PERSON, "phuc-hoi"))), A))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                s.save(
                    T,
                    TASK,
                    0,
                    input(
                        0,
                        List.of(
                            rsa(ASSIGNER, "a"),
                            rsa(OUTSIDER, "x"),
                            rsa(RECOVERY_PERSON, "phuc-hoi"))),
                    A))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> s.save(T, TASK, 0, input(7, ownAndRecovery(ASSIGNER, "a")), A))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> s.save(T, TASK, 0, new SaveInput("a", "b", "c", "d", null), A))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                s.save(T, TASK, 0, input(0, java.util.Arrays.asList(rsa(ASSIGNER, "a"), null)), A))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    store.facts =
        new TaskSecretContents.TaskFacts(
            TASK, UUID.randomUUID(), 20264, false, ASSIGNER, ACCEPTOR, Set.of(DOER));
    assertThatThrownBy(this::firstSave)
        .satisfies(t -> assertThat(code(t)).isEqualTo("TASK-409-INVALID-STATE"));
    assertThat(store.calls).isEmpty();
  }

  @Test
  void docChiChoNguoiGiuBanBocMoiNhat() {
    assertThatThrownBy(() -> service().get(T, TASK, A))
        .isInstanceOf(java.util.NoSuchElementException.class);
    firstSave();
    var view = service().get(T, TASK, A);
    assertThat(view.wrap().recipientId()).isEqualTo(ASSIGNER);
    assertThat(view.content().sealed().ciphertext()).isEqualTo(CIPHER);
    assertThat(audits.get(audits.size() - 1).action()).isEqualTo("XEM_NOI_DUNG_MAT");
    assertThatThrownBy(() -> service().get(T, TASK, D))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service().get(T, UUID.randomUUID(), A))
        .isInstanceOf(java.util.NoSuchElementException.class);
  }

  @Test
  void chiaSeChoNguoiThamGiaKhiConKhoaPhucHoi() {
    firstSave();
    Meta m = service().share(T, TASK, List.of(rsa(DOER, "d")), A);
    assertThat(m.recipientCount()).isEqualTo(3);
    assertThat(audits.get(audits.size() - 1).action()).isEqualTo("CHIA_SE_KHOA_NOI_DUNG_MAT");
    assertThatThrownBy(() -> service().share(T, TASK, List.of(rsa(DOER, "d2")), OUT))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service().share(T, TASK, List.of(rsa(OUTSIDER, "x")), A))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service().share(T, TASK, List.of(rsa(DOER, "phuc-hoi")), A))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    key = Optional.empty();
    assertThatThrownBy(() -> service().share(T, TASK, List.of(rsa(DOER, "d3")), A))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void chiaSeKhiChuaCoNoiDungLaKhongTimThay() {
    assertThatThrownBy(() -> service().share(T, TASK, List.of(rsa(DOER, "d")), A))
        .isInstanceOf(java.util.NoSuchElementException.class);
    assertThatThrownBy(() -> service().revoke(T, TASK, DOER, A))
        .isInstanceOf(java.util.NoSuchElementException.class);
    assertThatThrownBy(() -> service().restore(T, TASK, RECOVERY_ID, rsa(DOER, "d"), A))
        .isInstanceOf(java.util.NoSuchElementException.class);
  }

  @Test
  void thuHoiBoiNguoiGiaoHoacLanhDaoDangGiuBanBoc() {
    firstSave();
    service().share(T, TASK, List.of(rsa(ACCEPTOR, "c")), A);
    service().revoke(T, TASK, DOER, A);
    assertThat(audits.get(audits.size() - 1).action()).isEqualTo("THU_HOI_KHOA_NOI_DUNG_MAT");
    int before = audits.size();
    store.revokedPersons = 0;
    service().revoke(T, TASK, DOER, MANAGER);
    assertThat(audits).hasSize(before);
    assertThatThrownBy(() -> service().revoke(T, TASK, ASSIGNER, D))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service().revoke(T, TASK, ASSIGNER, OUT))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(() -> service().revoke(T, TASK, RECOVERY_PERSON, A))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    key = Optional.empty();
    service().revoke(T, TASK, DOER, A);
  }

  @Test
  void napKhoaSauKhoiPhucTheoBienBan() {
    firstSave();
    records.put(
        RECOVERY_ID,
        new RecoveryRecords.Recovery(
            "TASK_CONTENT", TASK, DOER, device("d-moi"), NOW.minus(Duration.ofHours(1))));
    Meta m = service().restore(T, TASK, RECOVERY_ID, rsa(DOER, "d-moi"), MANAGER);
    assertThat(m.rowVersion()).isZero();
    assertThat(m.recipientCount()).isEqualTo(3);
    assertThat(audits.get(audits.size() - 1).action()).isEqualTo("KHOI_PHUC_KHOA_NOI_DUNG_MAT");
  }

  @Test
  void napKhoaSaiBienBanBiChan() {
    firstSave();
    TaskSecretContentService s = service();
    assertThatThrownBy(() -> s.restore(T, TASK, null, rsa(DOER, "d"), MANAGER))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> s.restore(T, TASK, RECOVERY_ID, null, MANAGER))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () -> s.restore(T, TASK, RECOVERY_ID, rsa(RECOVERY_PERSON, "phuc-hoi"), MANAGER))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> s.restore(T, TASK, RECOVERY_ID, rsa(DOER, "d"), MANAGER))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    for (RecoveryRecords.Recovery wrong :
        List.of(
            new RecoveryRecords.Recovery("KHAC", TASK, DOER, device("d"), NOW),
            new RecoveryRecords.Recovery("TASK_CONTENT", UUID.randomUUID(), DOER, device("d"), NOW),
            new RecoveryRecords.Recovery("TASK_CONTENT", TASK, ACCEPTOR, device("d"), NOW),
            new RecoveryRecords.Recovery("TASK_CONTENT", TASK, DOER, device("khac"), NOW),
            new RecoveryRecords.Recovery(
                "TASK_CONTENT", TASK, DOER, device("d"), NOW.minus(Duration.ofHours(25))))) {
      records.put(RECOVERY_ID, wrong);
      assertThatThrownBy(() -> s.restore(T, TASK, RECOVERY_ID, rsa(DOER, "d"), MANAGER))
          .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    }
    records.put(
        RECOVERY_ID,
        new RecoveryRecords.Recovery("TASK_CONTENT", TASK, OUTSIDER, device("x"), NOW));
    assertThatThrownBy(() -> s.restore(T, TASK, RECOVERY_ID, rsa(OUTSIDER, "x"), MANAGER))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    key = Optional.empty();
    records.put(
        RECOVERY_ID, new RecoveryRecords.Recovery("TASK_CONTENT", TASK, DOER, device("d"), NOW));
    assertThat(s.restore(T, TASK, RECOVERY_ID, rsa(DOER, "d"), MANAGER)).isNotNull();
  }

  @Test
  void khongCoNguonBienBanThiKhoiPhucTra503() {
    firstSave();
    TaskSecretContentService noRecords =
        new TaskSecretContentService(store, tenant -> key, audits::add, clock);
    assertThatThrownBy(() -> noRecords.restore(T, TASK, RECOVERY_ID, rsa(DOER, "d"), MANAGER))
        .isInstanceOf(DependencyUnavailableException.class);
  }
}
