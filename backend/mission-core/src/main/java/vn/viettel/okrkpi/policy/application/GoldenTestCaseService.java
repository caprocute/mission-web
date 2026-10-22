package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.policy.application.port.GoldenTestCaseRepository;
import vn.viettel.okrkpi.policy.application.port.GoldenTestCaseRepository.SuiteSummary;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyCaseSigner;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.CanonicalJson;
import vn.viettel.okrkpi.policy.domain.GoldenCaseRules;
import vn.viettel.okrkpi.policy.domain.GoldenCaseSource;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.policy.domain.PolicyManifestRules.Problem;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bộ ca mẫu vàng của gói (CORE-05-06, QT-15, SCR-1503): thêm ca {@code NHAP}, ký duyệt ca bởi người
 * khác người tạo trên băm chuẩn hoá RFC 8785, ca đã duyệt bất biến (BR-15-08); nhập và xuất tệp
 * {@code bo_ca_mau}. Gói và phiên bản không có ném {@link NoSuchElementException}.
 */
public class GoldenTestCaseService {

  static final String TX = PolicyPackService.TX;
  static final String OBJECT_TYPE = "golden_test_case";

  private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** Nội dung ca gửi lên ({@code GoldenTestCaseCreate}). */
  public record CaseEdit(
      UUID packId,
      String caseCode,
      String description,
      Map<String, Object> input,
      Map<String, Object> expected,
      String legalRef,
      String source) {}

  /** Một trang ca và tóm tắt bộ vàng. */
  public record CasePage(List<GoldenTestCase> items, SuiteSummary summary) {}

  /** Kết quả nhập tệp bộ ca. */
  public record ImportResult(int created, int updated, int unchanged, int retired) {}

  private final PolicyPackRepository packs;
  private final GoldenTestCaseRepository cases;
  private final PolicyCaseSigner signer;
  private final PolicyAudit audit;
  private final ObjectMapper json;
  private final Clock clock;

  public GoldenTestCaseService(
      PolicyPackRepository packs,
      GoldenTestCaseRepository cases,
      PolicyCaseSigner signer,
      PolicyAudit audit,
      ObjectMapper json,
      Clock clock) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.cases = Objects.requireNonNull(cases, "cases");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** {@code listGoldenTestCases}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public CasePage listCases(
      TenantId tenant,
      UUID packId,
      GoldenCaseStatus status,
      String q,
      String afterCode,
      int limit) {
    packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    return new CasePage(
        cases.listCases(tenant, packId, status, q, afterCode, limit),
        cases.summary(tenant, packId));
  }

  /** {@code getGoldenTestCase}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<GoldenTestCase> findCase(TenantId tenant, UUID id) {
    return cases.findCase(tenant, id);
  }

  /** {@code createGoldenTestCase}: ca mới ở {@code NHAP}, mã ca duy nhất trong gói. */
  @Transactional(TX)
  public GoldenTestCase addCase(TenantId tenant, UUID actor, CaseEdit edit) {
    Objects.requireNonNull(edit, "edit");
    PolicyPack pack =
        packs.findPack(tenant, edit.packId()).orElseThrow(NoSuchElementException::new);
    GoldenTestCase created =
        GoldenTestCase.draft(
            Ids.newUuidV7(),
            pack.id(),
            edit.caseCode(),
            edit.description(),
            write(edit.input()),
            write(edit.expected()),
            blankToNull(edit.legalRef()),
            validate(tenant, pack, edit),
            actor);
    if (cases.findCaseByCode(tenant, pack.id(), edit.caseCode()).isPresent()
        || !cases.appendCase(tenant, created)) {
      throw codeExists(edit.caseCode());
    }
    audit.changed(
        tenant,
        actor,
        "TAO_CA_KIEM_THU_VANG",
        OBJECT_TYPE,
        pack.code() + "/" + created.caseCode(),
        created.id(),
        created.rowVersion());
    return created;
  }

  /**
   * {@code approveGoldenTestCase}: người ký khác người tạo ({@code POLICY-403-SAME-AUTHOR}), chỉ ca
   * {@code NHAP} ({@code POLICY-409-NOT-PENDING}); ký trên {@link #canonical(GoldenTestCase)}.
   */
  @Transactional(TX)
  public GoldenTestCase approveCase(TenantId tenant, UUID actor, UUID id, long expectedRowVersion) {
    GoldenTestCase current = cases.findCase(tenant, id).orElseThrow(NoSuchElementException::new);
    if (current.rowVersion() != expectedRowVersion) {
      throw new VersionConflictException("POLICY", expectedRowVersion, current.rowVersion());
    }
    if (!current.editable()) {
      throw new BusinessException(
          "POLICY-409-NOT-PENDING",
          "Ca " + current.caseCode() + " không ở trạng thái chờ ký duyệt");
    }
    if (current.authoredBy(actor)) {
      throw new BusinessException(
          "POLICY-403-SAME-AUTHOR",
          "Người ký duyệt ca phải khác người tạo ca",
          List.of(new ProblemFieldError("approvedBy", "Người ký trùng người tạo ca", "BR-15-08")));
    }
    byte[] signature = signer.sign(canonical(current).getBytes(StandardCharsets.UTF_8));
    GoldenTestCase approved = current.approved(actor, clock.instant(), signature);
    if (!cases.storeApproval(tenant, approved, expectedRowVersion)) {
      throw new VersionConflictException("POLICY", expectedRowVersion, null);
    }
    audit.changed(
        tenant,
        actor,
        "KY_DUYET_CA_KIEM_THU_VANG",
        OBJECT_TYPE,
        current.caseCode(),
        approved.id(),
        approved.rowVersion());
    return approved;
  }

