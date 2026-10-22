package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.okr.application.OkrComposeService.LinkDraft;
import vn.viettel.okrkpi.okr.application.OkrComposeService.ObjectiveDraft;
import vn.viettel.okrkpi.okr.application.OkrComposeService.Owner;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.LinkRow;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.KeyResultData;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.ObjectiveHead;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.ObjectiveText;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị phía soạn OKR (CORE-01-03) với kho giả lập. */
class OkrComposeServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID ACTOR = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID VERIFIER = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final PeriodSnapshot Q1 = period(PERIOD, PeriodStatus.MO);

  private final OkrWriteRepository repo = mock(OkrWriteRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OkrComposeService service = new OkrComposeService(repo, periods);

  static PeriodSnapshot period(UUID id, PeriodStatus status) {
    return new PeriodSnapshot(
        PeriodId.of(id),
        TENANT,
        "Q1",
        "Quý I năm 2027",
        LocalDate.of(2027, 1, 1),
        LocalDate.of(2027, 3, 31),
        status);
  }

  static KeyResultData kr(UUID id, String title, String weight) {
    return new KeyResultData(
        id,
        title,
        null,
        "chi bộ",
        null,
        BigDecimal.TEN,
        null,
        weight == null ? null : new BigDecimal(weight),
        null,
        VERIFIER,
        null,
        null,
        null,
        null,
        null);
  }

  static ObjectiveDraft draft(List<KeyResultData> krs) {
    return new ObjectiveDraft(
        "  Nâng chất lượng sinh hoạt  ", "Mô tả", 1, null, List.of(), null, null, krs);
  }

  static ObjectiveHead head(UUID id, String status, long version) {
    return new ObjectiveHead(id, PERIOD, 20271, "TAP_THE", UNIT, null, "O3", null, status, version);
  }

  static List<String> fields(Throwable t) {
    return ((BusinessException) t).errors().stream().map(ProblemFieldError::field).toList();
  }

  @BeforeEach
  void setUp() {
    when(periods.findById(eq(TENANT), eq(PeriodId.of(PERIOD)))).thenReturn(Optional.of(Q1));
  }

  @Test
  void taoMucTieuGhiMaVaKr() {
    when(repo.maxObjectiveNo(TENANT, 20271, UNIT, PERIOD)).thenReturn(2);
    UUID basis = UUID.randomUUID();
    when(repo.existingBases(eq(TENANT), anyList())).thenReturn(List.of(basis));
    ObjectiveDraft d =
        new ObjectiveDraft(
            "Tiêu đề",
            null,
            null,
            null,
            List.of(basis, basis),
            null,
            null,
            List.of(kr(null, "KR một", "0.5"), kr(null, "KR hai", "0.5")));
    UUID id = service.create(TENANT, ACTOR, new Owner(PERIOD, "TAP_THE", UNIT, null), d, 5);
    assertThat(id).isNotNull();
    verify(repo)
        .insertObjective(
            eq(TENANT),
            eq(id),
            eq(PERIOD),
            eq(20271),
            eq("TAP_THE"),
            eq(UNIT),
            eq(null),
            eq("O3"),
            any(),
            eq(ACTOR));
    verify(repo).replaceBases(TENANT, 20271, PERIOD, id, List.of(basis), ACTOR);
    verify(repo).insertKeyResult(any(), any(), any(), anyInt(), eq(id), eq("KR 3.1"), any(), any());
    verify(repo).insertKeyResult(any(), any(), any(), anyInt(), eq(id), eq("KR 3.2"), any(), any());
  }

  @Test
  void taoMucTieuThieuTruongVaCaNhanNguoiKhac() {
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT,
                    ACTOR,
                    new Owner(null, "CA_NHAN", null, UUID.randomUUID()),
                    draft(List.of()),
                    5))
        .satisfies(
            t -> assertThat(fields(t)).containsExactly("periodId", "ownerUnitId", "ownerPersonId"));
  }

  @Test
  void taoMucTieuKyKhongTonTaiHoacDaKhoa() {
    UUID other = UUID.randomUUID();
    when(periods.findById(TENANT, PeriodId.of(other))).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT, ACTOR, new Owner(other, "TAP_THE", UNIT, null), draft(List.of()), 5))
        .satisfies(t -> assertThat(fields(t)).containsExactly("periodId"));

    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(eq(TENANT), eq(PeriodId.of(PERIOD)), any());
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT, ACTOR, new Owner(PERIOD, "TAP_THE", UNIT, null), draft(List.of()), 5))
        .isInstanceOf(PeriodLockedException.class);
  }

  @Test
  void taoMucTieuKiemTraTruongVaKr() {
    UUID parent = UUID.randomUUID();
    when(repo.findObjective(TENANT, parent))
        .thenReturn(
            Optional.of(
                new ObjectiveHead(
                    parent,
                    UUID.randomUUID(),
                    20271,
                    "TAP_THE",
                    UNIT,
                    null,
                    "O1",
                    null,
                    "DA_DUYET",
                    0)));
    when(repo.existingBases(eq(TENANT), anyList())).thenReturn(List.of());
    when(repo.countOwnerObjectives(any(), any(), any(), any(), any())).thenReturn(5L);
    KeyResultData bad =
        new KeyResultData(
            UUID.randomUUID(),
            "x".repeat(301),
            "TY_LE",
            "u".repeat(33),
            BigDecimal.ONE,
            BigDecimal.ONE,
            "GIAM",
            new BigDecimal("1.5"),
            LocalDate.of(2028, 1, 1),
            ACTOR,
            "d".repeat(201),
            "f".repeat(1001),
            "THANG",
            "AI_ACCEPTED",
            null);
    KeyResultData empty =
        new KeyResultData(
            null, " ", null, null, null, null, null, null, null, null, null, null, null, null,
            null);
    List<KeyResultData> six = new ArrayList<>(List.of(bad, empty));
    for (int i = 0; i < 4; i++) {
      six.add(kr(null, "KR " + i, "0.3"));
    }
    ObjectiveDraft d =
        new ObjectiveDraft(
            "t".repeat(201),
            "d".repeat(4001),
            4,
            parent,
            List.of(UUID.randomUUID()),
            "r".repeat(501),
            null,
            six);
    assertThatThrownBy(
            () -> service.create(TENANT, ACTOR, new Owner(PERIOD, "TAP_THE", UNIT, null), d, 5))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            t ->
                assertThat(fields(t))
                    .contains(
                        "title",
                        "description",
                        "tier",
                        "overLimitReason",
                        "parentObjectiveId",
                        "basisIds",
                        "keyResults",
                        "keyResults[0].id",
                        "keyResults[0].title",
                        "keyResults[0].unit",
                        "keyResults[0].weight",
                        "keyResults[0].targetValue",
                        "keyResults[0].dueDate",
                        "keyResults[0].verifierPersonId",
                        "keyResults[0].contentSource",
                        "keyResults[0].dataSource",
                        "keyResults[0].formulaText",
                        "keyResults[1].title",
                        "keyResults[1].unit",
                        "keyResults[1].weight",
                        "keyResults[1].verifierPersonId"));
  }

  @Test
  void vuotSoMucTieuCanLyDo() {
    when(repo.countOwnerObjectives(any(), any(), any(), any(), any())).thenReturn(5L);
    ObjectiveDraft d =
        new ObjectiveDraft(
            "Tiêu đề", null, 1, null, null, null, null, List.of(kr(null, "KR", "1")));
    assertThatThrownBy(
            () -> service.create(TENANT, ACTOR, new Owner(PERIOD, "TAP_THE", UNIT, null), d, 5))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errorCode().code())
                    .isEqualTo("OKR-422-OVER-LIMIT-REASON-REQUIRED"));
    // Mục tiêu cấp trên chính nó khi sửa.
    UUID id = UUID.randomUUID();
    when(repo.lockObjective(TENANT, id)).thenReturn(Optional.of(head(id, "NHAP", 0)));
    ObjectiveDraft self =
        new ObjectiveDraft("Tiêu đề", null, 1, id, null, "Lý do", null, List.of());
    assertThatThrownBy(() -> service.update(TENANT, ACTOR, id, "\"0\"", self, 5))
        .satisfies(t -> assertThat(fields(t)).containsExactly("parentObjectiveId"));
  }

  @Test
  void suaMucTieuGhiLaiThemVaXoaKr() {
    UUID id = UUID.randomUUID();
    UUID keep = UUID.randomUUID();
    UUID drop = UUID.randomUUID();
    UUID parent = UUID.randomUUID();
    when(repo.lockObjective(TENANT, id)).thenReturn(Optional.of(head(id, "NHAP", 4)));
    when(repo.keyResultIds(TENANT, 20271, id)).thenReturn(List.of(keep, drop));
    when(repo.maxKeyResultNo(TENANT, 20271, id)).thenReturn(2);
    when(repo.rewriteObjective(eq(TENANT), any(), any(), eq(ACTOR))).thenReturn(5L);
    when(repo.findObjective(TENANT, parent)).thenReturn(Optional.of(head(parent, "DA_DUYET", 0)));
    ObjectiveDraft d =
        new ObjectiveDraft(
            "Tiêu đề",
            null,
            2,
            parent,
            null,
            null,
            null,
            List.of(kr(keep, "Giữ", "0.4"), kr(null, "Mới", "0.6")));
    assertThat(service.update(TENANT, ACTOR, id, "\"4\"", d, 5)).isEqualTo(5L);
    verify(repo).rewriteKeyResult(eq(TENANT), eq(20271), any(), eq(ACTOR));
    verify(repo).insertKeyResult(any(), any(), any(), anyInt(), eq(id), eq("KR 3.3"), any(), any());
    verify(repo).replaceParentLink(TENANT, PERIOD, id, parent, ACTOR);
    verify(repo).dropLinksTouching(TENANT, List.of(drop));
    verify(repo).dropKeyResults(TENANT, 20271, List.of(drop));
  }

  @Test
  void suaMucTieuLechPhienBanDaTrinhHoacKhongCo() {
    UUID id = UUID.randomUUID();
    when(repo.lockObjective(TENANT, id)).thenReturn(Optional.of(head(id, "NHAP", 4)));
    assertThatThrownBy(() -> service.update(TENANT, ACTOR, id, "\"3\"", draft(List.of()), 5))
        .isInstanceOf(VersionConflictException.class);
    when(repo.lockObjective(TENANT, id)).thenReturn(Optional.of(head(id, "CHO_DUYET", 4)));
    assertThatThrownBy(() -> service.update(TENANT, ACTOR, id, "\"4\"", draft(List.of()), 5))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errorCode().code())
                    .isEqualTo("OKR-409-INVALID-STATE"));
    UUID missing = UUID.randomUUID();
    when(repo.lockObjective(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(TENANT, ACTOR, missing, "\"0\"", draft(List.of()), 5))
        .isInstanceOf(OkrComposeService.NotFound.class);
    // Kỳ của mục tiêu không còn.
    UUID orphan = UUID.randomUUID();
    UUID gone = UUID.randomUUID();
    when(repo.lockObjective(TENANT, orphan))
        .thenReturn(
            Optional.of(
                new ObjectiveHead(
                    orphan, gone, 20271, "TAP_THE", UNIT, null, "O1", null, "NHAP", 0)));
    when(periods.findById(TENANT, PeriodId.of(gone))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(TENANT, ACTOR, orphan, "\"0\"", draft(List.of()), 5))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void themKrKiemSoLuongVaTrongSo() {
    UUID id = UUID.randomUUID();
    when(repo.lockObjective(TENANT, id)).thenReturn(Optional.of(head(id, "NHAP", 1)));
    when(repo.keyResultsOf(TENANT, 20271, id)).thenReturn(List.of(kr(null, "Có", "0.5")));
    when(repo.maxKeyResultNo(TENANT, 20271, id)).thenReturn(1);
    UUID krId = service.addKeyResult(TENANT, ACTOR, id, kr(null, "Mới", "0.5"));
    assertThat(krId).isNotNull();
    verify(repo)
        .insertKeyResult(any(), eq(krId), any(), anyInt(), eq(id), eq("KR 3.2"), any(), any());
    verify(repo).touchObjective(eq(TENANT), any(), eq(ACTOR));

    assertThatThrownBy(() -> service.addKeyResult(TENANT, ACTOR, id, kr(null, "Quá", "0.6")))
        .satisfies(t -> assertThat(fields(t)).containsExactly("weight"));
    when(repo.keyResultsOf(TENANT, 20271, id))
        .thenReturn(
            List.of(
                kr(null, "1", "0"),
                kr(null, "2", "0"),
                kr(null, "3", "0"),
                kr(null, "4", "0"),
                kr(null, "5", "0")));
    assertThatThrownBy(() -> service.addKeyResult(TENANT, ACTOR, id, kr(null, "6", null)))
        .satisfies(t -> assertThat(fields(t)).contains("keyResults", "weight"));
    assertThatThrownBy(() -> service.addKeyResult(TENANT, ACTOR, null, kr(null, "x", "0.1")))
        .satisfies(t -> assertThat(fields(t)).containsExactly("objectiveId"));
    UUID missing = UUID.randomUUID();
    when(repo.lockObjective(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.addKeyResult(TENANT, ACTOR, missing, kr(null, "x", "0.1")))
        .satisfies(t -> assertThat(fields(t)).containsExactly("objectiveId"));
  }

  @Test
  void saoChepTuKyTruoc() {
    UUID from = UUID.randomUUID();
    UUID src = UUID.randomUUID();
    when(periods.findById(TENANT, PeriodId.of(from)))
        .thenReturn(Optional.of(period(from, PeriodStatus.DA_KHOA)));
    when(repo.maxObjectiveNo(TENANT, 20271, UNIT, PERIOD)).thenReturn(1);
    when(repo.objectivesOf(TENANT, from, UNIT, null)).thenReturn(List.of(src));
    when(repo.findObjective(TENANT, src)).thenReturn(Optional.of(head(src, "DA_DUYET", 3)));
    when(repo.objectiveText(TENANT, 20271, src)).thenReturn(new ObjectiveText("Cũ", null, 1));
    when(repo.keyResultsOf(TENANT, 20271, src))
        .thenReturn(List.of(kr(UUID.randomUUID(), "K", "1")));
    List<UUID> ids = service.copyFromPeriod(TENANT, ACTOR, from, PERIOD, UNIT, null);
    assertThat(ids).hasSize(1);
    verify(repo)
        .insertKeyResult(
            any(),
            any(),
            eq(PERIOD),
            eq(20271),
            eq(ids.getFirst()),
            eq("KR 2.1"),
            any(),
            eq(ACTOR));

    assertThatThrownBy(() -> service.copyFromPeriod(TENANT, ACTOR, null, null, null, null))
        .satisfies(
            t ->
                assertThat(fields(t)).containsExactly("fromPeriodId", "toPeriodId", "ownerUnitId"));
    assertThatThrownBy(() -> service.copyFromPeriod(TENANT, ACTOR, PERIOD, PERIOD, UNIT, null))
        .satisfies(t -> assertThat(fields(t)).containsExactly("toPeriodId"));
    UUID gone = UUID.randomUUID();
    when(periods.findById(TENANT, PeriodId.of(gone))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.copyFromPeriod(TENANT, ACTOR, gone, PERIOD, UNIT, null))
        .satisfies(t -> assertThat(fields(t)).containsExactly("fromPeriodId"));
  }

  @Test
  void taoLienKetTuCanCuVaTuMucTieu() {
    UUID basis = UUID.randomUUID();
    UUID objective = UUID.randomUUID();
    UUID keyResult = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    LinkRow row =
        new LinkRow(
            UUID.randomUUID(), PERIOD, "BASIS", basis, "OBJECTIVE", objective, "CAN_CU", null, 0);
    when(repo.existingBases(TENANT, List.of(basis))).thenReturn(List.of(basis));
    when(repo.findObjective(TENANT, objective)).thenReturn(Optional.of(head(objective, "NHAP", 0)));
    when(repo.findObjective(TENANT, other)).thenReturn(Optional.of(head(other, "DA_DUYET", 0)));
    when(repo.objectiveOfKeyResult(TENANT, keyResult)).thenReturn(Optional.of(objective));
    when(repo.findLink(eq(TENANT), any())).thenReturn(Optional.of(row));
    assertThat(
            service.createLink(
                TENANT,
                ACTOR,
                new LinkDraft("BASIS", basis, "OBJECTIVE", objective, "CAN_CU", null)))
        .isEqualTo(row);
    service.createLink(
        TENANT,
        ACTOR,
        new LinkDraft("KEY_RESULT", keyResult, "OBJECTIVE", other, "DONG_GOP", BigDecimal.ONE));
    verify(repo, times(2))
        .insertLink(any(), any(), eq(PERIOD), any(), any(), any(), any(), any(), any(), eq(ACTOR));

    when(repo.linkExists(TENANT, "OBJECTIVE", objective, "TASK", other)).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.createLink(
                    TENANT,
                    ACTOR,
                    new LinkDraft("OBJECTIVE", objective, "TASK", other, "DONG_GOP", null)))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errorCode().code())
                    .isEqualTo("OKR-409-LINK-EXISTS"));
    assertThatThrownBy(
            () ->
                service.createLink(
                    TENANT, ACTOR, new LinkDraft("OBJECTIVE", null, "OBJECTIVE", null, "X", null)))
        .satisfies(t -> assertThat(fields(t)).containsExactly("fromId", "toId"));
    assertThatThrownBy(
            () ->
                service.createLink(
                    TENANT,
                    ACTOR,
                    new LinkDraft("OBJECTIVE", objective, "OBJECTIVE", objective, "X", null)))
        .satisfies(t -> assertThat(fields(t)).containsExactly("toId"));
    when(repo.existingBases(TENANT, List.of(other))).thenReturn(List.of());
    assertThatThrownBy(
            () ->
                service.createLink(
                    TENANT,
                    ACTOR,
                    new LinkDraft("BASIS", other, "OBJECTIVE", objective, "X", null)))
        .satisfies(t -> assertThat(fields(t)).containsExactly("fromId"));
    assertThatThrownBy(
            () ->
                service.createLink(
                    TENANT, ACTOR, new LinkDraft("PLAN", other, "OBJECTIVE", objective, "X", null)))
        .satisfies(t -> assertThat(fields(t)).containsExactly("fromId"));
    UUID ghost = UUID.randomUUID();
    when(repo.objectiveOfKeyResult(TENANT, ghost)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.createLink(
                    TENANT,
                    ACTOR,
                    new LinkDraft("KEY_RESULT", ghost, "OBJECTIVE", objective, "X", null)))
        .satisfies(t -> assertThat(fields(t)).containsExactly("fromId"));
    when(repo.findObjective(TENANT, ghost)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.createLink(
                    TENANT,
                    ACTOR,
                    new LinkDraft("OBJECTIVE", ghost, "OBJECTIVE", objective, "X", null)))
        .satisfies(t -> assertThat(fields(t)).containsExactly("fromId"));
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(eq(TENANT), eq(PeriodId.of(PERIOD)), any());
    assertThatThrownBy(
            () ->
                service.createLink(
                    TENANT,
                    ACTOR,
                    new LinkDraft("OBJECTIVE", objective, "TASK", UUID.randomUUID(), "X", null)))
        .isInstanceOf(PeriodLockedException.class);
  }

  @Test
  void xoaLienKet() {
    UUID objective = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID basisLink = UUID.randomUUID();
    when(repo.findObjective(TENANT, objective)).thenReturn(Optional.of(head(objective, "NHAP", 0)));
    when(repo.findLink(TENANT, id))
        .thenReturn(
            Optional.of(
                new LinkRow(
                    id,
                    PERIOD,
                    "OBJECTIVE",
                    objective,
                    "TASK",
                    UUID.randomUUID(),
                    "DONG_GOP",
                    null,
                    2)));
    when(repo.findLink(TENANT, basisLink))
        .thenReturn(
            Optional.of(
                new LinkRow(
                    basisLink,
                    PERIOD,
                    "BASIS",
                    UUID.randomUUID(),
                    "OBJECTIVE",
                    objective,
                    "CAN_CU",
                    null,
                    0)));
    service.deleteLink(TENANT, id, "\"2\"");
    service.deleteLink(TENANT, basisLink, "\"0\"");
    verify(repo).dropLink(TENANT, id);
    verify(repo).dropLink(TENANT, basisLink);
    UUID missing = UUID.randomUUID();
    when(repo.findLink(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.deleteLink(TENANT, missing, "\"0\""))
        .isInstanceOf(OkrComposeService.NotFound.class);
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(eq(TENANT), eq(PeriodId.of(PERIOD)), any());
    assertThatThrownBy(() -> service.deleteLink(TENANT, id, "\"2\""))
        .isInstanceOf(PeriodLockedException.class);
    verify(repo, never()).dropLinksTouching(any(), any());
  }

  @Test
  void maKrBucketVaDocDauMucTieu() {
    assertThat(OkrComposeService.keyResultCode("O12", 3)).isEqualTo("KR 12.3");
    assertThat(OkrComposeService.keyResultCode("DV-1", 1)).isEqualTo("KR DV-1.1");
    assertThat(OkrComposeService.bucket(Q1)).isEqualTo(20271);
    assertThat(
            OkrComposeService.bucket(
                new PeriodSnapshot(
                    PeriodId.of(PERIOD),
                    TENANT,
                    "N",
                    "Năm 2027",
                    LocalDate.of(2027, 1, 1),
                    LocalDate.of(2027, 12, 31),
                    PeriodStatus.MO)))
        .isEqualTo(20270);
    UUID id = UUID.randomUUID();
    when(repo.findObjective(TENANT, id)).thenReturn(Optional.of(head(id, "NHAP", 0)));
    assertThat(service.head(TENANT, id)).isPresent();
  }
}
