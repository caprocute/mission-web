package vn.viettel.okrkpi.analytics.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.application.CoreDetailPort.Lookup;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.TraceChain;
import vn.viettel.okrkpi.analytics.domain.TraceLink;
import vn.viettel.okrkpi.analytics.domain.TraceObjectType;
import vn.viettel.okrkpi.analytics.domain.Viewer;

/**
 * DV-15-06 · {@code getTraceChain} (QT-16, SCR-1603, BR-16-03, BR-16-04): chuỗi điểm → cấu phần →
 * nhiệm vụ hoặc KR → sản phẩm → minh chứng → kế hoạch → mục tiêu O → căn cứ.
 *
 * <ul>
 *   <li>Điểm, cấu phần, nhiệm vụ lấy mã và số từ kho phân tích; điểm ngoài nhánh tổ chức của người
 *       xem thì che.
 *   <li>Nội dung từ nhiệm vụ trở xuống lấy từ mission-core bằng token của người xem; mắt xích bị từ
 *       chối trả {@code masked = true} kèm MSG-16-06, chuỗi vẫn tiếp tục. Mã của mắt xích bị che
 *       lấy từ kho (minh chứng theo nhiệm vụ, mục tiêu của KR) để chuỗi không đứt.
 *   <li>Minh chứng có nhiều phiên bản kèm MSG-16-07 và phiên bản được tính điểm (bản xác nhận mới
 *       nhất).
 * </ul>
 *
 * Lần chuỗi từ cấu phần, kế hoạch hay căn cứ chưa hỗ trợ vì các đối tượng đó không có khoá liên kết
 * ngược duy nhất về một nhiệm vụ (suy luận, wiki/14).
 */
public class TraceService {

  static final int MAX_TASKS = 20;
  static final int MAX_EVIDENCES = 20;

  /** Khoá cấu phần → nhãn hiển thị, theo thứ tự công thức điểm. */
  static final Map<String, String> COMPONENTS =
      Map.of(
          "THUONG_XUYEN", "Nhiệm vụ thường xuyên",
          "THEN_CHOT", "Nhiệm vụ then chốt",
          "TIEU_CHI_CHUNG", "Tiêu chí chung",
          "KPI", "Chỉ tiêu KPI");

  private final ScopeService scopes;
  private final OrgScopePort org;
  private final TraceFactsPort facts;
  private final CoreDetailPort core;

  public TraceService(
      ScopeService scopes, OrgScopePort org, TraceFactsPort facts, CoreDetailPort core) {
    this.scopes = scopes;
    this.org = org;
    this.facts = facts;
    this.core = core;
  }

  /** Rỗng khi đối tượng không có (404); đối tượng chọn bị từ chối thì 403. */
  public Optional<TraceChain> chain(Viewer viewer, String bearer, TraceObjectType type, UUID id) {
    Scope scope = scopes.resolve(viewer, null);
    Run run = new Run(scope, bearer);
    boolean found =
        switch (type) {
          case DIEM -> run.fromScore(id);
          case KPI_NHIEM_VU -> run.fromTask(id, true);
          case KET_QUA_THEN_CHOT -> run.fromKeyResult(id);
          case SAN_PHAM -> run.fromDeliverable(id);
          case MINH_CHUNG -> run.fromEvidence(id);
          case MUC_TIEU -> run.fromObjective(id);
          case CAU_PHAN, KE_HOACH, CAN_CU ->
              throw AnalyticsErrors.validation(
                  "objectType", "Chưa hỗ trợ lần chuỗi truy vết từ loại đối tượng này");
        };
    return found ? Optional.of(new TraceChain(type, id, run.links, null)) : Optional.empty();
  }

  /** Mã cấu phần ổn định theo ảnh chụp điểm (cấu phần không phải bản ghi riêng ở kho). */
  public static UUID componentId(UUID scoreId, String component) {
    return UUID.nameUUIDFromBytes((scoreId + ":" + component).getBytes(StandardCharsets.UTF_8));
  }