  /**
   * Nhập tệp {@code bo_ca_mau}: ca mới thêm ở {@code NHAP}, ca {@code NHAP} khác nội dung được ghi
   * đè, ca đã duyệt khác nội dung bị từ chối (BR-15-08); {@code thay_the} chuyển ca cũ sang {@code
   * NGUNG}.
   */
  @Transactional(TX)
  public ImportResult importSuite(TenantId tenant, UUID actor, UUID packId, String yaml) {
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    GoldenSuiteYaml.Suite suite;
    try {
      suite = GoldenSuiteYaml.parse(yaml);
    } catch (RuntimeException e) {
      throw invalid("ca", "Tệp bộ ca không đọc được: " + e.getMessage());
    }
    int created = 0;
    int updated = 0;
    int unchanged = 0;
    int retired = 0;
    for (GoldenSuiteYaml.Entry e : suite.entries()) {
      CaseEdit edit =
          new CaseEdit(
              packId,
              e.caseCode(),
              e.description(),
              e.input(),
              e.expected(),
              e.legalRef(),
              e.source() == null ? GoldenCaseSource.BA.name() : e.source());
      Optional<GoldenTestCase> existing = cases.findCaseByCode(tenant, packId, e.caseCode());
      if (existing.isEmpty()) {
        addCase(tenant, actor, edit);
        created++;
      } else if (sameContent(existing.get(), edit)) {
        unchanged++;
      } else if (!existing.get().editable()) {
        throw new BusinessException(
            "POLICY-409-NOT-PENDING",
            "Ca "
                + e.caseCode()
                + " đã ký duyệt nên không sửa được; hãy tạo ca mới và ghi thay_the.");
      } else {
        GoldenTestCase old = existing.get();
        GoldenTestCase changed =
            new GoldenTestCase(
                old.id(),
                old.packId(),
                old.caseCode(),
                edit.description(),
                write(edit.input()),
                write(edit.expected()),
                blankToNull(edit.legalRef()),
                validate(tenant, pack, edit),
                GoldenCaseStatus.NHAP,
                null,
                null,
                null,
                old.createdBy(),
                old.rowVersion() + 1);
        if (!cases.replaceContent(tenant, changed, old.rowVersion(), actor)) {
          throw new VersionConflictException("POLICY", old.rowVersion(), null);
        }
        updated++;
      }
      if (e.replaces() != null) {
        GoldenTestCase old =
            cases
                .findCaseByCode(tenant, packId, e.replaces())
                .orElseThrow(
                    () -> invalid("thay_the", "Không có ca " + e.replaces() + " để thay thế"));
        if (old.status() != GoldenCaseStatus.NGUNG) {
          if (!cases.storeStatus(
              tenant, old.id(), GoldenCaseStatus.NGUNG, old.rowVersion(), actor)) {
            throw new VersionConflictException("POLICY", old.rowVersion(), null);
          }
          audit.changed(
              tenant,
              actor,
              "NGUNG_CA_KIEM_THU_VANG",
              OBJECT_TYPE,
              pack.code() + "/" + old.caseCode(),
              old.id(),
              old.rowVersion() + 1);
          retired++;
        }
      }
    }
    return new ImportResult(created, updated, unchanged, retired);
  }

  /** Xuất toàn bộ ca của gói thành tệp {@code bo_ca_mau}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public String exportSuite(TenantId tenant, UUID packId) {
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    List<Map<String, Object>> out = new ArrayList<>();
    String after = null;
    while (true) {
      List<GoldenTestCase> page = cases.listCases(tenant, packId, null, null, after, 200);
      for (GoldenTestCase c : page) {
        out.add(
            GoldenSuiteYaml.entry(
                c.caseCode(),
                c.description(),
                read(c.inputJson()),
                read(c.expectedJson()),
                c.legalRef(),
                c.source().name(),
                c.status().name()));
      }
      if (page.size() < 200) {
        break;
      }
      after = page.get(page.size() - 1).caseCode();
    }
    return GoldenSuiteYaml.write("bo-ca-mau-" + pack.code().replace('/', '-'), pack.code(), out);
  }

  /**
   * Nội dung được ký: JSON chuẩn hoá RFC 8785 của mã gói, mã ca, đầu vào và kết quả mong đợi (suy
   * luận phạm vi ký từ BR-15-08).
   */
  public String canonical(GoldenTestCase c) {
    Map<String, Object> signed = new LinkedHashMap<>();
    signed.put("policyPackId", c.packId().toString());
    signed.put("caseCode", c.caseCode());
    signed.put("input", read(c.inputJson()));
    signed.put("expected", read(c.expectedJson()));
    return CanonicalJson.write(signed);
  }

