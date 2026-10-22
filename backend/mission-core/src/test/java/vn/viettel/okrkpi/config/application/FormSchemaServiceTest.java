package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.PolicySchemaPort;
import vn.viettel.okrkpi.config.application.port.FormSchemaRepository;
import vn.viettel.okrkpi.config.application.port.UiPackNotices;
import vn.viettel.okrkpi.config.domain.FormSchema;
import vn.viettel.okrkpi.config.domain.FormSchemaStatus;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-13: soạn, kiểm trường tính điểm, gắn biểu mẫu vào UI Pack. */
class FormSchemaServiceTest {

  private static final Instant NOW = Instant.parse("2027-01-04T01:00:00Z");
  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID AUTHOR = UUID.randomUUID();
  private static final UUID POLICY = UUID.randomUUID();
  private static final ObjectMapper JSON = new ObjectMapper();

  static final class Forms implements FormSchemaRepository {
    final Map<String, FormSchema> rows = new HashMap<>();

    private static String k(String key, String v) {
      return key + "@" + v;
    }

    @Override
    public List<FormSchema> listLatest(TenantId t, String q, String afterKey, int limit) {
      Map<String, FormSchema> latest = new HashMap<>();
      rows.values()
          .forEach(
              f -> latest.merge(f.formKey(), f, (a, b) -> a.id().compareTo(b.id()) > 0 ? a : b));
      return latest.values().stream()
          .filter(f -> afterKey == null || f.formKey().compareTo(afterKey) > 0)
          .filter(f -> q == null || f.formKey().contains(q))
          .sorted(Comparator.comparing(FormSchema::formKey))
          .limit(limit)
          .toList();
    }

    @Override
    public Optional<FormSchema> find(TenantId t, String key, String v) {
      return Optional.ofNullable(rows.get(k(key, v)));
    }

    @Override
    public Optional<FormSchema> findCurrent(TenantId t, String key) {
      return rows.values().stream()
          .filter(f -> f.formKey().equals(key) && f.status() == FormSchemaStatus.DA_PHAT_HANH)
          .findFirst();
    }

    @Override
    public boolean insert(TenantId t, FormSchema f) {
      return rows.putIfAbsent(k(f.formKey(), f.version()), f) == null;
    }

    @Override
    public boolean update(TenantId t, FormSchema f, int expected) {
      FormSchema cur = rows.get(k(f.formKey(), f.version()));
      if (cur == null || cur.rowVersion() != expected) {
        return false;
      }
      rows.put(k(f.formKey(), f.version()), f);
      return true;
    }
  }

  final Forms forms = new Forms();
  final UiPackServiceTest.Repo packRepo = new UiPackServiceTest.Repo();
  final List<String> audits = new ArrayList<>();
  final UiPackService packs =
      new UiPackService(
          packRepo,
          new UiPackNotices() {
            @Override
            public void reviewRequested(TenantId t, String n, UiPackVersion v, List<UUID> to) {}

            @Override
            public void rejected(TenantId t, String n, UiPackVersion v, String c, List<UUID> to) {}
          },
          (t, doc) -> List.of(),
          new UiPackServiceTest.Jobs(),
          (t, role, at) -> List.of(),
          (t, a, action, type, id, agg, v) -> audits.add(action + ":" + id),
          JSON,
          Clock.fixed(NOW),
          Runnable::run,
          TransactionOperations.withoutTransaction());
  final FormSchemaService service =
      new FormSchemaService(
          forms,
          packRepo,
          new FormSchemaValidator(),
          (t, a, action, type, id, agg, v) -> audits.add(action + ":" + id),
          JSON,
          Clock.fixed(NOW));

  {
    service.setPolicySchemas(
        new PolicySchemaPort() {
          @Override
          public Optional<Set<String>> fields(TenantId t, String ref, String range) {
            return Optional.empty();
          }

          @Override
          public Map<String, String> scoredFields(TenantId t, UUID policyPack) {
            return POLICY.equals(policyPack) ? Map.of("ty_le_hsba_dat", "number") : Map.of();
          }
        });
  }

  private static JsonNode json(String s) throws Exception {
    return JSON.readTree(s);
  }

  private FormSchema saved() throws Exception {
    FormSchema draft = service.create(TENANT, AUTHOR, "phieu-vc-yte", "12", null, POLICY);
    return service.update(
        TENANT,
        AUTHOR,
        "phieu-vc-yte",
        "12",
        draft.rowVersion(),
        json(FormSchemaValidatorTest.VALID),
        json("{\"mo_ta_su_co\":{\"label\":\"Mô tả\"}}"),
        null,
        null);
  }

