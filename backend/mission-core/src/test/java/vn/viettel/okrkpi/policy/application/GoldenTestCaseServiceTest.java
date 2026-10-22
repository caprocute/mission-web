package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import vn.viettel.okrkpi.policy.application.GoldenTestCaseService.CaseEdit;
import vn.viettel.okrkpi.policy.application.GoldenTestCaseService.ImportResult;
import vn.viettel.okrkpi.policy.application.port.GoldenTestCaseRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyCaseSigner;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.GoldenCaseSource;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bộ ca mẫu vàng (CORE-05-06): mã ca trùng 422, tự duyệt bị chặn, ca đã duyệt không sửa được, chữ
 * ký kiểm được trên JSON chuẩn hoá RFC 8785, nhập rồi xuất tệp {@code bo_ca_mau} cho nội dung tương
 * đương (gồm bộ ca giả lập của DL-03).
 */
class GoldenTestCaseServiceTest {

  private static final Path PACK = Path.of("../../data/policy-pack/khoi-dang/quy-dinh-moi");
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Kho ca trong bộ nhớ. */
  static final class Cases implements GoldenTestCaseRepository {
    final Map<UUID, GoldenTestCase> rows = new LinkedHashMap<>();
    boolean rejectWrites;

    @Override
    public Optional<GoldenTestCase> findCase(TenantId tenant, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<GoldenTestCase> findCaseByCode(TenantId tenant, UUID packId, String code) {
      return rows.values().stream()
          .filter(c -> c.packId().equals(packId) && c.caseCode().equals(code))
          .findFirst();
    }

    @Override
    public List<GoldenTestCase> listCases(
        TenantId tenant, UUID packId, GoldenCaseStatus status, String q, String after, int limit) {
      return rows.values().stream()
          .filter(c -> c.packId().equals(packId))
          .filter(c -> status == null || c.status() == status)
          .filter(c -> q == null || c.caseCode().contains(q) || c.description().contains(q))
          .filter(c -> after == null || c.caseCode().compareTo(after) > 0)
          .sorted(Comparator.comparing(GoldenTestCase::caseCode))
          .limit(limit)
          .toList();
    }

    @Override
    public boolean appendCase(TenantId tenant, GoldenTestCase c) {
      if (rejectWrites) {
        return false;
      }
      rows.put(c.id(), c);
      return true;
    }

    @Override
    public boolean replaceContent(TenantId tenant, GoldenTestCase c, long expected, UUID actor) {
      if (rejectWrites || rows.get(c.id()).rowVersion() != expected) {
        return false;
      }
      rows.put(c.id(), c);
      return true;
    }

    @Override
    public boolean storeApproval(TenantId tenant, GoldenTestCase c, long expected) {
      if (rejectWrites) {
        return false;
      }
      rows.put(c.id(), c);
      return true;
    }

    @Override
    public boolean storeStatus(
        TenantId tenant, UUID id, GoldenCaseStatus status, long expected, UUID actor) {
      if (rejectWrites) {
        return false;
      }
      GoldenTestCase c = rows.get(id);
      rows.put(
          id,
          new GoldenTestCase(
              c.id(),
              c.packId(),
              c.caseCode(),
              c.description(),
              c.inputJson(),
              c.expectedJson(),
              c.legalRef(),
              c.source(),
              status,
              c.approvedBy(),
              c.approvedAt(),
              c.signature(),
              c.createdBy(),
              c.rowVersion() + 1));
      return true;
    }

    @Override
    public SuiteSummary summary(TenantId tenant, UUID packId) {
      List<GoldenTestCase> all = listCases(tenant, packId, null, null, null, 10_000);
      return new SuiteSummary(
          (int) all.stream().filter(c -> c.status() == GoldenCaseStatus.DA_DUYET).count(),
          (int) all.stream().filter(GoldenTestCase::editable).count(),
          all.stream()
              .map(GoldenTestCase::approvedAt)
              .filter(java.util.Objects::nonNull)
              .max(Comparator.naturalOrder())
              .orElse(null));
    }
  }