  private boolean sameContent(GoldenTestCase c, CaseEdit e) {
    return Objects.equals(c.description(), e.description())
        && Objects.equals(c.legalRef(), blankToNull(e.legalRef()))
        && c.source().name().equals(e.source())
        && CanonicalJson.write(read(c.inputJson())).equals(CanonicalJson.write(e.input()))
        && CanonicalJson.write(read(c.expectedJson())).equals(CanonicalJson.write(e.expected()));
  }

  private GoldenCaseSource validate(TenantId tenant, PolicyPack pack, CaseEdit edit) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (edit.caseCode() == null || !CODE.matcher(edit.caseCode()).matches()) {
      errors.add(field("caseCode", "Mã ca gồm chữ, số, dấu chấm, gạch; tối đa 64 ký tự"));
    }
    if (edit.description() == null
        || edit.description().isBlank()
        || edit.description().length() > 2000) {
      errors.add(field("description", "Cần mô tả ca, tối đa 2.000 ký tự"));
    }
    if (edit.legalRef() != null && edit.legalRef().length() > 200) {
      errors.add(field("legalRef", "Căn cứ tối đa 200 ký tự"));
    }
    GoldenCaseSource source = null;
    try {
      source = GoldenCaseSource.valueOf(String.valueOf(edit.source()));
    } catch (IllegalArgumentException e) {
      errors.add(field("source", "Nguồn ca chưa hợp lệ"));
    }
    Optional<PolicyPackVersion> latest =
        packs.listVersions(tenant, pack.id(), null, null, 1).stream().findFirst();
    if (latest.isEmpty()) {
      errors.add(field("policyPackId", "Gói chưa có phiên bản để đối chiếu khai báo đầu vào"));
    } else {
      Map<String, Object> manifest = read(latest.get().manifestJson());
      for (Problem p : GoldenCaseRules.checkInput(edit.input(), declaredInputs(manifest))) {
        errors.add(field(p.field(), p.message()));
      }
      for (Problem p :
          GoldenCaseRules.checkExpected(edit.expected(), outputs(tenant, latest.get(), manifest))) {
        errors.add(field(p.field(), p.message()));
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException("COMMON-422-VALIDATION", errors.get(0).message(), errors);
    }
    return source;
  }

  /** Biến khai báo của gói; trường hợp đặc thù (PL 6) nhận khoá {@code truong_hop}. */
  private static Set<String> declaredInputs(Map<String, Object> manifest) {
    Set<String> names = new HashSet<>();
    for (String block : List.of("inputs", "inputs_nam", "ngu_canh")) {
      if (manifest.get(block) instanceof Map<?, ?> m) {
        m.keySet().forEach(k -> names.add(String.valueOf(k)));
      }
    }
    if (manifest.get("truong_hop_dac_thu") instanceof List<?>) {
      names.add("truong_hop");
    }
    return names;
  }

  /** Đầu ra của gói: khoá quy tắc, định nghĩa và thuộc tính của trường hợp đặc thù. */
  private Set<String> outputs(
      TenantId tenant, PolicyPackVersion version, Map<String, Object> manifest) {
    Set<String> names = new HashSet<>();
    packs.rules(tenant, version.id()).stream().map(PolicyRule::ruleKey).forEach(names::add);
    for (String block : List.of("definitions", "definitions_nam")) {
      if (manifest.get(block) instanceof Map<?, ?> scopes) {
        for (Object scope : scopes.values()) {
          if (scope instanceof Map<?, ?> defs) {
            defs.keySet().forEach(k -> names.add(String.valueOf(k)));
          }
        }
      }
    }
    if (manifest.get("truong_hop_dac_thu") instanceof List<?> cases) {
      for (Object c : cases) {
        if (c instanceof Map<?, ?> m) {
          m.keySet().forEach(k -> names.add(String.valueOf(k)));
        }
      }
    }
    return names;
  }

  private Map<String, Object> read(String value) {
    try {
      return value == null || value.isBlank() ? Map.of() : json.readValue(value, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON đã lưu không đọc được", e);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value == null ? Map.of() : value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Không ghi được JSON", e);
    }
  }

  static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static BusinessException codeExists(String code) {
    String message = "Mã ca " + code + " đã có trong bộ ca của gói";
    return new BusinessException(
        "POLICY-422-CASE-CODE-EXISTS", message, List.of(field("caseCode", message)));
  }

  private static BusinessException invalid(String at, String message) {
    return new BusinessException("COMMON-422-VALIDATION", message, List.of(field(at, message)));
  }

  private static ProblemFieldError field(String field, String message) {
    return new ProblemFieldError(field, message, null);
  }
}