  /** Số hiển thị kiểu Việt Nam: dấu phẩy thập phân, bỏ số 0 thừa, tối đa 3 chữ số lẻ. */
  public static String number(BigDecimal v) {
    if (v == null) {
      return null;
    }
    BigDecimal n = v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    if (n.scale() < 0) {
      n = n.setScale(0);
    }
    return n.toPlainString().replace('.', ',');
  }

  private final class Run {
    final Scope scope;
    final String bearer;
    final List<TraceLink> links = new ArrayList<>();

    Run(Scope scope, String bearer) {
      this.scope = scope;
      this.bearer = bearer;
    }

    boolean fromScore(UUID id) {
      Optional<TraceFactsPort.Score> score = facts.score(scope.tenant(), id);
      if (score.isEmpty()) {
        return false;
      }
      if (!inScope(score.get().unitId())) {
        throw AnalyticsErrors.outOfScope("Điểm thuộc đơn vị ngoài phạm vi được xem");
      }
      addScore(score.get(), null);
      for (TraceFactsPort.Task t :
          facts.tasksOfProfile(scope.tenant(), score.get().profileId(), MAX_TASKS)) {
        addTask(t.id(), Optional.of(t), core.task(bearer, t.id()));
      }
      return true;
    }

    boolean fromTask(UUID id, boolean focus) {
      Optional<TraceFactsPort.Task> fact = facts.task(scope.tenant(), id);
      Lookup<CoreDetailPort.Task> detail = core.task(bearer, id);
      if (fact.isEmpty() && detail instanceof Lookup.Missing) {
        return false;
      }
      if (focus && detail instanceof Lookup.Denied) {
        throw AnalyticsErrors.outOfScope("Nhiệm vụ ngoài phạm vi được xem");
      }
      facts
          .scoreOfTaskOwner(scope.tenant(), id)
          .ifPresent(
              s ->
                  addScore(
                      s, fact.map(t -> t.keyTask() ? "THEN_CHOT" : "THUONG_XUYEN").orElse(null)));
      UUID keyResult = addTask(id, fact, detail);
      addProducts(id, detail);
      addPlan(fact, detail);
      if (keyResult != null) {
        addObjective(keyResult, null);
      }
      return true;
    }

    boolean fromKeyResult(UUID id) {
      Lookup<CoreDetailPort.KeyResult> kr = core.keyResult(bearer, id);
      if (kr instanceof Lookup.Missing) {
        return false;
      }
      if (kr instanceof Lookup.Denied) {
        throw AnalyticsErrors.outOfScope("Kết quả then chốt ngoài phạm vi được xem");
      }
      addKeyResult(id, kr);
      addObjective(id, ((Lookup.Found<CoreDetailPort.KeyResult>) kr).value().objectiveId());
      return true;
    }

    boolean fromDeliverable(UUID id) {
      return switch (core.deliverable(bearer, id)) {
        case Lookup.Found<CoreDetailPort.Deliverable> f -> fromTask(f.value().taskId(), false);
        case Lookup.Denied<CoreDetailPort.Deliverable> d ->
            throw AnalyticsErrors.outOfScope("Sản phẩm ngoài phạm vi được xem");
        case Lookup.Missing<CoreDetailPort.Deliverable> m -> false;
      };
    }

    boolean fromEvidence(UUID id) {
      return switch (core.evidence(bearer, id)) {
        case Lookup.Found<CoreDetailPort.Evidence> f -> fromTask(f.value().taskId(), false);
        case Lookup.Denied<CoreDetailPort.Evidence> d ->
            throw AnalyticsErrors.outOfScope("Minh chứng ngoài phạm vi được xem");
        case Lookup.Missing<CoreDetailPort.Evidence> m -> false;
      };
    }

    boolean fromObjective(UUID id) {
      Lookup<CoreDetailPort.Objective> o = core.objective(bearer, id);
      if (o instanceof Lookup.Missing) {
        return false;
      }
      if (o instanceof Lookup.Denied) {
        throw AnalyticsErrors.outOfScope("Mục tiêu ngoài phạm vi được xem");
      }
      addObjectiveLinks(id, o);
      return true;
    }

