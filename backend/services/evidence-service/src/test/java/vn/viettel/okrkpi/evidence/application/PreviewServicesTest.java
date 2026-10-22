package vn.viettel.okrkpi.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.PreviewRendering.PageSink;
import vn.viettel.okrkpi.evidence.application.PreviewRendering.RenderFailure;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Claim;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Due;
import vn.viettel.okrkpi.evidence.domain.PageSize;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.error.BusinessException;

class PreviewServicesTest {

  static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-000000000001");

  @TempDir Path work;

  final PreviewQueue queue = mock(PreviewQueue.class);
  final ObjectStorage storage = mock(ObjectStorage.class);
  final PreviewRendering rendering = mock(PreviewRendering.class);
  final FileAccessGuard guard = mock(FileAccessGuard.class);

  PreviewService worker() {
    return new PreviewService(
        queue,
        storage,
        rendering,
        TransactionOperations.withoutTransaction(),
        new PreviewService.Settings(
            Duration.ofMinutes(10), Duration.ofSeconds(30), 2, 150f, 300, 1240, work),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  static StoredFile file(String mediaType, String cls, String scan) {
    UUID v = UUID.randomUUID();
    return new StoredFile(
        UUID.randomUUID(),
        v,
        1,
        TENANT,
        UUID.randomUUID(),
        "product",
        null,
        mediaType,
        cls,
        10,
        new byte[32],
        "ev-dev-x",
        "k",
        "cv",
        NOW.plusSeconds(86400),
        scan,
        NOW);
  }

  StoredFile queued(StoredFile f, int attempt) throws Exception {
    when(queue.due(anyInt())).thenReturn(List.of(new Due(f.versionId(), TENANT)));
    when(queue.claim(eq(f.versionId()), any())).thenReturn(Optional.of(new Claim(f, attempt)));
    doAnswer(
        i ->
            ((ObjectStorage.StreamReader<?>) i.getArgument(2))
                .read(new ByteArrayInputStream(new byte[] {1, 2})))
        .when(storage)
        .stream(any(), any(), any());
    return f;
  }

  @Test
  void docxQuaPdfRoiDungAnhTungTrang() throws Exception {
    StoredFile f =
        queued(
            file(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "C1",
                "SACH"),
            1);
    doAnswer(
            i -> {
              PageSink sink = i.getArgument(3);
              sink.accept(1, new byte[] {9}, 1240, 1754);
              sink.accept(2, new byte[] {9}, 1240, 1754);
              return 2;
            })
        .when(rendering)
        .rasterizePdf(any(), anyFloat(), anyInt(), any());
    assertThat(worker().renderDue(5)).isEqualTo(1);
    verify(rendering).toPdf(any(), eq("tep.docx"), any());
    verify(queue).ready(f.versionId(), List.of(new PageSize(1240, 1754), new PageSize(1240, 1754)));
    verify(storage, org.mockito.Mockito.times(2))
        .put(any(), any(), eq("image/png"), eq(f.lockUntil()));
    assertThat(work.toFile().list()).isEmpty();
  }

  @Test
  void anhGocThuNhoVaLoiTamThoiThuLai() throws Exception {
    StoredFile img = queued(file("image/png", "C1", "SACH"), 1);
    doThrow(new RenderFailure("Gotenberg bận", true, null))
        .when(rendering)
        .scaleImage(any(), anyInt(), any());
    worker().renderDue(5);
    verify(queue).retryAt(img.versionId(), NOW.plusSeconds(30));

    StoredFile last = queued(file("image/png", "C1", "SACH"), 2);
    worker().renderDue(5);
    verify(queue).failed(last.versionId());

    StoredFile broken = queued(file("application/pdf", "C1", "SACH"), 1);
    doThrow(new RenderFailure("PDF hỏng", false, null))
        .when(rendering)
        .rasterizePdf(any(), anyFloat(), anyInt(), any());
    worker().renderDue(5);
    verify(queue).failed(broken.versionId());

    StoredFile c3 = queued(file("application/octet-stream", "C3", "SACH"), 1);
    worker().renderDue(5);
    verify(queue).failed(c3.versionId());
    verify(storage, never()).put(any(), any(), any(), any());
  }

  @Test
  void truyVanCuaSoTrangVaCacTrangThai() {
    PreviewQueryService q =
        new PreviewQueryService(
            queue,
            storage,
            guard,
            TransactionOperations.withoutTransaction(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    StoredFile ready = file("application/pdf", "C1", "SACH");
    when(queue.state(ready.objectId(), null))
        .thenReturn(
            Optional.of(
                new PreviewQueue.State(
                    ready,
                    "SAN_SANG",
                    List.of(new PageSize(10, 20), new PageSize(10, 20), new PageSize(10, 20)))));
    when(storage.presignGet(any(), any(), any(), any(), any())).thenReturn(URI.create("http://x"));
    var p = q.preview(ready.objectId(), null, 2, 5);
    assertThat(p.pageCount()).isEqualTo(3);
    assertThat(p.pages()).extracting(PreviewQueryService.PageLink::page).containsExactly(2, 3);
    assertThat(p.expiresAt()).isEqualTo(NOW.plusSeconds(300));

    StoredFile waiting = file("application/pdf", "C1", "SACH");
    when(queue.state(waiting.objectId(), null))
        .thenReturn(Optional.of(new PreviewQueue.State(waiting, null, List.of())));
    assertThat(q.preview(waiting.objectId(), null, 1, 5).pending()).isTrue();

    StoredFile failed = file("application/pdf", "C1", "SACH");
    when(queue.state(failed.objectId(), null))
        .thenReturn(Optional.of(new PreviewQueue.State(failed, "LOI", List.of())));
    assertThat(q.preview(failed.objectId(), null, 1, 5).status()).isEqualTo("LOI");

    StoredFile dirty = file("application/pdf", "C1", "CHO_QUET");
    when(queue.state(dirty.objectId(), null))
        .thenReturn(Optional.of(new PreviewQueue.State(dirty, null, List.of())));
    assertThatThrownBy(() -> q.preview(dirty.objectId(), null, 1, 5))
        .isInstanceOf(BusinessException.class);

    StoredFile denied = file("application/pdf", "C1", "SACH");
    when(queue.state(denied.objectId(), null))
        .thenReturn(Optional.of(new PreviewQueue.State(denied, "SAN_SANG", List.of())));
    doThrow(new IllegalStateException("403")).when(guard).checkPreview(any());
    assertThatThrownBy(() -> q.preview(denied.objectId(), null, 1, 5))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> q.preview(UUID.randomUUID(), null, 1, 5))
        .isInstanceOf(UploadService.FileNotFound.class);
  }
}
