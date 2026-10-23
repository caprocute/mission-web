package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedProfile;
import vn.viettel.okrkpi.policy.application.CustomerProfileService.CreateProfile;
import vn.viettel.okrkpi.policy.application.CustomerProfileService.VersionView;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService.Approver;
import vn.viettel.okrkpi.policy.application.port.CustomerProfileRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner.SignedHash;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository.PublishedVersion;
import vn.viettel.okrkpi.policy.domain.CustomerProfile;
import vn.viettel.okrkpi.policy.domain.CustomerProfileVersion;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.ParamConstraint;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Dịch vụ hồ sơ tập khách hàng trên kho trong bộ nhớ (CORE-05-12, BS-03 ADR-33, DC-02). */
class CustomerProfileServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
  private static final String PACK = "goi-mau/diem";

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();
  private final UUID approver = UUID.randomUUID();
  private final ObjectMapper json = new ObjectMapper();

  private MemoryProfiles repo;
  private PolicyPackRepository packs;
  private PolicyResolutionRepository resolutions;
  private PolicyResolutionService resolution;
  private PolicyPackSigner signer;
  private CustomerProfileService service;
  private PolicyVersionStatus packStatus;

  @BeforeEach
  void setUp() {
    repo = new MemoryProfiles();
    packs = mock(PolicyPackRepository.class);
    resolutions = mock(PolicyResolutionRepository.class);
    resolution = mock(PolicyResolutionService.class);
    signer = mock(PolicyPackSigner.class);
    packStatus = PolicyVersionStatus.DAT_KIEM_THU;
    when(resolutions.versionByCode(any(), eq(PACK), eq("1.0.0")))
        .thenAnswer(
            i ->
                Optional.of(
                    new PublishedVersion(
                        UUID.randomUUID(),
                        PACK,
                        "GOC_TOAN_CUC",
                        "DANG",
                        UUID.randomUUID(),
                        "1.0.0",
                        packStatus,
                        "ab",
                        null,
                        null,
                        "{}")));
    when(resolutions.versionByCode(any(), eq("khong-co"), anyString()))
        .thenReturn(Optional.empty());
    when(signer.sign(any(), any(), any(), any(), any()))
        .thenReturn(new SignedHash(new byte[] {1}, UUID.randomUUID(), NOW, null));
    Clock clock = () -> NOW;
    service =
        new CustomerProfileService(
            repo,
            packs,
            resolutions,
            () -> resolution,
            signer,
            mock(PolicyAudit.class),
            json,
            clock,
            Duration.ofMinutes(5));
  }

  private static Map<String, Object> manifest(String code, Map<String, Object> points) {
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("code", code);
    ref.put("version", "1.0.0");
    ref.put("flow", "DANG");
    Map<String, Object> components = new LinkedHashMap<>();
    components.put("policyPack", ref);
    components.put("catalog", null);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("components", components);
    m.put("pluginPoints", points);
    return m;
  }

  private UUID profile(String code) {
    return service
        .newProfile(tenant, author, new CreateProfile(code, "Hồ sơ " + code, true, null, null))
        .profile()
        .id();
  }

  private static ParamConstraint frame(String key, String min, String max) {
    return new ParamConstraint(
        null,
        key,
        false,
        false,
        min == null ? null : new BigDecimal(min),
        max == null ? null : new BigDecimal(max),
        "KHONG",
        List.of("KHOI"));
  }

  private String code(Runnable r) {
    try {
      r.run();
    } catch (BusinessException e) {
      return e.errorCode().code();
    }
    throw new AssertionError("Không có lỗi nghiệp vụ");
  }

  private VersionView publish(UUID profileId, Map<String, Object> points) {
    VersionView v =
        service.newVersion(tenant, author, profileId, null, "1.0.0", "Đầu", manifest(PACK, points));
    v = service.validate(tenant, author, profileId, "1.0.0", v.version().rowVersion());
    packStatus = PolicyVersionStatus.DA_PHAT_HANH;
    v = service.submit(tenant, author, profileId, "1.0.0", v.version().rowVersion(), null);
    return service.approve(
        tenant,
        new Approver(approver, "aal2", NOW.getEpochSecond() - 30),
        profileId,
        "1.0.0",
        v.version().rowVersion(),
        null,
        "Đồng ý");
  }

  @Test
  void taoHoSoKiemDangVaMaTrung() {
    assertThat(
            code(
                () ->
                    service.newProfile(
                        tenant, author, new CreateProfile("Ma Sai", "Tên", true, null, null))))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            code(
                () ->
                    service.newProfile(
                        tenant, author, new CreateProfile("mau/a", " ", true, null, null))))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            code(
                () ->
                    service.newProfile(
                        tenant, author, new CreateProfile("mau/a", "Tên", null, null, null))))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            code(
                () ->
                    service.newProfile(
                        tenant,
                        author,
                        new CreateProfile("mau/a", "Tên", true, List.of(""), null))))
        .isEqualTo("COMMON-422-VALIDATION");
    UUID id = profile("mau/a");
    assertThat(code(() -> profile("mau/a"))).isEqualTo("POLICY-422-PROFILE-CODE-EXISTS");
    assertThat(service.findProfile(tenant, id)).isPresent();
    assertThat(service.listProfiles(tenant, null, null, 10)).hasSize(1);
  }

  @Test
  void phienBanKiemThamChieuVaNhanBanKhung() {
    UUID id = profile("mau/b");
    assertThat(code(() -> service.newVersion(tenant, author, id, null, null, " ", null)))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(code(() -> service.newVersion(tenant, author, id, null, null, "Đầu", null)))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(code(() -> service.newVersion(tenant, author, id, "x", null, "Đầu", null)))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(code(() -> service.newVersion(tenant, author, id, "9.9.9", null, "Đầu", null)))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            code(
                () ->
                    service.newVersion(
                        tenant, author, id, null, "1", "Đầu", manifest(PACK, Map.of()))))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            code(
                () ->
                    service.newVersion(
                        tenant, author, id, null, null, "Đầu", manifest("khong-co", Map.of()))))
        .isEqualTo("POLICY-422-PROFILE-REF");
    assertThat(
            code(
                () ->
                    service.newVersion(
                        tenant,
                        author,
                        id,
                        null,
                        null,
                        "Đầu",
                        manifest(PACK, Map.of("workItemScore", "co", "assignmentMode", "X")))))
        .isEqualTo("POLICY-422-PROFILE-REF");
    assertThat(
            code(
                () ->
                    service.newVersion(
                        tenant, author, id, null, null, "Đầu", Map.of("pluginPoints", "x"))))
        .isEqualTo("POLICY-422-PROFILE-REF");

    VersionView first =
        service.newVersion(tenant, author, id, null, null, "Đầu", manifest(PACK, Map.of()));
    assertThat(first.version().version()).isEqualTo("0.1.0");
    assertThat(
            code(
                () ->
                    service.newVersion(
                        tenant, author, id, null, "0.1.0", "Trùng", manifest(PACK, Map.of()))))
        .isEqualTo("POLICY-422-VERSION-EXISTS");
    assertThat(
            code(
                () ->
                    service.replaceConstraints(
                        tenant, author, id, "0.1.0", 0, List.of(frame("tham_so.a", "2", "1")))))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            code(
                () ->
                    service.replaceConstraints(
                        tenant,
                        author,
                        id,
                        "0.1.0",
                        0,
                        List.of(frame("tham_so.a", null, null), frame("tham_so.a", null, null)))))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(code(() -> service.replaceConstraints(tenant, author, id, "0.1.0", 0, null)))
        .isEqualTo("COMMON-422-VALIDATION");
    VersionView framed =
        service.replaceConstraints(
            tenant, author, id, "0.1.0", 0, List.of(frame("tham_so.he_so_nhom", "1.0", "1.5")));
    assertThat(framed.constraintCount()).isEqualTo(1);
    assertThat(framed.version().rowVersion()).isEqualTo(1);
    assertThatThrownBy(() -> service.replaceConstraints(tenant, author, id, "0.1.0", 0, List.of()))
        .isInstanceOf(VersionConflictException.class);

    VersionView copy = service.newVersion(tenant, author, id, "0.1.0", null, "Bản sau", null);
    assertThat(copy.version().version()).isEqualTo("0.2.0");
    assertThat(copy.constraintCount()).isEqualTo(1);
    assertThat(service.constraints(tenant, id, "0.2.0"))
        .singleElement()
        .extracting(ParamConstraint::paramKey)
        .isEqualTo("tham_so.he_so_nhom");
    assertThat(service.listVersions(tenant, id, null, null, 10)).hasSize(2);
    assertThat(service.findVersion(tenant, id, "0.2.0")).isPresent();
    assertThatThrownBy(() -> service.listVersions(tenant, UUID.randomUUID(), null, null, 10))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void vongDoiChanThanhPhanChuaPhatHanhVaKyHaiNguoi() {
    UUID id = profile("mau/c");
    VersionView v =
        service.newVersion(
            tenant,
            author,
            id,
            null,
            "1.0.0",
            "Đầu",
            manifest(PACK, Map.of("workItemScore", true)));
    VersionView tested = service.validate(tenant, author, id, "1.0.0", 0);
    assertThat(tested.version().status()).isEqualTo(PolicyVersionStatus.DAT_KIEM_THU);
    assertThat(tested.version().contentHash()).hasSize(64);
    assertThat(code(() -> service.validate(tenant, author, id, "1.0.0", 1)))
        .isEqualTo("POLICY-409-VERSION-FROZEN");

    VersionView edited =
        service.update(tenant, author, id, "1.0.0", 1, manifest(PACK, Map.of()), "Sửa");
    assertThat(edited.version().status()).isEqualTo(PolicyVersionStatus.NHAP);
    assertThat(code(() -> service.update(tenant, author, id, "1.0.0", 2, null, null)))
        .isEqualTo("COMMON-422-VALIDATION");
    service.validate(tenant, author, id, "1.0.0", 2);

    assertThat(code(() -> service.submit(tenant, author, id, "1.0.0", 3, null)))
        .isEqualTo("POLICY-422-PROFILE-REF");
    packStatus = PolicyVersionStatus.DA_PHAT_HANH;
    assertThat(code(() -> service.submit(tenant, author, id, "1.0.0", 3, "x".repeat(2001))))
        .isEqualTo("COMMON-422-VALIDATION");
    VersionView pending = service.submit(tenant, author, id, "1.0.0", 3, "Trình");
    assertThat(pending.version().status()).isEqualTo(PolicyVersionStatus.CHO_PHE_DUYET);
    assertThat(
            code(
                () ->
                    service.update(tenant, author, id, "1.0.0", 4, manifest(PACK, Map.of()), null)))
        .isEqualTo("POLICY-409-VERSION-FROZEN");

    assertThat(
            code(
                () ->
                    service.approve(
                        tenant,
                        new Approver(author, "aal2", NOW.getEpochSecond()),
                        id,
                        "1.0.0",
                        4,
                        null,
                        null)))
        .isEqualTo("POLICY-403-SAME-AUTHOR");
    assertThatThrownBy(
            () ->
                service.approve(
                    tenant, new Approver(approver, null, null), id, "1.0.0", 4, null, null))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                service.approve(
                    tenant,
                    new Approver(approver, "aal2", NOW.getEpochSecond() - 3600),
                    id,
                    "1.0.0",
                    4,
                    null,
                    null))
        .isInstanceOf(StepUpRequiredException.class);
    when(signer.sign(any(), any(), any(), any(), any()))
        .thenThrow(new PolicyPackSigner.SigningFailedException("lỗi", null));
    assertThat(
            code(
                () ->
                    service.approve(
                        tenant,
                        new Approver(approver, "aal2", NOW.getEpochSecond()),
                        id,
                        "1.0.0",
                        4,
                        null,
                        null)))
        .isEqualTo("POLICY-409-NOT-SIGNED");

    assertThat(code(() -> service.reject(tenant, approver, id, "1.0.0", 4, "ngắn")))
        .isEqualTo("COMMON-422-VALIDATION");
    VersionView rejected =
        service.reject(tenant, approver, id, "1.0.0", 4, "Cần rà lại thành phần bộ quy tắc");
    assertThat(rejected.version().status()).isEqualTo(PolicyVersionStatus.NHAP);
  }

  @Test
  void ghimVaPhanGiaiTraCoDiemCam() {
    UUID id = profile("mau/d");
    VersionView published =
        publish(id, Map.of("milestoneMetric", true, "assignmentMode", "MOT_NGUOI_MOT_VIEC"));
    assertThat(published.version().status()).isEqualTo(PolicyVersionStatus.DA_PHAT_HANH);
    UUID binding = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    when(resolution.resolve(tenant, PeriodId.of(period), "DANG", null, null))
        .thenReturn(
            Optional.of(
                new ResolvedPack(
                    UUID.randomUUID(),
                    PACK,
                    UUID.randomUUID(),
                    "1.0.0",
                    "ab",
                    null,
                    binding,
                    false)));
    assertThat(service.resolve(tenant, PeriodId.of(period), "DANG", null, null)).isEmpty();
    repo.bindings.put(binding, null);
    service.pinProfile(tenant, author, binding, published.version().id());
    ResolvedProfile r =
        service.resolve(tenant, PeriodId.of(period), "DANG", null, null).orElseThrow();
    assertThat(r.code()).isEqualTo("mau/d");
    assertThat(r.pluginPoints().milestoneMetric()).isTrue();
    assertThat(r.pluginPoints().assignmentMode()).isEqualTo("MOT_NGUOI_MOT_VIEC");
    assertThat(service.resolve(tenant, PeriodId.of(period), "DANG", null, null)).contains(r);

    VersionView draft = service.newVersion(tenant, author, id, "1.0.0", null, "Nháp", null);
    assertThat(code(() -> service.pinProfile(tenant, author, binding, draft.version().id())))
        .isEqualTo("POLICY-422-PROFILE-REF");
    assertThatThrownBy(
            () -> service.pinProfile(tenant, author, UUID.randomUUID(), published.version().id()))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(CustomerProfileService.pluginPoints("x").workItemScore()).isFalse();
  }

  @Test
  void khungThamSoChanGoiCapDuoiNgoaiKhung() {
    UUID id = profile("mau/e");
    VersionView v =
        service.newVersion(
            tenant,
            author,
            id,
            null,
            "1.0.0",
            "Đầu",
            manifest(PACK, Map.of("boundedInheritance", true)));
    service.replaceConstraints(
        tenant, author, id, "1.0.0", 0, List.of(frame("tham_so.he_so_nhom", "1.0", "1.5")));
    VersionView tested = service.validate(tenant, author, id, "1.0.0", 1);
    packStatus = PolicyVersionStatus.DA_PHAT_HANH;
    VersionView pending = service.submit(tenant, author, id, "1.0.0", 2, null);
    service.approve(
        tenant, new Approver(approver, "aal2", NOW.getEpochSecond()), id, "1.0.0", 3, null, null);

    PolicyPack parent = pack(PACK, null);
    PolicyPack child = pack("goi-mau/cap-duoi", parent.id());
    PolicyPackVersion parentVersion = packVersion(parent.id(), "1.0.0", 1.0, null);
    when(packs.findPack(tenant, parent.id())).thenReturn(Optional.of(parent));
    when(packs.findVersionById(tenant, parentVersion.id())).thenReturn(Optional.of(parentVersion));

    PolicyPackVersion outOfFrame = packVersion(child.id(), "1.0.0", 1.6, parentVersion.id());
    assertThatThrownBy(() -> service.checkInheritance(tenant, child, outOfFrame))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo("POLICY-422-PARAM-CONSTRAINT");
              assertThat(e.errors().get(0).field()).isEqualTo("tham_so.he_so_nhom");
              assertThat(e.errors().get(0).message()).contains("1,6").contains("[1; 1,5]");
            });
    service.checkInheritance(
        tenant, child, packVersion(child.id(), "1.1.0", 1.2, parentVersion.id()));
    service.checkInheritance(tenant, parent, outOfFrame);

    when(packs.listVersions(tenant, parent.id(), PolicyVersionStatus.DA_PHAT_HANH, null, 1))
        .thenReturn(List.of(parentVersion));
    assertThatThrownBy(
            () ->
                service.checkInheritance(
                    tenant, child, packVersion(child.id(), "1.2.0", 1.6, null)))
        .isInstanceOf(BusinessException.class);
    PolicyPack orphan = pack("goi-mau/mo-coi", UUID.randomUUID());
    service.checkInheritance(tenant, orphan, outOfFrame);
    assertThat(v.version().id()).isEqualTo(tested.version().id()).isEqualTo(pending.version().id());
  }

  private PolicyPack pack(String code, UUID parent) {
    return new PolicyPack(
        UUID.randomUUID(),
        code,
        "Gói " + code,
        "KHOI_DANG",
        "DANG",
        parent == null ? PackOrigin.GOC_TOAN_CUC : PackOrigin.TENANT,
        parent,
        "Giả lập",
        NOW,
        author,
        0);
  }

  private PolicyPackVersion packVersion(UUID packId, String version, double heSo, UUID source) {
    return new PolicyPackVersion(
        UUID.randomUUID(),
        packId,
        version,
        PolicyVersionStatus.DAT_KIEM_THU,
        null,
        "{\"params\":{\"he_so_nhom\":{\"kieu\":\"so\",\"mac_dinh\":" + heSo + "}}}",
        "ab",
        BigDecimal.ONE,
        null,
        false,
        null,
        null,
        null,
        null,
        source,
        "Ghi chú",
        NOW,
        author,
        NOW,
        author,
        0);
  }

  /** Kho trong bộ nhớ đủ cho dịch vụ. */
  static final class MemoryProfiles implements CustomerProfileRepository {
    final Map<UUID, CustomerProfile> profiles = new LinkedHashMap<>();
    final Map<UUID, CustomerProfileVersion> versions = new LinkedHashMap<>();
    final Map<UUID, List<ParamConstraint>> constraints = new LinkedHashMap<>();
    final Map<UUID, UUID> bindings = new LinkedHashMap<>();

    @Override
    public List<CustomerProfile> listProfiles(
        TenantId tenant, String query, String afterCode, int limit) {
      return List.copyOf(profiles.values());
    }

    @Override
    public Optional<CustomerProfile> findProfile(TenantId tenant, UUID profileId) {
      return Optional.ofNullable(profiles.get(profileId));
    }

    @Override
    public boolean insertProfile(TenantId tenant, CustomerProfile profile) {
      if (profiles.values().stream().anyMatch(p -> p.code().equals(profile.code()))) {
        return false;
      }
      profiles.put(profile.id(), profile);
      return true;
    }

    @Override
    public Map<UUID, List<CustomerProfileVersion>> recentVersions(
        TenantId tenant, Collection<UUID> profileIds, int perProfile) {
      Map<UUID, List<CustomerProfileVersion>> out = new LinkedHashMap<>();
      versions.values().stream()
          .filter(v -> profileIds.contains(v.profileId()))
          .forEach(v -> out.computeIfAbsent(v.profileId(), k -> new ArrayList<>()).add(v));
      return out;
    }

    @Override
    public List<CustomerProfileVersion> listVersions(
        TenantId tenant, UUID profileId, PolicyVersionStatus status, UUID afterId, int limit) {
      return versions.values().stream().filter(v -> v.profileId().equals(profileId)).toList();
    }

    @Override
    public Optional<CustomerProfileVersion> findVersion(
        TenantId tenant, UUID profileId, String version) {
      return versions.values().stream()
          .filter(v -> v.profileId().equals(profileId) && v.version().equals(version))
          .findFirst();
    }

    @Override
    public Optional<CustomerProfileVersion> findVersionById(TenantId tenant, UUID versionId) {
      return Optional.ofNullable(versions.get(versionId));
    }

    @Override
    public List<String> versionNumbers(TenantId tenant, UUID profileId) {
      return listVersions(tenant, profileId, null, null, 0).stream()
          .map(CustomerProfileVersion::version)
          .toList();
    }

    @Override
    public boolean insertVersion(TenantId tenant, CustomerProfileVersion version) {
      if (findVersion(tenant, version.profileId(), version.version()).isPresent()) {
        return false;
      }
      versions.put(version.id(), version);
      return true;
    }

    @Override
    public boolean storeVersion(TenantId tenant, CustomerProfileVersion version, long expected) {
      CustomerProfileVersion old = versions.get(version.id());
      if (old == null || old.rowVersion() != expected) {
        return false;
      }
      versions.put(version.id(), version);
      return true;
    }

    @Override
    public boolean storeApproval(
        TenantId tenant, CustomerProfileVersion version, SignedHash signed, long expected) {
      return storeVersion(tenant, version, expected);
    }

    @Override
    public List<ParamConstraint> constraints(TenantId tenant, UUID versionId) {
      return constraints.getOrDefault(versionId, List.of()).stream()
          .sorted(Comparator.comparing(ParamConstraint::paramKey))
          .toList();
    }

    @Override
    public int countConstraints(TenantId tenant, UUID versionId) {
      return constraints.getOrDefault(versionId, List.of()).size();
    }

    @Override
    public void replaceConstraints(
        TenantId tenant, UUID versionId, List<ParamConstraint> items, UUID actor) {
      constraints.put(versionId, List.copyOf(items));
    }

    @Override
    public List<CustomerProfileVersion> publishedVersions(TenantId tenant) {
      return versions.values().stream()
          .filter(v -> v.status() == PolicyVersionStatus.DA_PHAT_HANH)
          .toList();
    }

    @Override
    public boolean pinBinding(TenantId tenant, UUID bindingId, UUID profileVersionId, UUID actor) {
      if (!bindings.containsKey(bindingId)) {
        return false;
      }
      bindings.put(bindingId, profileVersionId);
      return true;
    }

    @Override
    public Optional<UUID> pinnedVersion(TenantId tenant, UUID bindingId) {
      return Optional.ofNullable(bindings.get(bindingId));
    }
  }
}
