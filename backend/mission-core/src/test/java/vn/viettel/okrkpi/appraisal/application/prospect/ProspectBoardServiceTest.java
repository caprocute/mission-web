package vn.viettel.okrkpi.appraisal.application.prospect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.BoardView;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.EntryInput;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectBoardRepository;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectCouncil;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectEvents;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Phân nhóm triển vọng (CORE-04-12) trên kho giả: quy tắc lưu, trình, kết quả tập thể, ký. */
class ProspectBoardServiceTest {

  static final TenantId T = TenantId.of(UUID.randomUUID());
  static final UUID YEAR = UUID.randomUUID();
  static final UUID QUARTER = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID WEAK = UUID.randomUUID();
  static final UUID FLAGGED = UUID.randomUUID();
  static final UUID GOOD = UUID.randomUUID();
  static final UUID ME = UUID.randomUUID();
  static final String REASON = "Đồng chí chủ trì thành công đề án chuyển đổi số của đơn vị.";

  final Repo repo = new Repo();
  final PeriodApi periods = mock(PeriodApi.class);
  final ProspectEvents events = mock(ProspectEvents.class);
  final ProspectBoardService service;

  ProspectBoardServiceTest() {
    when(periods.findById(T, PeriodId.of(YEAR))).thenReturn(Optional.of(period(YEAR, 1, 12)));
    when(periods.findById(T, PeriodId.of(QUARTER)))
        .thenReturn(Optional.of(period(QUARTER, 10, 12)));
    repo.candidates.add(candidate(WEAK, "Nguyễn Văn A", "KHTNV", false));
    repo.candidates.add(candidate(FLAGGED, "Trần Văn B", "HTNV", true));
    repo.candidates.add(candidate(GOOD, "Lê Văn C", "HTTNV", false));
    repo.suggest(GOOD, "NHOM_2");
    service =
        new ProspectBoardService(
            repo, periods, new ProspectEligibility(null, null), new Plain(), events);
  }

  @Test
  void bangMacDinhTheoGoiYVaChiMoOKyNam() {
    BoardView v = service.view(T, YEAR, UNIT);
    assertThat(v.status()).isEqualTo("NHAP");
    assertThat(v.rowVersion()).isZero();
    assertThat(v.boardVersion()).isEqualTo(1);
    assertThat(v.entries())
        .extracting(e -> e.groupCode())
        .containsExactly("NHOM_3", "NHOM_3", "NHOM_2");
    assertThat(v.flow()).isEqualTo("DANG");
    assertThatThrownBy(() -> service.view(T, QUARTER, UNIT))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> code(e, ProspectBoardService.QUARTER));
    assertThatThrownBy(() -> service.view(T, UUID.randomUUID(), UNIT))
        .isInstanceOf(NoSuchElementException.class);
    repo.candidates.clear();
    assertThatThrownBy(() -> service.view(T, YEAR, UNIT))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void luuNhapKiemDuLieuVaQuyTacNhom() {
    assertRejected(List.of(in(UUID.randomUUID(), "NHOM_3", 1, null)), "COMMON-422-VALIDATION");
    assertRejected(
        List.of(in(WEAK, "NHOM_3", 1, null), in(WEAK, "NHOM_3", 2, null)), "COMMON-422-VALIDATION");
    assertRejected(List.of(in(WEAK, "NHOM_9", 1, null)), "COMMON-422-VALIDATION");
    assertRejected(List.of(in(WEAK, "NHOM_3", 0, null)), "COMMON-422-VALIDATION");
    assertRejected(
        List.of(in(WEAK, "NHOM_3", 1, null), in(GOOD, "NHOM_3", 1, null)), "COMMON-422-VALIDATION");
    assertRejected(List.of(in(WEAK, "NHOM_3", 1, "ngắn")), "COMMON-422-VALIDATION");
    assertRejected(
        List.of(new EntryInput(WEAK, "NHOM_3", 1, null, "x".repeat(201))), "COMMON-422-VALIDATION");
    assertThatThrownBy(() -> save(0, List.of(in(WEAK, "NHOM_1", 1, null))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              code(e, ProspectBoardService.NOT_ELIGIBLE);
              assertThat(e.detail())
                  .contains("Nguyễn Văn A", "không hoàn thành nhiệm vụ", "Nhóm 1");
            });
    assertThatThrownBy(() -> save(0, List.of(in(FLAGGED, "NHOM_2", 1, null))))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("bố trí lại", "Nhóm 2"));