  /** Bộ ký ES256 khoá tạm, kiểm lại được. */
  static final class Signer implements PolicyCaseSigner {
    final java.security.KeyPair pair;

    Signer() throws Exception {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      pair = g.generateKeyPair();
    }

    @Override
    public String keyId() {
      return "kiem-thu";
    }

    @Override
    public byte[] sign(byte[] data) {
      try {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(pair.getPrivate());
        s.update(data);
        return s.sign();
      } catch (java.security.GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }

    boolean verify(byte[] data, byte[] sig) throws Exception {
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initVerify(pair.getPublic());
      s.update(data);
      return s.verify(sig);
    }
  }

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();
  private final UUID approver = UUID.randomUUID();
  private final PolicyPackRepository packs = mock(PolicyPackRepository.class);
  private final Cases cases = new Cases();
  private final Signer signer;
  private final GoldenTestCaseService service;
  private final PolicyPack pack;

  GoldenTestCaseServiceTest() throws Exception {
    signer = new Signer();
    service =
        new GoldenTestCaseService(
            packs, cases, signer, mock(PolicyAudit.class), JSON, Clock.systemUtc());
    pack =
        new PolicyPack(
            UUID.randomUUID(),
            "khoi-dang/quy-dinh-moi",
            "Gói",
            "KHOI_DANG",
            "DANG",
            PackOrigin.GOC_TOAN_CUC,
            null,
            "QĐ",
            null,
            null,
            0);
    when(packs.findPack(tenant, pack.id())).thenReturn(Optional.of(pack));
    Object manifest =
        new Yaml(new SafeConstructor(new LoaderOptions()))
            .load(Files.readString(PACK.resolve("policy.yaml")));
    PolicyPackVersion v =
        new PolicyPackVersion(
            UUID.randomUUID(),
            pack.id(),
            "2.1.0",
            PolicyVersionStatus.NHAP,
            null,
            JSON.writeValueAsString(manifest),
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            "x",
            null,
            null,
            null,
            null,
            0);
    when(packs.listVersions(eq(tenant), eq(pack.id()), isNull(), isNull(), anyInt()))
        .thenReturn(List.of(v));
    when(packs.rules(tenant, v.id()))
        .thenReturn(
            List.of(
                new PolicyRule(
                    UUID.randomUUID(), v.id(), "C", "1.0", "DECIMAL", "{}", "PL", 1, "{}", true)));
  }

  private static CaseEdit edit(UUID packId, String code, Map<String, Object> expected) {
    return new CaseEdit(
        packId,
        code,
        "Ngày nghỉ bù trùng ngày lễ không tính ngày chậm",
        Map.of("ty_le_O", 1.0, "nhiem_vu", List.of(Map.of("ke_hoach", 4))),
        expected,
        "PL 3; PL A4",
        "BA");
  }

  private static String code(Throwable t) {
    return ((BusinessException) t).getMessage();
  }

