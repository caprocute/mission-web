package vn.viettel.okrkpi.workflow.application.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.workflow.application.port.BindingDraftStore;
import vn.viettel.okrkpi.workflow.application.port.TemplateDirectory;
import vn.viettel.okrkpi.workflow.domain.binding.BindingDraft;
import vn.viettel.okrkpi.workflow.domain.binding.BindingScope;
import vn.viettel.okrkpi.workflow.domain.binding.BindingStatus;
import vn.viettel.okrkpi.workflow.domain.binding.SampleRun;
import vn.viettel.okrkpi.workflow.domain.binding.SimulationSample;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateInfo;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateParamRules;

/** Mã lỗi và thứ tự kiểm khi lưu tham số luồng (hợp đồng {@code saveWorkflowBinding}). */
class WorkflowBindingServiceTest {

  static final UUID TENANT = UUID.randomUUID();
  static final UUID VERSION = UUID.randomUUID();

  final TemplateInfo template =
      new TemplateInfo(
          "duyet-ho-so",
          "Duyệt hồ sơ",
          1,
          "DANH_GIA",
          new TemplateParamRules(2, 4, Set.of(), Set.of(TemplateParamRules.MIDDLE), List.of()));
  final Map<String, BindingDraft> store = new HashMap<>();
  boolean samplesPass = true;
  int runs;

  final WorkflowBindingService service =
      new WorkflowBindingService(
          new Drafts(),
          new TemplateDirectory() {
            @Override
            public List<TemplateInfo> published() {
              return List.of(template);
            }

            @Override
            public Optional<TemplateInfo> find(String key, int versionNo) {
              return published().stream()
                  .filter(t -> t.key().equals(key) && t.versionNo() == versionNo)
                  .findFirst();
            }
          },
          (t, params, sample, skipped) -> {
            runs++;
            return new SampleRun(sample, List.of(), sample.expectedEnd(), samplesPass);
          },
          new CelSkipConditions());

  static Map<String, Object> params(String skip) {
    List<Map<String, Object>> cap = new ArrayList<>();
    cap.add(Map.of("vai_tro", "member"));
    Map<String, Object> middle = new HashMap<>(Map.of("vai_tro", "manager"));
    if (skip != null) {
      middle.put("bo_qua_khi", skip);
    }
    cap.add(middle);
    cap.add(Map.of("vai_tro", "decider"));
    return new HashMap<>(Map.of("cap", cap));
  }

  WorkflowBindingService.SaveCommand command(Map<String, Object> params, long expected) {
    return new WorkflowBindingService.SaveCommand(
        TENANT,
        VERSION,
        "DANH_GIA",
        "duyet-ho-so",
        1,
        new BindingScope("DANG", null, "QUY", UUID.randomUUID()),
        params,
        expected,
        UUID.randomUUID());
  }