    BoardView saved = save(0, List.of(in(GOOD, "NHOM_1", 1, REASON), in(WEAK, "NHOM_4", 1, "  ")));
    assertThat(saved.rowVersion()).isEqualTo(1);
    assertThat(saved.entries().get(2).reason()).isEqualTo(REASON);
    assertThat(saved.entries().get(0).reason()).isNull();
    UUID firstId = saved.entries().get(2).id();
    BoardView again = save(1, List.of(in(GOOD, "NHOM_1", 1, null)));
    assertThat(again.entries().get(2).id()).isEqualTo(firstId);
    assertThat(again.entries().get(2).reason()).isNull();
    assertThatThrownBy(() -> save(1, List.of(in(GOOD, "NHOM_1", 1, null))))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> code(e, ProspectBoardService.VERSION_CONFLICT));
  }

  @Test
  void trinhCanDuNhomDuLyDoVaPhatSuKien() {
    save(0, List.of(in(WEAK, "NHOM_3", 1, null), in(FLAGGED, "NHOM_4", 1, null)));
    assertThatThrownBy(() -> service.submit(T, YEAR, UNIT, 1, ME))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              code(e, "COMMON-422-VALIDATION");
              assertThat(e.detail()).contains("Lê Văn C");
            });
    save(1, all("NHOM_1", null));
    assertThatThrownBy(() -> service.submit(T, YEAR, UNIT, 2, ME))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> code(e, ProspectBoardService.REASON_REQUIRED));
    repo.entries.get(WEAK).groupCode = "NHOM_2";
    assertThatThrownBy(() -> service.submit(T, YEAR, UNIT, 2, ME))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> code(e, ProspectBoardService.NOT_ELIGIBLE));
    repo.entries.get(WEAK).groupCode = "NHOM_4";
    repo.entries.get(WEAK).rank = 2;
    assertThatThrownBy(() -> service.submit(T, YEAR, UNIT, 2, ME))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.detail()).contains("Thứ tự trong Nhóm 4 bị trùng"));
    save(2, all("NHOM_1", REASON));

    ProspectCouncil council = mock(ProspectCouncil.class);
    when(council.start(eq(T), any(), eq(YEAR), eq(UNIT), eq(1), eq(ME)))
        .thenReturn(Optional.of("wf-1"));
    BoardView submitted =
        new ProspectBoardSubmission(service, council).submit(T, YEAR, UNIT, 3, ME);
    assertThat(submitted.status()).isEqualTo("DA_TRINH");
    assertThat(repo.board.process).isEqualTo("wf-1");
    verify(events).boardStepCompleted(T, repo.board.id, "NHAP", "DA_TRINH", ME, 4);
    assertThatThrownBy(() -> save(4, all("NHOM_1", REASON)))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> code(e, ProspectBoardService.INVALID_STATUS));
  }

  @Test
  void khongThongQuaVeNhapThongQuaRoiKyXong() {
    save(0, all("NHOM_2", null));
    new ProspectBoardSubmission(service, null).submit(T, YEAR, UNIT, 1, ME);
    assertThatThrownBy(() -> service.councilApproved(T, YEAR, UNIT, null, ME))
        .isInstanceOf(NullPointerException.class);
    BoardView back = service.councilRejected(T, YEAR, UNIT, "Cần cân nhắc lại thứ tự.", ME);
    assertThat(back.status()).isEqualTo("NHAP");
    assertThat(back.boardVersion()).isEqualTo(2);
    assertThat(back.rejectionReason()).isEqualTo("Cần cân nhắc lại thứ tự.");
    assertThatThrownBy(() -> service.councilApproved(T, YEAR, UNIT, UUID.randomUUID(), ME))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> code(e, ProspectBoardService.INVALID_STATUS));

    service.submit(T, YEAR, UNIT, back.rowVersion(), ME);
    UUID sign = UUID.randomUUID();
    service.councilApproved(T, YEAR, UNIT, sign, ME);
    Instant at = Instant.parse("2030-01-20T03:00:00Z");
    assertThat(service.onSigned(T, UUID.randomUUID(), at)).isFalse();
    assertThat(service.onSigned(T, sign, at)).isTrue();
    assertThat(service.onSigned(T, sign, at)).isTrue();
    assertThat(repo.board.status).isEqualTo("DA_KY");
    verify(events)
        .boardStepCompleted(
            eq(T), eq(repo.board.id), eq("DA_TRINH"), eq("DA_KY"), eq(ME), any(Long.class));
    assertThatThrownBy(() -> service.councilRejected(T, YEAR, UNIT, null, ME))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> code(e, ProspectBoardService.INVALID_STATUS));
  }

  @Test
  void quyTacCelLayTuGoiQuyDinhKhiCo() {
    PolicyQueryApi policies = mock(PolicyQueryApi.class);
    UUID version = UUID.randomUUID();
    when(policies.content(T, version))
        .thenReturn(
            Optional.of(
                new PolicyQueryApi.PolicyContent(
                    version,
                    "v2",
                    "h",
                    List.of(
                        new PolicyQueryApi.RuleView(
                            "phan_nhom.phan_nhom_hop_le",
                            "nhom_chon != 'NHOM_1'",
                            "BOOLEAN",
                            null,
                            null,
                            400,
                            null,
                            false)),
                    List.of())));
    ProspectEligibility rules = new ProspectEligibility(null, policies);
    assertThat(rules.eligible(T, version, "NHOM_1", "HTXSNV", false)).isFalse();
    assertThat(rules.eligible(T, version, "NHOM_2", "KHTNV", true)).isTrue();
    assertThat(rules.eligible(T, UUID.randomUUID(), "NHOM_2", null, true)).isFalse();
    assertThat(rules.expression(T, null)).isEqualTo(ProspectEligibility.DEFAULT_EXPRESSION);
    assertThat(ProspectBoardService.label(null)).isEqualTo("nhóm");
    verify(policies, never()).content(T, null);
  }

  private void assertRejected(List<EntryInput> inputs, String code) {
    assertThatThrownBy(() -> save(0, inputs))
        .isInstanceOfSatisfying(BusinessException.class, e -> code(e, code));
  }

  private BoardView save(long version, List<EntryInput> inputs) {
    return service.save(T, YEAR, UNIT, version, inputs, ME);
  }

  private static List<EntryInput> all(String goodGroup, String reason) {
    return List.of(
        in(WEAK, "NHOM_4", 1, null),
        in(FLAGGED, "NHOM_4", 2, null),
        in(GOOD, goodGroup, 1, reason));
  }

  private static EntryInput in(UUID person, String group, int rank, String reason) {
    return new EntryInput(person, group, rank, reason, null);
  }

  private static void code(BusinessException e, String code) {
    assertThat(e.errorCode().code()).isEqualTo(code);
  }

  private static ProspectBoardRepository.Candidate candidate(
      UUID person, String name, String rating, boolean reassign) {
    return new ProspectBoardRepository.Candidate(
        person, name, rating, reassign, List.of(UUID.randomUUID()), "DANG", null);
  }

  private static PeriodSnapshot period(UUID id, int fromMonth, int toMonth) {
    return new PeriodSnapshot(
        PeriodId.of(id),
        T,
        "K",
        "Kỳ",
        LocalDate.of(2029, fromMonth, 1),
        LocalDate.of(2029, toMonth, 31),
        PeriodStatus.MO);
  }

  /** Niêm phong giả: giữ nguyên byte. */
  static final class Plain implements TextSealer {
    @Override
    public SealedText seal(TenantId tenant, String column, UUID rowId, String plain) {
      return plain == null ? null : new SealedText(plain.getBytes(StandardCharsets.UTF_8), rowId);
    }

    @Override
    public String open(TenantId tenant, String column, UUID rowId, SealedText sealed) {
      return sealed == null ? null : new String(sealed.cipher(), StandardCharsets.UTF_8);
    }
  }

  /** Kho giả một đơn vị. */
  static final class Repo implements ProspectBoardRepository {

    static final class Row {
      UUID id;
      String groupCode;
      String suggested;
      Integer rank;
      SealedText reason;
      String plan;
      Instant decidedAt;
    }

    static final class B {
      UUID id = UUID.randomUUID();
      String status = "NHAP";
      int boardVersion = 1;
      long rowVersion;
      SealedText rejection;
      UUID sign;
      UUID updatedBy;
      String process;
    }

    final List<Candidate> candidates = new ArrayList<>();
    final Map<UUID, Row> entries = new HashMap<>();
    B board;

    void suggest(UUID person, String group) {
      Row r = new Row();
      r.id = UUID.randomUUID();
      r.groupCode = group;
      r.suggested = group;
      entries.put(person, r);
    }

    private Board view() {
      return new Board(
          board.id,
          YEAR,
          20290,
          UNIT,
          board.status,
          board.boardVersion,
          board.rowVersion,
          board.rejection,
          board.sign,
          board.updatedBy);
    }

    @Override
    public Optional<Board> find(TenantId tenant, int bucket, UUID orgUnitId) {
      return board == null ? Optional.empty() : Optional.of(view());
    }

    @Override
    public Board lockOrCreate(
        TenantId tenant, UUID periodId, int bucket, UUID orgUnitId, UUID actor) {
      if (board == null) {
        board = new B();
      }
      return view();
    }

    @Override
    public List<Candidate> candidates(TenantId tenant, UUID periodId, int bucket, UUID orgUnitId) {
      return List.copyOf(candidates);
    }

    @Override
    public List<Entry> entries(TenantId tenant, int bucket, List<UUID> personIds) {
      List<Entry> out = new ArrayList<>();
      for (UUID p : personIds) {
        Row r = entries.get(p);
        if (r != null) {
          out.add(
              new Entry(r.id, p, r.groupCode, r.suggested, r.rank, r.reason, r.plan, r.decidedAt));
        }
      }
      return out;
    }

    @Override
    public void saveEntries(
        TenantId tenant, UUID periodId, int bucket, List<EntryWrite> rows, UUID actor) {
      for (EntryWrite w : rows) {
        Row r = entries.computeIfAbsent(w.personId(), k -> new Row());
        r.id = w.id();
        r.groupCode = w.groupCode();
        r.rank = w.rankInGroup();
        r.reason = w.reason();
        r.plan = w.trainingPlanRef();
      }
    }

    @Override
    public long touch(TenantId tenant, int bucket, UUID boardId, UUID actor) {
      return ++board.rowVersion;
    }

    @Override
    public long markSubmitted(TenantId tenant, int bucket, UUID boardId, UUID actor) {
      board.status = "DA_TRINH";
      return ++board.rowVersion;
    }

    @Override
    public void recordProcess(TenantId tenant, int bucket, UUID boardId, String processInstanceId) {
      board.process = processInstanceId;
    }

    @Override
    public long markRejected(
        TenantId tenant, int bucket, UUID boardId, SealedText reason, UUID actor) {
      board.status = "NHAP";
      board.boardVersion++;
      board.rejection = reason;
      return ++board.rowVersion;
    }

    @Override
    public long markApproved(
        TenantId tenant, int bucket, UUID boardId, UUID signRequestId, UUID actor) {
      board.sign = signRequestId;
      board.updatedBy = actor;
      return ++board.rowVersion;
    }

    @Override
    public Optional<Board> findBySignRequest(TenantId tenant, UUID signRequestId) {
      return board != null && signRequestId.equals(board.sign)
          ? Optional.of(view())
          : Optional.empty();
    }

    @Override
    public long markSigned(TenantId tenant, Board b, Instant signedAt) {
      board.status = "DA_KY";
      entries.values().forEach(r -> r.decidedAt = signedAt);
      return ++board.rowVersion;
    }
  }
}
