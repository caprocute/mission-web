package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.policy.application.PolicyPackService.PackImport;
import vn.viettel.okrkpi.policy.application.PolicyPackService.VersionView;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyManifestRules;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nhân bản, chuyển trạng thái, kế thừa {@code overridable} và nhập gói (CORE-05-02). */
class PolicyPackServiceTest {

  private static final Instant AT = Instant.parse("2026-10-22T02:00:00Z");
  private static final UUID ACTOR = UUID.randomUUID();

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final PolicyPackRepository repo = mock(PolicyPackRepository.class);
  private final PolicyAudit audit = mock(PolicyAudit.class);
  private final PolicyPackService service =
      new PolicyPackService(repo, audit, new ObjectMapper(), Clock.fixed(AT));

  private final PolicyPack root = pack("khoi-chinh-quyen/nd335", PackOrigin.GOC_TOAN_CUC, null);
  private final PolicyPack child = pack("tenant-a/nd335", PackOrigin.TENANT, root.id());

  private static PolicyPack pack(String code, PackOrigin origin, UUID parent) {
    return new PolicyPack(
        UUID.randomUUID(),
        code,
        "Gói",
        "KHOI_CHINH_QUYEN",
        "NHA_NUOC",
        origin,
        parent,
        "NĐ 335",
        AT,
        ACTOR,
        0);
  }

  private static PolicyPackVersion version(
      PolicyPack pack, String v, PolicyVersionStatus status, UUID source) {
    return new PolicyPackVersion(
        UUID.randomUUID(),
        pack.id(),
        v,
        status,
        null,
        "{\"a\":1}",
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        source,
        "Ghi chú",
        AT,
        ACTOR,
        AT,
        ACTOR,
        3);
  }

  private static PolicyRule rule(UUID versionId, String key, boolean overridable) {
    return new PolicyRule(
        UUID.randomUUID(), versionId, key, "1.0", "DECIMAL", "{}", "PL 3", 1, "{}", overridable);
  }

