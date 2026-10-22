package vn.viettel.okrkpi.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Quy tắc thuần của tầng domain (DV-06-02). */
class DomainRulesTest {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-000000000001");
  private static final UUID UNIT = UUID.fromString("0192f1a2-3333-7ccc-8ddd-000000000a01");
  private static final UUID ME = UUID.fromString("0192f1a2-2222-7aaa-8bbb-000000000b04");
  private static final Instant NOW = Instant.parse("2027-01-07T03:00:00Z");

  @Test
  void businessKeyDocVaGhiDungKhuon() {
    UUID id = UUID.randomUUID();
    BusinessKey key = BusinessKey.parse("OKR_OBJECTIVE:" + id).orElseThrow();
    assertThat(key.type()).isEqualTo("OKR_OBJECTIVE");
    assertThat(key.id()).isEqualTo(id);
    assertThat(key.value()).isEqualTo("OKR_OBJECTIVE:" + id);
    assertThat(key).hasToString("OKR_OBJECTIVE:" + id);

    assertThat(BusinessKey.parse(null)).isEmpty();
    assertThat(BusinessKey.parse("OKR_OBJECTIVE")).isEmpty();
    assertThat(BusinessKey.parse(":" + id)).isEmpty();
    assertThat(BusinessKey.parse("OKR:")).isEmpty();
    assertThat(BusinessKey.parse("okr:" + id)).isEmpty();
    assertThat(BusinessKey.parse("OKR:khong-phai-uuid")).isEmpty();
    assertThatThrownBy(() -> new BusinessKey("sai kieu", id))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nhomUngVienTheoKhuonTenantVaiTroDonVi() {
    CandidateGroup group = CandidateGroup.parse(TENANT + ":leader:" + UNIT).orElseThrow();
    assertThat(group.role()).isEqualTo("leader");
    assertThat(group.groupId()).isEqualTo(TENANT + ":leader:" + UNIT);

    assertThat(CandidateGroup.parse(null)).isEmpty();
    assertThat(CandidateGroup.parse("leader")).isEmpty();
    assertThat(CandidateGroup.parse(TENANT + ":Leader:" + UNIT)).isEmpty();
    assertThat(CandidateGroup.parse("x:leader:" + UNIT)).isEmpty();
    assertThatThrownBy(() -> new CandidateGroup(TENANT, "LEADER", UNIT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nguoiDuocGiaoHoacUngVienMoiDuocXuLy() {
    Approver leader = new Approver(TENANT, ME, Set.of("leader"), UNIT);
    CandidateGroup group = new CandidateGroup(TENANT, "leader", UNIT);
    BusinessKey key = new BusinessKey("PLAN", UUID.randomUUID());

    OpenTask assignedToMe = new OpenTask("t1", TENANT, ME, List.of(), 1, key, null);
    OpenTask assignedToOther =
        new OpenTask("t2", TENANT, UUID.randomUUID(), List.of(group), 1, key, null);
    OpenTask candidate = new OpenTask("t3", TENANT, null, List.of(group), 1, key, null);
    OpenTask otherUnit =
        new OpenTask(
            "t4",
            TENANT,
            null,
            List.of(new CandidateGroup(TENANT, "leader", UUID.randomUUID())),
            1,
            key,
            null);
    OpenTask otherTenant = new OpenTask("t5", UUID.randomUUID(), ME, List.of(), 1, key, null);

    assertThat(assignedToMe.actionableBy(leader)).isTrue();
    assertThat(assignedToMe.needsClaim()).isFalse();
    assertThat(assignedToOther.actionableBy(leader)).isFalse();
    assertThat(candidate.actionableBy(leader)).isTrue();
    assertThat(candidate.needsClaim()).isTrue();
    assertThat(otherUnit.actionableBy(leader)).isFalse();
    assertThat(otherTenant.actionableBy(leader)).isFalse();
    assertThat(candidate.actionableBy(new Approver(TENANT, ME, Set.of("member"), UNIT))).isFalse();
    assertThat(new Approver(TENANT, ME, null, null).roles()).isEmpty();
    assertThat(new OpenTask("t6", TENANT, ME, null, 1, key, null).candidateGroups()).isEmpty();
  }

  @Test
  void traLaiPhaiCoLyDoVaYKienKhongQua2000KyTu() {
    assertThat(CompletionRules.checkComment(Decision.DUYET, null)).isEmpty();
    assertThat(CompletionRules.checkComment(Decision.DUYET, "Đồng ý.")).isEmpty();
    assertThat(CompletionRules.checkComment(Decision.TRA_LAI, "  "))
        .contains(CompletionRules.Violation.COMMENT_REQUIRED);
    assertThat(CompletionRules.checkComment(Decision.TRA_LAI, null))
        .contains(CompletionRules.Violation.COMMENT_REQUIRED);
    assertThat(CompletionRules.checkComment(Decision.DUYET, "a".repeat(2001)))
        .contains(CompletionRules.Violation.COMMENT_TOO_LONG);
    assertThat(CompletionRules.checkComment(Decision.TRA_LAI, "a".repeat(2000))).isEmpty();
  }

  @Test
  void dongChieuKiemTieuDePhienBanVaQuaHan() {
    BusinessKey key = new BusinessKey("PLAN", UUID.randomUUID());
    TaskProjection p =
        new TaskProjection(
            "t1",
            TENANT,
            ME,
            null,
            null,
            key,
            "duyet-ke-hoach-quy",
            "Duyệt",
            null,
            NOW.minusSeconds(1),
            NOW.minusSeconds(60),
            1);
    assertThat(p.overdueAt(NOW)).isTrue();
    TaskProjection noDue =
        new TaskProjection("t2", TENANT, ME, null, null, key, null, "Duyệt", null, null, NOW, 1);
    assertThat(noDue.overdueAt(NOW)).isFalse();
    assertThatThrownBy(
            () ->
                new TaskProjection(
                    "t3", TENANT, ME, null, null, key, null, " ", null, null, NOW, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TaskProjection(
                    "t3", TENANT, ME, null, null, key, null, "a".repeat(256), null, null, NOW, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TaskProjection(
                    "t3", TENANT, ME, null, null, key, null, "Duyệt", null, null, NOW, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(InboxPosition.after(p)).isEqualTo(new InboxPosition(p.dueAt(), "t1"));
  }

  @Test
  void boLocHopViecVaSoDem() {
    Approver me = new Approver(TENANT, ME, Set.of("leader"), UNIT);
    UUID period = UUID.randomUUID();
    assertThat(new InboxFilter(me, " ", null).businessType()).isNull();
    assertThat(new InboxFilter(me, null, null).cursorScope())
        .isEqualTo("workflow.inbox|" + ME + "||");
    assertThat(new InboxFilter(me, "PLAN", period).cursorScope())
        .isEqualTo("workflow.inbox|" + ME + "|PLAN|" + period);
    assertThat(new InboxCounts(3, 1, 1).total()).isEqualTo(3);
    assertThatThrownBy(() -> new InboxCounts(-1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InboxCounts(0, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InboxCounts(0, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lichSuSapTheoThoiGianVaLietKeNguoiThamGia() {
    BusinessKey key = new BusinessKey("OKR_OBJECTIVE", UUID.randomUUID());
    UUID author = UUID.randomUUID();
    ProcessHistory history =
        new ProcessHistory(
            key,
            "duyet-okr",
            2,
            true,
            author,
            List.of(
                new ProcessHistory.Entry(NOW, HistoryAction.KET_THUC, null, null, null),
                new ProcessHistory.Entry(
                    NOW.minusSeconds(60), HistoryAction.DUYET, ME, "Duyệt", "Đồng ý."),
                new ProcessHistory.Entry(
                    NOW.minusSeconds(120), HistoryAction.TAO_TIEN_TRINH, author, null, null),
                new ProcessHistory.Entry(
                    NOW.minusSeconds(30), HistoryAction.DUYET, ME, "Duyệt", null)));
    assertThat(history.entries())
        .extracting(ProcessHistory.Entry::action)
        .containsExactly(
            HistoryAction.TAO_TIEN_TRINH,
            HistoryAction.DUYET,
            HistoryAction.DUYET,
            HistoryAction.KET_THUC);
    assertThat(history.participants()).containsExactly(author, ME);
    assertThat(new ProcessHistory(key, null, null, false, null, null).entries()).isEmpty();
    assertThat(HistoryAction.of(Decision.DUYET)).isEqualTo(HistoryAction.DUYET);
    assertThat(HistoryAction.of(Decision.TRA_LAI)).isEqualTo(HistoryAction.TRA_LAI);
  }

  @Test
  void tacVuLoTinhTienDoVaBaoThatBaiKhiTreoQuaLau() {
    BatchJob job = BatchJob.accepted(UUID.randomUUID(), TENANT, ME, Decision.DUYET, 4, NOW);
    assertThat(job.status()).isEqualTo(BatchJob.Status.CHO);
    assertThat(job.progress()).isZero();
    assertThat(job.effectiveStatus(NOW.plus(Duration.ofMinutes(5)))).isEqualTo(BatchJob.Status.CHO);
    assertThat(job.effectiveStatus(NOW.plus(Duration.ofMinutes(11))))
        .isEqualTo(BatchJob.Status.THAT_BAI);

    BatchJob done =
        new BatchJob(
            job.id(),
            TENANT,
            ME,
            Decision.DUYET,
            BatchJob.Status.HOAN_TAT,
            4,
            3,
            1,
            List.of(
                new BatchJob.ItemResult("a", 200, null),
                new BatchJob.ItemResult("b", 409, "WORKFLOW-409-VERSION-CONFLICT")),
            NOW);
    assertThat(done.progress()).isEqualTo(100);
    assertThat(done.effectiveStatus(NOW.plus(Duration.ofDays(1))))
        .isEqualTo(BatchJob.Status.HOAN_TAT);
    assertThat(done.results().get(0).succeeded()).isTrue();
    assertThat(done.results().get(1).succeeded()).isFalse();
    assertThat(new BatchJob.ItemResult("c", 199, null).succeeded()).isFalse();
    assertThat(BatchJob.Status.THAT_BAI.terminal()).isTrue();
    assertThat(BatchJob.Status.DANG_CHAY.terminal()).isFalse();
    assertThatThrownBy(
            () -> BatchJob.accepted(UUID.randomUUID(), TENANT, ME, Decision.DUYET, 0, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> BatchJob.accepted(UUID.randomUUID(), TENANT, ME, Decision.DUYET, 201, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new BatchJob(
                    job.id(), TENANT, ME, Decision.DUYET, BatchJob.Status.CHO, 1, 0, 0, null, NOW)
                .results())
        .isEmpty();
  }
}
