package vn.viettel.okrkpi.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.scoring.application.policy.PolicyCompiler;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.ProfileScorer;
import vn.viettel.okrkpi.scoring.application.policy.RatingClassifier;
import vn.viettel.okrkpi.scoring.application.policy.SamplePolicyPack;
import vn.viettel.okrkpi.scoring.application.policy.ScoreCalculator;
import vn.viettel.okrkpi.scoring.application.port.PolicyPackSource;
import vn.viettel.okrkpi.scoring.application.port.ScoreChunkQueue;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput;
import vn.viettel.okrkpi.scoring.domain.JobScope;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreJobError;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.infrastructure.policy.FlowableDecisionTables;

/**
 * Tính một lô: phiên bản ghim theo hồ sơ, kiểm khớp phiên bản lõi, hồ sơ lỗi, tính lại đối chiếu.
 */
class ScoreJobRunnerTest {

  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a001");
  static final UUID PERIOD = UUID.fromString("0192f1a2-0000-7000-8000-0000000000b1");
  static final UUID ACTOR = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2027-04-02T03:00:00Z");

  private static FlowableDecisionTables tables;
  private static PolicyContent pack;
  private static PolicyContent newer;
  private static final ProfileScorer SCORER =
      new ProfileScorer(new ScoreCalculator(), new RatingClassifier());