    void addScore(TraceFactsPort.Score s, String onlyComponent) {
      if (!inScope(s.unitId())) {
        links.add(TraceLink.masked(TraceObjectType.DIEM, s.id()));
        return;
      }
      String label = s.periodName() == null ? "Điểm đánh giá" : "Điểm " + s.periodName();
      Map<String, Object> details = new LinkedHashMap<>();
      if (s.policyVersion() != null) {
        details.put("policyVersion", s.policyVersion());
      }
      links.add(
          new TraceLink(
              TraceObjectType.DIEM,
              s.id(),
              label,
              number(s.total()),
              null,
              null,
              null,
              false,
              null,
              details));
      s.components()
          .forEach(
              (code, v) -> {
                if (onlyComponent == null || onlyComponent.equals(code)) {
                  links.add(
                      new TraceLink(
                          TraceObjectType.CAU_PHAN,
                          componentId(s.id(), code),
                          COMPONENTS.getOrDefault(code, "Cấu phần điểm"),
                          number(v),
                          null,
                          null,
                          null,
                          false,
                          null,
                          Map.of("component", code)));
                }
              });
    }

    /** Mắt xích nhiệm vụ (và KR nếu có); trả mã KR để lần tiếp lên mục tiêu. */
    UUID addTask(UUID id, Optional<TraceFactsPort.Task> fact, Lookup<CoreDetailPort.Task> detail) {
      UUID keyResult = fact.map(TraceFactsPort.Task::keyResultId).orElse(null);
      if (detail instanceof Lookup.Found<CoreDetailPort.Task> f) {
        CoreDetailPort.Task t = f.value();
        keyResult = t.keyResultId() != null ? t.keyResultId() : keyResult;
        String value = t.progress() == null ? null : t.progress() + "%";
        links.add(
            new TraceLink(
                TraceObjectType.KPI_NHIEM_VU,
                id,
                t.title(),
                value,
                t.status(),
                null,
                t.unitName(),
                false,
                null,
                t.code() == null ? null : Map.of("code", t.code())));
      } else if (detail instanceof Lookup.Denied || fact.isPresent()) {
        links.add(TraceLink.masked(TraceObjectType.KPI_NHIEM_VU, id));
      }
      if (keyResult != null) {
        addKeyResult(keyResult, core.keyResult(bearer, keyResult));
      }
      return keyResult;
    }

    void addKeyResult(UUID id, Lookup<CoreDetailPort.KeyResult> kr) {
      switch (kr) {
        case Lookup.Found<CoreDetailPort.KeyResult> f ->
            links.add(
                new TraceLink(
                    TraceObjectType.KET_QUA_THEN_CHOT,
                    id,
                    join(f.value().code(), f.value().title()),
                    null,
                    f.value().status(),
                    null,
                    null,
                    false,
                    null,
                    null));
        case Lookup.Denied<CoreDetailPort.KeyResult> d ->
            links.add(TraceLink.masked(TraceObjectType.KET_QUA_THEN_CHOT, id));
        case Lookup.Missing<CoreDetailPort.KeyResult> m -> {}
      }
    }

    void addProducts(UUID taskId, Lookup<CoreDetailPort.Task> task) {
      List<TraceFactsPort.EvidenceRef> refs =
          facts.evidencesOfTask(scope.tenant(), taskId, MAX_EVIDENCES);
      Lookup<List<CoreDetailPort.Deliverable>> products =
          task instanceof Lookup.Denied ? new Lookup.Denied<>() : core.deliverables(bearer, taskId);
      if (products instanceof Lookup.Found<List<CoreDetailPort.Deliverable>> f) {
        for (CoreDetailPort.Deliverable d : f.value()) {
          String value =
              d.planned() == null ? null : number(d.accepted()) + "/" + number(d.planned());
          links.add(
              new TraceLink(
                  TraceObjectType.SAN_PHAM,
                  d.id(),
                  d.name(),
                  value,
                  d.status(),
                  null,
                  null,
                  false,
                  null,
                  d.unit() == null ? null : Map.of("unit", d.unit())));
        }
      } else if (products instanceof Lookup.Denied) {
        Set<UUID> ids = new LinkedHashSet<>();
        refs.stream()
            .map(TraceFactsPort.EvidenceRef::deliverableId)
            .filter(java.util.Objects::nonNull)
            .forEach(ids::add);
        ids.forEach(d -> links.add(TraceLink.masked(TraceObjectType.SAN_PHAM, d)));
      }
      for (TraceFactsPort.EvidenceRef r : refs) {
        switch (core.evidence(bearer, r.id())) {
          case Lookup.Found<CoreDetailPort.Evidence> f -> links.add(evidenceLink(f.value()));
          case Lookup.Denied<CoreDetailPort.Evidence> d ->
              links.add(TraceLink.masked(TraceObjectType.MINH_CHUNG, r.id()));
          case Lookup.Missing<CoreDetailPort.Evidence> m -> {}
        }
      }
    }

