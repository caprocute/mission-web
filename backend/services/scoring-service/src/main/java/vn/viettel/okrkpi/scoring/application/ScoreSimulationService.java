package vn.viettel.okrkpi.scoring.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.scoring.application.policy.CompiledPolicy;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.ProfileScorer;
import vn.viettel.okrkpi.scoring.application.port.Designations;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.AuthzResourceProvider;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;

/**
 * Tính thử và xem trước điểm (DV-07-08; QT-07 SCR-0703, QT-12 SCR-1202, QT-13): đọc {@code
 * input_doc} của kết quả gốc, tính lại bằng đúng phiên bản Policy Pack đã dùng (băm {@code
 * policy_content_hash}, BR-13-06) dù đã có phiên bản mới, rồi trả kết quả mà không ghi kết quả, vết
 * hay outbox (BR-09-16, BR-12-08).
 */
public class ScoreSimulationService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final Map<String, String> TASK_COMPONENTS =
      Map.of("A", "kpi_A", "B", "kpi_B", "C", "kpi_C");

  /** Một điều chỉnh cấu phần đề nghị ({@code AppraisalSimulationInput.adjustments}). */
  public record Adjustment(String component, UUID taskId, BigDecimal newValue) {}

  /** Điểm và mức của một lần tính. */
  public record Outcome(BigDecimal totalScore, String ratingCode) {}

  /** Kết quả tính thử một hồ sơ. */
  public record Simulation(
      UUID profileId, String policyVersion, Outcome before, Outcome after, String explanation) {}

  /** Điều chỉnh của một hồ sơ trong phạm vi yêu cầu mở lại (QT-13). */
  public record ProfileAdjustments(UUID profileId, List<Adjustment> adjustments) {}

  /** Tổng hợp ảnh hưởng của yêu cầu mở lại (BR-13-06). */
  public record Impact(
      int profiles, int scoreChanged, int ratingChanged, List<Simulation> simulations) {}

  /** Đầu vào xem trước: mức chất lượng dự kiến và nguyên nhân chỉnh sửa. */
  public record PreviewRequest(
      UUID taskId, BigDecimal qualityLevel, String revisionCause, Boolean objectiveRevision) {}

  /** A, B, C, KPI dự kiến của nhiệm vụ. */
  public record Preview(BigDecimal a, BigDecimal b, BigDecimal c, BigDecimal kpi, String policy) {}

  /** Một lỗi trường của đầu vào. */
  public record Issue(String field, String message) {}

  /** Đầu vào không hợp lệ (422). */
  public static class Rejected extends RuntimeException {
    private final transient List<Issue> issues;

    public Rejected(String message, List<Issue> issues) {
      super(message);
      this.issues = List.copyOf(issues);
    }

    public List<Issue> issues() {
      return issues;
    }
  }

  /** Tài nguyên OPA của tính thử: hồ sơ, người xử lý là người được giao. */
  public record ProfileSimulation(StoredScores.Stored stored, List<UUID> designated)
      implements AuthzResourceProvider {
    @Override
    public AuthzResource toAuthzResource() {
      return AuthzResource.builder()
          .type("appraisal_profile")
          .id(stored.profileId().toString())
          .tenantId(stored.tenantId().toString())
          .ownerIds(List.of(stored.personId().toString()))
          .designatedIds(designated.stream().map(UUID::toString).toList())
          .participants(Map.of("flow", List.of(stored.flow())))
          .build();
    }
  }

  /** Tài nguyên OPA của xem trước: nhiệm vụ, người nghiệm thu là người được giao. */
  public record TaskPreview(UUID taskId, StoredScores.Stored stored, List<UUID> designated)
      implements AuthzResourceProvider {
    @Override
    public AuthzResource toAuthzResource() {
      return AuthzResource.builder()
          .type("task")
          .id(taskId.toString())
          .tenantId(stored.tenantId().toString())
          .ownerIds(List.of(stored.personId().toString()))
          .designatedIds(designated.stream().map(UUID::toString).toList())
          .build();
    }
  }

  private final StoredScores store;
  private final PolicyPackCache policies;
  private final ProfileScorer scorer;
  private final Designations designations;
  private final Guard guard;

  public ScoreSimulationService(
      StoredScores store,
      PolicyPackCache policies,
      ProfileScorer scorer,
      Designations designations,
      Guard guard) {
    this.store = Objects.requireNonNull(store, "store");
    this.policies = Objects.requireNonNull(policies, "policies");
    this.scorer = Objects.requireNonNull(scorer, "scorer");
    this.designations = Objects.requireNonNull(designations, "designations");
    this.guard = Objects.requireNonNull(guard, "guard");
  }

  /**
   * Tính thử một hồ sơ với điều chỉnh đề nghị ({@code simulateAppraisalScore}).
   *
   * @throws ScoreTraceService.TraceNotFound hồ sơ chưa có kết quả trong tenant (404)
   * @throws Rejected điều chỉnh sai cấu phần hoặc ngoài miền hợp lệ (422)
   */
  @Transactional(readOnly = true)
  public Simulation simulate(UUID profileId, List<Adjustment> adjustments) {
    StoredScores.Stored stored =
        store.latest(profileId).orElseThrow(ScoreTraceService.TraceNotFound::new);
    guard.checkSimulate(new ProfileSimulation(stored, designations.forProfile(profileId)));
    return compute(stored, adjustments, "adjustments");
  }

  /**
   * Tính thử theo phạm vi yêu cầu mở lại cho MOD-07 (QT-13, BR-13-06): mỗi hồ sơ dùng phiên bản gói
   * của kết quả gốc; hồ sơ chưa có kết quả bị bỏ qua.
   */
  @Transactional(readOnly = true)
  public Impact simulateReopen(List<ProfileAdjustments> scope) {
    List<Simulation> out = new ArrayList<>();
    int scoreChanged = 0;
    int ratingChanged = 0;
    for (int i = 0; i < scope.size(); i++) {
      ProfileAdjustments p = scope.get(i);
      var stored = store.latest(p.profileId());
      if (stored.isEmpty()) {
        continue;
      }
      guard.checkSimulate(
          new ProfileSimulation(stored.get(), designations.forProfile(p.profileId())));
      Simulation s = compute(stored.get(), p.adjustments(), "profiles[" + i + "].adjustments");
      out.add(s);
      if (s.before().totalScore().compareTo(s.after().totalScore()) != 0) {
        scoreChanged++;
      }
      if (!s.before().ratingCode().equals(s.after().ratingCode())) {
        ratingChanged++;
      }
    }
    return new Impact(out.size(), scoreChanged, ratingChanged, out);
  }

  /**
   * Xem trước A, B, C, KPI của nhiệm vụ đang nghiệm thu ({@code previewAppraisalTaskScore}): phần
   * tử nhiệm vụ của lần tính gần nhất có thêm một sản phẩm được duyệt với mức chất lượng đã chọn;
   * chỉnh sửa chủ quan cộng một lần chỉnh sửa (suy luận, wiki 14).
   *
   * @throws Rejected nhiệm vụ chưa có dữ liệu tính điểm trong kỳ (422)
   */
  @Transactional(readOnly = true)
  public Preview preview(PreviewRequest request) {
    StoredScores.Stored stored =
        store
            .latestWithTask(request.taskId())
            .orElseThrow(
                () ->
                    new Rejected(
                        "Nhiệm vụ chưa có dữ liệu tính điểm trong kỳ.",
                        List.of(
                            new Issue("taskId", "Nhiệm vụ chưa có dữ liệu tính điểm trong kỳ."))));
    guard.checkPreview(
        new TaskPreview(request.taskId(), stored, designations.forTask(request.taskId())));
    CompiledPolicy policy = policyOf(stored);
    Map<String, Object> vars = ProfileScorer.variablesOf(policy, read(stored.inputDoc()));
    Map<String, Object> nv = new LinkedHashMap<>(taskOf(vars, request.taskId()));
    nv.put("so_luong_duyet", count(nv.get("so_luong_duyet")) + 1);
    nv.put("muc_chat_luong", request.qualityLevel().doubleValue());
    if (request.revisionCause() != null && !Boolean.TRUE.equals(request.objectiveRevision())) {
      nv.put("so_lan_chinh_sua", count(nv.get("so_lan_chinh_sua")) + 1);
    }
    Map<String, Object> r = scorer.previewTask(policy, nv);
    return new Preview(
        rounded(r.get("kpi_A")),
        rounded(r.get("kpi_B")),
        rounded(r.get("kpi_C")),
        rounded(r.get("kpi_nhiem_vu")),
        versionOf(policy, stored));
  }

  private Simulation compute(StoredScores.Stored stored, List<Adjustment> adjustments, String at) {
    CompiledPolicy policy = policyOf(stored);
    Map<String, Object> vars = ProfileScorer.variablesOf(policy, read(stored.inputDoc()));
    Map<String, Object> adjusted = new HashMap<>(vars);
    Map<String, Map<String, Object>> fixed = new HashMap<>();
    List<Issue> issues = new ArrayList<>();
    for (int i = 0; i < adjustments.size(); i++) {
      apply(policy, adjustments.get(i), at + "[" + i + "]", vars, adjusted, fixed, issues);
    }
    if (!issues.isEmpty()) {
      throw new Rejected("Điều chỉnh tính thử không hợp lệ.", issues);
    }
    ComputedScore before = rescore(policy, stored, vars, Map.of());
    ComputedScore after = rescore(policy, stored, adjusted, fixed);
    Outcome b = new Outcome(before.totalScore(), before.autoRatingCode());
    Outcome a = new Outcome(after.totalScore(), after.autoRatingCode());
    return new Simulation(
        stored.profileId(), versionOf(policy, stored), b, a, explain(policy, b, a, after));
  }

  private ComputedScore rescore(
      CompiledPolicy policy,
      StoredScores.Stored stored,
      Map<String, Object> vars,
      Map<String, Map<String, Object>> fixed) {
    return scorer.rescore(
        stored.resultId(),
        policy,
        stored.profileId(),
        stored.personId(),
        stored.flow(),
        vars,
        fixed);
  }

  /**
   * A, B, C của một nhiệm vụ cố định giá trị quy tắc; biến cấp hồ sơ khai báo ở {@code inputs} của
   * gói thay giá trị đầu vào, kiểm miền {@code min}/{@code max} và kiểu.
   */
  private static void apply(
      CompiledPolicy policy,
      Adjustment adj,
      String at,
      Map<String, Object> vars,
      Map<String, Object> adjusted,
      Map<String, Map<String, Object>> fixed,
      List<Issue> issues) {
    String component = adj.component() == null ? "" : adj.component().strip();
    BigDecimal value = adj.newValue();
    if (value == null) {
      issues.add(new Issue(at + ".newValue", "Chưa nhập giá trị mới."));
      return;
    }
    String rule = TASK_COMPONENTS.get(component.toUpperCase(Locale.ROOT));
    if (rule != null) {
      if (adj.taskId() == null || !hasTask(vars, adj.taskId())) {
        issues.add(new Issue(at + ".taskId", "Nhiệm vụ không thuộc hồ sơ được tính."));
        return;
      }
      if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
        issues.add(new Issue(at + ".newValue", "Giá trị cấu phần phải trong khoảng 0 đến 1."));
        return;
      }
      fixed
          .computeIfAbsent(adj.taskId().toString(), k -> new HashMap<>())
          .put(rule, value.doubleValue());
      return;
    }
    Map<String, Object> inputs = mapOf(policy.manifest().get("inputs"));
    Map<String, Object> decl = mapOf(inputs.get(component));
    if (decl.isEmpty() || ScoringInput.TASKS.equals(component)) {
      issues.add(new Issue(at + ".component", "Cấu phần không được hỗ trợ khi tính thử."));
      return;
    }
    Object type = decl.get("type");
    if ("bool".equals(type)) {
      if (value.compareTo(BigDecimal.ZERO) != 0 && value.compareTo(BigDecimal.ONE) != 0) {
        issues.add(new Issue(at + ".newValue", "Giá trị chỉ nhận 0 (không) hoặc 1 (có)."));
        return;
      }
      adjusted.put(component, value.signum() == 1);
      return;
    }
    if (!"double".equals(type) && !"int".equals(type)) {
      issues.add(new Issue(at + ".component", "Cấu phần không được hỗ trợ khi tính thử."));
      return;
    }
    if (outside(value, decl.get("min"), decl.get("max"))) {
      issues.add(new Issue(at + ".newValue", "Giá trị mới nằm ngoài miền hợp lệ của cấu phần."));
      return;
    }
    if ("int".equals(type)) {
      if (value.stripTrailingZeros().scale() > 0) {
        issues.add(new Issue(at + ".newValue", "Giá trị mới phải là số nguyên."));
        return;
      }
      adjusted.put(component, value.longValue());
    } else {
      adjusted.put(component, value.doubleValue());
    }
  }

  private static boolean outside(BigDecimal value, Object min, Object max) {
    return (min instanceof Number lo && value.compareTo(new BigDecimal(lo.toString())) < 0)
        || (max instanceof Number hi && value.compareTo(new BigDecimal(hi.toString())) > 0);
  }

  /**
   * Phiên bản gói của kết quả gốc: theo băm trong bộ đệm, hoặc phiên bản ghim của kỳ nếu cùng băm,
   * hoặc tải đúng phiên bản đã dùng (kiểm băm). Không bao giờ dùng phiên bản mới hơn (BR-13-06).
   */
  private CompiledPolicy policyOf(StoredScores.Stored stored) {
    String hash = PinnedPolicy.normalize(stored.policyContentHash());
    return policies
        .cached(hash)
        .orElseGet(
            () -> {
              PinnedPolicy pin =
                  policies.pin(
                      PolicyBindingKey.of(stored.tenantId(), stored.periodId(), stored.flow()));
              if (pin.contentHash().equals(hash)) {
                return policies.get(stored.tenantId(), pin);
              }
              return policies.preload(
                  stored.tenantId(), pin.packId(), stored.policyPackVersionId(), hash);
            });
  }

  private static String versionOf(CompiledPolicy policy, StoredScores.Stored stored) {
    return stored.policyVersion() != null ? stored.policyVersion() : policy.pinned().version();
  }

  /** Câu giải thích bằng nhãn nghiệp vụ của gói, kèm lời giải thích xếp loại sau điều chỉnh. */
  static String explain(CompiledPolicy policy, Outcome before, Outcome after, ComputedScore res) {
    StringBuilder text = new StringBuilder("Sau điều chỉnh, ");
    int cmp = after.totalScore().compareTo(before.totalScore());
    if (cmp == 0) {
      text.append("điểm giữ nguyên ").append(number(before.totalScore()));
    } else {
      text.append(cmp > 0 ? "điểm tăng từ " : "điểm giảm từ ")
          .append(number(before.totalScore()))
          .append(cmp > 0 ? " lên " : " xuống ")
          .append(number(after.totalScore()));
    }
    if (before.ratingCode().equals(after.ratingCode())) {
      text.append(", mức xếp loại giữ nguyên ").append(label(policy, after.ratingCode()));
    } else {
      text.append(", mức xếp loại chuyển từ ")
          .append(label(policy, before.ratingCode()))
          .append(" sang ")
          .append(label(policy, after.ratingCode()));
    }
    text.append('.');
    Object why = read(res.result().json()).get("giai_thich");
    if (why instanceof String s && !s.isBlank()) {
      text.append(' ').append(s.strip());
    }
    return text.toString();
  }

  static String label(CompiledPolicy policy, String code) {
    Map<String, Object> labels =
        mapOf(
            mapOf(mapOf(policy.manifest().get("classification")).get("nhan_hien_thi"))
                .get("muc_xep_loai"));
    return labels.get(code) instanceof String s ? s.toLowerCase(Locale.forLanguageTag("vi")) : code;
  }

  static String number(BigDecimal value) {
    DecimalFormat f =
        new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi")));
    return f.format(value);
  }

  private static boolean hasTask(Map<String, Object> vars, UUID taskId) {
    return tasks(vars).stream().anyMatch(t -> taskId.toString().equals(t.get("task_id")));
  }

  private static Map<String, Object> taskOf(Map<String, Object> vars, UUID taskId) {
    return tasks(vars).stream()
        .filter(t -> taskId.toString().equals(t.get("task_id")))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Đầu vào không có nhiệm vụ " + taskId));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> tasks(Map<String, Object> vars) {
    return vars.get(ScoringInput.TASKS) instanceof List<?> l
        ? (List<Map<String, Object>>) l
        : List.of();
  }

  private static long count(Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  private static BigDecimal rounded(Object value) {
    if (!(value instanceof Number n)) {
      throw new IllegalStateException("Gói quy tắc không tính ra cấu phần nhiệm vụ");
    }
    return BigDecimal.valueOf(n.doubleValue())
        .setScale(3, RoundingMode.HALF_UP)
        .stripTrailingZeros();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapOf(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private static Map<String, Object> read(String json) {
    try {
      return JSON.readValue(json, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Dữ liệu tính điểm không đọc được", e);
    }
  }

  /** Điểm chặn OPA, tách bean để {@code PermissionInterceptor} bọc được. */
  public static class Guard {
    @RequiresPermission(operationId = "simulateAppraisalScore", resourceType = "appraisal_profile")
    public void checkSimulate(ProfileSimulation resource) {
      // Chỉ để chặn trước bởi PermissionInterceptor.
    }

    @RequiresPermission(operationId = "previewAppraisalTaskScore", resourceType = "task")
    public void checkPreview(TaskPreview resource) {
      // Chỉ để chặn trước bởi PermissionInterceptor.
    }
  }
}
