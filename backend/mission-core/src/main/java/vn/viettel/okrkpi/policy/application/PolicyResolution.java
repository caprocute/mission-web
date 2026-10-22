package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.DecisionTableView;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.PolicyRulesView;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPolicyPackView;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.RuleView;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.TaskTransitionView;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository.BindingRow;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository.PackVersionRow;
import vn.viettel.okrkpi.policy.domain.PolicyEffectiveRange;
import vn.viettel.okrkpi.policy.domain.SemVer;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phân giải phiên bản Policy Pack (CORE-05-03, ADR-19). Thứ tự: dòng {@code policy_binding} của kỳ
 * khớp cụ thể nhất (đơn vị, rồi khối, rồi luồng đúng trước {@code CA_HAI}); không có thì chọn theo
 * khoảng hiệu lực của TL-01 "Hiệu lực theo kỳ" (X-PP-10: {@code DA_PHAT_HANH} hoặc {@code NGUNG},
 * đúng luồng, kỳ nằm trong {@code tu_ky..den_ky}, {@code tu_ky} lớn nhất rồi phiên bản lớn nhất) và
 * ghim kết quả thành dòng gắn toàn tenant của kỳ để các lần sau, kể cả tính lại, dùng đúng phiên
 * bản đó (BR-08-07).
 */
public class PolicyResolution {

  /** Người tạo dòng gắn khi hệ thống tự ghim (cùng quy ước của MOD-08). */
  static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  private static final Logger LOG = LoggerFactory.getLogger(PolicyResolution.class);

  private final PolicyResolutionRepository repo;
  private final PolicyPackRepository packs;
  private final ObjectProvider<PeriodApi> periods;
  private final ObjectMapper json;

  /**
   * Nội dung phiên bản đã đọc một lần cho bộ đệm: tham số, {@code task_lifecycle}, quy tắc, schema
   * biểu mẫu ({@code forms.<tên>.schema}) và đầu vào lấy từ biểu mẫu ({@code inputs.*.source} bắt
   * đầu bằng {@code form.}).
   */
  public record VersionContent(
      PackVersionRow version,
      Map<String, Object> parameters,
      List<TaskTransitionView> lifecycle,
      PolicyRulesView rules,
      Map<String, Set<String>> formFields,
      Map<String, String> scoredFields) {}

  public PolicyResolution(
      PolicyResolutionRepository repo,
      PolicyPackRepository packs,
      ObjectProvider<PeriodApi> periods,
      ObjectMapper json) {
    this.repo = Objects.requireNonNull(repo, "repo");
    this.packs = Objects.requireNonNull(packs, "packs");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.json = Objects.requireNonNull(json, "json");
  }

  /** Nội dung phiên bản; rỗng khi phiên bản không thuộc tenant. */
  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<VersionContent> content(TenantId tenant, UUID versionId) {
    Optional<PackVersionRow> version = repo.version(tenant, versionId);
    if (version.isEmpty()) {
      return Optional.empty();
    }
    JsonNode manifest = read(repo.manifest(tenant, versionId).orElse("{}"));
    List<RuleView> rules =
        packs.rules(tenant, versionId).stream()
            .map(
                r ->
                    new RuleView(
                        r.ruleKey(),
                        r.expression(),
                        r.resultType(),
                        r.roundingJson(),
                        r.evalOrder(),
                        r.paramsJson(),
                        r.legalRef()))
            .toList();
    List<DecisionTableView> tables =
        packs.decisionTables(tenant, versionId).stream()
            .map(
                t ->
                    new DecisionTableView(
                        t.tableKey(),
                        t.hitPolicy(),
                        HexFormat.of().formatHex(t.dmnHash()),
                        t.legalRef(),
                        t.rulesJson()))
            .toList();
    return Optional.of(
        new VersionContent(
            version.get(),
            parameters(manifest.path("params")),
            lifecycle(manifest.path("task_lifecycle")),
            new PolicyRulesView(versionId, rules, tables),
            formFields(manifest.path("forms")),
            scoredFields(manifest.path("inputs"))));
  }

  /** Mọi phiên bản của gói theo mã (cổng {@code PolicySchemaPort}). */
  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public List<PackVersionRow> versionsOfPack(TenantId tenant, String code) {
    return repo.versionsOfPack(tenant, code);
  }

  @Transactional(PolicyPackService.TX)
  public Optional<ResolvedPolicyPackView> resolve(
      TenantId tenant, PeriodId period, String flow, UUID subjectGroupId, UUID orgUnitId) {
    Optional<BindingRow> bound =
        mostSpecific(repo.bindings(tenant, period.value(), flow), flow, subjectGroupId, orgUnitId);
    if (bound.isPresent()) {
      return bound.map(PolicyResolution::view);
    }
    Optional<LocalDate> start =
        periods.getObject().findById(tenant, period).map(PeriodSnapshot::startDate);
    if (start.isEmpty()) {
      return Optional.empty();
    }
    Optional<PackVersionRow> chosen = effective(repo.releasedVersions(tenant, flow), start.get());
    if (chosen.isEmpty()) {
      return Optional.empty();
    }
    repo.pin(tenant, Ids.newUuidV7(), period.value(), flow, chosen.get().versionId(), SYSTEM_ACTOR);
    return mostSpecific(
            repo.bindings(tenant, period.value(), flow), flow, subjectGroupId, orgUnitId)
        .map(PolicyResolution::view);
  }

  /** Phiên bản theo khoảng hiệu lực cho kỳ bắt đầu ngày {@code periodStart}. */
  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<PackVersionRow> effectiveFor(
      TenantId tenant, String flow, LocalDate periodStart) {
    return effective(repo.releasedVersions(tenant, flow), periodStart);
  }

