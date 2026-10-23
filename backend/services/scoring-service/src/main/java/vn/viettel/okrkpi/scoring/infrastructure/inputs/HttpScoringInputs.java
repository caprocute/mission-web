package vn.viettel.okrkpi.scoring.infrastructure.inputs;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.util.UriComponentsBuilder;
import vn.viettel.okrkpi.scoring.application.port.ScoringInputs;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Đọc đầu vào tính điểm của MOD-04 qua {@code listAppraisalScoringInputs} và {@code
 * listAppraisalWorkItems} của {@code appraisals.yaml} (CORE-04-06) bằng {@link ResilientClient}
 * (instance {@code appraisal-inputs}). Hồ sơ có hơn 200 việc lá được đọc tiếp theo {@code
 * workItemsNextCursor} trước khi trả trang. Tenant đi theo ngữ cảnh lời gọi nội bộ (client dịch
 * vụ).
 */
public class HttpScoringInputs implements ScoringInputs {

  static final String BASE = "/api/v1/appraisals/inputs";
  static final int WORK_ITEM_PAGE = 200;

  private final ResilientClient client;

  public HttpScoringInputs(ResilientClient client) {
    this.client = client;
  }

  @Override
  public Page page(UUID tenantId, UUID periodId, String cursor, int limit) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath(BASE)
            .queryParam("period", periodId)
            .queryParam("limit", limit);
    if (cursor != null) {
      uri.queryParam("cursor", cursor);
    }
    InputPage page = client.get(uri.build().toUriString(), InputPage.class);
    if (page == null || page.items() == null) {
      throw new IllegalStateException("Lõi trả trang đầu vào rỗng");
    }
    List<CoreScoringInput> items = new ArrayList<>(page.items().size());
    for (Input in : page.items()) {
      List<WorkItem> work = new ArrayList<>(in.workItems() == null ? List.of() : in.workItems());
      String next = in.workItemsNextCursor();
      while (next != null) {
        WorkItemPage more =
            client.get(
                UriComponentsBuilder.fromPath(BASE + "/" + in.profileId() + "/work-items")
                    .queryParam("limit", WORK_ITEM_PAGE)
                    .queryParam("cursor", next)
                    .build()
                    .toUriString(),
                WorkItemPage.class);
        if (more == null || more.items() == null) {
          throw new IllegalStateException("Lõi trả trang việc lá rỗng");
        }
        work.addAll(more.items());
        next = more.nextCursor();
      }
      items.add(map(in, work));
    }
    return new Page(items, page.nextCursor());
  }

  static CoreScoringInput map(Input in, List<WorkItem> work) {
    return new CoreScoringInput(
        in.profileId(),
        in.personId(),
        in.flow(),
        in.orgUnitId(),
        in.subjectGroupId(),
        Boolean.TRUE.equals(in.isHead()),
        Boolean.TRUE.equals(in.isLeadership()),
        in.policyPackVersionId(),
        in.generalCriteria() == null
            ? List.of()
            : in.generalCriteria().stream()
                .map(
                    c ->
                        new CoreScoringInput.Criterion(
                            c.criteriaCode(), Boolean.TRUE.equals(c.met())))
                .toList(),
        work.stream().map(HttpScoringInputs::workItem).toList(),
        in.incidents() == null ? 0 : in.incidents().size());
  }

  static CoreScoringInput.WorkItem workItem(WorkItem w) {
    return new CoreScoringInput.WorkItem(
        w.taskId(),
        w.parentTaskId(),
        w.standardScore(),
        w.difficulty(),
        w.taskGroup(),
        w.axisCode(),
        w.statusAtLock(),
        w.qualityLevel(),
        w.subjectiveLateWorkdays() == null ? 0 : w.subjectiveLateWorkdays(),
        w.objectiveLateWorkdays() == null ? 0 : w.objectiveLateWorkdays(),
        Boolean.TRUE.equals(w.exceeded()),
        Boolean.TRUE.equals(w.outOfScope()),
        Boolean.TRUE.equals(w.deferredByDecision()));
  }

  /** {@code AppraisalScoringInputPage}. */
  record InputPage(List<Input> items, String nextCursor) {}

  /** {@code AppraisalScoringInput} (trường cần dùng). */
  record Input(
      UUID profileId,
      UUID personId,
      String flow,
      UUID orgUnitId,
      UUID subjectGroupId,
      Boolean isHead,
      Boolean isLeadership,
      UUID policyPackVersionId,
      List<Criterion> generalCriteria,
      List<WorkItem> workItems,
      String workItemsNextCursor,
      List<Map<String, Object>> incidents) {}

  record Criterion(String criteriaCode, Boolean met) {}

  /** {@code WorkItem}. */
  record WorkItem(
      UUID taskId,
      UUID parentTaskId,
      BigDecimal standardScore,
      Integer difficulty,
      Map<String, Object> coefficientLevels,
      String taskGroup,
      String axisCode,
      BigDecimal componentScore,
      String statusAtLock,
      BigDecimal qualityLevel,
      Integer subjectiveLateWorkdays,
      Integer objectiveLateWorkdays,
      Boolean exceeded,
      Boolean outOfScope,
      Boolean deferredByDecision) {}

  /** {@code AppraisalWorkItemPage}. */
  record WorkItemPage(List<WorkItem> items, String nextCursor) {}
}
