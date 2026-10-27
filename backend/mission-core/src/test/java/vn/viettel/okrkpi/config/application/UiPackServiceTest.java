package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.application.port.UiPackChecker;
import vn.viettel.okrkpi.config.application.port.UiPackJobs;
import vn.viettel.okrkpi.config.application.port.UiPackNotices;
import vn.viettel.okrkpi.config.application.port.UiPackRepository;
import vn.viettel.okrkpi.config.domain.UiPack;
import vn.viettel.okrkpi.config.domain.UiPackContent;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.config.domain.UiPackVersionStatus;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Dịch vụ UI Pack trên kho trong bộ nhớ, kiểm tra chạy đồng bộ (CORE-08-08). */
class UiPackServiceTest {

  private static final Instant NOW = Instant.parse("2027-01-04T01:00:00Z");
  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID AUTHOR = UUID.randomUUID();
  private static final UUID REVIEWER = UUID.randomUUID();
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Kho trong bộ nhớ. */
  static final class Repo implements UiPackRepository {
    final Map<UUID, UiPack> packs = new HashMap<>();
    final Map<UUID, UiPackVersion> versions = new HashMap<>();

    @Override
    public List<UiPack> listPacks(TenantId t, UUID afterId, int limit) {
      return packs.values().stream()
          .filter(p -> afterId == null || p.id().compareTo(afterId) > 0)
          .sorted(Comparator.comparing(UiPack::id))
          .limit(limit)
          .toList();
    }

    @Override
    public Optional<UiPack> findPack(TenantId t, UUID packId) {
      return Optional.ofNullable(packs.get(packId));
    }

    @Override
    public void insertPack(TenantId t, UiPack pack, UUID actorId) {
      packs.put(pack.id(), pack);
    }

    @Override
    public List<UiPackVersion> listVersions(
        TenantId t, UUID packId, UiPackVersionStatus status, UUID beforeId, int limit) {
      return versions.values().stream()
          .filter(v -> v.uiPackId().equals(packId))
          .filter(v -> status == null || v.status() == status)
          .filter(v -> beforeId == null || v.id().compareTo(beforeId) < 0)
          .sorted(Comparator.comparing(UiPackVersion::id).reversed())
          .limit(limit)
          .toList();
    }

    @Override
    public Optional<UiPackVersion> findVersion(TenantId t, UUID packId, String version) {
      return versions.values().stream()
          .filter(v -> v.uiPackId().equals(packId) && v.version().equals(version))
          .findFirst();
    }

    @Override
    public Optional<UiPackVersion> findVersionById(TenantId t, UUID versionId) {
      return Optional.ofNullable(versions.get(versionId));
    }

    @Override
    public Optional<String> findInProgressVersion(TenantId t, UUID packId) {
      return listVersions(t, packId, null, null, 100).stream()
          .filter(v -> v.status().inProgress())
          .map(UiPackVersion::version)
          .findFirst();
    }

    @Override
    public boolean insertVersion(TenantId t, UiPackVersion v) {
      if (findVersion(t, v.uiPackId(), v.version()).isPresent()) {
        return false;
      }
      versions.put(v.id(), v);
      return true;
    }

    @Override
    public boolean updateVersion(TenantId t, UiPackVersion v, int expectedVersion) {
      UiPackVersion cur = versions.get(v.id());
      if (cur == null || cur.rowVersion() != expectedVersion) {
        return false;
      }
      versions.put(v.id(), v);
      return true;
    }

    @Override
    public Optional<UiPack> findCurrentPack(TenantId t) {
      return packs.values().stream().filter(p -> p.currentVersionId() != null).findFirst();
    }

    @Override
    public boolean setCurrentVersion(
        TenantId t, UiPack pack, UUID versionId, UUID actorId, Instant at) {
      UiPack cur = packs.get(pack.id());
      if (cur == null || cur.rowVersion() != pack.rowVersion()) {
        return false;
      }
      packs.put(
          pack.id(),
          new UiPack(pack.id(), pack.code(), pack.name(), versionId, pack.rowVersion() + 1));
      return true;
    }
  }

  /** Ghi nhận tác vụ. */
  static final class Jobs implements UiPackJobs {
    final Map<String, String> state = new HashMap<>();
    final Map<String, List<ProblemFieldError>> errors = new HashMap<>();
    final Map<String, String> messages = new HashMap<>();

    @Override
    public void accepted(TenantId t, String jobId, UUID initiator, String type) {
      state.put(jobId, "CHO");
    }

    @Override
    public void running(TenantId t, String jobId) {
      state.put(jobId, "DANG_CHAY");
    }

    @Override
    public void passed(TenantId t, String jobId, String location, String message) {
      state.put(jobId, "HOAN_TAT " + location);
      messages.put(jobId, message);
    }

