package vn.viettel.okrkpi.appraisal.application.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Command;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Item;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Prepared;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.View;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.BatchRating;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.BatchRow;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.NewBatch;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.ProfileFacts;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.CurrentRating;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Quyết định xếp loại hàng loạt với cổng giả (CORE-04-07). */
class BatchDecisionServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2027-01-12T03:00:00Z"), ZoneOffset.UTC);
  private static final int BUCKET = 20264;
  private static final String REASON =
      "Tập thể thống nhất hạ một mức do chưa đạt mục tiêu then chốt.";

  private final UUID period = UUID.randomUUID();
  private final UUID decider = UUID.randomUUID();
  private final UUID appraiser = UUID.randomUUID();
  private final UUID acceptor = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID p1 = UUID.randomUUID();
  private final UUID p2 = UUID.randomUUID();
  private final AppraisalProfileRepository profiles = mock(AppraisalProfileRepository.class);
  private final BatchDecisionRepository batches = mock(BatchDecisionRepository.class);
  private final AppraisalProfileService steps = mock(AppraisalProfileService.class);
  private final PolicyQueryApi policies = mock(PolicyQueryApi.class);
  private final Map<UUID, ProfileFacts> facts = new HashMap<>();
  private BatchDecisionService service;

  @BeforeEach
  void setUp() {
    service =
        new BatchDecisionService(
            profiles,
            batches,
            steps,
            new PlainSealer(),
            (t, per, who) -> Set.of(acceptor),
            policies,
            CLOCK);
    lock(p1, AppraisalStatus.CHO_QUYET_DINH, 3, RatingLevel.HTTNV, RatingLevel.HTTNV);
    lock(p2, AppraisalStatus.CHO_QUYET_DINH, 5, RatingLevel.HTNV, RatingLevel.HTNV);
    when(batches.findByNumber(any(), anyInt(), any())).thenReturn(Optional.empty());
    when(batches.facts(eq(TENANT), eq(BUCKET), any())).thenReturn(facts);
    when(batches.outOfScope(eq(TENANT), eq(BUCKET), any(), any())).thenReturn(Set.of());
    when(steps.recordRating(
            any(), any(), isNull(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            a ->
                new CurrentRating(
                    a.getArgument(9),
                    LevelKind.QUYET_DINH,
                    a.getArgument(4),
                    decider,
                    a.getArgument(7) != null,
                    a.getArgument(7) != null,
                    false));
  }

  @Test
  void ghiLoVaMucQuyetDinhTrongMotGiaoDich() {
    Command cmd =
        command(
            false, item(p1, RatingLevel.HTTNV, null, 3), item(p2, RatingLevel.KHTNV, REASON, 5));
    Prepared prepared = service.prepare(TENANT, decider, cmd, Scope.NONE);
    assertThat(prepared.signRequestId()).isNull();
    assertThat(prepared.periodBucket()).isEqualTo(BUCKET);
    verify(batches).insert(eq(TENANT), any(NewBatch.class));
    verify(steps, times(2))
        .recordRating(
            eq(TENANT),
            any(),
            isNull(),
            eq(LevelKind.QUYET_DINH),
            any(),
            eq(decider),
            eq("decider"),
            any(),
            isNull(),
            any(),
            eq(prepared.batchId()));
    verify(steps)
        .recordExplanation(
            TENANT, p2, LevelKind.QUYET_DINH, "HA_MUC", null, "QĐ Điều 16", null, decider);
    verify(batches).touchProfiles(eq(TENANT), eq(BUCKET), any(), eq(decider), any());
    service.attachSignRequest(TENANT, prepared, UUID.randomUUID(), decider);
    verify(batches)
        .attachSignRequest(eq(TENANT), eq(BUCKET), eq(prepared.batchId()), any(), any(), any());
  }

  @Test
  void kiemDuLieuDauVao() {
    List<Item> many = new ArrayList<>();
    for (int i = 0; i <= BatchDecisionService.MAX_ITEMS; i++) {
      many.add(item(UUID.randomUUID(), RatingLevel.HTNV, null, 1));
    }
    assertCode(
        () ->
            service.prepare(
                TENANT,
                decider,
                new Command(period, "DANG", "1-QĐ", LocalDate.now(), false, many),
                Scope.NONE),
        "COMMON-422-VALIDATION");
    assertCode(
        () ->
            service.prepare(
                TENANT, decider, new Command(null, "X", " ", null, false, null), Scope.NONE),
        "COMMON-422-VALIDATION");
    assertThatThrownBy(
            () ->
                service.prepare(
                    TENANT,
                    decider,
                    command(
                        false,
                        item(p1, null, null, 3),
                        item(p1, RatingLevel.HTNV, "x".repeat(2001), null)),
                    Scope.NONE))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(f -> f.field())
                    .contains(
                        "items[0].ratingCode",
                        "items[1].profileId",
                        "items[1].rowVersion",
                        "items[1].reason"));
    when(profiles.lock(TENANT, p2)).thenReturn(Optional.empty());
    assertCode(
        () ->
            service.prepare(
                TENANT, decider, command(false, item(p2, RatingLevel.HTNV, null, 5)), Scope.NONE),
        "COMMON-422-VALIDATION");
  }

  @Test
  void guiLaiCungSoQuyetDinhTiepTucLoCu() {
    Command cmd = command(false, item(p1, RatingLevel.HTTNV, null, 3));
    UUID sign = UUID.randomUUID();
    BatchRow open =
        batch(
            BatchDecisionService.CHO_KY,
            decider,
            BatchDecisionService.profilesHash(cmd.items()),
            sign);
    when(batches.findByNumber(TENANT, 2026, "215-QĐ/ĐUK")).thenReturn(Optional.of(open));
    Prepared prepared = service.prepare(TENANT, decider, cmd, Scope.NONE);
    assertThat(prepared.batchId()).isEqualTo(open.id());
    assertThat(prepared.signRequestId()).isEqualTo(sign);
    verify(batches, never()).insert(any(), any());

    when(batches.findByNumber(TENANT, 2026, "215-QĐ/ĐUK"))
        .thenReturn(Optional.of(batch(BatchDecisionService.NHAP, decider, new byte[32], null)));
    assertCode(
        () -> service.prepare(TENANT, decider, cmd, Scope.NONE),
        "APPRAISAL-409-DECISION-NO-DUPLICATE");
    when(batches.findByNumber(TENANT, 2026, "215-QĐ/ĐUK"))
        .thenReturn(
            Optional.of(
                batch(
                    BatchDecisionService.HUY,
                    decider,
                    BatchDecisionService.profilesHash(cmd.items()),
                    null)));
    assertCode(
        () -> service.prepare(TENANT, decider, cmd, Scope.NONE),
        "APPRAISAL-409-DECISION-NO-DUPLICATE");
    when(batches.findByNumber(TENANT, 2026, "215-QĐ/ĐUK")).thenReturn(Optional.of(open));
    assertCode(
        () -> service.prepare(TENANT, UUID.randomUUID(), cmd, Scope.NONE),
        "APPRAISAL-409-DECISION-NO-DUPLICATE");
  }

  @Test
  void trungSoKhiGhiDongThoi() {
    doThrow(new DuplicateKeyException("uq")).when(batches).insert(any(), any());
    assertCode(
        () ->
            service.prepare(
                TENANT, decider, command(false, item(p1, RatingLevel.HTTNV, null, 3)), Scope.NONE),
        "APPRAISAL-409-DECISION-NO-DUPLICATE");
  }

  @Test
  void lechPhienBanTuChoiCaLo() {
    assertThatThrownBy(
            () ->
                service.prepare(
                    TENANT,
                    decider,
                    command(
                        false,
                        item(p1, RatingLevel.HTTNV, null, 3),
                        item(p2, RatingLevel.HTNV, null, 4)),
                    Scope.NONE))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e).hasMessage("APPRAISAL-409-VERSION-CONFLICT");
              assertThat(e.errors())
                  .singleElement()
                  .extracting(f -> f.field())
                  .isEqualTo("items[1].rowVersion");
            });
    verify(batches, never()).insert(any(), any());
    lock(p2, AppraisalStatus.DA_KY_SO, 5, RatingLevel.HTNV, RatingLevel.HTNV);
    assertCode(
        () ->
            service.prepare(
                TENANT, decider, command(false, item(p2, RatingLevel.HTNV, null, 5)), Scope.NONE),
        "APPRAISAL-409-INVALID-STATUS");
  }

  @Test
  void hoSoDangOLoKhacPhamViVaTachVaiTro() {
    Command cmd = command(false, item(p1, RatingLevel.HTTNV, null, 3));
    facts.put(p1, new ProfileFacts(p1, unit, false, UUID.randomUUID()));
    assertCode(
        () -> service.prepare(TENANT, decider, cmd, Scope.NONE), "APPRAISAL-409-INVALID-STATUS");
    facts.clear();
    when(batches.outOfScope(eq(TENANT), eq(BUCKET), any(), any())).thenReturn(Set.of(p1));
    assertCode(() -> service.prepare(TENANT, decider, cmd, Scope.NONE), "COMMON-403-OUT-OF-SCOPE");
    when(batches.outOfScope(eq(TENANT), eq(BUCKET), any(), any())).thenReturn(Set.of());
    assertCode(
        () -> service.prepare(TENANT, appraiser, cmd, Scope.NONE),
        "APPRAISAL-403-SEPARATION-OF-DUTIES");
    assertCode(
        () -> service.prepare(TENANT, acceptor, cmd, Scope.NONE),
        "APPRAISAL-403-SEPARATION-OF-DUTIES");
  }

  @Test
  void lyDoDoiMuc() {
    assertCode(
        () ->
            service.prepare(
                TENANT, decider, command(false, item(p2, RatingLevel.KHTNV, null, 5)), Scope.NONE),
        "APPRAISAL-422-REASON-REQUIRED");
    assertCode(
        () ->
            service.prepare(
                TENANT,
                decider,
                command(false, item(p2, RatingLevel.KHTNV, "Quá ngắn", 5)),
                Scope.NONE),
        "COMMON-422-VALIDATION");
    lock(p1, AppraisalStatus.CHO_QUYET_DINH, 3, RatingLevel.HTXSNV, RatingLevel.HTTNV);
    assertCode(
        () ->
            service.prepare(
                TENANT, decider, command(true, item(p1, RatingLevel.HTTNV, null, 3)), Scope.NONE),
        "APPRAISAL-422-SUGGESTED-REASON-REQUIRED");
  }

  @Test
  void mucGoiYTheoAnhChupNguoiQuyetDinhDaXem() {
    lock(p1, AppraisalStatus.CHO_QUYET_DINH, 3, RatingLevel.HTTNV, RatingLevel.HTTNV);
    UUID older = UUID.randomUUID();
    when(profiles.snapshotRating(TENANT, BUCKET, p1, older))
        .thenReturn(Optional.of(RatingLevel.HTXSNV));
    assertCode(
        () ->
            service.prepare(
                TENANT,
                decider,
                command(true, new Item(p1, RatingLevel.HTTNV, null, 3L, older)),
                Scope.NONE),
        "APPRAISAL-422-SUGGESTED-REASON-REQUIRED");
    assertThatThrownBy(
            () ->
                service.prepare(
                    TENANT,
                    decider,
                    command(true, new Item(p1, RatingLevel.HTTNV, null, 3L, UUID.randomUUID())),
                    Scope.NONE))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e).hasMessage("COMMON-422-VALIDATION");
              assertThat(e.errors().get(0).field()).endsWith("seenScoreSnapshotId");
            });
  }

  @Test
  void nguongHtxsnvCanXacNhanHieuChinh() {
    lock(p1, AppraisalStatus.CHO_QUYET_DINH, 3, RatingLevel.HTXSNV, RatingLevel.HTXSNV);
    Command cmd =
        command(false, item(p1, RatingLevel.HTXSNV, null, 3), item(p2, RatingLevel.HTNV, null, 5));
    assertThatThrownBy(() -> service.prepare(TENANT, decider, cmd, Scope.NONE))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e).hasMessage("APPRAISAL-422-CALIBRATION-REQUIRED");
              assertThat(e.detail()).contains("50%").contains("20%");
              assertThat(e.msg()).isEqualTo("MSG-09-13");
            });
    when(policies.parameter(any(), any(), eq("phan_bo.toi_da_htxsnv")))
        .thenReturn(Optional.of(0.6));
    assertThat(service.prepare(TENANT, decider, cmd, Scope.NONE)).isNotNull();
    when(policies.parameter(any(), any(), eq("phan_bo.toi_da_htxsnv"))).thenReturn(Optional.of(30));
    assertCode(
        () -> service.prepare(TENANT, decider, cmd, Scope.NONE),
        "APPRAISAL-422-CALIBRATION-REQUIRED");
    Command ack =
        command(true, item(p1, RatingLevel.HTXSNV, null, 3), item(p2, RatingLevel.HTNV, null, 5));
    assertThat(service.prepare(TENANT, decider, ack, Scope.NONE)).isNotNull();
    BatchDecisionService noPolicy =
        new BatchDecisionService(profiles, batches, steps, new PlainSealer(), null, null, CLOCK);
    assertCode(
        () -> noPolicy.prepare(TENANT, decider, cmd, Scope.NONE),
        "APPRAISAL-422-CALIBRATION-REQUIRED");
  }

  @Test
  void nguoiDungDauQuyetDinhSauCapDuoi() {
    facts.put(p1, new ProfileFacts(p1, unit, true, null));
    when(batches.undecidedSubordinates(
            eq(TENANT), eq(BUCKET), eq(period), eq("DANG"), eq(unit), any()))
        .thenReturn(2);
    Command cmd = command(false, item(p1, RatingLevel.HTTNV, null, 3));
    assertThatThrownBy(() -> service.prepare(TENANT, decider, cmd, Scope.NONE))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e).hasMessage("APPRAISAL-422-HEAD-TOO-EARLY");
              assertThat(e.msg()).isEqualTo("MSG-09-14");
            });
    when(batches.undecidedSubordinates(
            eq(TENANT), eq(BUCKET), eq(period), eq("DANG"), eq(unit), any()))
        .thenReturn(0);
    assertThat(service.prepare(TENANT, decider, cmd, Scope.NONE).batchId()).isNotNull();
  }

  @Test
  void huyLo() {
    UUID sign = UUID.randomUUID();
    BatchRow b = batch(BatchDecisionService.CHO_KY, decider, new byte[32], sign);
    when(batches.find(TENANT, b.id(), true)).thenReturn(Optional.of(b));
    when(batches.find(TENANT, b.id(), false)).thenReturn(Optional.of(b));
    assertThatThrownBy(() -> service.cancel(TENANT, b.id(), 2, UUID.randomUUID()))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> service.cancel(TENANT, b.id(), 1, decider))
        .isInstanceOf(VersionConflictException.class);
    when(batches.supersedeUnsigned(eq(TENANT), eq(BUCKET), eq(b.id()), eq(decider), any()))
        .thenReturn(List.of(p1));
    when(batches.signedCount(TENANT, BUCKET, b.id())).thenReturn(0);
    service.cancel(TENANT, b.id(), 2, decider);
    verify(batches).setStatus(eq(TENANT), eq(BUCKET), eq(b.id()), eq("HUY"), eq(decider), any());
    verify(batches).touchProfiles(eq(TENANT), eq(BUCKET), eq(List.of(p1)), eq(decider), any());
    when(batches.signedCount(TENANT, BUCKET, b.id())).thenReturn(3);
    assertThat(service.cancel(TENANT, b.id(), 2, decider).signedCount()).isEqualTo(3);
    verify(batches)
        .setStatus(eq(TENANT), eq(BUCKET), eq(b.id()), eq("LOI_MOT_PHAN"), eq(decider), any());

    BatchRow done = batch(BatchDecisionService.DA_KY, decider, new byte[32], sign);
    when(batches.find(TENANT, done.id(), true)).thenReturn(Optional.of(done));
    assertCode(
        () -> service.cancel(TENANT, done.id(), 2, decider), "APPRAISAL-409-BATCH-COMPLETED");
    BatchRow gone = batch(BatchDecisionService.HUY, decider, new byte[32], null);
    when(batches.find(TENANT, gone.id(), true)).thenReturn(Optional.of(gone));
    when(batches.find(TENANT, gone.id(), false)).thenReturn(Optional.of(gone));
    assertThat(service.cancel(TENANT, gone.id(), 2, decider).batch().status()).isEqualTo("HUY");
  }

  @Test
  void taiLieuKyVaTheoDoi() {
    BatchRow b = batch(BatchDecisionService.NHAP, decider, new byte[32], null);
    when(batches.find(TENANT, b.id(), false)).thenReturn(Optional.of(b));
    when(batches.ratings(TENANT, BUCKET, b.id()))
        .thenReturn(
            List.of(
                new BatchRating(UUID.randomUUID(), p1, "HTTNV", null),
                new BatchRating(UUID.randomUUID(), p2, "HTNV", Instant.now())));
    var source = service.signSource(TENANT, b.id()).orElseThrow();
    assertThat(source.ready()).isTrue();
    assertThat(source.signer()).isEqualTo(decider);
    assertThat(source.documents())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.profileId()).isEqualTo(p1);
              assertThat(d.contentHash()).hasSize(32);
            });
    BatchRow gone = batch(BatchDecisionService.HUY, decider, new byte[32], null);
    when(batches.find(TENANT, gone.id(), false)).thenReturn(Optional.of(gone));
    assertThat(service.signSource(TENANT, gone.id()).orElseThrow().ready()).isFalse();
    assertThat(service.view(TENANT, b.id(), UUID.randomUUID())).isEmpty();
    assertThat(service.job(TENANT, b.id())).isPresent();
    when(batches.page(TENANT, decider, null, null, Set.of(), null, 11)).thenReturn(List.of(b));
    assertThat(service.page(TENANT, decider, null, null, Set.of(), null, 11))
        .extracting(View::batch)
        .containsExactly(b);
  }

  @Test
  void mucLienTruocLaDeXuatRoiThamDinh() {
    UUID x = UUID.randomUUID();
    ProfileRow both =
        row(
            p1,
            AppraisalStatus.CHO_QUYET_DINH,
            1,
            RatingLevel.HTNV,
            List.of(
                new CurrentRating(
                    x, LevelKind.THAM_DINH, RatingLevel.HTNV, appraiser, false, false, false),
                new CurrentRating(
                    x, LevelKind.DE_XUAT, RatingLevel.HTTNV, appraiser, false, false, false)));
    assertThat(BatchDecisionService.previous(both)).isEqualTo(RatingLevel.HTTNV);
    assertThat(
            BatchDecisionService.previous(
                row(p1, AppraisalStatus.CHO_QUYET_DINH, 1, null, List.of())))
        .isNull();
    List<Item> a =
        new ArrayList<>(
            List.of(item(p1, RatingLevel.HTNV, null, 1), item(p2, RatingLevel.HTTNV, null, 1)));
    List<Item> b = new ArrayList<>(a);
    Collections.reverse(b);
    assertThat(BatchDecisionService.profilesHash(a))
        .isEqualTo(BatchDecisionService.profilesHash(b));
  }

  // ---------------------------------------------------------------- hỗ trợ

  private void lock(
      UUID id, AppraisalStatus status, long version, RatingLevel auto, RatingLevel appraised) {
    List<CurrentRating> ratings =
        List.of(
            new CurrentRating(
                UUID.randomUUID(), LevelKind.THAM_DINH, appraised, appraiser, false, false, false));
    when(profiles.lock(TENANT, id))
        .thenReturn(Optional.of(row(id, status, version, auto, ratings)));
  }

  private ProfileRow row(
      UUID id,
      AppraisalStatus status,
      long version,
      RatingLevel auto,
      List<CurrentRating> ratings) {
    return new ProfileRow(
        id,
        period,
        BUCKET,
        UUID.randomUUID(),
        "DANG",
        status,
        appraiser,
        null,
        UUID.randomUUID(),
        auto,
        null,
        0,
        UUID.randomUUID(),
        version,
        ratings);
  }

  private BatchRow batch(String status, UUID who, byte[] hash, UUID sign) {
    return new BatchRow(
        UUID.randomUUID(),
        period,
        BUCKET,
        "DANG",
        "215-QĐ/ĐUK",
        LocalDate.parse("2027-01-12"),
        who,
        "Người quyết định",
        2,
        hash,
        sign,
        status,
        2,
        Instant.parse("2027-01-12T03:00:00Z"));
  }

  private Command command(boolean ack, Item... items) {
    return new Command(
        period, "DANG", "215-QĐ/ĐUK", LocalDate.parse("2027-01-12"), ack, List.of(items));
  }

  private static Item item(UUID id, RatingLevel level, String reason, Integer version) {
    return new Item(id, level, reason, version == null ? null : version.longValue());
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
    assertThatThrownBy(call).isInstanceOf(BusinessException.class).hasMessage(code);
  }

  /** Niêm phong giả: giữ nguyên byte. */
  static final class PlainSealer implements TextSealer {
    @Override
    public SealedText seal(TenantId tenant, String column, UUID rowId, String plain) {
      return plain == null ? null : new SealedText(plain.getBytes(StandardCharsets.UTF_8), rowId);
    }

    @Override
    public String open(TenantId tenant, String column, UUID rowId, SealedText sealed) {
      return sealed == null ? null : new String(sealed.cipher(), StandardCharsets.UTF_8);
    }
  }
}
