package vn.viettel.okrkpi.okr.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.okr.model.OkrCheckLevel;
import vn.viettel.okrkpi.contract.okr.model.OkrPeriodInfo;
import vn.viettel.okrkpi.contract.okr.model.OkrPersonOption;
import vn.viettel.okrkpi.contract.okr.model.OkrReviewCheck;
import vn.viettel.okrkpi.contract.okr.model.OkrReviewKr;
import vn.viettel.okrkpi.contract.okr.model.OkrReviewObjective;
import vn.viettel.okrkpi.contract.okr.model.OkrReviewQueue;
import vn.viettel.okrkpi.contract.okr.model.OkrReviewQueueWorkflow;
import vn.viettel.okrkpi.contract.okr.model.OkrReviewSet;
import vn.viettel.okrkpi.contract.okr.model.Ref;
import vn.viettel.okrkpi.okr.application.OkrCheckService;
import vn.viettel.okrkpi.okr.application.OkrReadService;
import vn.viettel.okrkpi.okr.application.OkrReadService.KeyResultView;
import vn.viettel.okrkpi.okr.application.OkrReadService.ObjectiveView;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Finding;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveFilter;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveRow;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Hàng chờ phê duyệt OKR của SCR-0203 ({@code listOkrReviews}, CORE-01-05). Bộ trình duyệt là các
 * mục tiêu {@code CHO_DUYET} của đơn vị trực thuộc trực tiếp đơn vị người gọi trên các cây tổ chức,
 * khớp quy tắc DMN {@code chon-cap-duyet} của DV-06-03 cho OKR ("leader của đơn vị cấp trên trực
 * tiếp"). Đây là nguồn tạm (suy luận): khi hợp đồng có thao tác đọc hộp việc cho dịch vụ gọi dịch
 * vụ, nguồn sẽ đổi sang {@code flowable.wf_task_projection} để có cả duyệt thay theo uỷ quyền và
 * hạn duyệt. Kết quả kiểm tra tính lại khi đọc, không lưu.
 */
@RestController
@RequestMapping("/api/v1/okr")
public class OkrReviewController {

  static final String WORKFLOW = "duyet-okr";
  static final int MAX_OBJECTIVES = 1000;

  private final OkrReadService reads;
  private final OkrCheckService checks;
  private final ObjectProvider<OrgDirectoryApi> org;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OkrCaller> callers;

  public OkrReviewController(
      OkrReadService reads,
      OkrCheckService checks,
      ObjectProvider<OrgDirectoryApi> org,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OkrCaller> callers) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.checks = Objects.requireNonNull(checks, "checks");
    this.org = Objects.requireNonNull(org, "org");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @GetMapping("/reviews")
  @RequiresPermission(operationId = "listOkrReviews", module = "okr")
  public OkrReviewQueue listOkrReviews(
      @RequestParam(value = "period", required = false) UUID period) {
    TenantId tenant = TenantContext.require();
    UUID viewer =
        caller(OkrCaller::currentUserId, OpaInput.Subject::userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập"));
    PeriodSnapshot p;
    try {
      p = reads.period(tenant, period);
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    OkrPeriodInfo info = OkrReadController.periodInfo(reads.periodView(p));
    List<OkrReviewSet> sets = new ArrayList<>();
    Optional<UUID> unit = caller(OkrCaller::currentUnitId, OpaInput.Subject::unitId);
    List<UUID> children = unit.map(u -> children(tenant, u)).orElse(List.of());
    if (!children.isEmpty()) {
      List<ObjectiveView> pending =
          reads.objectives(
              tenant,
              new ObjectiveFilter(
                  p.id().value(),
                  OkrReadService.buckets(p),
                  children,
                  null,
                  null,
                  List.of("CHO_DUYET"),
                  null),
              null,
              null,
              MAX_OBJECTIVES);
      Map<UUID, List<ObjectiveView>> byUnit = new LinkedHashMap<>();
      for (ObjectiveView o : pending) {
        byUnit.computeIfAbsent(o.row().ownerUnitId(), k -> new ArrayList<>()).add(o);
      }
      byUnit.forEach((u, list) -> sets.add(set(tenant, viewer, p, u, list)));
    }
    return new OkrReviewQueue(
        info,
        List.of(info),
        new OkrPersonOption(viewer, ""),
        new OkrReviewQueueWorkflow(WORKFLOW, 1),
        sets);
  }

  private OkrReviewSet set(
      TenantId tenant, UUID viewer, PeriodSnapshot p, UUID unit, List<ObjectiveView> list) {
    int blocking = 0;
    int warnings = 0;
    int krCount = 0;
    boolean author = false;
    Instant submittedAt = Instant.EPOCH;
    ObjectiveRow latest = list.getFirst().row();
    List<OkrReviewObjective> objectives = new ArrayList<>();
    for (ObjectiveView o : list) {
      List<Finding> findings = checks.check(tenant, o);
      blocking += (int) count(findings, OkrRuleChecker.LOI_CHAN);
      warnings += (int) count(findings, OkrRuleChecker.CANH_BAO);
      krCount += o.keyResults().size();
      author |= viewer.equals(o.row().createdBy());
      if (o.row().updatedAt() != null && o.row().updatedAt().isAfter(submittedAt)) {
        submittedAt = o.row().updatedAt();
        latest = o.row();
      }
      objectives.add(objective(tenant, o, findings));
    }
    int maxVersion = list.stream().mapToInt(o -> o.row().versionNo()).max().orElse(1);
    return new OkrReviewSet(
        unit + ":" + p.id().value(),
        new Ref(unit, ""),
        p.id().value(),
        submittedAt.atOffset(ZoneOffset.UTC),
        new Ref(latest.createdBy(), ""),
        Math.max(1, maxVersion),
        list.size(),
        krCount,
        summary(blocking, warnings),
        blocking,
        warnings,
        author,
        objectives);
  }

  private OkrReviewObjective objective(TenantId tenant, ObjectiveView o, List<Finding> findings) {
    String parentCode =
        o.row().parentObjectiveId() == null
            ? null
            : reads
                .objectiveRow(tenant, o.row().parentObjectiveId())
                .map(ObjectiveRow::code)
                .orElse(null);
    List<OkrReviewKr> krs = new ArrayList<>();
    for (KeyResultView k : o.keyResults()) {
      List<Finding> own =
          findings.stream().filter(f -> k.row().id().equals(f.keyResultId())).toList();
      krs.add(
          new OkrReviewKr(
              k.row().id(),
              k.row().code(),
              k.row().title(),
              target(k.row().targetValue(), k.row().unit()),
              k.row().weight(),
              null,
              badge(own)));
    }
    return new OkrReviewObjective(
            o.row().id(),
            o.row().code(),
            o.row().title(),
            parentCode,
            Math.max(1, o.row().versionNo()),
            (int) o.row().rowVersion(),
            badge(findings.stream().filter(f -> f.keyResultId() == null).toList()),
            krs)
        .checks(OkrReadController.checks(findings));
  }

  /** Nhãn một dòng: lỗi chặn trước, rồi cảnh báo, không có thì nhãn đạt đầu tiên. */
  static OkrReviewCheck badge(List<Finding> findings) {
    for (String level : List.of(OkrRuleChecker.LOI_CHAN, OkrRuleChecker.CANH_BAO)) {
      Optional<Finding> f = findings.stream().filter(x -> level.equals(x.level())).findFirst();
      if (f.isPresent()) {
        return new OkrReviewCheck(OkrCheckLevel.fromValue(level), f.get().message());
      }
    }
    return new OkrReviewCheck(
        OkrCheckLevel.DAT,
        findings.stream().findFirst().map(Finding::message).orElse("Đạt yêu cầu"));
  }

  static OkrReviewCheck summary(int blocking, int warnings) {
    if (blocking > 0) {
      return new OkrReviewCheck(OkrCheckLevel.LOI_CHAN, blocking + " lỗi cần sửa");
    }
    if (warnings > 0) {
      return new OkrReviewCheck(OkrCheckLevel.CANH_BAO, warnings + " cảnh báo");
    }
    return new OkrReviewCheck(OkrCheckLevel.DAT, "Đạt yêu cầu");
  }

  private static long count(List<Finding> findings, String level) {
    return findings.stream().filter(f -> level.equals(f.level())).count();
  }

  private static String target(BigDecimal value, String unit) {
    String v = value == null ? "" : value.stripTrailingZeros().toPlainString().replace('.', ',');
    return unit == null || unit.isBlank() ? v : (v + " " + unit).strip();
  }

  /** Đơn vị trực thuộc trực tiếp trên mọi cây tổ chức (không gồm chính đơn vị). */
  private List<UUID> children(TenantId tenant, UUID unit) {
    OrgDirectoryApi directory = org.getIfAvailable();
    if (directory == null) {
      return List.of();
    }
    Set<UUID> result = new LinkedHashSet<>();
    for (OrgTree tree : OrgTree.values()) {
      result.addAll(directory.descendantsOf(tenant, tree, unit, 1));
    }
    result.remove(unit);
    return result.stream().sorted(Comparator.naturalOrder()).toList();
  }

  private Optional<UUID> caller(
      java.util.function.Function<OkrCaller, Optional<UUID>> fromCaller,
      java.util.function.Function<OpaInput.Subject, String> fromSubject) {
    OkrCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return fromCaller.apply(caller);
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    String value = subject == null ? null : fromSubject.apply(subject);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