    @Override
    public void failed(TenantId t, String jobId, String message, List<ProblemFieldError> e) {
      state.put(jobId, "THAT_BAI");
      errors.put(jobId, e);
    }
  }

  final Repo repo = new Repo();
  final Jobs jobs = new Jobs();
  final List<String> notices = new ArrayList<>();
  final List<String> audits = new ArrayList<>();
  final List<UiPackChecker.Result> checkerResults = new ArrayList<>();
  boolean checkerBroken;
  final UiPackNotices noticePort =
      new UiPackNotices() {
        @Override
        public void reviewRequested(TenantId t, String name, UiPackVersion v, List<UUID> to) {
          notices.add("review:" + v.version() + ":" + to);
        }

        @Override
        public void rejected(TenantId t, String name, UiPackVersion v, String c, List<UUID> to) {
          notices.add("rejected:" + c + ":" + to);
        }
      };
  final UiPackService service =
      new UiPackService(
          repo,
          noticePort,
          (t, doc) -> {
            if (checkerBroken) {
              throw new IllegalStateException("Kho đối tượng không phản hồi");
            }
            return List.copyOf(checkerResults);
          },
          jobs,
          (t, role, at) -> List.of(AUTHOR, REVIEWER),
          (t, a, action, type, id, agg, v) -> audits.add(action + ":" + id + ":" + v),
          JSON,
          Clock.fixed(NOW),
          Runnable::run,
          TransactionOperations.withoutTransaction());

  private UUID packId() {
    return service.listPacks(TENANT, AUTHOR, null, 20).get(0).pack().id();
  }

  private UiPackContent content(JsonNode tokens, JsonNode layouts) {
    return service.content(tokens, layouts, JSON.createArrayNode(), "1.0.0", null, true, true);
  }

  private static Actor mfa(UUID id) {
    return new Actor(id, "aal2", NOW.getEpochSecond() - 60);
  }

  @Test
  void listCreatesDefaultPackOnceAndShowsDraft() {
    List<UiPackService.PackSummary> first = service.listPacks(TENANT, AUTHOR, null, 20);
    assertThat(first)
        .singleElement()
        .satisfies(s -> assertThat(s.pack().code()).isEqualTo("mac-dinh"));
    UUID pack = first.get(0).pack().id();
    assertThat(service.listPacks(TENANT, AUTHOR, pack, 20)).isEmpty();
    service.create(TENANT, AUTHOR, pack, "1.0.0", null);
    UiPackService.PackSummary again = service.listPacks(TENANT, AUTHOR, null, 20).get(0);
    assertThat(again.draftVersion()).isEqualTo("1.0.0");
    assertThat(again.currentVersion()).isNull();
    assertThat(repo.packs).hasSize(1);
  }

