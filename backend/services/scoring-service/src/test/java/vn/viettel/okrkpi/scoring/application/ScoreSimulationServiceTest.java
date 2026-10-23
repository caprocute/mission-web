package vn.viettel.okrkpi.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.scoring.application.policy.CompiledPolicy;
import vn.viettel.okrkpi.scoring.application.policy.PolicyCompiler;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.ProfileScorer;
import vn.viettel.okrkpi.scoring.application.policy.RatingClassifier;
import vn.viettel.okrkpi.scoring.application.policy.SamplePolicyPack;
import vn.viettel.okrkpi.scoring.application.policy.ScoreCalculator;
import vn.viettel.okrkpi.scoring.application.port.Designations;
import vn.viettel.okrkpi.scoring.application.port.PolicyPackSource;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.DelaySegment;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.scoring.domain.ProductInput;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.scoring.domain.TaskInput;
import vn.viettel.okrkpi.scoring.infrastructure.policy.FlowableDecisionTables;
import vn.viettel.okrkpi.shared.authz.AuthzResource;

/**
 * Tính thử và xem trước (DV-07-08): phân giải phiên bản gói gốc theo băm, kiểm miền điều chỉnh, câu
 * giải thích bằng nhãn nghiệp vụ, tài nguyên OPA.
 */
class ScoreSimulationServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID PERIOD = UUID.randomUUID();
  private static final UUID PROFILE = UUID.randomUUID();
  private static final UUID PERSON = UUID.randomUUID();
  private static final UUID TASK = UUID.randomUUID();
  private static final UUID HANDLER = UUID.randomUUID();

  private static final Map<String, Object> PARTY =
      Map.of(
          "ty_le_O", 1.0,
          "ty_le_dap_ung_tieu_chi_chung", 1.0,
          "giai_phap_dot_pha_da_kiem_chung", true,
          "loai_tru", false,
          "la_nguoi_dung_dau", false,
          "tap_the_dat_05_chi_tieu", true,
          "tap_the_dat_tat_ca_O", true,
          "ty_le_don_vi_khtnv", 0.0,
          "giu_chuc_vu", false,
          "xep_loai_nha_nuoc", "");

  private static FlowableDecisionTables tables;
  private static PolicyCompiler compiler;
  private static PolicyContent old;
  private static PolicyContent newer;
  private static CompiledPolicy original;
  private static final ProfileScorer SCORER =
      new ProfileScorer(new ScoreCalculator(), new RatingClassifier());

  @BeforeAll
  static void compile() {
    tables = new FlowableDecisionTables();
    compiler = new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables);
    old = SamplePolicyPack.content();
    newer =
        SamplePolicyPack.content(
            old.pinned().packId(), UUID.randomUUID(), "2.2.0", Map.of("kpi_C", "1.0"));
    original = compiler.compile(old);
  }

  @AfterAll
  static void stop() {
    tables.destroy();
  }

  /** Nguồn gói đếm số lần tải; {@code pinned} là phiên bản kỳ đang ghim. */
  private static final class Source implements PolicyPackSource {
    final PinnedPolicy pinned;
    final AtomicInteger loads = new AtomicInteger();

    Source(PinnedPolicy pinned) {
      this.pinned = pinned;
    }

    @Override
    public Optional<PinnedPolicy> resolve(PolicyBindingKey key) {
      return Optional.of(pinned);
    }

    @Override
    public PolicyContent load(UUID tenantId, UUID packId, UUID versionId, String version) {
      loads.incrementAndGet();
      return versionId.equals(old.pinned().versionId()) ? old : newer;
    }
  }

  private static StoredScores.Stored stored(UUID task, String policyVersion) {
    TaskInput nv =
        new TaskInput(
            task,
            "NV-1",
            "THEN_CHOT",
            1,
            new BigDecimal("30"),
            false,
            List.of(
                new ProductInput(
                    UUID.randomUUID(), ProductInput.CONFIRMED, BigDecimal.ONE, false, 0)),
            List.of(new DelaySegment(2, 2, DelaySegment.Cause.CHU_QUAN)));
    ScoringInput input = new ScoringInput(PROFILE, PERSON, "DANG", List.of(nv), PARTY);
    return new StoredScores.Stored(
        UUID.randomUUID(),
        TENANT,
        PERIOD,
        PROFILE,
        PERSON,
        "DANG",
        old.pinned().versionId(),
        original.contentHash(),
        policyVersion,
        input.document().json(),
        20271,
        UUID.randomUUID());
  }

  private static StoredScores store(StoredScores.Stored row) {
    return new StoredScores() {
      @Override
      public Optional<Stored> latest(UUID profileId) {
        return profileId.equals(row.profileId()) ? Optional.of(row) : Optional.empty();
      }

      @Override
      public Optional<Stored> latestWithTask(UUID taskId) {
        return row.inputDoc().contains(taskId.toString()) ? Optional.of(row) : Optional.empty();
      }

      @Override
      public Optional<Stored> byId(int periodBucket, UUID resultId) {
        return Optional.empty();
      }

      @Override
      public java.util.List<Affected> withTask(UUID taskId) {
        return java.util.List.of();
      }

      @Override
      public Optional<Integer> bucketOf(UUID periodId) {
        return Optional.empty();
      }

      @Override
      public java.util.List<Stored> originals(UUID periodId, int periodBucket) {
        return java.util.List.of();
      }
    };
  }

  private static ScoreSimulationService service(
      StoredScores store, PolicyPackCache cache, ScoreSimulationService.Guard guard) {
    Designations designations =
        new Designations() {
          @Override
          public List<UUID> forTask(UUID taskId) {
            return List.of(HANDLER);
          }

          @Override
          public List<UUID> forProfile(UUID profileId) {
            return List.of(HANDLER);
          }
        };
    return new ScoreSimulationService(store, cache, SCORER, designations, guard);
  }

  private static ScoreSimulationService.Adjustment adj(String component, UUID task, String v) {
    return new ScoreSimulationService.Adjustment(
        component, task, v == null ? null : new BigDecimal(v));
  }

  @Test
  void kyGhimPhienBanMoiThiTaiDungPhienBanGocTheoBam() {
    Source source = new Source(newer.pinned());
    PolicyPackCache cache = new PolicyPackCache(source, compiler, 4);
    AtomicReference<ScoreSimulationService.ProfileSimulation> checked = new AtomicReference<>();
    ScoreSimulationService.Guard guard =
        new ScoreSimulationService.Guard() {
          @Override
          public void checkSimulate(ScoreSimulationService.ProfileSimulation resource) {
            checked.set(resource);
          }
        };
    ScoreSimulationService service = service(store(stored(TASK, null)), cache, guard);

    ScoreSimulationService.Simulation s = service.simulate(PROFILE, List.of(adj("c", TASK, "1")));
    ComputedScore expected = SCORER.score(UUID.randomUUID(), original, input());
    assertThat(s.before().totalScore()).isEqualByComparingTo(expected.totalScore());
    assertThat(s.after().totalScore()).isGreaterThan(s.before().totalScore());
    assertThat(s.policyVersion()).isEqualTo("2.1.0");
    assertThat(s.explanation()).contains("điểm tăng từ").contains("mức xếp loại");
    assertThat(cache.cached(original.contentHash())).isPresent();
    assertThat(source.loads).hasValue(1);

    AuthzResource resource = checked.get().toAuthzResource();
    assertThat(resource.type()).isEqualTo("appraisal_profile");
    assertThat(resource.id()).isEqualTo(PROFILE.toString());
    assertThat(resource.ownerIds()).containsExactly(PERSON.toString());
    assertThat(resource.designatedIds()).containsExactly(HANDLER.toString());

    // Lần sau trúng bộ đệm theo băm, không tải lại.
    service.simulate(PROFILE, List.of(adj("A", TASK, "0.5")));
    assertThat(source.loads).hasValue(1);
  }

  @Test
  void kyGhimCungBamThiDungPhienBanGhim() {
    Source source = new Source(old.pinned());
    PolicyPackCache cache = new PolicyPackCache(source, compiler, 4);
    ScoreSimulationService service =
        service(store(stored(TASK, "2.1.0")), cache, new ScoreSimulationService.Guard());
    ScoreSimulationService.Simulation s =
        service.simulate(PROFILE, List.of(adj("B", TASK, "0.25")));
    assertThat(s.after().totalScore()).isLessThan(s.before().totalScore());
    assertThat(s.explanation()).contains("điểm giảm từ").contains(" xuống ");
    assertThat(source.loads).hasValue(1);
  }

  @Test
  void bienHoSoTheoKieuKhaiBaoVaGiaiThichKhiDoiMuc() {
    PolicyPackCache cache = new PolicyPackCache(new Source(old.pinned()), compiler, 4);
    ScoreSimulationService service =
        service(store(stored(TASK, null)), cache, new ScoreSimulationService.Guard());

    ScoreSimulationService.Simulation same =
        service.simulate(PROFILE, List.of(adj("giai_phap_dot_pha_da_kiem_chung", null, "1")));
    assertThat(same.after()).isEqualTo(same.before());
    assertThat(same.explanation()).contains("điểm giữ nguyên").contains("mức xếp loại giữ nguyên");

    ScoreSimulationService.Simulation excluded =
        service.simulate(PROFILE, List.of(adj("loai_tru", null, "1")));
    assertThat(excluded.after().ratingCode()).isNotEqualTo(excluded.before().ratingCode());
    assertThat(excluded.explanation()).contains("mức xếp loại chuyển từ");

    ScoreSimulationService.Simulation ratio =
        service.simulate(PROFILE, List.of(adj("ty_le_O", null, "0.5")));
    assertThat(ratio.before()).isEqualTo(same.before());
  }

  @Test
  void dieuChinhSaiThi422VaKhongCoKetQuaThi404() {
    PolicyPackCache cache = new PolicyPackCache(new Source(old.pinned()), compiler, 4);
    ScoreSimulationService service =
        service(store(stored(TASK, null)), cache, new ScoreSimulationService.Guard());
    List<ScoreSimulationService.Adjustment> bad = new ArrayList<>();
    bad.add(adj("C", TASK, null));
    bad.add(adj("C", null, "1"));
    bad.add(adj("A", TASK, "-0.1"));
    bad.add(adj("loai_tru", null, "0.5"));
    bad.add(adj("xep_loai_nha_nuoc", null, "1"));
    bad.add(adj("nhiem_vu", null, "1"));
    bad.add(adj(null, null, "1"));
    bad.add(adj("ty_le_don_vi_khtnv", null, "-1"));
    assertThatThrownBy(() -> service.simulate(PROFILE, bad))
        .isInstanceOfSatisfying(
            ScoreSimulationService.Rejected.class,
            e ->
                assertThat(e.issues())
                    .extracting(ScoreSimulationService.Issue::field)
                    .containsExactly(
                        "adjustments[0].newValue",
                        "adjustments[1].taskId",
                        "adjustments[2].newValue",
                        "adjustments[3].newValue",
                        "adjustments[4].component",
                        "adjustments[5].component",
                        "adjustments[6].component",
                        "adjustments[7].newValue"));
    assertThatThrownBy(() -> service.simulate(UUID.randomUUID(), List.of(adj("C", TASK, "1"))))
        .isInstanceOf(ScoreTraceService.TraceNotFound.class);
  }

  @Test
  void xemTruocTheoMucChatLuongVaNguyenNhanChinhSua() {
    PolicyPackCache cache = new PolicyPackCache(new Source(old.pinned()), compiler, 4);
    AtomicReference<ScoreSimulationService.TaskPreview> checked = new AtomicReference<>();
    ScoreSimulationService.Guard guard =
        new ScoreSimulationService.Guard() {
          @Override
          public void checkPreview(ScoreSimulationService.TaskPreview resource) {
            checked.set(resource);
          }
        };
    ScoreSimulationService service = service(store(stored(TASK, null)), cache, guard);

    ScoreSimulationService.Preview p =
        service.preview(
            new ScoreSimulationService.PreviewRequest(
                TASK, new BigDecimal("0.5"), "THE_THUC", true));
    assertThat(p.a()).isEqualByComparingTo("1");
    assertThat(p.b()).isEqualByComparingTo("0.5");
    assertThat(p.c()).isEqualByComparingTo("0.9");
    assertThat(p.kpi()).isEqualByComparingTo("0.8");
    assertThat(p.policy()).isEqualTo("2.1.0");
    AuthzResource resource = checked.get().toAuthzResource();
    assertThat(resource.type()).isEqualTo("task");
    assertThat(resource.id()).isEqualTo(TASK.toString());
    assertThat(resource.designatedIds()).containsExactly(HANDLER.toString());

    ScoreSimulationService.Preview plain =
        service.preview(
            new ScoreSimulationService.PreviewRequest(TASK, BigDecimal.ONE, null, null));
    assertThat(plain.b()).isEqualByComparingTo("1");

    assertThatThrownBy(
            () ->
                service.preview(
                    new ScoreSimulationService.PreviewRequest(
                        UUID.randomUUID(), BigDecimal.ONE, null, null)))
        .isInstanceOfSatisfying(
            ScoreSimulationService.Rejected.class,
            e -> assertThat(e.issues()).extracting("field").containsExactly("taskId"));
  }

  @Test
  void tongHopAnhHuongMoLai() {
    PolicyPackCache cache = new PolicyPackCache(new Source(old.pinned()), compiler, 4);
    ScoreSimulationService service =
        service(store(stored(TASK, null)), cache, new ScoreSimulationService.Guard());
    ScoreSimulationService.Impact impact =
        service.simulateReopen(
            List.of(
                new ScoreSimulationService.ProfileAdjustments(
                    PROFILE, List.of(adj("loai_tru", null, "1"))),
                new ScoreSimulationService.ProfileAdjustments(
                    PROFILE, List.of(adj("giai_phap_dot_pha_da_kiem_chung", null, "1"))),
                new ScoreSimulationService.ProfileAdjustments(UUID.randomUUID(), List.of())));
    assertThat(impact.profiles()).isEqualTo(2);
    assertThat(impact.ratingChanged()).isEqualTo(1);
    assertThat(impact.simulations()).hasSize(2);
  }

  @Test
  void nhanVaSoKieuViet() {
    assertThat(ScoreSimulationService.label(original, "HTTNV"))
        .isEqualTo("hoàn thành tốt nhiệm vụ");
    assertThat(ScoreSimulationService.label(original, "KHAC")).isEqualTo("KHAC");
    assertThat(ScoreSimulationService.number(new BigDecimal("91.25"))).isEqualTo("91,25");
    assertThat(ScoreSimulationService.number(new BigDecimal("90"))).isEqualTo("90");
    assertThat(Designations.NONE.forTask(TASK)).isEmpty();
    assertThat(Designations.NONE.forProfile(PROFILE)).isEmpty();
  }

  @Test
  void bienDocLaiTheoKieuCuaGoi() {
    Map<String, Object> doc = new HashMap<>();
    doc.put("profile_id", PROFILE.toString());
    doc.put("ty_le_O", 1);
    doc.put("khong_khai_bao", 2);
    doc.put("nhiem_vu", List.of(Map.of("ke_hoach", 1.0, "muc_chat_luong", 1, "ma", "NV-1")));
    Map<String, Object> vars = ProfileScorer.variablesOf(original, doc);
    assertThat(vars).doesNotContainKey("profile_id");
    assertThat(vars.get("ty_le_O")).isEqualTo(1.0);
    assertThat(vars.get("khong_khai_bao")).isEqualTo(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> nv = ((List<Map<String, Object>>) vars.get("nhiem_vu")).get(0);
    assertThat(nv.get("ke_hoach")).isEqualTo(1L);
    assertThat(nv.get("muc_chat_luong")).isEqualTo(1.0);
    assertThat(nv.get("ma")).isEqualTo("NV-1");
  }

  private static ScoringInput input() {
    TaskInput nv =
        new TaskInput(
            TASK,
            "NV-1",
            "THEN_CHOT",
            1,
            new BigDecimal("30"),
            false,
            List.of(
                new ProductInput(
                    UUID.randomUUID(), ProductInput.CONFIRMED, BigDecimal.ONE, false, 0)),
            List.of(new DelaySegment(2, 2, DelaySegment.Cause.CHU_QUAN)));
    return new ScoringInput(PROFILE, PERSON, "DANG", List.of(nv), PARTY);
  }
}