  @Test
  void newVersionClonesRulesTablesAndManifest() {
    PolicyPackVersion base = version(root, "1.4.0", PolicyVersionStatus.DA_PHAT_HANH, null);
    when(repo.findPack(tenant, root.id())).thenReturn(Optional.of(root));
    when(repo.findVersion(tenant, root.id(), "1.4.0")).thenReturn(Optional.of(base));
    when(repo.insertVersion(eq(tenant), any())).thenReturn(true);
    when(repo.rules(tenant, base.id()))
        .thenReturn(List.of(rule(base.id(), "A", false), rule(base.id(), "B", true)));
    when(repo.decisionTables(tenant, base.id()))
        .thenReturn(
            List.of(
                new DecisionTable(
                    UUID.randomUUID(),
                    base.id(),
                    "xep_loai",
                    "<dmn/>",
                    "FIRST",
                    new byte[] {1},
                    "PL 5",
                    "[]",
                    "{}")));

    VersionView view = service.newVersion(tenant, ACTOR, root.id(), "1.4.0", "1.5.0", " Sửa ");

    PolicyPackVersion draft = view.version();
    assertThat(draft.status()).isEqualTo(PolicyVersionStatus.NHAP);
    assertThat(draft.version()).isEqualTo("1.5.0");
    assertThat(draft.manifestJson()).isEqualTo(base.manifestJson());
    assertThat(draft.changeNote()).isEqualTo("Sửa");
    assertThat(draft.sourceVersionId()).isNull();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PolicyRule>> rules = ArgumentCaptor.forClass(List.class);
    verify(repo).insertRules(eq(tenant), rules.capture(), eq(ACTOR));
    assertThat(rules.getValue())
        .extracting(PolicyRule::ruleKey, PolicyRule::versionId, PolicyRule::overridable)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("A", draft.id(), false),
            org.assertj.core.groups.Tuple.tuple("B", draft.id(), true));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DecisionTable>> tables = ArgumentCaptor.forClass(List.class);
    verify(repo).insertDecisionTables(eq(tenant), tables.capture(), eq(ACTOR));
    assertThat(tables.getValue())
        .singleElement()
        .extracting(DecisionTable::versionId)
        .isEqualTo(draft.id());
    verify(audit)
        .changed(
            tenant,
            ACTOR,
            "TAO_PHIEN_BAN_POLICY_PACK",
            PolicyPackService.OBJECT_TYPE,
            "khoi-chinh-quyen/nd335@1.5.0",
            draft.id(),
            0);
  }

  @Test
  void newVersionWithoutNumberBumpsHighestMinorOfSameMajor() {
    PolicyPackVersion base = version(root, "2.0.0", PolicyVersionStatus.DA_PHAT_HANH, null);
    when(repo.findPack(tenant, root.id())).thenReturn(Optional.of(root));
    when(repo.findVersion(tenant, root.id(), "2.0.0")).thenReturn(Optional.of(base));
    when(repo.versionNumbers(tenant, root.id()))
        .thenReturn(List.of("1.9.0", "2.0.0", "2.3.1", "3.0.0", "rác"));
    when(repo.insertVersion(eq(tenant), any())).thenReturn(true);

    assertThat(service.newVersion(tenant, ACTOR, root.id(), "2.0.0", null, "x").version().version())
        .isEqualTo("2.4.0");
  }

  @Test
  void tenantPackClonesFromParentAndRemembersSource() {
    PolicyPackVersion parentVersion =
        version(root, "1.4.0", PolicyVersionStatus.DA_PHAT_HANH, null);
    when(repo.findPack(tenant, child.id())).thenReturn(Optional.of(child));
    when(repo.findVersion(tenant, child.id(), "1.4.0")).thenReturn(Optional.empty());
    when(repo.findVersion(tenant, root.id(), "1.4.0")).thenReturn(Optional.of(parentVersion));
    when(repo.insertVersion(eq(tenant), any())).thenReturn(true);

    VersionView view = service.newVersion(tenant, ACTOR, child.id(), "1.4.0", "1.4.1", "Riêng");
    assertThat(view.version().sourceVersionId()).isEqualTo(parentVersion.id());
    assertThat(view.version().inheritsFromParent()).isTrue();

    PolicyPackVersion own = version(child, "1.4.1", PolicyVersionStatus.NHAP, parentVersion.id());
    when(repo.findVersion(tenant, child.id(), "1.4.1")).thenReturn(Optional.of(own));
    VersionView next = service.newVersion(tenant, ACTOR, child.id(), "1.4.1", "1.4.2", "Tiếp");
    assertThat(next.version().sourceVersionId()).isEqualTo(parentVersion.id());
  }

  @Test
  void newVersionRejectsBadInput() {
    when(repo.findPack(tenant, root.id())).thenReturn(Optional.of(root));
    when(repo.findVersion(eq(tenant), any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.newVersion(tenant, ACTOR, root.id(), "1.0.0", null, " "))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.newVersion(tenant, ACTOR, root.id(), "1.0.0", null, null))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () -> service.newVersion(tenant, ACTOR, root.id(), "1.0.0", null, "x".repeat(2001)))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.newVersion(tenant, ACTOR, root.id(), "1.0", null, "x"))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.newVersion(tenant, ACTOR, root.id(), "1.0.0", "2", "x"))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.newVersion(tenant, ACTOR, root.id(), "1.0.0", null, "x"))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(((BusinessException) e).detail()).contains("1.0.0"));
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(() -> service.newVersion(tenant, ACTOR, missing, "1.0.0", null, "x"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void duplicateVersionNumberIsRejected() {
    PolicyPackVersion base = version(root, "1.4.0", PolicyVersionStatus.DA_PHAT_HANH, null);
    when(repo.findPack(tenant, root.id())).thenReturn(Optional.of(root));
    when(repo.findVersion(tenant, root.id(), "1.4.0")).thenReturn(Optional.of(base));
    when(repo.insertVersion(eq(tenant), any())).thenReturn(false);
    assertThatThrownBy(() -> service.newVersion(tenant, ACTOR, root.id(), "1.4.0", "1.4.0", "x"))
        .hasMessage("POLICY-422-VERSION-EXISTS");
    verify(repo, never()).insertRules(any(), any(), any());
  }

  @Test
  void changeStatusChecksRowVersionTransitionAndWritesAudit() {
    PolicyPackVersion v = version(root, "1.5.0", PolicyVersionStatus.NHAP, null);
    when(repo.findPack(tenant, root.id())).thenReturn(Optional.of(root));
    when(repo.findVersion(tenant, root.id(), "1.5.0")).thenReturn(Optional.of(v));
    assertThatThrownBy(
            () ->
                service.changeStatus(
                    tenant, ACTOR, root.id(), "1.5.0", 2, PolicyVersionStatus.DANG_KIEM_THU))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () ->
                service.changeStatus(
                    tenant, ACTOR, root.id(), "1.5.0", 3, PolicyVersionStatus.DA_PHAT_HANH))
        .hasMessage("POLICY-409-VERSION-CONFLICT");
    when(repo.storeStatus(eq(tenant), any(), eq(3L))).thenReturn(true);
    PolicyPackVersion moved =
        service.changeStatus(
            tenant, ACTOR, root.id(), "1.5.0", 3, PolicyVersionStatus.DANG_KIEM_THU);
    assertThat(moved.status()).isEqualTo(PolicyVersionStatus.DANG_KIEM_THU);
    assertThat(moved.rowVersion()).isEqualTo(4);
    verify(audit)
        .changed(
            tenant,
            ACTOR,
            "CHUYEN_TRANG_THAI_POLICY_PACK_DANG_KIEM_THU",
            PolicyPackService.OBJECT_TYPE,
            "khoi-chinh-quyen/nd335@1.5.0",
            v.id(),
            4);
    when(repo.storeStatus(eq(tenant), any(), anyLong())).thenReturn(false);
    assertThatThrownBy(
            () ->
                service.changeStatus(
                    tenant, ACTOR, root.id(), "1.5.0", 3, PolicyVersionStatus.DANG_KIEM_THU))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void onlyOverridableParentRulesAreEditable() {
    PolicyPackVersion parent = version(root, "1.4.0", PolicyVersionStatus.DA_PHAT_HANH, null);
    PolicyPackVersion own = version(child, "1.4.1", PolicyVersionStatus.NHAP, parent.id());
    when(repo.findPack(tenant, child.id())).thenReturn(Optional.of(child));
    when(repo.findVersion(tenant, child.id(), "1.4.1")).thenReturn(Optional.of(own));
    when(repo.findRule(tenant, parent.id(), "A"))
        .thenReturn(Optional.of(rule(parent.id(), "A", false)));
    when(repo.findRule(tenant, parent.id(), "B"))
        .thenReturn(Optional.of(rule(parent.id(), "B", true)));
    when(repo.findRule(tenant, parent.id(), "rieng")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.assertRuleEditable(tenant, child.id(), "1.4.1", "A"))
        .hasMessage("POLICY-403-NOT-OVERRIDABLE");
    service.assertRuleEditable(tenant, child.id(), "1.4.1", "B");
    service.assertRuleEditable(tenant, child.id(), "1.4.1", "rieng");

    PolicyPackVersion rootDraft = version(root, "1.5.0", PolicyVersionStatus.NHAP, null);
    when(repo.findPack(tenant, root.id())).thenReturn(Optional.of(root));
    when(repo.findVersion(tenant, root.id(), "1.5.0")).thenReturn(Optional.of(rootDraft));
    service.assertRuleEditable(tenant, root.id(), "1.5.0", "A");
    when(repo.findVersion(tenant, root.id(), "1.4.0")).thenReturn(Optional.of(parent));
    assertThatThrownBy(() -> service.assertRuleEditable(tenant, root.id(), "1.4.0", "A"))
        .hasMessage("POLICY-409-VERSION-FROZEN");
  }

  private static Map<String, Object> manifest(String id, String version) {
    return Map.of(
        "apiVersion", PolicyManifestRules.API_VERSION,
        "kind", PolicyManifestRules.KIND,
        "metadata",
            Map.of(
                "id",
                id,
                "version",
                version,
                "luong",
                "NHA_NUOC",
                "ten",
                "Gói thử",
                "can_cu_phap_ly",
                List.of("NĐ 335/2025", "NĐ 233/2026")),
        "inputs", Map.of("x", Map.of("type", "double")),
        "definitions", Map.of("ho_so", Map.of()),
        "rounding", Map.of("diem", Map.of("digits", 1)),
        "classification", Map.of(),
        "quotas", Map.of());
  }

  @Test
  void importPackValidatesManifestAndCreatesPackOnce() {
    assertThatThrownBy(
            () ->
                service.importPack(
                    tenant,
                    ACTOR,
                    new PackImport(
                        Map.of("kind", "x"), PackOrigin.GOC_TOAN_CUC, "KHOI", null, null)))
        .hasMessage("COMMON-422-VALIDATION");

    when(repo.findPackByCode(tenant, "khoi-thu/goc")).thenReturn(Optional.empty());
    when(repo.insertPack(eq(tenant), any())).thenReturn(true);
    when(repo.insertVersion(eq(tenant), any())).thenReturn(true);
    VersionView created =
        service.importPack(
            tenant,
            ACTOR,
            new PackImport(
                manifest("khoi-thu/goc", "1.0.0"), PackOrigin.GOC_TOAN_CUC, "KHOI", null, null));
    assertThat(created.pack().legalBasis()).isEqualTo("NĐ 335/2025; NĐ 233/2026");
    assertThat(created.pack().flow()).isEqualTo("NHA_NUOC");
    assertThat(created.version().changeNote()).isEqualTo("Nhập gói khoi-thu/goc phiên bản 1.0.0");
    assertThat(created.version().manifestJson()).contains("\"apiVersion\"");

    when(repo.findPackByCode(tenant, "khoi-thu/goc")).thenReturn(Optional.of(created.pack()));
    VersionView second =
        service.importPack(
            tenant,
            ACTOR,
            new PackImport(
                manifest("khoi-thu/goc", "1.1.0"),
                PackOrigin.GOC_TOAN_CUC,
                "KHOI",
                null,
                "Bản 1.1"));
    assertThat(second.pack()).isSameAs(created.pack());
    assertThat(second.version().changeNote()).isEqualTo("Bản 1.1");
    verify(repo).insertPack(eq(tenant), any());

    when(repo.insertVersion(eq(tenant), any())).thenReturn(false);
    assertThatThrownBy(
            () ->
                service.importPack(
                    tenant,
                    ACTOR,
                    new PackImport(
                        manifest("khoi-thu/goc", "1.1.0"),
                        PackOrigin.GOC_TOAN_CUC,
                        "KHOI",
                        null,
                        null)))
        .hasMessage("POLICY-422-VERSION-EXISTS");

    when(repo.findPackByCode(tenant, "khoi-thu/trung")).thenReturn(Optional.empty());
    when(repo.insertPack(eq(tenant), any())).thenReturn(false);
    assertThatThrownBy(
            () ->
                service.importPack(
                    tenant,
                    ACTOR,
                    new PackImport(
                        manifest("khoi-thu/trung", "1.0.0"),
                        PackOrigin.TENANT,
                        "KHOI",
                        root.id(),
                        null)))
        .hasMessage("COMMON-422-VALIDATION");
  }

  @Test
  void readsAttachRecentVersionsAndBindings() {
    PolicyPackVersion v = version(root, "1.4.0", PolicyVersionStatus.DA_PHAT_HANH, null);
    v =
        new PolicyPackVersion(
            v.id(),
            v.packId(),
            v.version(),
            v.status(),
            null,
            v.manifestJson(),
            null,
            BigDecimal.ONE,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            v.changeNote(),
            AT,
            ACTOR,
            AT,
            ACTOR,
            1);
    when(repo.listPacks(tenant, "NHA_NUOC", null, null, null, 21)).thenReturn(List.of(root, child));
    when(repo.recentVersions(eq(tenant), any(), eq(PolicyPackService.RECENT_VERSIONS)))
        .thenReturn(Map.of(root.id(), List.of(v)));
    assertThat(service.listPacks(tenant, "NHA_NUOC", null, null, null, 21))
        .extracting(p -> p.versions().size())
        .containsExactly(1, 0);
    when(repo.findPack(tenant, root.id())).thenReturn(Optional.of(root));
    assertThat(service.findPack(tenant, root.id()).orElseThrow().versions()).containsExactly(v);
    when(repo.findVersion(tenant, root.id(), "1.4.0")).thenReturn(Optional.of(v));
    when(repo.bindings(tenant, v.id())).thenReturn(List.of());
    assertThat(service.findVersion(tenant, root.id(), "1.4.0").orElseThrow().version())
        .isEqualTo(v);
    when(repo.listVersions(tenant, root.id(), null, null, 21)).thenReturn(List.of(v));
    assertThat(service.listVersions(tenant, root.id(), null, null, 21)).containsExactly(v);
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(() -> service.listVersions(tenant, missing, null, null, 21))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(service.findPack(tenant, missing)).isEmpty();
  }
}