  @Test
  void duplicateCodeIsRejectedAndInvalidContentListsFields() {
    GoldenTestCase created =
        service.addCase(tenant, author, edit(pack.id(), "TC-149", Map.of("C", 1.0)));
    assertThat(created.status()).isEqualTo(GoldenCaseStatus.NHAP);
    assertThat(created.source()).isEqualTo(GoldenCaseSource.BA);
    assertThatThrownBy(
            () -> service.addCase(tenant, author, edit(pack.id(), "TC-149", Map.of("C", 1.0))))
        .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-422-CASE-CODE-EXISTS"));
    cases.rejectWrites = true;
    assertThatThrownBy(
            () -> service.addCase(tenant, author, edit(pack.id(), "TC-150", Map.of("C", 1.0))))
        .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-422-CASE-CODE-EXISTS"));

    CaseEdit bad =
        new CaseEdit(
            pack.id(),
            "mã có dấu",
            " ",
            Map.of("ho_ten", "x"),
            Map.of("la", 1),
            "x".repeat(201),
            "KHAC");
    assertThatThrownBy(() -> service.addCase(tenant, author, bad))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errors())
                    .extracting(e -> e.field())
                    .containsExactlyInAnyOrder(
                        "caseCode",
                        "description",
                        "legalRef",
                        "source",
                        "input.ho_ten",
                        "expected"));
    UUID missing = UUID.randomUUID();
    when(packs.findPack(tenant, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.addCase(tenant, author, edit(missing, "X", Map.of())))
        .isInstanceOf(NoSuchElementException.class);
    PolicyPack empty =
        new PolicyPack(
            UUID.randomUUID(),
            "rong",
            "Rỗng",
            "K",
            "DANG",
            PackOrigin.GOC_TOAN_CUC,
            null,
            "QĐ",
            null,
            null,
            0);
    when(packs.findPack(tenant, empty.id())).thenReturn(Optional.of(empty));
    assertThatThrownBy(() -> service.addCase(tenant, author, edit(empty.id(), "X", Map.of("C", 1))))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errors().get(0).field())
                    .isEqualTo("policyPackId"));
  }

  @Test
  void approvalNeedsAnotherPersonAndSignsCanonicalContent() throws Exception {
    GoldenTestCase created =
        service.addCase(tenant, author, edit(pack.id(), "TC-149", Map.of("C", 1.0)));
    assertThatThrownBy(() -> service.approveCase(tenant, author, created.id(), 0))
        .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-403-SAME-AUTHOR"));
    assertThatThrownBy(() -> service.approveCase(tenant, approver, created.id(), 3))
        .isInstanceOf(VersionConflictException.class);

    GoldenTestCase approved = service.approveCase(tenant, approver, created.id(), 0);
    assertThat(approved.status()).isEqualTo(GoldenCaseStatus.DA_DUYET);
    assertThat(approved.approvedBy()).isEqualTo(approver);
    assertThat(approved.approvedAt()).isBeforeOrEqualTo(Instant.now());
    String canonical = service.canonical(approved);
    assertThat(canonical)
        .startsWith("{\"caseCode\":\"TC-149\",\"expected\":{\"C\":1},\"input\":{\"nhiem_vu\":");
    assertThat(signer.verify(canonical.getBytes(StandardCharsets.UTF_8), approved.signature()))
        .isTrue();
    assertThatThrownBy(() -> service.approveCase(tenant, approver, created.id(), 1))
        .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-409-NOT-PENDING"));
    assertThat(service.findCase(tenant, created.id())).contains(approved);

    var page = service.listCases(tenant, pack.id(), GoldenCaseStatus.DA_DUYET, null, null, 10);
    assertThat(page.items()).containsExactly(approved);
    assertThat(page.summary().total()).isEqualTo(1);
    assertThat(page.summary().pending()).isZero();

    GoldenTestCase other =
        service.addCase(tenant, author, edit(pack.id(), "TC-150", Map.of("C", 1.0)));
    cases.rejectWrites = true;
    assertThatThrownBy(() -> service.approveCase(tenant, approver, other.id(), 0))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.approveCase(tenant, approver, UUID.randomUUID(), 0))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void dl03SuiteImportsAndExportsEquivalently() throws Exception {
    String yaml = Files.readString(PACK.resolve("tests/bo-ca-mau-gia-lap.yaml"));
    ImportResult first = service.importSuite(tenant, author, pack.id(), yaml);
    assertThat(first.created()).isEqualTo(63);
    assertThat(service.importSuite(tenant, author, pack.id(), yaml))
        .isEqualTo(new ImportResult(0, 0, 63, 0));

    String exported = service.exportSuite(tenant, pack.id());
    GoldenTestCaseService again =
        new GoldenTestCaseService(
            packs, new Cases(), signer, mock(PolicyAudit.class), JSON, Clock.systemUtc());
    assertThat(again.importSuite(tenant, author, pack.id(), exported).created()).isEqualTo(63);
    assertThat(again.exportSuite(tenant, pack.id())).isEqualTo(exported);

    GoldenTestCase q001 = cases.findCaseByCode(tenant, pack.id(), "Q-001").orElseThrow();
    Map<?, ?> input = JSON.readValue(q001.inputJson(), Map.class);
    assertThat(input.get("loai")).isEqualTo("QUY");
    Map<?, ?> dauVao = (Map<?, ?>) input.get("dau_vao");
    assertThat(dauVao.get("ty_le_O")).isEqualTo(1.0);
    assertThat(((Map<?, ?>) ((List<?>) dauVao.get("nhiem_vu")).get(2)).get("so_luong_duyet"))
        .isEqualTo(12);
    assertThat(((Map<?, ?>) ((List<?>) dauVao.get("nhiem_vu")).get(2)).get("ke_hoach"))
        .isEqualTo(10);
  }

  @Test
  void approvedCaseCannotBeChangedOnlyReplaced() {
    String v1 =
        """
        bo_ca_mau: bo-thu
        goi: khoi-dang/quy-dinh-moi
        ca:
          - id: TC-1
            mo_ta: Ca thứ nhất
            dau_vao: {ty_le_O: 1.0}
            mong_doi: {diem: 90.0, xep_loai: HTXSNV}
          - id: TC-2
            mo_ta: Ca thứ hai
            source: KHACH_HANG
            dau_vao: {ty_le_O: 0.5}
            mong_doi: {xep_loai: HTNV}
        """;
    assertThat(service.importSuite(tenant, author, pack.id(), v1).created()).isEqualTo(2);
    GoldenTestCase tc1 = cases.findCaseByCode(tenant, pack.id(), "TC-1").orElseThrow();
    service.approveCase(tenant, approver, tc1.id(), 0);

    String draftChanged = v1.replace("mong_doi: {xep_loai: HTNV}", "mong_doi: {xep_loai: HTTNV}");
    assertThat(service.importSuite(tenant, author, pack.id(), draftChanged))
        .isEqualTo(new ImportResult(0, 1, 1, 0));
    assertThat(cases.findCaseByCode(tenant, pack.id(), "TC-2").orElseThrow().expectedJson())
        .contains("HTTNV");

    String approvedChanged = draftChanged.replace("diem: 90.0", "diem: 91.0");
    assertThatThrownBy(() -> service.importSuite(tenant, author, pack.id(), approvedChanged))
        .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-409-NOT-PENDING"));

    String replaced =
        draftChanged
            + """
              - id: TC-1b
                mo_ta: Ca thay thế ca thứ nhất
                thay_the: TC-1
                dau_vao: {ty_le_O: 1.0}
                mong_doi: {diem: 91.0, xep_loai: HTXSNV}
            """;
    assertThat(service.importSuite(tenant, author, pack.id(), replaced))
        .isEqualTo(new ImportResult(1, 0, 2, 1));
    assertThat(cases.findCaseByCode(tenant, pack.id(), "TC-1").orElseThrow().status())
        .isEqualTo(GoldenCaseStatus.NGUNG);
    assertThat(service.importSuite(tenant, author, pack.id(), replaced).retired()).isZero();

    assertThatThrownBy(
            () ->
                service.importSuite(
                    tenant,
                    author,
                    pack.id(),
                    replaced.replace("thay_the: TC-1", "thay_the: TC-9")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> service.importSuite(tenant, author, pack.id(), "chi: la chuoi"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> service.importSuite(tenant, author, pack.id(), "ca: [1]"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));

    cases.rejectWrites = true;
    String again =
        draftChanged.replace("mong_doi: {xep_loai: HTTNV}", "mong_doi: {xep_loai: HTNV}");
    assertThatThrownBy(() -> service.importSuite(tenant, author, pack.id(), again))
        .isInstanceOf(VersionConflictException.class);
    List<GoldenTestCase> all = new ArrayList<>(cases.rows.values());
    assertThat(all).hasSize(3);
  }
}
