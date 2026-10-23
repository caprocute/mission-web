package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.PluginPoints;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedProfile;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService.Approver;
import vn.viettel.okrkpi.policy.application.port.CustomerProfileRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner.SignedHash;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository.PublishedVersion;
import vn.viettel.okrkpi.policy.domain.CanonicalJson;
import vn.viettel.okrkpi.policy.domain.CustomerProfile;
import vn.viettel.okrkpi.policy.domain.CustomerProfileVersion;
import vn.viettel.okrkpi.policy.domain.ParamConstraint;
import vn.viettel.okrkpi.policy.domain.ParamConstraintCheck;
import vn.viettel.okrkpi.policy.domain.ParamConstraintCheck.Violation;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyTransitionException;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.policy.domain.SemVer;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ tập khách hàng (CORE-05-12, BS-03 ADR-33): tạo hồ sơ và phiên bản, khung tham số DC-02,
 * vòng đời kiểm tra, gửi duyệt, ký duyệt, từ chối dùng cùng máy trạng thái của Policy Pack; phân
 * giải hồ sơ ghim cùng gắn của kỳ cho {@code PolicyQueryApi}; kiểm khung tham số khi gửi phát hành
 * gói cấp dưới. Lõi không biết hồ sơ cụ thể nào: mọi giá trị riêng nằm trong bản kê và các thành
 * phần.
 *
 * <p>MOD-05 chỉ đối chiếu được thành phần {@code policyPack}; {@code catalog}, {@code
 * alertRuleSet}, {@code workflowParams}, {@code uiPack} thuộc mô-đun hoặc dịch vụ khác chưa có cổng
 * tra trạng thái phiên bản nên chỉ kiểm dạng (khoảng trống ghi ở wiki 14).
 */
public class CustomerProfileService {

  public static final String OBJECT_TYPE = "customer_profile_version";
  static final Duration STEP_UP_WINDOW = PolicyApprovalService.STEP_UP_WINDOW;
  static final Set<PolicyVersionStatus> TESTED =
      Set.of(
          PolicyVersionStatus.DAT_KIEM_THU,
          PolicyVersionStatus.CHO_PHE_DUYET,
          PolicyVersionStatus.DA_PHAT_HANH);
  static final Set<String> FLOWS = Set.of("DANG", "NHA_NUOC", "CA_HAI");
  static final Set<String> ASSIGNMENT_MODES = Set.of("CHU_TRI_PHOI_HOP", "MOT_NGUOI_MOT_VIEC");
  static final List<String> PLUGIN_FLAGS =
      List.of(
          "workItemScore",
          "boundedInheritance",
          "responsibilityScope",
          "milestoneMetric",
          "classificationChain");
  private static final Pattern SEMVER = SemVer.PATTERN;
  private static final int TEXT_MAX = 2000;
  private static final int REASON_MIN = 10;
  private static final int CONSTRAINTS_MAX = 500;
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** Hồ sơ kèm tóm tắt phiên bản gần nhất. */
  public record ProfileView(CustomerProfile profile, List<CustomerProfileVersion> versions) {}

  /** Phiên bản kèm số khung tham số. */
  public record VersionView(CustomerProfileVersion version, int constraintCount) {}

  /** Lệnh tạo hồ sơ theo {@code CustomerProfileCreate}. */
  public record CreateProfile(
      String code,
      String name,
      Boolean simulated,
      List<String> legalBasis,
      List<String> tenantTags) {}

  private record VersionKey(TenantId tenant, UUID versionId) {}

  private record BindingKey(TenantId tenant, UUID bindingId) {}

  private final CustomerProfileRepository profiles;
  private final PolicyPackRepository packs;
  private final PolicyResolutionRepository resolutions;
  private final Supplier<PolicyResolutionService> resolution;
  private final PolicyPackSigner signer;
  private final PolicyAudit audit;
  private final ObjectMapper json;
  private final Clock clock;
  private final Cache<BindingKey, Optional<UUID>> pinned;
  private final Cache<VersionKey, ResolvedProfile> resolved;