  @Test
  void createFromDefaultOrCloneAndRejectBadInput() {
    UUID pack = packId();
    UiPackVersion v1 = service.create(TENANT, AUTHOR, pack, "1.0.0", null);
    assertThat(v1.status()).isEqualTo(UiPackVersionStatus.NHAP);
    assertThat(v1.content().elderMode()).isTrue();
    UiPackVersion v2 = service.create(TENANT, AUTHOR, pack, "1.1.0", "1.0.0");
    assertThat(v2.content()).isEqualTo(v1.content());
    assertThat(audits).contains("TAO_PHIEN_BAN_UI_PACK:1.1.0:0");
    assertThatThrownBy(() -> service.create(TENANT, AUTHOR, pack, "1.1.0", null))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors())
                    .extracting(ProblemFieldError::field)
                    .containsExactly("version"));
    assertThatThrownBy(() -> service.create(TENANT, AUTHOR, pack, "abc", null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.create(TENANT, AUTHOR, pack, "2.0.0", "9.9.9"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors())
                    .extracting(ProblemFieldError::field)
                    .containsExactly("cloneFromVersion"));
    assertThatThrownBy(() -> service.create(TENANT, AUTHOR, UUID.randomUUID(), "1.0.0", null))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(service.listVersions(TENANT, pack, null, null, 10))
        .extracting(UiPackVersion::version)
        .containsExactly("1.1.0", "1.0.0");
    assertThat(service.listVersions(TENANT, pack, UiPackVersionStatus.CHO_DUYET, null, 10))
        .isEmpty();
    assertThat(service.get(TENANT, pack, "1.0.0").id()).isEqualTo(v1.id());
  }

  @Test
  void updateChecksDraftVersionSchemaWidgetsAndCheckers() throws Exception {
    UUID pack = packId();
    UiPackVersion v = service.create(TENANT, AUTHOR, pack, "1.0.0", null);
    JsonNode tokens = service.read(v.content().tokensJson());
    ObjectNode layouts = (ObjectNode) service.read(v.content().layoutsJson());
    UiPackVersion saved =
        service.update(TENANT, AUTHOR, pack, "1.0.0", 0, content(tokens, layouts));
    assertThat(saved.rowVersion()).isEqualTo(1);
    assertThat(saved.content().fewSteps()).isTrue();

    assertThatThrownBy(
            () -> service.update(TENANT, AUTHOR, pack, "1.0.0", 0, content(tokens, layouts)))
        .isInstanceOf(VersionConflictException.class);

    ObjectNode html = tokens.deepCopy();
    ((ObjectNode) html.at("/color/primary")).put("$value", "<script>");
    assertThatThrownBy(
            () -> service.update(TENANT, AUTHOR, pack, "1.0.0", 1, content(html, layouts)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-422-VALIDATION")
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors())
                    .extracting(ProblemFieldError::field)
                    .contains("tokens.color.primary.$value"));

    ObjectNode many = layouts.deepCopy();
    ArrayNode children = (ArrayNode) many.at("/home/children");
    for (int i = 0; i < 12; i++) {
      children.addObject().put("component", "vt.widget.kpiTile");
    }
    assertThatThrownBy(
            () -> service.update(TENANT, AUTHOR, pack, "1.0.0", 1, content(tokens, many)))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errors())
                    .extracting(ProblemFieldError::field)
                    .contains("layouts.home.children"));

    checkerResults.add(
        new UiPackChecker.Result(
            UiPackChecker.Code.REGISTRY,
            false,
            "Thành phần chưa có trong danh mục.",
            "MSG-18-02",
            List.of(new ProblemFieldError("layouts.home.component", "Thành phần lạ", "BR-18-02"))));
    assertThatThrownBy(
            () -> service.update(TENANT, AUTHOR, pack, "1.0.0", 1, content(tokens, layouts)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("UI-422-UNKNOWN-COMPONENT");
    checkerResults.set(
        0,
        new UiPackChecker.Result(
            UiPackChecker.Code.CONTRAST, false, "Tương phản thấp", "MSG-18-01", null));
    assertThatThrownBy(
            () -> service.update(TENANT, AUTHOR, pack, "1.0.0", 1, content(tokens, layouts)))
        .hasMessage("UI-422-CONTRAST");
    checkerResults.set(
        0,
        new UiPackChecker.Result(UiPackChecker.Code.CEL_PARITY, false, "Khác kết quả", null, null));
    assertThatThrownBy(
            () -> service.update(TENANT, AUTHOR, pack, "1.0.0", 1, content(tokens, layouts)))
        .hasMessage("UI-422-CEL-INVALID");
    checkerResults.set(
        0, new UiPackChecker.Result(UiPackChecker.Code.SIZE, false, "Quá 30 KB", null, null));
    assertThatThrownBy(
            () -> service.update(TENANT, AUTHOR, pack, "1.0.0", 1, content(tokens, layouts)))
        .hasMessage("COMMON-422-VALIDATION");
    checkerResults.clear();

    assertThatThrownBy(() -> service.content(tokens, layouts, null, null, null, false, false))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void submitRunsChecksThenRejectReturnsToDraft() throws Exception {
    UUID pack = packId();
    service.create(TENANT, AUTHOR, pack, "1.0.0", null);
    String job = service.submit(TENANT, AUTHOR, pack, "1.0.0", 0);
    assertThat(jobs.state.get(job))
        .isEqualTo("HOAN_TAT /api/v1/ui/packs/" + pack + "/versions/1.0.0");
    UiPackVersion waiting = service.get(TENANT, pack, "1.0.0");
    assertThat(waiting.status()).isEqualTo(UiPackVersionStatus.CHO_DUYET);
    assertThat(notices).containsExactly("review:1.0.0:[" + REVIEWER + "]");
    assertThat(audits).contains("GUI_DUYET_UI_PACK:1.0.0:1", "KIEM_TRA_UI_PACK_DAT:1.0.0:2");
    assertThatThrownBy(() -> service.submit(TENANT, AUTHOR, pack, "1.0.0", 2))
        .hasMessage("UI-409-NOT-DRAFT");
    assertThatThrownBy(() -> service.update(TENANT, AUTHOR, pack, "1.0.0", 2, waiting.content()))
        .hasMessage("UI-409-NOT-DRAFT");

    assertThatThrownBy(
            () ->
                service.reject(
                    TENANT, new Actor(REVIEWER, "aal1", null), pack, "1.0.0", 2, "Chưa đạt"))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                service.reject(
                    TENANT,
                    new Actor(REVIEWER, "aal2", NOW.getEpochSecond() - 3600),
                    pack,
                    "1.0.0",
                    2,
                    "Chưa đạt"))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(() -> service.reject(TENANT, mfa(AUTHOR), pack, "1.0.0", 2, "Chưa đạt"))
        .hasMessage("UI-403-SAME-PERSON");
    assertThatThrownBy(() -> service.reject(TENANT, mfa(REVIEWER), pack, "1.0.0", 2, "  "))
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.reject(TENANT, mfa(REVIEWER), pack, "1.0.0", 1, "Chưa đạt"))
        .isInstanceOf(VersionConflictException.class);
    UiPackService.Rejected r =
        service.reject(TENANT, mfa(REVIEWER), pack, "1.0.0", 2, " Chỉnh lại màu chữ ");
    assertThat(r.comment()).isEqualTo("Chỉnh lại màu chữ");
    assertThat(r.version().status()).isEqualTo(UiPackVersionStatus.NHAP);
    assertThat(notices).contains("rejected:Chỉnh lại màu chữ:[" + AUTHOR + "]");
    assertThatThrownBy(() -> service.reject(TENANT, mfa(REVIEWER), pack, "1.0.0", 3, "Lần hai"))
        .hasMessage("UI-409-NOT-DRAFT");
  }

  @Test
  void failedChecksSendDraftBackWithErrors() {
    UUID pack = packId();
    service.create(TENANT, AUTHOR, pack, "1.0.0", null);
    checkerResults.add(new UiPackChecker.Result(UiPackChecker.Code.SIZE, false, null, null, null));
    checkerResults.add(
        new UiPackChecker.Result(
            UiPackChecker.Code.CONTRAST,
            false,
            "Tương phản thấp",
            "MSG-18-01",
            List.of(new ProblemFieldError("tokens.color.primary", "3,1 : 1", "BR-18-01"))));
    String job = service.submit(TENANT, AUTHOR, pack, "1.0.0", 0);
    assertThat(jobs.state.get(job)).isEqualTo("THAT_BAI");
    assertThat(jobs.errors.get(job))
        .extracting(ProblemFieldError::field)
        .containsExactly("SIZE", "tokens.color.primary");
    assertThat(service.get(TENANT, pack, "1.0.0").status()).isEqualTo(UiPackVersionStatus.NHAP);
    assertThat(notices).isEmpty();
    assertThat(audits).contains("KIEM_TRA_UI_PACK_KHONG_DAT:1.0.0:2");

    // Phiên bản bị đổi giữa chừng: tác vụ báo lỗi hệ thống, không treo.
    String lost = "lost";
    jobs.accepted(TENANT, lost, AUTHOR);
    service.check(TENANT, pack, "9.9.9", lost);
    assertThat(jobs.state.get(lost)).isEqualTo("THAT_BAI");
  }

  @Test
  void checkCrashReturnsVersionToDraftSoAuthorCanResubmit() {
    UUID pack = packId();
    service.create(TENANT, AUTHOR, pack, "1.0.0", null);
    checkerBroken = true;
    String job = service.submit(TENANT, AUTHOR, pack, "1.0.0", 0);
    assertThat(jobs.state.get(job)).isEqualTo("THAT_BAI");
    UiPackVersion back = service.get(TENANT, pack, "1.0.0");
    assertThat(back.status()).isEqualTo(UiPackVersionStatus.NHAP);

    checkerBroken = false;
    String again = service.submit(TENANT, AUTHOR, pack, "1.0.0", back.rowVersion());
    assertThat(jobs.state.get(again)).startsWith("HOAN_TAT");
  }

  @Test
  void previewFlattensTokensAndAppliesElderlyMode() {
    UUID pack = packId();
    service.create(TENANT, AUTHOR, pack, "1.0.0", null);
    UiPackService.Preview normal = service.preview(TENANT, pack, "1.0.0", false);
    assertThat(normal.mode()).isEqualTo("default");
    assertThat(normal.cssVariables())
        .containsEntry("--vt-color-primary", "#0A5FA8")
        .containsEntry("--vt-font-size-body", "15px")
        .doesNotContainKey("--vt-$extensions");
    assertThat(normal.checks()).singleElement().satisfies(c -> assertThat(c.passed()).isTrue());
    assertThat(normal.bundleSizeGzipBytes()).isPositive();
    assertThat(normal.layouts().has("home")).isTrue();
    UiPackService.Preview elderly = service.preview(TENANT, pack, "1.0.0", true);
    assertThat(elderly.mode()).isEqualTo("elderly");
    assertThat(elderly.cssVariables()).containsEntry("--vt-font-size-body", "18px");
    assertThatThrownBy(() -> service.preview(TENANT, pack, "2.0.0", false))
        .isInstanceOf(NoSuchElementException.class);
  }
}
