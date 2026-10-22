package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyBinding;
import vn.viettel.okrkpi.policy.domain.PolicyEffectiveRange;
import vn.viettel.okrkpi.policy.domain.PolicyManifestRules;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyTransitionException;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.policy.domain.SemVer;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Gói và phiên bản Policy Pack của MOD-05 (CORE-05-02, QT-15, SCR-1501): liệt kê gói kèm phiên bản
 * gần nhất, nhân bản phiên bản thành nháp mới (QT-15 bước 1), chuyển trạng thái theo baseline mục
 * 9.1, luật kế thừa {@code overridable} (BR-15-13) và nhập gói gốc có kiểm manifest.
 */
public class PolicyPackService {

  public static final String TX = "policyTransactionManager";
  public static final String OBJECT_TYPE = "policy_pack_version";

  /** Số phiên bản tóm tắt kèm mỗi gói trong danh sách (suy luận, hợp đồng để máy chủ chọn). */
  public static final int RECENT_VERSIONS = 5;

  static final int CHANGE_NOTE_MAX = 2000;

  /** Gói kèm các phiên bản mới nhất trước. */
  public record PackView(PolicyPack pack, List<PolicyPackVersion> versions) {}

  /** Phiên bản kèm gói và các gắn kỳ hiệu lực. */
  public record VersionView(
      PolicyPack pack, PolicyPackVersion version, List<PolicyBinding> bindings) {}

  /**
   * Lệnh nhập gói gốc từ tệp {@code policy.yaml} đã đọc thành bảng.
   *
   * @param parentPackId gói cha khi nhập gói kế thừa của tenant
   */
  public record PackImport(
      Map<String, ?> manifest,
      PackOrigin origin,
      String sector,
      UUID parentPackId,
      String changeNote) {}

  private final PolicyPackRepository repo;
  private final PolicyAudit audit;
  private final ObjectMapper json;
  private final Clock clock;
  private ApplicationEventPublisher events;

