package vn.viettel.okrkpi.docgen.domain.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bất biến của mẫu, tác vụ sinh và tệp kết quả (DV-11-01). */
class RenderDomainTest {

  private static final Instant NOW = Instant.parse("2026-10-22T05:00:00Z");

  private static RenderJob job() {
    return RenderJob.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new byte[32],
        UUID.randomUUID(),
        NOW);
  }

  private static Template template(TemplateStatus status, List<String> groups) {
    return new Template(
        UUID.randomUUID(), UUID.randomUUID(), "MAU_2", 3, groups, "k", new byte[32], status);
  }

  @Test
  void jobFollowsItsLifecycle() {
    RenderJob job = job();
    assertThat(job.status()).isEqualTo(RenderJobStatus.CHO);
    assertThat(job.rowVersion()).isEqualTo(1);
    job.start(NOW);
    assertThat(job.attempts()).isEqualTo(1);
    job.complete(NOW);
    assertThat(job.status().isTerminal()).isTrue();
    assertThat(job.error()).isNull();
    assertThatThrownBy(() -> job.start(NOW)).isInstanceOf(IllegalStateException.class);
    assertThat(job.inputMatches(new byte[32])).isTrue();
    assertThat(job.inputMatches(new byte[31])).isFalse();
    assertThat(RenderJobStatus.CHO.isTerminal()).isFalse();
    assertThat(RenderJobStatus.DANG_SINH.isTerminal()).isFalse();
    assertThat(RenderJobStatus.LOI.isTerminal()).isTrue();
  }

  @Test
  void retryRequeuesUntilAttemptsRunOutAndErrorIsTruncated() {
    RenderJob job = job();
    job.start(NOW);
    assertThat(job.retryLater("tạm", 2, NOW)).isTrue();
    assertThat(job.status()).isEqualTo(RenderJobStatus.CHO);
    job.start(NOW);
    assertThat(job.retryLater("x".repeat(3000), 2, NOW)).isFalse();
    assertThat(job.status()).isEqualTo(RenderJobStatus.LOI);
    assertThat(job.error()).hasSize(RenderJob.MAX_ERROR);
    RenderJob other = job();
    other.start(NOW);
    other.fail(null, NOW);
    assertThat(other.error()).isNull();
    assertThatThrownBy(() -> job().complete(NOW)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                RenderJob.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new byte[8],
                    UUID.randomUUID(),
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void templateAppliesOnlyWhenActiveAndToItsGroups() {
    assertThat(template(TemplateStatus.HIEU_LUC, List.of()).appliesTo("KHOI_A")).isTrue();
    assertThat(template(TemplateStatus.HIEU_LUC, List.of("KHOI_A")).appliesTo("KHOI_A")).isTrue();
    assertThat(template(TemplateStatus.HIEU_LUC, List.of("KHOI_A")).appliesTo(null)).isTrue();
    assertThat(template(TemplateStatus.HIEU_LUC, List.of("KHOI_A")).appliesTo("KHOI_B")).isFalse();
    assertThat(template(TemplateStatus.NHAP, null).appliesTo("KHOI_A")).isFalse();
    Template t = template(TemplateStatus.NGUNG, null);
    t.contentSha256()[0] = 1;
    assertThat(t.contentSha256()[0]).isZero();
    assertThat(t).isEqualTo(t).isNotEqualTo(template(TemplateStatus.NGUNG, null)).isNotEqualTo("x");
    assertThat(t.hashCode()).isEqualTo(t.id().hashCode());
    assertThat(t.toString()).contains("MAU_2 v3");
    assertThatThrownBy(
            () ->
                new Template(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "M",
                    0,
                    null,
                    "k",
                    new byte[32],
                    TemplateStatus.NHAP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Template(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "M",
                    1,
                    null,
                    "k",
                    new byte[2],
                    TemplateStatus.NHAP))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void outputRequiresHashAndContent() {
    RenderOutput o =
        new RenderOutput(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            OutputKind.PDFA,
            "k",
            new byte[32],
            10,
            true);
    o.pdfSha256()[0] = 1;
    assertThat(o.pdfSha256()[0]).isZero();
    assertThat(o).isEqualTo(o).isNotEqualTo("x");
    assertThat(o.hashCode()).isEqualTo(o.id().hashCode());
    assertThat(o.toString()).contains("PDFA");
    assertThatThrownBy(
            () ->
                new RenderOutput(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    OutputKind.DOCX,
                    "k",
                    new byte[32],
                    0,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RenderOutput(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    OutputKind.DOCX,
                    "k",
                    new byte[3],
                    1,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