    void addPlan(Optional<TraceFactsPort.Task> fact, Lookup<CoreDetailPort.Task> detail) {
      UUID period =
          detail instanceof Lookup.Found<CoreDetailPort.Task> f && f.value().periodId() != null
              ? f.value().periodId()
              : fact.map(TraceFactsPort.Task::periodId).orElse(null);
      UUID unit = fact.map(TraceFactsPort.Task::unitId).orElse(null);
      if (period == null || unit == null) {
        return;
      }
      if (core.plans(bearer, period, unit) instanceof Lookup.Found<List<CoreDetailPort.Plan>> f
          && !f.value().isEmpty()) {
        CoreDetailPort.Plan p = f.value().get(0);
        links.add(
            new TraceLink(
                TraceObjectType.KE_HOACH,
                p.id(),
                p.code() + " v" + p.versionNo(),
                null,
                p.status(),
                p.versionNo(),
                p.unitName(),
                false,
                null,
                null));
      }
    }

    /** Mục tiêu chứa KR (mã lấy từ kho khi KR bị từ chối) và căn cứ của mục tiêu. */
    void addObjective(UUID keyResult, UUID knownObjective) {
      UUID objective =
          knownObjective != null
              ? knownObjective
              : facts.objectiveOfKeyResult(scope.tenant(), keyResult).orElse(null);
      if (objective == null) {
        return;
      }
      Lookup<CoreDetailPort.Objective> o = core.objective(bearer, objective);
      if (o instanceof Lookup.Denied) {
        links.add(TraceLink.masked(TraceObjectType.MUC_TIEU, objective));
      } else {
        addObjectiveLinks(objective, o);
      }
    }

    void addObjectiveLinks(UUID id, Lookup<CoreDetailPort.Objective> o) {
      if (!(o instanceof Lookup.Found<CoreDetailPort.Objective> f)) {
        return;
      }
      CoreDetailPort.Objective v = f.value();
      links.add(
          new TraceLink(
              TraceObjectType.MUC_TIEU,
              id,
              join(v.code(), v.title()),
              null,
              v.status(),
              null,
              v.unitName(),
              false,
              null,
              null));
      for (CoreDetailPort.Basis b : v.bases()) {
        links.add(
            new TraceLink(
                TraceObjectType.CAN_CU,
                b.id(),
                join(b.docNumber(), b.title()),
                null,
                null,
                null,
                null,
                false,
                null,
                null));
      }
    }

    boolean inScope(UUID unit) {
      return unit != null && org.paths(scope.tenant(), unit).stream().anyMatch(scope::covers);
    }
  }

  static TraceLink evidenceLink(CoreDetailPort.Evidence e) {
    Integer scored =
        e.versions().stream()
            .filter(v -> "DA_XAC_NHAN".equals(v.status()))
            .map(CoreDetailPort.Version::versionNo)
            .max(Integer::compare)
            .orElse(null);
    int count =
        Math.max(e.versions().size(), e.currentVersionNo() == null ? 0 : e.currentVersionNo());
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("versionCount", count);
    if (scored != null) {
      details.put("scoredVersionNo", scored);
    }
    return new TraceLink(
        TraceObjectType.MINH_CHUNG,
        e.id(),
        e.title(),
        null,
        e.currentStatus(),
        scored != null ? scored : e.currentVersionNo(),
        null,
        false,
        count > 1 ? TraceLink.MSG_VERSIONS : null,
        details);
  }

  private static String join(String code, String title) {
    if (code == null || code.isBlank()) {
      return title == null ? "" : title;
    }
    return title == null || title.isBlank() ? code : code + " – " + title;
  }
}