  public PolicyPackService(
      PolicyPackRepository repo, PolicyAudit audit, ObjectMapper json, Clock clock) {
    this.repo = Objects.requireNonNull(repo, "repo");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Nơi phát {@link PolicyVersionStatusChanged} cho bộ đệm phân giải (CORE-05-03). */
  public void setEvents(ApplicationEventPublisher events) {
    this.events = events;
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public List<PackView> listPacks(
      TenantId tenant, String flow, PackOrigin origin, String query, String afterCode, int limit) {
    List<PolicyPack> packs = repo.listPacks(tenant, flow, origin, query, afterCode, limit);
    Map<UUID, List<PolicyPackVersion>> recent =
        repo.recentVersions(tenant, packs.stream().map(PolicyPack::id).toList(), RECENT_VERSIONS);
    return packs.stream()
        .map(p -> new PackView(p, recent.getOrDefault(p.id(), List.of())))
        .toList();
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<PackView> findPack(TenantId tenant, UUID packId) {
    return repo.findPack(tenant, packId)
        .map(
            p ->
                new PackView(
                    p,
                    repo.recentVersions(tenant, List.of(p.id()), RECENT_VERSIONS)
                        .getOrDefault(p.id(), List.of())));
  }

  /** Phiên bản của gói, mới nhất trước; gói không có ném {@link NoSuchElementException}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<PolicyPackVersion> listVersions(
      TenantId tenant, UUID packId, PolicyVersionStatus status, UUID afterId, int limit) {
    requirePack(tenant, packId);
    return repo.listVersions(tenant, packId, status, afterId, limit);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<VersionView> findVersion(TenantId tenant, UUID packId, String version) {
    return repo.findPack(tenant, packId)
        .flatMap(
            p ->
                repo.findVersion(tenant, packId, version)
                    .map(v -> new VersionView(p, v, repo.bindings(tenant, v.id()))));
  }

  /**
   * Nhân bản {@code baseVersion} thành phiên bản {@code NHAP} mới kèm toàn bộ quy tắc (cả tham số)
   * và bảng quyết định (QT-15 bước 1). Phiên bản nguồn tìm trong gói, rồi trong gói cha nếu gói kế
   * thừa; khi nhân bản từ gói cha, {@code source_version_id} trỏ tới phiên bản đó (BR-15-13). Bỏ
   * trống {@code version} thì tăng số phụ của phiên bản cao nhất cùng số chính.
   */
  @Transactional(TX)
  public VersionView newVersion(
      TenantId tenant,
      UUID actor,
      UUID packId,
      String baseVersion,
      String version,
      String changeNote) {
    String note = changeNote == null ? "" : changeNote.strip();
    if (note.isEmpty()) {
      throw invalid("changeNote", "Cần nhập ghi chú thay đổi");
    }
    if (note.length() > CHANGE_NOTE_MAX) {
      throw invalid("changeNote", "Ghi chú thay đổi tối đa 2000 ký tự");
    }
    SemVer base =
        SemVer.parse(baseVersion)
            .orElseThrow(() -> invalid("baseVersion", "Phiên bản nguồn phải có dạng 1.0.0"));
    SemVer requested = null;
    if (version != null) {
      requested =
          SemVer.parse(version)
              .orElseThrow(() -> invalid("version", "Số phiên bản phải có dạng 1.0.0"));
    }
    PolicyPack pack = requirePack(tenant, packId);
    Optional<PolicyPackVersion> own = repo.findVersion(tenant, packId, base.toString());
    PolicyPackVersion source;
    UUID sourceVersionId;
    if (own.isPresent()) {
      source = own.get();
      sourceVersionId = source.sourceVersionId();
    } else if (pack.inherits()) {
      source =
          repo.findVersion(tenant, pack.parentPackId(), base.toString())
              .orElseThrow(() -> noBase(base));
      sourceVersionId = source.id();
    } else {
      throw noBase(base);
    }
    SemVer target = requested == null ? nextMinor(tenant, packId, base) : requested;
    Instant at = clock.instant();
    PolicyPackVersion draft =
        PolicyPackVersion.draft(
            Ids.newUuidV7(),
            packId,
            target.toString(),
            source.manifestJson(),
            sourceVersionId,
            note,
            actor,
            at);
    if (!repo.insertVersion(tenant, draft)) {
      throw versionExists(target);
    }
    repo.insertRules(
        tenant,
        repo.rules(tenant, source.id()).stream()
            .map(r -> r.copyTo(Ids.newUuidV7(), draft.id()))
            .toList(),
        actor);
    repo.insertDecisionTables(
        tenant,
        repo.decisionTables(tenant, source.id()).stream()
            .map(t -> t.copyTo(Ids.newUuidV7(), draft.id()))
            .toList(),
        actor);
    audit.changed(
        tenant, actor, "TAO_PHIEN_BAN_POLICY_PACK", OBJECT_TYPE, key(pack, draft), draft.id(), 0);
    return new VersionView(pack, draft, List.of());
  }

  /**
   * Chuyển trạng thái phiên bản theo sơ đồ TL-02b QT-15, có khoá lạc quan; chuyển trái phép trả mã
   * lỗi của hợp đồng (409 {@code POLICY-409-VERSION-CONFLICT}, {@code POLICY-409-NOT-TESTED},
   * {@code POLICY-409-NOT-SIGNED}, 403 {@code POLICY-403-SAME-AUTHOR}). Dùng cho CORE-05-07..10.
   */
  @Transactional(TX)
  public PolicyPackVersion changeStatus(
      TenantId tenant,
      UUID actor,
      UUID packId,
      String version,
      long expectedRowVersion,
      PolicyVersionStatus target) {
    PolicyPack pack = requirePack(tenant, packId);
    PolicyPackVersion current =
        repo.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    if (current.rowVersion() != expectedRowVersion) {
      throw new VersionConflictException("POLICY", expectedRowVersion, current.rowVersion());
    }
    PolicyPackVersion moved;
    try {
      moved = current.moveTo(target, actor, clock.instant());
    } catch (PolicyTransitionException e) {
      throw new BusinessException(e.code(), e.getMessage());
    }
    if (target == PolicyVersionStatus.DA_PHAT_HANH) {
      assertNoOverlap(tenant, moved);
    }
    if (!repo.storeStatus(tenant, moved, expectedRowVersion)) {
      throw new VersionConflictException("POLICY", expectedRowVersion, null);
    }
    if (events != null) {
      events.publishEvent(
          new PolicyVersionStatusChanged(tenant, moved.packId(), moved.id(), moved.status()));
    }
    audit.changed(
        tenant,
        actor,
        "CHUYEN_TRANG_THAI_POLICY_PACK_" + target.name(),
        OBJECT_TYPE,
        key(pack, moved),
        moved.id(),
        moved.rowVersion());
    return moved;
  }

  /**
   * Kiểm trước khi sửa quy tắc {@code ruleKey} của một phiên bản (CORE-05-04 gọi): phiên bản phải ở
   * {@code NHAP} (409 {@code POLICY-409-VERSION-FROZEN}); phiên bản kế thừa gói gốc chỉ được sửa
   * quy tắc gói gốc đánh dấu {@code overridable} (403 {@code POLICY-403-NOT-OVERRIDABLE},
   * BR-15-13). Quy tắc tenant tự thêm, không có ở gói gốc, sửa được.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public void assertRuleEditable(TenantId tenant, UUID packId, String version, String ruleKey) {
    requirePack(tenant, packId);
    PolicyPackVersion v =
        repo.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    if (v.status() != PolicyVersionStatus.NHAP) {
      throw new BusinessException(
          "POLICY-409-VERSION-FROZEN",
          "Phiên bản " + version + " không ở trạng thái nháp nên không sửa được");
    }
    if (!v.inheritsFromParent()) {
      return;
    }
    Optional<PolicyRule> parent = repo.findRule(tenant, v.sourceVersionId(), ruleKey);
    if (parent.isPresent() && !parent.get().overridable()) {
      throw new BusinessException(
          "POLICY-403-NOT-OVERRIDABLE",
          "Quy tắc này thuộc gói gốc và không cho phép đơn vị điều chỉnh",
          List.of(
              new ProblemFieldError(
                  "ruleKey", "Quy tắc gói gốc không cho phép điều chỉnh", "BR-15-13")));
    }
  }

  /**
   * Nhập gói từ {@code policy.yaml} (TL-01 "Cấu trúc gói"): kiểm manifest, tạo gói nếu chưa có theo
   * {@code metadata.id} rồi tạo phiên bản {@code NHAP} số {@code metadata.version}.
   */
  @Transactional(TX)
  public VersionView importPack(TenantId tenant, UUID actor, PackImport cmd) {
    Objects.requireNonNull(cmd, "cmd");
    List<PolicyManifestRules.Problem> problems = PolicyManifestRules.check(cmd.manifest());
    if (!problems.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Nội dung gói chưa đúng cấu trúc",
          problems.stream().map(p -> new ProblemFieldError(p.field(), p.message(), null)).toList());
    }
    Map<?, ?> meta = (Map<?, ?>) cmd.manifest().get("metadata");
    String code = (String) meta.get("id");
    String version = (String) meta.get("version");
    Instant at = clock.instant();
    PolicyPack pack =
        repo.findPackByCode(tenant, code)
            .orElseGet(
                () -> {
                  PolicyPack created =
                      new PolicyPack(
                          Ids.newUuidV7(),
                          code,
                          (String) meta.get("ten"),
                          cmd.sector(),
                          (String) meta.get("luong"),
                          cmd.origin(),
                          cmd.parentPackId(),
                          legalBasis(meta.get("can_cu_phap_ly")),
                          at,
                          actor,
                          0);
                  if (!repo.insertPack(tenant, created)) {
                    throw invalid("metadata.id", "Mã gói " + code + " đã có");
                  }
                  return created;
                });
    String note =
        cmd.changeNote() == null || cmd.changeNote().isBlank()
            ? "Nhập gói " + code + " phiên bản " + version
            : cmd.changeNote().strip();
    PolicyPackVersion draft =
        PolicyPackVersion.draft(
            Ids.newUuidV7(), pack.id(), version, write(cmd.manifest()), null, note, actor, at);
    if (!repo.insertVersion(tenant, draft)) {
      throw versionExists(draft.semVer());
    }
    audit.changed(tenant, actor, "NHAP_POLICY_PACK", OBJECT_TYPE, key(pack, draft), draft.id(), 0);
    return new VersionView(pack, draft, List.of());
  }

  private PolicyPack requirePack(TenantId tenant, UUID packId) {
    return repo.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
  }

  private SemVer nextMinor(TenantId tenant, UUID packId, SemVer base) {
    SemVer highest =
        repo.versionNumbers(tenant, packId).stream()
            .map(SemVer::parse)
            .flatMap(Optional::stream)
            .filter(s -> s.major() == base.major())
            .max(SemVer::compareTo)
            .orElse(base);
    return (highest.compareTo(base) < 0 ? base : highest).nextMinor();
  }

  private static String key(PolicyPack pack, PolicyPackVersion v) {
    return pack.code() + "@" + v.version();
  }

  private static String legalBasis(Object value) {
    if (value instanceof Collection<?> items) {
      return items.stream().map(String::valueOf).collect(Collectors.joining("; "));
    }
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * Chống chồng khoảng hiệu lực ở tầng ứng dụng (TL-01 "Hiệu lực theo kỳ", wiki trang 14 mục 4):
   * phiên bản mới cùng {@code tu_ky} với một phiên bản đã phát hành thì thay thế nó, phiên bản có
   * {@code tu_ky} muộn hơn nối tiếp phiên bản mở; còn hai khoảng có {@code tu_ky} khác nhau mà một
   * bên đã khai {@code den_ky} và hai khoảng giao nhau thì từ chối (suy luận).
   */
  private void assertNoOverlap(TenantId tenant, PolicyPackVersion target) {
    PolicyEffectiveRange range =
        range(target)
            .orElseThrow(
                () ->
                    new BusinessException(
                        "POLICY-409-VERSION-CONFLICT",
                        "Khoảng hiệu lực của phiên bản " + target.version() + " viết sai dạng"));
    for (PolicyPackVersion other :
        repo.listVersions(tenant, target.packId(), PolicyVersionStatus.DA_PHAT_HANH, null, 200)) {
      if (other.id().equals(target.id())) {
        continue;
      }
      Optional<PolicyEffectiveRange> o = range(other);
      if (o.isPresent()
          && range.overlaps(o.get())
          && !range.start().equals(o.get().start())
          && (range.to() != null || o.get().to() != null)) {
        throw new BusinessException(
            "POLICY-409-VERSION-CONFLICT",
            "Khoảng hiệu lực của phiên bản "
                + target.version()
                + " chồng với phiên bản đã phát hành "
                + other.version());
      }
    }
  }

  private Optional<PolicyEffectiveRange> range(PolicyPackVersion v) {
    try {
      JsonNode hieuLuc = json.readTree(v.manifestJson()).path("metadata").path("hieu_luc");
      return PolicyEffectiveRange.of(
          hieuLuc.path("tu_ky").isTextual() ? hieuLuc.path("tu_ky").asText() : null,
          hieuLuc.path("den_ky").isTextual() ? hieuLuc.path("den_ky").asText() : null,
          v.effectiveFrom());
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }

  private String write(Map<String, ?> manifest) {
    try {
      return json.writeValueAsString(manifest);
    } catch (JsonProcessingException e) {
      throw invalid("manifest", "Nội dung gói không chuyển được sang JSON");
    }
  }

  private static BusinessException noBase(SemVer base) {
    return invalid("baseVersion", "Không có phiên bản " + base + " để nhân bản");
  }

  private static BusinessException versionExists(SemVer v) {
    String message = "Phiên bản " + v + " đã có trong gói";
    return new BusinessException(
        "POLICY-422-VERSION-EXISTS",
        message,
        List.of(new ProblemFieldError("version", message, null)));
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
