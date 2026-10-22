package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.okr.application.OkrComposeService.ObjectiveDraft;
import vn.viettel.okrkpi.okr.application.OkrComposeService.Owner;
import vn.viettel.okrkpi.okr.application.OkrReviewService.HistoryEntry;
import vn.viettel.okrkpi.okr.application.port.OkrAiSuggestions;
import vn.viettel.okrkpi.okr.application.port.OkrAiSuggestions.Suggestion;
import vn.viettel.okrkpi.okr.application.port.OkrAiTraceRepository;
import vn.viettel.okrkpi.okr.application.port.OkrAiTraceRepository.AiTrace;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.KeyResultData;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị nội dung chèn từ gợi ý AI ở MOD-01 (CORE-01-13). */
class OkrAiContentTest {

  static final TenantId TENANT = OkrComposeServiceTest.TENANT;
  static final UUID ACTOR = OkrComposeServiceTest.ACTOR;
  static final UUID UNIT = OkrComposeServiceTest.UNIT;
  static final UUID PERIOD = OkrComposeServiceTest.PERIOD;
  static final UUID S1 = UUID.randomUUID();
  static final UUID S2 = UUID.randomUUID();

  private final OkrWriteRepository repo = mock(OkrWriteRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OkrAiTraceRepository traces = mock(OkrAiTraceRepository.class);
  private final OkrComposeService service = new OkrComposeService(repo, periods, traces);
  private final OkrAiSuggestions port = mock(OkrAiSuggestions.class);
  private final OkrAiProvenance provenance = new OkrAiProvenance(() -> port);

  static final Map<UUID, Suggestion> AI =
      Map.of(
          S1,
          new Suggestion(
              S1,
              "MUC_TIEU",
              "CHAP_NHAN",
              Map.of(
                  "objective",
                  Map.of(
                      "title",
                      "Nâng chất lượng sinh hoạt",
                      "keyResults",
                      List.of(
                          Map.of(
                              "title", "Số chi bộ đạt chuẩn", "unit", "chi bộ", "targetValue", 8),
                          Map.of("title", "Số chuyên đề được tổ chức", "unit", "chuyên đề"))))),
          S2,
          new Suggestion(S2, "SOAT_KR", null, Map.of("text", "Số chi bộ đạt chuẩn")));

  static KeyResultData aiKr(UUID id, String title, UUID suggestion) {
    return new KeyResultData(
        id,
        title,
        null,
        "chi bộ",
        null,
        BigDecimal.TEN,
        null,
        new BigDecimal("0.5"),
        null,
        OkrComposeServiceTest.VERIFIER,
        null,
        null,
        null,
        "AI_EDITED",
        suggestion,
        null,
        null);
  }

  static ObjectiveDraft draft(UUID suggestion, List<KeyResultData> krs) {
    return new ObjectiveDraft(
        "  Nâng chất lượng   sinh hoạt ", null, null, null, List.of(), null, suggestion, krs);
  }

  @BeforeEach
  void setUp() {
    when(periods.findById(eq(TENANT), eq(PeriodId.of(PERIOD))))
        .thenReturn(Optional.of(OkrComposeServiceTest.Q1));
    when(repo.existingBases(eq(TENANT), anyList())).thenReturn(List.of());
  }

  @SuppressWarnings("unchecked")
  @Test
  void taoMucTieuGhiDauVetTruongGiuNguyenVaDaSua() {
    ObjectiveDraft d =
        draft(
            S1,
            List.of(
                aiKr(null, "Số chi bộ đạt chuẩn", S1),
                OkrComposeServiceTest.kr(null, "KR tự soạn", "0.5")));
    UUID id = service.create(TENANT, ACTOR, new Owner(PERIOD, "TAP_THE", UNIT, null), d, 5, AI);
    ArgumentCaptor<List<AiTrace>> rows = ArgumentCaptor.forClass(List.class);
    verify(traces).record(eq(TENANT), eq(20271), rows.capture());
    assertThat(rows.getValue()).hasSize(2);
    AiTrace objective = rows.getValue().get(0);
    assertThat(objective.keyResultId()).isNull();
    assertThat(objective.keptFields()).containsExactly("title");
    assertThat(objective.contentSource()).isEqualTo("AI_ACCEPTED");
    AiTrace kr = rows.getValue().get(1);
    assertThat(kr.keyResultId()).isNotNull();
    assertThat(kr.keptFields()).containsExactly("title", "unit");
    assertThat(kr.editedFields()).containsExactly("targetValue");
    assertThat(kr.contentSource()).isEqualTo("AI_EDITED");
    verify(traces).refreshObjectiveAiAssisted(TENANT, 20271, id);
  }

  @Test
  void luuVaThemKrGhiDauVetVaDatLaiCoAi() {
    UUID id = UUID.randomUUID();
    UUID keep = UUID.randomUUID();
    when(repo.lockObjective(TENANT, id))
        .thenReturn(Optional.of(OkrComposeServiceTest.head(id, "NHAP", 4)));
    when(repo.keyResultIds(TENANT, 20271, id)).thenReturn(List.of(keep));
    when(repo.rewriteObjective(eq(TENANT), any(), any(), eq(ACTOR))).thenReturn(5L);
    service.update(
        TENANT, ACTOR, id, "\"4\"", draft(null, List.of(aiKr(keep, "Số chi bộ", S2))), 5, AI);
    verify(traces).record(eq(TENANT), eq(20271), anyList());

    when(repo.keyResultsOf(TENANT, 20271, id)).thenReturn(List.of());
    service.addKeyResult(TENANT, ACTOR, id, aiKr(null, "Khác hẳn", UUID.randomUUID()), AI);
    // Gợi ý chưa kiểm (không có trong bản đồ) thì không ghi dấu vết, chỉ đặt lại cờ.
    verify(traces, org.mockito.Mockito.times(2)).refreshObjectiveAiAssisted(TENANT, 20271, id);

    OkrComposeService plain = new OkrComposeService(repo, periods);
    plain.addKeyResult(TENANT, ACTOR, id, aiKr(null, "Số chi bộ", S2), AI);
    verify(traces, org.mockito.Mockito.times(1)).record(any(), any(Integer.class), anyList());
  }

  @Test
  void kiemGoiYTruocKhiLuu() {
    ObjectiveDraft d = draft(S1, List.of(aiKr(null, "KR", S2)));
    when(port.find(TENANT, ACTOR, S1, "Bearer t")).thenReturn(Optional.of(AI.get(S1)));
    when(port.find(TENANT, ACTOR, S2, "Bearer t")).thenReturn(Optional.of(AI.get(S2)));
    assertThat(provenance.verify(TENANT, ACTOR, "Bearer t", d)).containsOnlyKeys(S1, S2);

    when(port.find(TENANT, ACTOR, S2, "Bearer t")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> provenance.verify(TENANT, ACTOR, "Bearer t", d))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo("OKR-422-VALIDATION");
              assertThat(e.errors())
                  .extracting(ProblemFieldError::field)
                  .containsExactly("keyResults[0].aiSuggestionId");
            });
    when(port.find(TENANT, ACTOR, S2, "Bearer t"))
        .thenReturn(Optional.of(new Suggestion(S2, null, "BO_QUA", Map.of())));
    assertThatThrownBy(() -> provenance.verify(TENANT, ACTOR, "Bearer t", aiKr(null, "KR", S2)))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errorCode().code())
                    .isEqualTo("OKR-422-VALIDATION"));
    when(port.find(TENANT, ACTOR, S2, "Bearer t"))
        .thenThrow(new OkrAiSuggestions.Unavailable("x", null));
    assertThatThrownBy(() -> provenance.verify(TENANT, ACTOR, "Bearer t", aiKr(null, "KR", S2)))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errorCode().code())
                    .isEqualTo("OKR-502-SOURCE-UNAVAILABLE"));
  }

  @Test
  void noiDungTuSoanKhongGoiTroLy() {
    OkrAiProvenance down = new OkrAiProvenance(() -> null);
    KeyResultData human = OkrComposeServiceTest.kr(null, "KR", "1");
    assertThat(down.verify(TENANT, ACTOR, null, human)).isEmpty();
    assertThat(down.verify(TENANT, ACTOR, null, draft(null, List.of(human)))).isEmpty();
    assertThat(down.verify(TENANT, ACTOR, null, draft(null, null))).isEmpty();
    verify(port, never()).find(any(), any(), any(), any());
    assertThatThrownBy(() -> down.verify(TENANT, ACTOR, null, draft(S1, List.of())))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errorCode().code())
                    .isEqualTo("OKR-502-SOURCE-UNAVAILABLE"));
  }

  @Test
  void lichSuMoiLanLuuMotMocCoCoAi() {
    UUID objective = UUID.randomUUID();
    Instant at = Instant.parse("2027-01-05T02:00:00Z");
    List<HistoryEntry> entries =
        OkrReviewService.aiSaves(
            List.of(
                new AiTrace(
                    objective, null, S1, "AI_ACCEPTED", List.of("title"), List.of(), at, ACTOR, 1),
                new AiTrace(
                    objective,
                    UUID.randomUUID(),
                    S1,
                    "AI_EDITED",
                    List.of("title"),
                    List.of("targetValue", "khac"),
                    at,
                    ACTOR,
                    1),
                new AiTrace(
                    objective,
                    null,
                    S2,
                    "AI_EDITED",
                    List.of(),
                    List.of("title"),
                    at.plusSeconds(60),
                    ACTOR,
                    1)));
    assertThat(entries).hasSize(2);
    HistoryEntry first = entries.get(0);
    assertThat(first.action()).isEqualTo("LUU_NHAP");
    assertThat(first.aiAssisted()).isTrue();
    assertThat(first.comment())
        .isEqualTo(
            "Chèn từ gợi ý của trợ lý. Mục tiêu: giữ nguyên tên; đã sửa không có. Kết quả then"
                + " chốt: giữ nguyên tên; đã sửa chỉ tiêu, khac.");
    assertThat(entries.get(1).comment()).contains("giữ nguyên không có; đã sửa tên");
  }
}