  @Test
  void createUpdateCloneAndScoredFieldGuard() throws Exception {
    FormSchema v12 = saved();
    assertThat(v12.rowVersion()).isEqualTo(1);
    assertThat(v12.policyPackVersionId()).isEqualTo(POLICY);
    assertThat(audits).contains("LUU_NHAP_BIEU_MAU:phieu-vc-yte@12");
    FormSchema v13 = service.create(TENANT, AUTHOR, "phieu-vc-yte", "13", "12", null);
    assertThat(v13.schemaJson()).isEqualTo(v12.schemaJson());
    assertThat(service.list(TENANT, null, null, 10))
        .extracting(FormSchema::version)
        .containsExactly("13");
    assertThatThrownBy(() -> service.create(TENANT, AUTHOR, "phieu-vc-yte", "13", null, null))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.create(TENANT, AUTHOR, "Phieu", "1", null, null))
        .hasMessage("COMMON-422-VALIDATION");

    // Đổi kiểu trường tính điểm sang string: 409 MSG-18-07.
    String retyped =
        FormSchemaValidatorTest.VALID
            .replace("\"type\":\"number\"", "\"type\":\"string\"")
            .replace("self >= 0.0 && self <= 100.0", "size(self) > 0");
    assertThatThrownBy(
            () ->
                service.update(
                    TENANT, AUTHOR, "phieu-vc-yte", "13", 0, json(retyped), json("{}"), null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessage("UI-409-SCORED-FIELD")
        .satisfies(
            e ->
                assertThat(((BusinessException) e).detail())
                    .isEqualTo(
                        "Trường ty_le_hsba_dat được Policy Pack "
                            + POLICY
                            + " dùng để tính điểm nên không thể đổi kiểu hoặc xoá."));
    assertThatThrownBy(
            () ->
                service.update(
                    TENANT,
                    AUTHOR,
                    "phieu-vc-yte",
                    "13",
                    0,
                    json(
                        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\",\"x-cel\":\"self >\",\"x-classification\":\"C1\"}}}"),
                    json("{}"),
                    null,
                    null))
        .hasMessage("UI-422-CEL-INVALID");
  }

  @Test
  void attachNeedsPassedTestsAndDraftPack() throws Exception {
    FormSchema form = saved();
    UUID pack = packs.listPacks(TENANT, AUTHOR, null, 10).get(0).pack().id();
    UiPackVersion draft = packs.create(TENANT, AUTHOR, pack, "1.0.0", null);

    assertThatThrownBy(
            () ->
                service.attach(
                    TENANT, AUTHOR, pack, "1.0.0", draft.rowVersion(), "phieu-vc-yte", "12"))
        .hasMessage("UI-422-FORM-TEST-FAILED");

    String run =
        "{\"total\":2,\"passed\":2,\"parity\":true,\"cases\":[],\"scoringHash\":\""
            + service.scoringHash(form)
            + "\"}";
    forms.update(TENANT, form.tested(run, AUTHOR, NOW), form.rowVersion());
    // Đổi thuần trình bày không làm mất hiệu lực bộ test (BR-18-07).
    FormSchema tested = service.get(TENANT, "phieu-vc-yte", "12");
    FormSchema relabeled =
        service.update(
            TENANT,
            AUTHOR,
            "phieu-vc-yte",
            "12",
            tested.rowVersion(),
            json(FormSchemaValidatorTest.VALID),
            json("{\"mo_ta_su_co\":{\"label\":\"Mô tả sự cố\"}}"),
            null,
            null);
    assertThat(service.testsPassed(relabeled)).isTrue();

    UiPackVersion attached =
        service.attach(TENANT, AUTHOR, pack, "1.0.0", draft.rowVersion(), "phieu-vc-yte", "12");
    assertThat(service.refs(attached))
        .containsExactly(new FormSchemaService.UiPackFormRef("phieu-vc-yte", "12"));
    assertThat(attached.rowVersion()).isEqualTo(draft.rowVersion() + 1);
    // Lưu nháp UI Pack giữ biểu mẫu đã gắn.
    UiPackVersion kept =
        packs.update(
            TENANT,
            AUTHOR,
            pack,
            "1.0.0",
            attached.rowVersion(),
            attached.content().withForms("[]"));
    assertThat(service.refs(kept)).hasSize(1);

    service.publishAttached(TENANT, kept, AUTHOR, NOW);
    assertThat(service.current(TENANT, "phieu-vc-yte").orElseThrow().version()).isEqualTo("12");

    // Đổi phần tính điểm thì phải chạy lại bộ test.
    FormSchema v13 = service.create(TENANT, AUTHOR, "phieu-vc-yte", "13", "12", null);
    FormSchema changed =
        service.update(
            TENANT,
            AUTHOR,
            "phieu-vc-yte",
            "13",
            v13.rowVersion(),
            json(FormSchemaValidatorTest.VALID.replace("2000", "1000")),
            json("{}"),
            null,
            null);
    FormSchema withOldRun = changed.tested(run, AUTHOR, NOW);
    assertThat(service.testsPassed(withOldRun)).isFalse();
    int published = service.get(TENANT, "phieu-vc-yte", "12").rowVersion();
    assertThatThrownBy(
            () ->
                service.update(
                    TENANT,
                    AUTHOR,
                    "phieu-vc-yte",
                    "12",
                    published,
                    json(FormSchemaValidatorTest.VALID),
                    json("{}"),
                    null,
                    null))
        .hasMessage("UI-409-NOT-DRAFT");
  }
}