  public CustomerProfileService(
      CustomerProfileRepository profiles,
      PolicyPackRepository packs,
      PolicyResolutionRepository resolutions,
      Supplier<PolicyResolutionService> resolution,
      PolicyPackSigner signer,
      PolicyAudit audit,
      ObjectMapper json,
      Clock clock,
      Duration ttl) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.packs = Objects.requireNonNull(packs, "packs");
    this.resolutions = Objects.requireNonNull(resolutions, "resolutions");
    this.resolution = Objects.requireNonNull(resolution, "resolution");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.pinned = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100_000).build();
    this.resolved = Caffeine.newBuilder().expireAfterAccess(ttl).maximumSize(2_000).build();
  }

  // ───── Hồ sơ ─────

  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public List<ProfileView> listProfiles(
      TenantId tenant, String query, String afterCode, int limit) {
    List<CustomerProfile> found = profiles.listProfiles(tenant, query, afterCode, limit);
    Map<UUID, List<CustomerProfileVersion>> recent =
        profiles.recentVersions(
            tenant,
            found.stream().map(CustomerProfile::id).toList(),
            PolicyPackService.RECENT_VERSIONS);
    return found.stream()
        .map(p -> new ProfileView(p, recent.getOrDefault(p.id(), List.of())))
        .toList();
  }

  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<ProfileView> findProfile(TenantId tenant, UUID profileId) {
    return profiles
        .findProfile(tenant, profileId)
        .map(
            p ->
                new ProfileView(
                    p,
                    profiles
                        .recentVersions(tenant, List.of(p.id()), PolicyPackService.RECENT_VERSIONS)
                        .getOrDefault(p.id(), List.of())));
  }

  @Transactional(PolicyPackService.TX)
  public ProfileView newProfile(TenantId tenant, UUID actor, CreateProfile cmd) {
    Objects.requireNonNull(cmd, "cmd");
    String code = cmd.code() == null ? "" : cmd.code().strip();
    if (code.isEmpty() || code.length() > 100 || !CustomerProfile.CODE.matcher(code).matches()) {
      throw invalid("code", "Mã hồ sơ chỉ gồm chữ thường, số và các dấu / _ -, tối đa 100 ký tự");
    }
    String name = cmd.name() == null ? "" : cmd.name().strip();
    if (name.isEmpty() || name.length() > 255) {
      throw invalid("name", "Cần nhập tên hồ sơ, tối đa 255 ký tự");
    }
    if (cmd.simulated() == null) {
      throw invalid("isSimulated", "Cần cho biết hồ sơ có phải giả lập hay không");
    }
    List<String> legalBasis = texts(cmd.legalBasis(), "legalBasis", 500);
    List<String> tags = texts(cmd.tenantTags(), "tenantTags", 64);
    Instant at = clock.instant();
    CustomerProfile profile =
        new CustomerProfile(
            Ids.newUuidV7(), code, name, cmd.simulated(), legalBasis, tags, at, actor, 0);
    if (!profiles.insertProfile(tenant, profile)) {
      throw new BusinessException(
          "POLICY-422-PROFILE-CODE-EXISTS",
          "Mã hồ sơ đã có",
          List.of(new ProblemFieldError("code", "Mã hồ sơ đã có", null)));
    }
    audit.changed(tenant, actor, "TAO_HO_SO_KHACH_HANG", "customer_profile", code, profile.id(), 0);
    return new ProfileView(profile, List.of());
  }

  // ───── Phiên bản ─────

  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public List<CustomerProfileVersion> listVersions(
      TenantId tenant, UUID profileId, PolicyVersionStatus status, UUID afterId, int limit) {
    requireProfile(tenant, profileId);
    return profiles.listVersions(tenant, profileId, status, afterId, limit);
  }

  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<VersionView> findVersion(TenantId tenant, UUID profileId, String version) {
    return profiles.findVersion(tenant, profileId, version).map(v -> view(tenant, v));
  }

  /**
   * Tạo phiên bản {@code NHAP}; có {@code baseVersion} thì nhân bản bản kê (khi không gửi bản kê
   * mới) và khung tham số. Số phiên bản bỏ trống thì tăng số phụ của phiên bản lớn nhất.
   */
  @Transactional(PolicyPackService.TX)
  public VersionView newVersion(
      TenantId tenant,
      UUID actor,
      UUID profileId,
      String baseVersion,
      String version,
      String changeNote,
      Map<String, Object> manifest) {
    CustomerProfile profile = requireProfile(tenant, profileId);
    String note = changeNote == null ? "" : changeNote.strip();
    if (note.isEmpty() || note.length() > TEXT_MAX) {
      throw invalid("changeNote", "Cần nhập ghi chú thay đổi, tối đa 2000 ký tự");
    }
    CustomerProfileVersion base = null;
    if (baseVersion != null) {
      if (!SEMVER.matcher(baseVersion).matches()) {
        throw invalid("baseVersion", "Phiên bản nguồn phải có dạng 1.0.0");
      }
      base =
          profiles
              .findVersion(tenant, profileId, baseVersion)
              .orElseThrow(() -> invalid("baseVersion", "Không tìm thấy phiên bản nguồn"));
    }
    if (version != null && !SEMVER.matcher(version).matches()) {
      throw invalid("version", "Số phiên bản phải có dạng 1.0.0");
    }
    String manifestJson;
    if (manifest != null) {
      manifestJson = manifestJson(tenant, manifest);
    } else if (base != null) {
      manifestJson = base.manifestJson();
    } else {
      throw invalid("manifest", "Cần nhập bản kê khi tạo phiên bản đầu");
    }
    String target = version == null ? nextMinor(tenant, profileId) : version;
    CustomerProfileVersion draft =
        CustomerProfileVersion.draft(
            Ids.newUuidV7(), profileId, target, manifestJson, note, actor, clock.instant());
    if (!profiles.insertVersion(tenant, draft)) {
      throw new BusinessException(
          "POLICY-422-VERSION-EXISTS",
          "Số phiên bản " + target + " đã có",
          List.of(new ProblemFieldError("version", "Số phiên bản đã có", null)));
    }
    if (base != null) {
      profiles.replaceConstraints(
          tenant,
          draft.id(),
          profiles.constraints(tenant, base.id()).stream()
              .map(c -> c.copyTo(Ids.newUuidV7()))
              .toList(),
          actor);
    }
    audit.changed(
        tenant,
        actor,
        "TAO_PHIEN_BAN_HO_SO_KHACH_HANG",
        OBJECT_TYPE,
        key(profile, draft),
        draft.id(),
        draft.rowVersion());
    return view(tenant, draft);
  }

  /** Sửa bản kê; phiên bản {@code DAT_KIEM_THU} về {@code NHAP}. */
  @Transactional(PolicyPackService.TX)
  public VersionView update(
      TenantId tenant,
      UUID actor,
      UUID profileId,
      String version,
      long expected,
      Map<String, Object> manifest,
      String changeNote) {
    CustomerProfile profile = requireProfile(tenant, profileId);
    CustomerProfileVersion current = current(tenant, profileId, version, expected);
    transition(
        () -> {
          current.requireEditable();
          return current;
        });
    if (manifest == null) {
      throw invalid("manifest", "Cần nhập bản kê");
    }
    String note = changeNote == null || changeNote.isBlank() ? null : changeNote.strip();
    if (note != null && note.length() > TEXT_MAX) {
      throw invalid("changeNote", "Ghi chú thay đổi tối đa 2000 ký tự");
    }
    String manifestJson = manifestJson(tenant, manifest);
    CustomerProfileVersion edited =
        transition(() -> current.edit(manifestJson, note, actor, clock.instant()));
    store(tenant, edited, expected);
    audit.changed(
        tenant,
        actor,
        "SUA_PHIEN_BAN_HO_SO_KHACH_HANG",
        OBJECT_TYPE,
        key(profile, edited),
        edited.id(),
        edited.rowVersion());
    return view(tenant, edited);
  }

  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public List<ParamConstraint> constraints(TenantId tenant, UUID profileId, String version) {
    CustomerProfileVersion v =
        profiles.findVersion(tenant, profileId, version).orElseThrow(NoSuchElementException::new);
    return profiles.constraints(tenant, v.id());
  }

  /**
   * Thay toàn bộ khung tham số (BS-03 DC-02); cùng điều kiện sửa như bản kê: phiên bản {@code
   * DAT_KIEM_THU} về {@code NHAP}, từ {@code CHO_PHE_DUYET} là {@code POLICY-409-VERSION-FROZEN}.
   */
  @Transactional(PolicyPackService.TX)
  public VersionView replaceConstraints(
      TenantId tenant,
      UUID actor,
      UUID profileId,
      String version,
      long expected,
      List<ParamConstraint> items) {
    CustomerProfile profile = requireProfile(tenant, profileId);
    CustomerProfileVersion current = current(tenant, profileId, version, expected);
    List<ParamConstraint> checked = checkConstraints(items);
    CustomerProfileVersion edited =
        transition(() -> current.edit(current.manifestJson(), null, actor, clock.instant()));
    store(tenant, edited, expected);
    profiles.replaceConstraints(tenant, edited.id(), checked, actor);
    audit.changed(
        tenant,
        actor,
        "SUA_KHUNG_THAM_SO_HO_SO",
        OBJECT_TYPE,
        key(profile, edited),
        edited.id(),
        edited.rowVersion());
    return view(tenant, edited);
  }

  // ───── Vòng đời ─────

  /**
   * Kiểm tra: gói được trỏ tới phải đã kiểm thử đạt toàn bộ ca vàng ({@code DAT_KIEM_THU} trở lên,
   * CSDL buộc {@code golden_pass_rate = 1}) hoặc đã phát hành; đạt thì {@code NHAP} → {@code
   * DAT_KIEM_THU} kèm băm nội dung trên bản kê và khung tham số.
   */
  @Transactional(PolicyPackService.TX)
  public VersionView validate(
      TenantId tenant, UUID actor, UUID profileId, String version, long expected) {
    CustomerProfile profile = requireProfile(tenant, profileId);
    CustomerProfileVersion current = current(tenant, profileId, version, expected);
    if (current.status() != PolicyVersionStatus.NHAP) {
      throw new BusinessException(
          "POLICY-409-VERSION-FROZEN", "Chỉ kiểm tra được phiên bản đang soạn");
    }
    Map<String, Object> manifest = read(current.manifestJson());
    requireComponents(tenant, manifest, TESTED, "đã kiểm thử đạt hoặc đã phát hành");
    List<ParamConstraint> constraints = profiles.constraints(tenant, current.id());
    String hash = contentHash(manifest, constraints);
    CustomerProfileVersion validated =
        transition(() -> current.validated(hash, actor, clock.instant()));
    store(tenant, validated, expected);
    audit.changed(
        tenant,
        actor,
        "KIEM_TRA_HO_SO_KHACH_HANG",
        OBJECT_TYPE,
        key(profile, validated),
        validated.id(),
        validated.rowVersion());
    return view(tenant, validated);
  }

  /** Gửi phê duyệt: mọi thành phần phải đã phát hành ({@code POLICY-422-PROFILE-REF}). */
  @Transactional(PolicyPackService.TX)
  public VersionView submit(
      TenantId tenant, UUID actor, UUID profileId, String version, long expected, String comment) {
    CustomerProfile profile = requireProfile(tenant, profileId);
    CustomerProfileVersion current = current(tenant, profileId, version, expected);
    text(comment, "comment");
    CustomerProfileVersion submitted = transition(() -> current.submit(actor, clock.instant()));
    requireComponents(
        tenant,
        read(current.manifestJson()),
        Set.of(PolicyVersionStatus.DA_PHAT_HANH),
        "đã phát hành");
    store(tenant, submitted, expected);
    audit.changed(
        tenant,
        actor,
        "GUI_PHE_DUYET_HO_SO_KHACH_HANG",
        OBJECT_TYPE,
        key(profile, submitted),
        submitted.id(),
        submitted.rowVersion());
    return view(tenant, submitted);
  }

  /**
   * Ký số phê duyệt trên băm nội dung rồi phát hành: người duyệt khác người soạn (BR-15-09), phiên
   * AAL2 trong 15 phút, mọi thành phần đã phát hành. Phát tán đa cell do saga phát hành
   * (CORE-05-10); ở cell hiện tại phiên bản chuyển {@code DA_PHAT_HANH} ngay khi ký xong.
   */
  @Transactional(PolicyPackService.TX)
  public VersionView approve(
      TenantId tenant,
      Approver approver,
      UUID profileId,
      String version,
      long expected,
      UUID esignSessionId,
      String comment) {
    CustomerProfile profile = requireProfile(tenant, profileId);
    CustomerProfileVersion current = current(tenant, profileId, version, expected);
    transition(
        () -> {
          current.requireApprovableBy(approver.userId());
          return current;
        });
    text(comment, "comment");
    requireStepUp(approver);
    requireComponents(
        tenant,
        read(current.manifestJson()),
        Set.of(PolicyVersionStatus.DA_PHAT_HANH),
        "đã phát hành");
    SignedHash signed;
    try {
      signed =
          signer.sign(
              tenant,
              approver.userId(),
              current.id(),
              HexFormat.of().parseHex(current.contentHash()),
              esignSessionId);
    } catch (PolicyPackSigner.SigningFailedException e) {
      throw new BusinessException(
          "POLICY-409-NOT-SIGNED", "Chưa ký số được phiên bản, vui lòng thử lại");
    }
    CustomerProfileVersion approved =
        transition(() -> current.approve(approver.userId(), signed.signedAt(), clock.instant()));
    if (!profiles.storeApproval(tenant, approved, signed, expected)) {
      throw new VersionConflictException("POLICY", expected, null);
    }
    audit.changed(
        tenant,
        approver.userId(),
        "KY_PHE_DUYET_HO_SO_KHACH_HANG",
        OBJECT_TYPE,
        key(profile, approved),
        approved.id(),
        approved.rowVersion());
    evict(tenant);
    return view(tenant, approved);
  }

  /** Từ chối có ý kiến: {@code CHO_PHE_DUYET} → {@code NHAP}. */
  @Transactional(PolicyPackService.TX)
  public VersionView reject(
      TenantId tenant, UUID actor, UUID profileId, String version, long expected, String reason) {
    CustomerProfile profile = requireProfile(tenant, profileId);
    CustomerProfileVersion current = current(tenant, profileId, version, expected);
    String why = reason == null ? "" : reason.strip();
    if (why.length() < REASON_MIN || why.length() > TEXT_MAX) {
      throw invalid("reason", "Ý kiến từ chối cần từ 10 đến 2000 ký tự");
    }
    CustomerProfileVersion rejected = transition(() -> current.reject(actor, clock.instant()));
    store(tenant, rejected, expected);
    audit.changed(
        tenant,
        actor,
        "TU_CHOI_HO_SO_KHACH_HANG",
        OBJECT_TYPE,
        key(profile, rejected),
        rejected.id(),
        rejected.rowVersion());
    return view(tenant, rejected);
  }

  // ───── Ghim và phân giải ─────

  /**
   * Ghim phiên bản hồ sơ đã phát hành vào gắn của kỳ; kỳ đã khoá thì trigger CSDL từ chối. Bên ghim
   * là saga phát hành hoặc thao tác quản trị (chưa có điểm cuối riêng trong hợp đồng).
   */
  @Transactional(PolicyPackService.TX)
  public void pinProfile(TenantId tenant, UUID actor, UUID bindingId, UUID profileVersionId) {
    CustomerProfileVersion v =
        profiles.findVersionById(tenant, profileVersionId).orElseThrow(NoSuchElementException::new);
    if (v.status() != PolicyVersionStatus.DA_PHAT_HANH) {
      throw new BusinessException(
          "POLICY-422-PROFILE-REF", "Chỉ ghim được phiên bản hồ sơ đã phát hành");
    }
    if (!profiles.pinBinding(tenant, bindingId, profileVersionId, actor)) {
      throw new NoSuchElementException();
    }
    pinned.invalidate(new BindingKey(tenant, bindingId));
  }

  /** Phiên bản hồ sơ ghim ở gắn của gói đã phân giải; đệm theo gắn và theo phiên bản. */
  @Transactional(PolicyPackService.TX)
  public Optional<ResolvedProfile> resolve(
      TenantId tenant, PeriodId periodId, String flow, UUID subjectGroupId, UUID orgUnitId) {
    PolicyResolutionService r = resolution.get();
    if (r == null) {
      return Optional.empty();
    }
    return r.resolve(tenant, periodId, flow, subjectGroupId, orgUnitId)
        .flatMap(pack -> forBinding(tenant, pack));
  }

  /** Phiên bản hồ sơ ghim ở gắn {@code pack.bindingId()}. */
  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<UUID> pinnedVersion(TenantId tenant, UUID bindingId) {
    return pinned.get(
        new BindingKey(tenant, bindingId), k -> profiles.pinnedVersion(tenant, bindingId));
  }

  /** Bỏ đệm phân giải của tenant (phát hành hoặc ghim lại). */
  public void evict(TenantId tenant) {
    pinned.asMap().keySet().removeIf(k -> k.tenant().equals(tenant));
    resolved.asMap().keySet().removeIf(k -> k.tenant().equals(tenant));
  }

  private Optional<ResolvedProfile> forBinding(TenantId tenant, ResolvedPack pack) {
    return pinnedVersion(tenant, pack.bindingId()).flatMap(id -> profileVersion(tenant, id));
  }

  private Optional<ResolvedProfile> profileVersion(TenantId tenant, UUID versionId) {
    VersionKey key = new VersionKey(tenant, versionId);
    ResolvedProfile hit = resolved.getIfPresent(key);
    if (hit != null) {
      return Optional.of(hit);
    }
    Optional<ResolvedProfile> found =
        profiles
            .findVersionById(tenant, versionId)
            .flatMap(
                v -> profiles.findProfile(tenant, v.profileId()).map(p -> resolvedProfile(p, v)));
    found.ifPresent(p -> resolved.put(key, p));
    return found;
  }

  private ResolvedProfile resolvedProfile(CustomerProfile p, CustomerProfileVersion v) {
    Map<String, Object> manifest = read(v.manifestJson());
    Map<String, Object> components =
        manifest.get("components") instanceof Map<?, ?> c ? cast(c) : Map.of();
    return new ResolvedProfile(
        p.id(),
        p.code(),
        v.id(),
        v.version(),
        v.contentHash(),
        components,
        pluginPoints(manifest.get("pluginPoints")));
  }

  static PluginPoints pluginPoints(Object raw) {
    if (!(raw instanceof Map<?, ?> m)) {
      return PluginPoints.DEFAULTS;
    }
    return new PluginPoints(
        Boolean.TRUE.equals(m.get("workItemScore")),
        Boolean.TRUE.equals(m.get("boundedInheritance")),
        Boolean.TRUE.equals(m.get("responsibilityScope")),
        Boolean.TRUE.equals(m.get("milestoneMetric")),
        Boolean.TRUE.equals(m.get("classificationChain")),
        m.get("assignmentMode") instanceof String s ? s : null);
  }

  // ───── Kiểm khung tham số khi phát hành gói cấp dưới (DC-02) ─────

  /**
   * Gói cấp dưới (có gói cha) gửi phát hành: tìm hồ sơ đã phát hành trỏ tới gói cha và bật {@code
   * boundedInheritance}, đối chiếu tham số của phiên bản với phiên bản cha theo khung; vi phạm trả
   * {@code POLICY-422-PARAM-CONSTRAINT} nêu tham số, giá trị và khung. Không có hồ sơ hoặc khung
   * nào thì không kiểm, giữ đúng cờ {@code overridable}.
   */
  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public void checkInheritance(TenantId tenant, PolicyPack pack, PolicyPackVersion child) {
    if (!pack.inherits()) {
      return;
    }
    PolicyPack parent = packs.findPack(tenant, pack.parentPackId()).orElse(null);
    if (parent == null) {
      return;
    }
    PolicyPackVersion parentVersion = parentVersion(tenant, parent, child).orElse(null);
    if (parentVersion == null) {
      return;
    }
    Optional<CustomerProfileVersion> profile =
        boundingProfile(tenant, parent.code(), parentVersion.version());
    if (profile.isEmpty()) {
      return;
    }
    List<ParamConstraint> constraints = profiles.constraints(tenant, profile.get().id());
    if (constraints.isEmpty()) {
      return;
    }
    List<Violation> violations =
        ParamConstraintCheck.check(
            constraints, params(parentVersion.manifestJson()), params(child.manifestJson()), null);
    if (!violations.isEmpty()) {
      throw new BusinessException(
          "POLICY-422-PARAM-CONSTRAINT",
          violations.get(0).message(),
          violations.stream()
              .map(
                  v ->
                      new ProblemFieldError(
                          v.paramKey(),
                          v.message()
                              + " (giá trị "
                              + plain(v.value())
                              + ", khung "
                              + v.frame()
                              + ")",
                          "DC-02"))
              .toList());
    }
  }

  private Optional<PolicyPackVersion> parentVersion(
      TenantId tenant, PolicyPack parent, PolicyPackVersion child) {
    if (child.sourceVersionId() != null) {
      Optional<PolicyPackVersion> source = packs.findVersionById(tenant, child.sourceVersionId());
      if (source.isPresent() && source.get().packId().equals(parent.id())) {
        return source;
      }
    }
    return packs
        .listVersions(tenant, parent.id(), PolicyVersionStatus.DA_PHAT_HANH, null, 1)
        .stream()
        .findFirst();
  }

  /**
   * Hồ sơ đã phát hành mới nhất trỏ tới gói cha và bật DC-02; khớp cả phiên bản gói được ưu tiên.
   */
  private Optional<CustomerProfileVersion> boundingProfile(
      TenantId tenant, String parentCode, String parentVersion) {
    CustomerProfileVersion byCode = null;
    for (CustomerProfileVersion v : profiles.publishedVersions(tenant)) {
      Map<String, Object> manifest = read(v.manifestJson());
      if (!pluginPoints(manifest.get("pluginPoints")).boundedInheritance()) {
        continue;
      }
      Map<String, Object> ref = policyPackRef(manifest);
      if (ref == null || !parentCode.equals(ref.get("code"))) {
        continue;
      }
      if (parentVersion.equals(ref.get("version"))) {
        return Optional.of(v);
      }
      if (byCode == null) {
        byCode = v;
      }
    }
    return Optional.ofNullable(byCode);
  }

  // ───── Hỗ trợ ─────

  private VersionView view(TenantId tenant, CustomerProfileVersion v) {
    return new VersionView(v, profiles.countConstraints(tenant, v.id()));
  }

  private CustomerProfile requireProfile(TenantId tenant, UUID profileId) {
    return profiles.findProfile(tenant, profileId).orElseThrow(NoSuchElementException::new);
  }

  private CustomerProfileVersion current(
      TenantId tenant, UUID profileId, String version, long expected) {
    CustomerProfileVersion current =
        profiles.findVersion(tenant, profileId, version).orElseThrow(NoSuchElementException::new);
    if (current.rowVersion() != expected) {
      throw new VersionConflictException("POLICY", expected, current.rowVersion());
    }
    return current;
  }

  private void store(TenantId tenant, CustomerProfileVersion v, long expected) {
    if (!profiles.storeVersion(tenant, v, expected)) {
      throw new VersionConflictException("POLICY", expected, null);
    }
  }

  private String nextMinor(TenantId tenant, UUID profileId) {
    return profiles.versionNumbers(tenant, profileId).stream()
        .map(SemVer::parse)
        .flatMap(Optional::stream)
        .max(Comparator.naturalOrder())
        .map(SemVer::nextMinor)
        .map(SemVer::toString)
        .orElse("0.1.0");
  }

  /**
   * Kiểm dạng bản kê và sự tồn tại của gói được trỏ tới ({@code POLICY-422-PROFILE-REF}); trả JSON
   * để lưu.
   */
  private String manifestJson(TenantId tenant, Map<String, Object> manifest) {
    List<ProblemFieldError> errors = new ArrayList<>();
    Object components = manifest.get("components");
    if (!(components instanceof Map<?, ?>)) {
      errors.add(new ProblemFieldError("manifest.components", "Cần khai báo thành phần", null));
    }
    Map<String, Object> ref = policyPackRef(manifest);
    if (components instanceof Map<?, ?> && ref != null) {
      Object code = ref.get("code");
      Object version = ref.get("version");
      if (!(code instanceof String c) || c.isBlank() || c.length() > 100) {
        errors.add(
            new ProblemFieldError(
                "manifest.components.policyPack.code", "Cần mã bộ quy tắc", null));
      } else if (!(version instanceof String v) || !SEMVER.matcher(v).matches()) {
        errors.add(
            new ProblemFieldError(
                "manifest.components.policyPack.version",
                "Phiên bản bộ quy tắc phải có dạng 1.0.0",
                null));
      } else if (resolutions.versionByCode(tenant, c, v).isEmpty()) {
        errors.add(
            new ProblemFieldError(
                "manifest.components.policyPack",
                "Không tìm thấy bộ quy tắc " + c + " phiên bản " + v,
                null));
      }
      Object flow = ref.get("flow");
      if (flow != null && !FLOWS.contains(flow)) {
        errors.add(
            new ProblemFieldError(
                "manifest.components.policyPack.flow", "Luồng đánh giá không hợp lệ", null));
      }
    }
    Object points = manifest.get("pluginPoints");
    if (!(points instanceof Map<?, ?> pp)) {
      errors.add(new ProblemFieldError("manifest.pluginPoints", "Cần khai báo điểm cắm", null));
    } else {
      for (String flag : PLUGIN_FLAGS) {
        Object v = pp.get(flag);
        if (v != null && !(v instanceof Boolean)) {
          errors.add(
              new ProblemFieldError(
                  "manifest.pluginPoints." + flag, "Giá trị phải là đúng hoặc sai", null));
        }
      }
      Object mode = pp.get("assignmentMode");
      if (mode != null && !ASSIGNMENT_MODES.contains(mode)) {
        errors.add(
            new ProblemFieldError(
                "manifest.pluginPoints.assignmentMode", "Chế độ phân công không hợp lệ", null));
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "POLICY-422-PROFILE-REF", errors.get(0).message(), List.copyOf(errors));
    }
    try {
      return json.writeValueAsString(manifest);
    } catch (JsonProcessingException e) {
      throw invalid("manifest", "Bản kê không hợp lệ");
    }
  }

  /** Thành phần có trạng thái tra được (gói) phải ở một trong {@code allowed}. */
  private void requireComponents(
      TenantId tenant,
      Map<String, Object> manifest,
      Set<PolicyVersionStatus> allowed,
      String need) {
    Map<String, Object> ref = policyPackRef(manifest);
    if (ref == null) {
      return;
    }
    String code = String.valueOf(ref.get("code"));
    String version = String.valueOf(ref.get("version"));
    Optional<PublishedVersion> found = resolutions.versionByCode(tenant, code, version);
    if (found.isEmpty() || !allowed.contains(found.get().status())) {
      String message = "Bộ quy tắc " + code + " phiên bản " + version + " chưa " + need;
      throw new BusinessException(
          "POLICY-422-PROFILE-REF",
          message,
          List.of(new ProblemFieldError("manifest.components.policyPack", message, null)));
    }
  }

  private static List<ParamConstraint> checkConstraints(List<ParamConstraint> items) {
    if (items == null) {
      throw invalid("items", "Cần danh sách khung tham số");
    }
    if (items.size() > CONSTRAINTS_MAX) {
      throw invalid("items", "Tối đa 500 khung tham số");
    }
    Set<String> keys = new HashSet<>();
    List<ParamConstraint> out = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      ParamConstraint c = items.get(i);
      String field = "items[" + i + "]";
      if (c.paramKey().isBlank() || c.paramKey().length() > 128) {
        throw invalid(field + ".paramKey", "Cần khoá tham số, tối đa 128 ký tự");
      }
      if (!keys.add(c.paramKey())) {
        throw invalid(field + ".paramKey", "Khoá tham số bị trùng");
      }
      if (!ParamConstraint.DIRECTIONS.contains(c.tightenDirection())) {
        throw invalid(field + ".tightenDirection", "Chiều siết không hợp lệ");
      }
      if (!ParamConstraint.SCOPES.containsAll(c.allowedScopes())) {
        throw invalid(field + ".allowedScopes", "Cấp được chỉnh không hợp lệ");
      }
      BigDecimal min = c.minValue();
      BigDecimal max = c.maxValue();
      if (min != null && max != null && min.compareTo(max) > 0) {
        throw invalid(field + ".minValue", "Giá trị tối thiểu không được lớn hơn tối đa");
      }
      out.add(c.copyTo(Ids.newUuidV7()));
    }
    return out;
  }

  /** SHA-256 trên dạng chuẩn hoá của bản kê và khung tham số sắp theo khoá. */
  String contentHash(Map<String, Object> manifest, List<ParamConstraint> constraints) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("manifest", manifest);
    List<Map<String, Object>> list = new ArrayList<>();
    constraints.stream()
        .sorted(Comparator.comparing(ParamConstraint::paramKey))
        .forEach(
            c -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("param_key", c.paramKey());
              m.put("can_toggle", c.canToggle());
              m.put("can_change_levels", c.canChangeLevels());
              m.put("min_value", c.minValue() == null ? null : c.minValue().toPlainString());
              m.put("max_value", c.maxValue() == null ? null : c.maxValue().toPlainString());
              m.put("tighten_direction", c.tightenDirection());
              m.put("allowed_scopes", c.allowedScopes().stream().sorted().toList());
              list.add(m);
            });
    content.put("param_constraints", list);
    return PolicyContentHash.sha256(CanonicalJson.write(content));
  }

  private static Map<String, Object> policyPackRef(Map<String, Object> manifest) {
    return manifest.get("components") instanceof Map<?, ?> c
            && c.get("policyPack") instanceof Map<?, ?> p
        ? cast(p)
        : null;
  }

  private Map<?, ?> params(String manifestJson) {
    Object params = read(manifestJson).get("params");
    return params instanceof Map<?, ?> m ? m : Map.of();
  }

  private Map<String, Object> read(String text) {
    try {
      Map<String, Object> m = json.readValue(text, MAP);
      return m == null ? Map.of() : m;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Bản kê không đọc được", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }

  private void requireStepUp(Approver approver) {
    long maxAge = STEP_UP_WINDOW.toSeconds();
    if (!StepUpRequiredException.REQUIRED_ACR.equals(
        AuthzSubjectResolver.normalizeAcr(approver.acr()))) {
      throw new StepUpRequiredException(
          "approveCustomerProfileVersion",
          maxAge,
          "Ký phê duyệt cần xác thực nhiều lớp trong 15 phút gần nhất.");
    }
    long age =
        clock.instant().getEpochSecond() - (approver.authTime() == null ? 0 : approver.authTime());
    if (approver.authTime() == null || age > maxAge || age < -30) {
      throw new StepUpRequiredException(
          "approveCustomerProfileVersion",
          maxAge,
          "Phiên xác thực nhiều lớp đã quá 15 phút, cần xác thực lại.");
    }
  }

  private static List<String> texts(List<String> values, String field, int max) {
    if (values == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String v : values) {
      String s = v == null ? "" : v.strip();
      if (s.isEmpty() || s.length() > max) {
        throw invalid(field, "Mỗi giá trị cần có nội dung, tối đa " + max + " ký tự");
      }
      out.add(s);
    }
    return out;
  }

  private static void text(String value, String field) {
    if (value != null && value.strip().length() > TEXT_MAX) {
      throw invalid(field, "Ý kiến tối đa 2000 ký tự");
    }
  }

  private static String plain(Object value) {
    if (value instanceof BigDecimal d) {
      return d.stripTrailingZeros().toPlainString().replace('.', ',');
    }
    return String.valueOf(value);
  }

  private static CustomerProfileVersion transition(Supplier<CustomerProfileVersion> move) {
    try {
      return move.get();
    } catch (PolicyTransitionException e) {
      throw new BusinessException(e.code(), e.getMessage());
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static String key(CustomerProfile profile, CustomerProfileVersion v) {
    return profile.code() + "@" + v.version();
  }
}