  private final StoredScores stored = mock(StoredScores.class);
  private PinnedPolicy pinned;
  private final PolicyPackCache cache =
      new PolicyPackCache(
          new PolicyPackSource() {
            @Override
            public Optional<PinnedPolicy> resolve(PolicyBindingKey key) {
              return "NHA_NUOC".equals(key.flow()) ? Optional.empty() : Optional.of(pinned);
            }

            @Override
            public PolicyContent load(UUID tenantId, UUID packId, UUID versionId, String version) {
              return versionId.equals(pack.pinned().versionId()) ? pack : newer;
            }
          },
          new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables),
          4);
  private final ScoreJobRunner runner =
      new ScoreJobRunner(
          cache, new InputAssembler(), SCORER, stored, Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeAll
  static void compile() {
    tables = new FlowableDecisionTables();
    pack = SamplePolicyPack.content();
    newer =
        SamplePolicyPack.content(
            pack.pinned().packId(), UUID.randomUUID(), "2.2.0", Map.of("kpi_C", "1.0"));
  }

  @AfterAll
  static void stop() {
    tables.destroy();
  }

  static ScoreChunkQueue.Job job(ScoreJobKind kind, JobScope scope) {
    return new ScoreChunkQueue.Job(
        new ScoreChunkQueue.Ref(TENANT, 20271, UUID.randomUUID()),
        PERIOD,
        kind,
        scope.toDoc().json(),
        pack.pinned().versionId(),
        ACTOR,
        0);
  }

  static CoreScoringInput in(String flow, UUID version, String quality) {
    return new CoreScoringInput(
        UUID.randomUUID(),
        UUID.randomUUID(),
        flow,
        UUID.randomUUID(),
        null,
        false,
        false,
        version,
        List.of(new CoreScoringInput.Criterion("TCC-01", true)),
        List.of(
            InputAssemblerTest.item("THEN_CHOT", "HOAN_THANH", quality, 0, false),
            InputAssemblerTest.item("THUONG_XUYEN", "HOAN_THANH", "1", 0, false)),
        0);
  }

  @Test
  void tinhTrongPhamViVaGomTheoPhienBan() {
    pinned = pack.pinned();
    UUID v = pack.pinned().versionId();
    CoreScoringInput ok = in("DANG", v, "0.75");
    CoreScoringInput noVersion = in("DANG", null, "1");
    CoreScoringInput mismatch = in("DANG", UUID.randomUUID(), "1");
    CoreScoringInput badQuality = in("DANG", v, "0.6");
    CoreScoringInput noBinding = in("NHA_NUOC", v, "1");
    CoreScoringInput outside = in("DANG", v, "1");
    JobScope scope =
        JobScope.of("CHOT_KY")
            .withProfiles(
                List.of(
                    ok.profileId(),
                    noVersion.profileId(),
                    mismatch.profileId(),
                    badQuality.profileId(),
                    noBinding.profileId()));

    ScoreJobRunner.Computed out =
        runner.scorePage(
            job(ScoreJobKind.CHOT_KY, scope),
            List.of(ok, noVersion, mismatch, badQuality, noBinding, outside));

    assertThat(out.scored()).isEqualTo(2);
    assertThat(out.batches()).hasSize(1);
    ScoreBatch b = out.batches().get(0);
    assertThat(b.policyPackVersionId()).isEqualTo(v);
    assertThat(b.jobKind()).isEqualTo(ScoreJobKind.CHOT_KY);
    assertThat(b.computedAt()).isEqualTo(NOW);
    assertThat(b.engineVersion()).isEqualTo(ScoreJobRunner.ENGINE_VERSION);
    assertThat(b.items())
        .extracting(ComputedScore::profileId)
        .containsExactly(ok.profileId(), noVersion.profileId());
    assertThat(out.errors())
        .extracting(ScoreJobError::reasonCode)
        .containsExactly(
            ScoreJobError.POLICY_MISMATCH,
            ScoreJobError.INPUT_INVALID,
            ScoreJobError.POLICY_MISSING);
    assertThat(out.errors().get(0).profileId()).isEqualTo(mismatch.profileId());
  }

  @Test
  void hauKiemDungPhienBanGocDuKyDaGhimPhienBanMoi() {
    pinned = newer.pinned();
    JobScope scope =
        JobScope.of("MO_LAI_HAU_KIEM")
            .withPolicyHashes(
                Map.of("DANG", pack.pinned().versionId() + ":" + pack.pinned().contentHash()));
    // Lõi ghi phiên bản mới nhưng tính lại hậu kiểm vẫn dùng phiên bản gốc (BR-13-06).
    CoreScoringInput dang = in("DANG", newer.pinned().versionId(), "1");
    CoreScoringInput nhaNuoc = in("NHA_NUOC", null, "1");
    ScoreJobRunner.Computed out =
        runner.scorePage(job(ScoreJobKind.TINH_LAI_HAU_KIEM, scope), List.of(dang, nhaNuoc));
    assertThat(out.batches()).hasSize(1);
    assertThat(out.batches().get(0).policyPackVersionId()).isEqualTo(pack.pinned().versionId());
    assertThat(out.errors())
        .extracting(ScoreJobError::reasonCode)
        .containsExactly(ScoreJobError.POLICY_MISSING);
  }

  @Test
  void tinhLaiDoiChieuChoCungBamKetQua() {
    pinned = pack.pinned();
    CoreScoringInput src = in("DANG", null, "0.75");
    var policy = cache.forBinding(src.bindingKey(TENANT, PERIOD));
    var input = new InputAssembler().assemble(policy, src).input();
    ComputedScore original = SCORER.score(UUID.randomUUID(), policy, input);
    UUID resultId = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    UUID broken = UUID.randomUUID();
    when(stored.byId(anyInt(), org.mockito.ArgumentMatchers.eq(resultId)))
        .thenReturn(Optional.of(row(resultId, src, input.document().json())));
    when(stored.byId(anyInt(), org.mockito.ArgumentMatchers.eq(broken)))
        .thenReturn(Optional.of(row(broken, src, "[1]")));
    when(stored.byId(anyInt(), org.mockito.ArgumentMatchers.eq(missing)))
        .thenReturn(Optional.empty());
    JobScope scope =
        JobScope.parse(
            "{\"recheckOf\":\""
                + UUID.randomUUID()
                + "\",\"results\":[\""
                + resultId
                + "\",\""
                + missing
                + "\",\""
                + broken
                + "\"]}");

    ScoreJobRunner.Computed out = runner.recheck(job(ScoreJobKind.KIEM_TRA_TAI_LAP, scope), 0);
    assertThat(out.scored()).isEqualTo(1);
    ComputedScore again = out.batches().get(0).items().get(0);
    assertThat(again.result().hash()).isEqualTo(original.result().hash());
    assertThat(again.totalScore()).isEqualByComparingTo(original.totalScore());
    assertThat(out.batches().get(0).jobKind()).isEqualTo(ScoreJobKind.KIEM_TRA_TAI_LAP);
    assertThat(out.errors())
        .extracting(ScoreJobError::reasonCode)
        .containsExactly(ScoreJobError.SOURCE_MISSING, ScoreJobError.SCORING_FAILED);
    assertThat(runner.recheck(job(ScoreJobKind.KIEM_TRA_TAI_LAP, scope), 600).batches()).isEmpty();
  }

  private StoredScores.Stored row(UUID id, CoreScoringInput src, String doc) {
    return new StoredScores.Stored(
        id,
        TENANT,
        PERIOD,
        src.profileId(),
        src.personId(),
        src.flow(),
        pack.pinned().versionId(),
        pack.pinned().contentHash(),
        "2.1.0",
        doc,
        20271,
        UUID.randomUUID());
  }
}