  /**
   * Ghim phiên bản đang hiệu lực cho kỳ vừa tạo (sự kiện nội bộ "kỳ đã tạo" của MOD-08); kỳ hai
   * luồng ghim riêng từng luồng vì mỗi luồng có gói riêng (TL-01 "Hai luồng Đảng và Nhà nước").
   *
   * @return số dòng gắn đã tạo
   */
  @Transactional(PolicyPackService.TX)
  public int pinForPeriod(TenantId tenant, PeriodId period, String periodFlow, LocalDate start) {
    List<String> flows =
        "CA_HAI".equals(periodFlow) ? List.of("DANG", "NHA_NUOC") : List.of(periodFlow);
    int created = 0;
    for (String flow : flows) {
      Optional<PackVersionRow> chosen = effective(repo.releasedVersions(tenant, flow), start);
      if (chosen.isEmpty()) {
        LOG.info("Kỳ {} luồng {} chưa có Policy Pack hiệu lực để ghim", period.value(), flow);
        continue;
      }
      if (repo.pin(
          tenant, Ids.newUuidV7(), period.value(), flow, chosen.get().versionId(), SYSTEM_ACTOR)) {
        created++;
      }
    }
    return created;
  }

  /** Dòng gắn khớp cụ thể nhất; dòng có khối hoặc đơn vị khác yêu cầu bị loại. */
  static Optional<BindingRow> mostSpecific(
      List<BindingRow> rows, String flow, UUID subjectGroupId, UUID orgUnitId) {
    return rows.stream()
        .filter(b -> b.orgUnitId() == null || b.orgUnitId().equals(orgUnitId))
        .filter(b -> b.subjectGroupId() == null || b.subjectGroupId().equals(subjectGroupId))
        .filter(b -> b.flow().equals(flow) || "CA_HAI".equals(b.flow()))
        .max(
            Comparator.comparingInt(
                    (BindingRow b) ->
                        (b.orgUnitId() != null ? 4 : 0)
                            + (b.subjectGroupId() != null ? 2 : 0)
                            + (b.flow().equals(flow) ? 1 : 0))
                .thenComparing(BindingRow::bindingId));
  }

  /** Thuật toán chọn gói theo khoảng hiệu lực (X-PP-10). */
  static Optional<PackVersionRow> effective(
      List<PackVersionRow> candidates, LocalDate periodStart) {
    record Candidate(PackVersionRow row, PolicyEffectiveRange range, SemVer semVer) {}
    return candidates.stream()
        .flatMap(
            r ->
                PolicyEffectiveRange.of(r.tuKy(), r.denKy(), r.effectiveFrom())
                    .filter(range -> range.covers(periodStart))
                    .flatMap(
                        range -> SemVer.parse(r.version()).map(s -> new Candidate(r, range, s)))
                    .stream())
        .max(
            Comparator.comparing((Candidate c) -> c.range().start())
                .thenComparing(Candidate::semVer)
                .thenComparing(c -> c.row().versionId()))
        .map(Candidate::row);
  }

  private JsonNode read(String text) {
    try {
      return json.readTree(text);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Manifest Policy Pack không đọc được", e);
    }
  }

  /** Khối {@code params}: mục dạng {@code {type, mac_dinh, ...}} lấy {@code mac_dinh}. */
  private Map<String, Object> parameters(JsonNode params) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = params.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode value =
          e.getValue().isObject() && e.getValue().has("mac_dinh")
              ? e.getValue().get("mac_dinh")
              : e.getValue();
      result.put(e.getKey(), json.convertValue(value, Object.class));
    }
    return result;
  }

  /**
   * {@code task_lifecycle} dạng tài liệu {@code TaskLifecycle} (có {@code transitions}) hoặc mảng.
   */
  private static List<TaskTransitionView> lifecycle(JsonNode node) {
    JsonNode transitions = node.isArray() ? node : node.path("transitions");
    List<TaskTransitionView> result = new ArrayList<>();
    for (JsonNode t : transitions) {
      List<String> roles = new ArrayList<>();
      t.path("allowedRoles").forEach(r -> roles.add(r.asText()));
      JsonNode guard = t.path("guardCel");
      result.add(
          new TaskTransitionView(
              t.path("from").asText(),
              t.path("command").asText(),
              t.path("to").asText(),
              roles,
              guard.isTextual() ? guard.asText() : null,
              t.path("reasonRequired").asBoolean(false)));
    }
    return List.copyOf(result);
  }

  private static Map<String, Set<String>> formFields(JsonNode forms) {
    Map<String, Set<String>> result = new LinkedHashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = forms.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      Set<String> names = new LinkedHashSet<>();
      e.getValue().path("schema").path("properties").fieldNames().forEachRemaining(names::add);
      result.put(e.getKey(), Set.copyOf(names));
    }
    return result;
  }

  /** Đầu vào tính điểm lấy từ biểu mẫu, đổi kiểu của gói sang kiểu JSON Schema. */
  private static Map<String, String> scoredFields(JsonNode inputs) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = inputs.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      String source = e.getValue().path("source").asText("");
      if (!source.startsWith("form.")) {
        continue;
      }
      String type =
          switch (e.getValue().path("type").asText("")) {
            case "double", "decimal" -> "number";
            case "int" -> "integer";
            case "bool" -> "boolean";
            case "string" -> "string";
            default -> e.getValue().path("type").asText("").startsWith("list") ? "array" : "object";
          };
      result.put(source.substring("form.".length()), type);
    }
    return result;
  }

  static ResolvedPolicyPackView view(BindingRow b) {
    PackVersionRow v = b.version();
    return new ResolvedPolicyPackView(
        v.packId(),
        v.code(),
        v.versionId(),
        v.version(),
        v.contentHash(),
        v.bundleObjectKey(),
        b.bindingId(),
        b.frozen());
  }
}
