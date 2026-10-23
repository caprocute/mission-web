package vn.viettel.okrkpi.product.application.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.AiDecisionPlan;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.DraftInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.FileInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.SubmitOutcome;
import vn.viettel.okrkpi.product.application.evidence.port.AiSuggestionDecisions;
import vn.viettel.okrkpi.product.application.evidence.port.AiSuggestionDecisions.Decision;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileRow;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.StoredObject;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceStatus;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceVersion;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskSummary;

/** CORE-03-05 · BR-07-09: ô do AI điền khi lưu nháp, kế hoạch ghi quyết định và nộp có xác nhận. */
class EvidenceAiPrefillTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID LEAD = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final EvidenceActor MEMBER = new EvidenceActor(LEAD, Set.of("member"));
  static final Instant NOW = Instant.parse("2027-02-01T08:00:00Z");

  private EvidenceSubmissionRepository repo;
  private EvidenceLifecycleService lifecycle;
  private EvidenceEvents events;
  private EvidenceSubmissionService service;

  private final UUID taskId = UUID.randomUUID();
  private final UUID evidenceId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final UUID objectId = UUID.randomUUID();
  private final UUID suggestion = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(EvidenceSubmissionRepository.class);
    lifecycle = mock(EvidenceLifecycleService.class);
    events = mock(EvidenceEvents.class);
    TaskApi tasks = mock(TaskApi.class);
    service =
        new EvidenceSubmissionService(
            repo,
            lifecycle,
            mock(PeriodApi.class),
            tasks,
            events,
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(tasks.find(TENANT, taskId))
        .thenReturn(
            Optional.of(
                new TaskSummary(
                    taskId,
                    PERIOD,
                    20271,
                    "NV-1",
                    "Nhiệm vụ",
                    "DANG_THUC_HIEN",
                    false,
                    false,
                    UUID.randomUUID(),
                    null,
                    LEAD,
                    LocalDate.of(2027, 3, 31),
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    1)));
    when(repo.findEvidence(TENANT, evidenceId))
        .thenReturn(
            Optional.of(
                new EvidenceView(
                    evidenceId,
                    PERIOD,
                    20271,
                    UUID.randomUUID(),
                    taskId,
                    "Báo cáo",
                    "TAI_LEN",
                    null,
                    1,
                    "NHAP",
                    false,
                    null,
                    null,
                    1)));
  }

  private static byte[] sha(int b) {
    byte[] s = new byte[32];
    Arrays.fill(s, (byte) b);
    return s;
  }

  private VersionView version(
      String status, boolean e2ee, boolean aiPrefill, UUID suggestionId, Instant confirmedAt) {
    return new VersionView(
        versionId,
        evidenceId,
        taskId,
        PERIOD,
        20271,
        1,
        status,
        "OCR_DI_DONG",
        null,
        "SACH",
        false,
        e2ee,
        aiPrefill,
        suggestionId,
        confirmedAt,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        3);
  }

  private void current(VersionView v) {
    when(repo.findVersion(TENANT, 20271, evidenceId, 1)).thenReturn(Optional.of(v));
  }

  private DraftInput aiDraft(boolean aiPrefill, UUID suggestionId, List<String> edited) {
    return new DraftInput(
        "OCR_DI_DONG",
        null,
        List.of(new FileInput(objectId, null, null)),
        aiPrefill,
        suggestionId,
        "47-BC/BTCĐUK",
        LocalDate.of(2027, 1, 16),
        "Báo cáo sơ kết",
        null,
        edited);
  }

  @Test
  void luuNhapChanOAiTrenTepC3VaGhiOSua() {
    current(version("NHAP", false, false, null, null));
    when(repo.storedObjects(any(), any()))
        .thenReturn(
            Map.of(
                objectId,
                new StoredObject(objectId, 1, sha(1), 10L, "application/pdf", true, "SACH", NOW)));
    BusinessException e3 =
        (BusinessException)
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    service.updateDraft(
                        TENANT, evidenceId, 1, 3, aiDraft(true, suggestion, List.of()), MEMBER));
    assertThat(e3.errorCode().code()).isEqualTo("COMMON-422-VALIDATION");
    assertThat(e3.errors().get(0).field()).isEqualTo("aiPrefill");
    verify(lifecycle, never()).saveDraft(any(), any(), anyLong(), any(), any());

    when(repo.storedObjects(any(), any()))
        .thenReturn(
            Map.of(
                objectId,
                new StoredObject(objectId, 1, sha(1), 10L, "application/pdf", false, "SACH", NOW)));
    service.updateDraft(
        TENANT, evidenceId, 1, 3, aiDraft(true, suggestion, List.of("taskId", "docDate")), MEMBER);
    ArgumentCaptor<EvidenceSubmissionRepository.DraftDetails> details =
        ArgumentCaptor.forClass(EvidenceSubmissionRepository.DraftDetails.class);
    verify(repo).writeDraftDetails(eq(TENANT), eq(20271), eq(versionId), details.capture());
    assertThat(details.getValue().aiFieldsEdited()).containsExactly("docDate", "taskId");
    assertThat(details.getValue().aiSuggestionId()).isEqualTo(suggestion);

    // Bỏ ô AI thì không giữ mã gợi ý.
    service.updateDraft(TENANT, evidenceId, 1, 3, aiDraft(false, suggestion, null), MEMBER);
    verify(repo, org.mockito.Mockito.times(2))
        .writeDraftDetails(eq(TENANT), eq(20271), eq(versionId), details.capture());
    assertThat(details.getValue().aiSuggestionId()).isNull();
    assertThat(details.getValue().aiFieldsEdited()).isEmpty();
  }

  @Test
  void keHoachGhiQuyetDinhChiKhiCoOAiChuaXacNhanVaKhongPhaiC3() {
    assertThat(service.aiDecisionPlan(TENANT, evidenceId, 1, false)).isEmpty();

    current(version("NHAP", false, true, suggestion, null));
    when(repo.aiFieldsEdited(TENANT, 20271, versionId)).thenReturn(List.of("docSummary"));
    assertThat(service.aiDecisionPlan(TENANT, evidenceId, 1, true))
        .contains(new AiDecisionPlan(versionId, suggestion, List.of("docSummary")));

    current(version("NHAP", true, true, suggestion, null));
    assertThat(service.aiDecisionPlan(TENANT, evidenceId, 1, true)).isEmpty();
    current(version("NHAP", false, true, null, null));
    assertThat(service.aiDecisionPlan(TENANT, evidenceId, 1, true)).isEmpty();
    current(version("NHAP", false, true, suggestion, NOW));
    assertThat(service.aiDecisionPlan(TENANT, evidenceId, 1, true)).isEmpty();
    current(version("DA_NOP", false, true, suggestion, null));
    assertThat(service.aiDecisionPlan(TENANT, evidenceId, 1, true)).isEmpty();
    current(version("NHAP", false, false, null, null));
    assertThat(service.aiDecisionPlan(TENANT, evidenceId, 1, true)).isEmpty();
    assertThat(service.aiDecisionPlan(TENANT, UUID.randomUUID(), 1, true)).isEmpty();
  }

  @Test
  void nopCoXacNhanGhiSoCaiOGiuOSua() {
    current(version("NHAP", false, true, suggestion, null));
    when(repo.aiFieldsEdited(TENANT, 20271, versionId)).thenReturn(List.of("taskId"));
    FileRow ok = new FileRow(objectId, 1, 1, sha(1), "SACH", null, null);
    when(repo.filesOf(TENANT, 20271, versionId))
        .thenReturn(
            List.of(
                new FileView(
                    ok,
                    new StoredObject(
                        objectId, 1, sha(1), 10L, "application/pdf", false, "SACH", NOW))));
    EvidenceVersion submitted =
        new EvidenceVersion(
            versionId,
            evidenceId,
            UUID.randomUUID(),
            taskId,
            1,
            EvidenceStatus.DA_NOP,
            objectId,
            sha(1),
            "SACH",
            true,
            NOW,
            LEAD,
            NOW,
            null,
            null,
            null,
            4);
    when(lifecycle.submitVersion(eq(TENANT), eq(versionId), anyLong(), any(), anyBoolean()))
        .thenReturn(
            new EvidenceLifecycleService.Result(submitted, 4, Optional.empty(), Optional.empty()));
    service.submit(TENANT, evidenceId, 1, 3, true, true, MEMBER);
    ArgumentCaptor<EvidenceEvents.AiFieldsConfirmed> audit =
        ArgumentCaptor.forClass(EvidenceEvents.AiFieldsConfirmed.class);
    verify(events).aiFieldsConfirmed(audit.capture());
    assertThat(audit.getValue().kept()).containsExactly("docNumber", "docDate", "docSummary");
    assertThat(audit.getValue().edited()).containsExactly("taskId");
    assertThat(audit.getValue().aiSuggestionId()).isEqualTo(suggestion);
    assertThat(audit.getValue().actorId()).isEqualTo(LEAD);
    assertThat(audit.getValue().aggregateVersion()).isEqualTo(4);

    // Phiên bản không có ô AI: không ghi sổ cái xác nhận.
    current(version("NHAP", false, false, null, null));
    service.submit(TENANT, evidenceId, 1, 3, true, true, MEMBER);
    verify(events).aiFieldsConfirmed(any());
  }

  // ---------------------------------------------------------------- nộp qua SVC-14

  record Call(UUID suggestion, Decision decision, String target, String auth, String key) {}

  @Test
  void ghiQuyetDinhTruocKhiNopVaLoiSvc14KhongNop() {
    EvidenceSubmissionService submissions = mock(EvidenceSubmissionService.class);
    List<Call> calls = new java.util.ArrayList<>();
    RuntimeException[] fail = {null};
    AiSuggestionDecisions decisions =
        (t, s, d, target, auth, key) -> {
          calls.add(new Call(s, d, target, auth, key));
          if (fail[0] != null) {
            throw fail[0];
          }
        };
    EvidenceAiConfirmationService confirm =
        new EvidenceAiConfirmationService(submissions, decisions);
    SubmitOutcome outcome = new SubmitOutcome(null, null, null);
    when(submissions.submit(any(), any(), anyInt(), anyLong(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(outcome);

    when(submissions.aiDecisionPlan(TENANT, evidenceId, 1, true))
        .thenReturn(Optional.of(new AiDecisionPlan(versionId, suggestion, List.of())));
    assertThat(confirm.submit(TENANT, evidenceId, 1, 3, true, true, MEMBER, "Bearer t"))
        .isSameAs(outcome);
    assertThat(calls)
        .containsExactly(
            new Call(
                suggestion,
                Decision.CHAP_NHAN,
                "product.evidence_version:" + versionId,
                "Bearer t",
                "xac-nhan-o-ai-" + versionId));

    when(submissions.aiDecisionPlan(TENANT, evidenceId, 1, true))
        .thenReturn(Optional.of(new AiDecisionPlan(versionId, suggestion, List.of("docDate"))));
    confirm.submit(TENANT, evidenceId, 1, 3, true, true, MEMBER, "Bearer t");
    assertThat(calls.get(1).decision()).isEqualTo(Decision.SUA);
    verify(submissions, org.mockito.Mockito.times(2))
        .submit(TENANT, evidenceId, 1, 3, true, true, MEMBER);

    // SVC-14 quá thời hạn: lỗi thử lại được, không nộp.
    fail[0] =
        new DependencyUnavailableException(
            "ai-gateway",
            DependencyUnavailableException.Reason.TIMEOUT,
            Duration.ofSeconds(5),
            null);
    assertThatThrownBy(
            () -> confirm.submit(TENANT, evidenceId, 1, 4, true, true, MEMBER, "Bearer t"))
        .isInstanceOf(DependencyUnavailableException.class);
    // Gợi ý không thuộc người nộp: chặn như ô AI chưa xác nhận.
    fail[0] = new AiSuggestionDecisions.SuggestionNotFoundException("404");
    BusinessException nf =
        (BusinessException)
            org.assertj.core.api.Assertions.catchThrowable(
                () -> confirm.submit(TENANT, evidenceId, 1, 4, true, true, MEMBER, "Bearer t"));
    assertThat(nf.errorCode().code()).isEqualTo("PRODUCT-422-AI-NOT-CONFIRMED");
    verify(submissions, never()).submit(TENANT, evidenceId, 1, 4, true, true, MEMBER);

    // Không có kế hoạch (C3, không có ô AI, chưa xác nhận): không gọi SVC-14.
    calls.clear();
    when(submissions.aiDecisionPlan(any(), any(), anyInt(), anyBoolean()))
        .thenReturn(Optional.empty());
    confirm.submit(TENANT, evidenceId, 1, 5, true, true, MEMBER, "Bearer t");
    assertThat(calls).isEmpty();

    // Chưa cấu hình SVC-14: bỏ qua ghi quyết định, vẫn nộp.
    when(submissions.aiDecisionPlan(TENANT, evidenceId, 1, true))
        .thenReturn(Optional.of(new AiDecisionPlan(versionId, suggestion, List.of())));
    new EvidenceAiConfirmationService(submissions, null)
        .submit(TENANT, evidenceId, 1, 6, true, true, MEMBER, null);
    verify(submissions).submit(TENANT, evidenceId, 1, 6, true, true, MEMBER);
  }
}