  @Test
  void loiKieuCelRiengThiTraMaCelType() {
    assertThatThrownBy(() -> service.save(command(params("muc_tham_dinh"), 0)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo(WorkflowBindingService.CEL_TYPE));
    Map<String, Object> mixed = params("muc_tham_dinh");
    mixed.put("khi_qua_han", "HUY");
    assertThatThrownBy(() -> service.save(command(mixed, 0)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo(WorkflowBindingService.WF_PARAMS));
  }

  @Test
  void hoSoMauKhongDatThiKhongLuu() {
    samplesPass = false;
    assertThatThrownBy(() -> service.save(command(params("khoi == 'DANG'"), 0)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getMessage()).isEqualTo(WorkflowBindingService.WF_PARAMS);
              assertThat(e.errors()).allMatch(f -> "kiem-thu-mau".equals(f.rule()));
            });
    assertThat(store).isEmpty();
  }

  @Test
  void lechIfMatchBaoTruocKhiChayEngineThu() {
    assertThatThrownBy(() -> service.save(command(params(null), 3)))
        .isInstanceOf(VersionConflictException.class);
    assertThat(runs).isZero();
    BindingDraft saved = service.save(command(params(null), 0));
    assertThat(saved.rowVersion()).isEqualTo(1);
    assertThat(saved.tests().total()).isEqualTo(7);
    assertThatThrownBy(() -> service.save(command(params(null), 0)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void loaiQuyTrinhKhacMauThiKhongNhan() {
    WorkflowBindingService.SaveCommand c = command(params(null), 0);
    WorkflowBindingService.SaveCommand wrongKind =
        new WorkflowBindingService.SaveCommand(
            c.tenantId(),
            c.packVersionId(),
            "OKR",
            c.templateKey(),
            1,
            c.scope(),
            c.params(),
            0,
            c.actor());
    assertThatThrownBy(() -> service.save(wrongKind))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errors().get(0).rule()).isEqualTo("loai-quy-trinh"));
  }

  @Test
  void luuLaiCapNhatBanNhapVaKhoaKhiDaHieuLuc() {
    BindingDraft first = service.save(command(params(null), 0));
    BindingDraft second = service.save(command(params(null), first.rowVersion()));
    assertThat(second.rowVersion()).isEqualTo(2);
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(service.list(TENANT, VERSION)).hasSize(1);
    assertThat(service.find(TENANT, VERSION, "DANH_GIA")).isPresent();
    store.put("DANH_GIA", withStatus(second, BindingStatus.HIEU_LUC));
    assertThatThrownBy(() -> service.save(command(params(null), 2)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo(WorkflowBindingService.FROZEN));
    assertThat(service.activate(TENANT, VERSION, UUID.randomUUID())).isEmpty();
    assertThat(service.deactivate(TENANT, VERSION, UUID.randomUUID())).isEmpty();
  }

  @Test
  void mauChuaPhatHanhVaPhamViSaiThiKhongNhan() {
    WorkflowBindingService.SaveCommand c = command(params(null), 0);
    WorkflowBindingService.SaveCommand missing =
        new WorkflowBindingService.SaveCommand(
            TENANT, VERSION, "DANH_GIA", "khong-co", 1, c.scope(), c.params(), 0, c.actor());
    assertThatThrownBy(() -> service.save(missing))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo(WorkflowBindingService.TEMPLATE_NOT_ACTIVE));
    WorkflowBindingService.SaveCommand badScope =
        new WorkflowBindingService.SaveCommand(
            TENANT,
            VERSION,
            "DANH_GIA",
            "duyet-ho-so",
            1,
            new BindingScope("KHAC", null, " ", UUID.randomUUID()),
            c.params(),
            0,
            c.actor());
    assertThatThrownBy(() -> service.save(badScope))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getMessage()).isEqualTo(WorkflowBindingService.WF_PARAMS);
              assertThat(e.errors()).hasSize(2).allMatch(f -> "pham-vi-ap-dung".equals(f.rule()));
            });
    WorkflowBindingService.SaveCommand longPeriod =
        new WorkflowBindingService.SaveCommand(
            TENANT,
            VERSION,
            "DANH_GIA",
            "duyet-ho-so",
            1,
            new BindingScope(null, null, "QUA_DAI_9_KY", UUID.randomUUID()),
            c.params(),
            0,
            c.actor());
    assertThatThrownBy(() -> service.save(longPeriod)).isInstanceOf(BusinessException.class);
  }

  @Test
  void moPhongLocCaMauVaKhongChayKhiLintHong() {
    String chosen = SimulationSample.values()[0].name();
    WorkflowBindingService.Simulation all = service.simulate("duyet-ho-so", 1, params(null), null);
    assertThat(all.sampleRuns()).hasSize(7);
    assertThat(all.templateTests().allPassed()).isTrue();
    assertThat(all.steps()).hasSize(3);
    WorkflowBindingService.Simulation one =
        service.simulate("duyet-ho-so", 1, params(null), List.of(chosen));
    assertThat(one.sampleRuns()).hasSize(1);
    assertThat(one.templateTests().total()).isEqualTo(7);
    int before = runs;
    WorkflowBindingService.Simulation broken =
        service.simulate("duyet-ho-so", 1, params("muc_tham_dinh"), List.of());
    assertThat(broken.lint().passed()).isFalse();
    assertThat(broken.templateTests()).isNull();
    assertThat(broken.sampleRuns()).isEmpty();
    assertThat(runs).isEqualTo(before);
    assertThatThrownBy(() -> service.simulate("duyet-ho-so", 1, params(null), List.of("LA")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errors().get(0).rule()).isEqualTo("ho-so-mau"));
  }

  @Test
  void danhMucMauLocTheoLoaiVaTuKhoa() {
    assertThat(service.templates(null, null)).hasSize(1);
    assertThat(service.templates("DANH_GIA", " DUYỆT ")).hasSize(1);
    assertThat(service.templates("DANH_GIA", "ho-so")).hasSize(1);
    assertThat(service.templates("DANH_GIA", "")).hasSize(1);
    assertThat(service.templates("OKR", null)).isEmpty();
    assertThat(service.templates(null, "khong-khop")).isEmpty();
    assertThat(service.findTemplate("duyet-ho-so", 1)).contains(template);
    assertThat(service.findTemplate("duyet-ho-so", 2)).isEmpty();
    assertThat(WorkflowBindingService.previewSteps(template)).isNotNull();
    assertThat(WorkflowBindingService.testCount()).isEqualTo(7);
  }

  static BindingDraft withStatus(BindingDraft d, BindingStatus status) {
    return new BindingDraft(
        d.id(),
        d.tenantId(),
        d.packVersionId(),
        d.processKind(),
        d.templateKey(),
        d.templateVersionNo(),
        d.scope(),
        d.params(),
        d.lint(),
        d.tests(),
        status,
        d.rowVersion());
  }

  final class Drafts implements BindingDraftStore {
    @Override
    public List<BindingDraft> list(UUID tenantId, UUID packVersionId) {
      return List.copyOf(store.values());
    }

    @Override
    public Optional<BindingDraft> find(UUID tenantId, UUID packVersionId, String processKind) {
      return Optional.ofNullable(store.get(processKind));
    }

    @Override
    public BindingDraft insert(BindingDraft d, UUID actor) {
      BindingDraft saved = copy(d, 1);
      store.put(d.processKind(), saved);
      return saved;
    }

    @Override
    public Optional<BindingDraft> update(BindingDraft d, long expected, UUID actor) {
      BindingDraft saved = copy(d, expected + 1);
      store.put(d.processKind(), saved);
      return Optional.of(saved);
    }

    @Override
    public List<BindingDraft> activate(UUID tenantId, UUID packVersionId, UUID actor) {
      return List.of();
    }

    @Override
    public List<BindingDraft> deactivate(UUID tenantId, UUID packVersionId, UUID actor) {
      return List.of();
    }

    private BindingDraft copy(BindingDraft d, long rowVersion) {
      return new BindingDraft(
          d.id(),
          d.tenantId(),
          d.packVersionId(),
          d.processKind(),
          d.templateKey(),
          d.templateVersionNo(),
          d.scope(),
          d.params(),
          d.lint(),
          d.tests(),
          d.status(),
          rowVersion);
    }
  }
}
