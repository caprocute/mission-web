package vn.viettel.okrkpi.esign.application.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.esign.application.sign.EsignService.ItemOutcome;
import vn.viettel.okrkpi.esign.domain.sign.BatchAuthorization;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRef;
import vn.viettel.okrkpi.esign.domain.sign.ProviderCode;
import vn.viettel.okrkpi.esign.domain.sign.SignItem;
import vn.viettel.okrkpi.esign.domain.sign.SignPurpose;
import vn.viettel.okrkpi.esign.domain.sign.SignRequest;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestRepository;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestStatus;
import vn.viettel.okrkpi.esign.domain.sign.SignatureFormat;
import vn.viettel.okrkpi.esign.infrastructure.sign.FakeSignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.esign.spi.SignedItem;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * Ký lô một lần xác nhận DV-10-06 ở tầng ca sử dụng: nhận diện lô, xác nhận lô, ký từng tài liệu
 * với thử lại có giới hạn, lỗi vĩnh viễn không làm hỏng cả lô, đóng lô.
 */
class EsignBatchServiceTest {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000e101");
  private static final UUID SIGNER = UUID.fromString("0192f1a2-0000-7000-8000-00000000e102");
  private static final Instant T0 = Instant.parse("2026-10-22T03:00:00Z");

  private final EsignServiceTest.MutableClock clock = new EsignServiceTest.MutableClock(T0);
  private final BatchRequests requests = new BatchRequests();
  private final EsignServiceTest.MemoryCertificates certificates =
      new EsignServiceTest.MemoryCertificates();
  private final Map<UUID, SourceDocumentPort.SourceDocuments> sources = new HashMap<>();
  private final List<UUID> itemEvents = new ArrayList<>();
  private ScriptedProvider provider;
  private EsignService service;

  /** Nhà cung cấp giả lập có kịch bản lỗi cho từng lần ký tài liệu. */
  static final class ScriptedProvider extends FakeSignatureProvider {
    enum Step {
      OK,
      TRANSIENT,
      PERMANENT,
      CRASH,
      OTHER_DOCUMENT
    }

    final Deque<Step> script = new ArrayDeque<>();

    ScriptedProvider(EsignServiceTest.MutableClock clock) {
      super(clock, Duration.ofSeconds(5));
    }

    @Override
    public SignedItem signItem(
        String sessionId, vn.viettel.okrkpi.esign.spi.SignRequest.Document document) {
      Step step = script.isEmpty() ? Step.OK : script.poll();
      return switch (step) {
        case OK -> super.signItem(sessionId, document);
        case TRANSIENT -> throw new SignatureProviderException("Hết thời gian chờ HSM", false);
        case PERMANENT -> throw new SignatureProviderException("Tài liệu bị từ chối", true);
        case CRASH -> throw new IllegalStateException("kết nối bị cắt");
        case OTHER_DOCUMENT -> {
          SignedItem real = super.signItem(sessionId, document);
          yield new SignedItem(
              new byte[32],
              real.signatureValue(),
              real.signedObjectKey(),
              real.certificate(),
              real.signedAt());
        }
      };
    }
  }

  @BeforeEach
  void setUp() {
    provider = new ScriptedProvider(clock);
    service =
        service(new EsignService.BatchSettings(3, 3, Duration.ofSeconds(2), Duration.ofSeconds(5)));
  }

  private EsignService service(EsignService.BatchSettings settings) {
    ProviderRegistry registry =
        new ProviderRegistry(
            List.of(provider),
            Map.of(ProviderCode.MYSIGN, FakeSignatureProvider.ID),
            ProviderCode.MYSIGN);
    SignatureEvents events =
        new SignatureEvents() {
          @Override
          public void completed(SignRequest request, CertificateRef certificate) {
            throw new AssertionError("lô không phát EVT-18 của cả yêu cầu");
          }

          @Override
          public void itemSigned(
              SignRequestRepository.BatchHeader request,
              SignItem item,
              CertificateRef certificate,
              Instant signedAt) {
            assertThat(item.signed()).isTrue();
            itemEvents.add(item.id());
          }
        };
    return new EsignService(
        requests,
        certificates,
        (tenant, module, ref, purpose) -> Optional.ofNullable(sources.get(ref)),
        registry,
        events,
        clock,
        Duration.ofSeconds(120),
        settings);
  }

  private static byte[] sha(int seed) {
    byte[] b = new byte[32];
    b[0] = (byte) seed;
    b[1] = 7;
    return b;
  }

  private UUID source(boolean batch, int docs) {
    UUID ref = UUID.randomUUID();
    List<SourceDocumentPort.Document> documents = new ArrayList<>();
    for (int i = 0; i < docs; i++) {
      documents.add(
          new SourceDocumentPort.Document(
              "PDF_A", "quyet-dinh/" + ref + "/" + i, sha(i + 1), SignatureFormat.PADES_LTA));
    }
    sources.put(
        ref, new SourceDocumentPort.SourceDocuments(true, Set.of(SIGNER), documents, batch));
    return ref;
  }

  private EsignService.CreateCommand cmd(String module, SignPurpose purpose, UUID ref) {
    return new EsignService.CreateCommand(TENANT, SIGNER, purpose, module, ref, null, null);
  }

  /** Tạo lô, mở phiên và xác nhận (STEP_UP của nhà cung cấp giả lập được ủy quyền ngay). */
  private SignRequest authorizedBatch(int docs) {
    SignRequest created = service.create(cmd("dossier", SignPurpose.BIEN_BAN, source(true, docs)));
    assertThat(created.batchMode()).isTrue();
    assertThat(created.batchAuthorization()).isEqualTo(BatchAuthorization.STEP_UP);
    assertThat(service.pollOnce(created.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    assertThat(service.pollOnce(created.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    SignRequest authorized = requests.findById(created.id()).orElseThrow();
    assertThat(authorized.batchAuthorized()).isTrue();
    assertThat(authorized.batchAuthorizedAt()).isEqualTo(T0);
    return authorized;
  }

  @Test
  void loKyTungTaiLieuThuLaiLoiTamThoiVaBoQuaTaiLieuLoiVinhVien() {
    SignRequest batch = authorizedBatch(3);
    List<UUID> ids = batch.items().stream().map(SignItem::id).toList();
    provider.script.addAll(
        List.of(
            ScriptedProvider.Step.OK,
            ScriptedProvider.Step.TRANSIENT,
            ScriptedProvider.Step.PERMANENT));
    assertThat(service.signBatchItem(ids.get(0))).isEqualTo(ItemOutcome.SIGNED);
    assertThat(service.signBatchItem(ids.get(1))).isEqualTo(ItemOutcome.RETRY);
    assertThat(service.signBatchItem(ids.get(2))).isEqualTo(ItemOutcome.FAILED);
    assertThat(requests.row(ids.get(1)).nextAttemptAt).isEqualTo(T0.plusSeconds(2));
    assertThat(service.finishBatch(batch.id())).isFalse();
    assertThat(service.signBatchItem(ids.get(1))).isEqualTo(ItemOutcome.SIGNED);
    // Tài liệu đã ký không bị ký lại.
    assertThat(service.signBatchItem(ids.get(0))).isEqualTo(ItemOutcome.SKIPPED);
    assertThat(itemEvents).containsExactly(ids.get(0), ids.get(1));

    assertThat(service.finishBatch(batch.id())).isTrue();
    assertThat(service.finishBatch(batch.id())).isFalse();
    SignRequest done = service.get(SIGNER, batch.id());
    assertThat(done.status()).isEqualTo(SignRequestStatus.DA_KY);
    assertThat(done.signedCount()).isEqualTo(2);
    assertThat(done.failedCount()).isEqualTo(1);
    SignItem failed = done.items().get(2);
    assertThat(failed.status()).isEqualTo(SignItem.Status.LOI);
    assertThat(failed.attempts()).isEqualTo(1);
    assertThat(failed.lastError()).contains("từ chối");
    // Lượt thử ghi khi lỗi; lần ký thành công không tăng thêm.
    assertThat(done.items().get(1).attempts()).isEqualTo(1);
  }

  @Test
  void hetLuotThuThiTaiLieuLoiVaLoKhongTaiLieuNaoKyDuocThiLoi() {
    SignRequest batch = authorizedBatch(2);
    UUID first = batch.items().get(0).id();
    UUID second = batch.items().get(1).id();
    provider.script.addAll(
        List.of(
            ScriptedProvider.Step.CRASH,
            ScriptedProvider.Step.TRANSIENT,
            ScriptedProvider.Step.TRANSIENT,
            ScriptedProvider.Step.OTHER_DOCUMENT));
    assertThat(service.signBatchItem(first)).isEqualTo(ItemOutcome.RETRY);
    assertThat(service.signBatchItem(first)).isEqualTo(ItemOutcome.RETRY);
    assertThat(requests.row(first).nextAttemptAt).isEqualTo(T0.plusSeconds(4));
    assertThat(service.signBatchItem(first)).isEqualTo(ItemOutcome.FAILED);
    assertThat(service.signBatchItem(second)).isEqualTo(ItemOutcome.FAILED);
    assertThat(requests.row(second).item.lastError()).contains("tài liệu khác");
    assertThat(service.finishBatch(batch.id())).isTrue();
    assertThat(service.get(SIGNER, batch.id()).status()).isEqualTo(SignRequestStatus.LOI);
    assertThat(itemEvents).isEmpty();
  }

  @Test
  void taiLieuCuaLoChuaXacNhanHoacKhongTonTaiThiBoQua() {
    SignRequest created = service.create(cmd("dossier", SignPurpose.BIEN_BAN, source(true, 2)));
    UUID item = created.items().get(0).id();
    assertThat(service.signBatchItem(item)).isEqualTo(ItemOutcome.SKIPPED);
    assertThat(service.signBatchItem(UUID.randomUUID())).isEqualTo(ItemOutcome.SKIPPED);
    assertThat(service.finishBatch(UUID.randomUUID())).isFalse();
    assertThat(service.finishBatch(created.id())).isFalse();
  }

  @Test
  void quyetDinhNhieuPhieuCuaAppraisalLaLoVaLoQuaTranBiTuChoi() {
    SignRequest decision =
        service.create(cmd("appraisal", SignPurpose.QUYET_DINH, source(false, 2)));
    assertThat(decision.batchMode()).isTrue();
    SignRequest single = service.create(cmd("appraisal", SignPurpose.QUYET_DINH, source(false, 1)));
    assertThat(single.batchMode()).isFalse();
    SignRequest minutes = service.create(cmd("dossier", SignPurpose.BIEN_BAN, source(false, 2)));
    assertThat(minutes.batchMode()).isFalse();
    assertThatThrownBy(() -> service.create(cmd("dossier", SignPurpose.BIEN_BAN, source(true, 4))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).contains("COMMON-422-VALIDATION"));
  }

  @Test
  void loDaXacNhanKhongHetHanTheoHanXacNhan() {
    SignRequest batch = authorizedBatch(1);
    clock.advance(Duration.ofSeconds(600));
    assertThat(service.pollOnce(batch.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    assertThat(service.signBatchItem(batch.items().get(0).id())).isEqualTo(ItemOutcome.SIGNED);
    assertThat(service.finishBatch(batch.id())).isTrue();
    assertThat(service.get(SIGNER, batch.id()).status()).isEqualTo(SignRequestStatus.DA_KY);
  }

  @Test
  void thoiGianChoThuLaiTangGapDoiVaCoTran() {
    EsignService.BatchSettings s =
        new EsignService.BatchSettings(10, 5, Duration.ofSeconds(2), Duration.ofSeconds(10));
    assertThat(s.delayAfter(0)).isEqualTo(Duration.ofSeconds(2));
    assertThat(s.delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
    assertThat(s.delayAfter(2)).isEqualTo(Duration.ofSeconds(4));
    assertThat(s.delayAfter(4)).isEqualTo(Duration.ofSeconds(10));
    assertThat(s.delayAfter(100)).isEqualTo(Duration.ofSeconds(10));
    assertThat(EsignService.BatchSettings.DEFAULT.maxItems()).isEqualTo(1_000);
  }

  // --- Kho trong bộ nhớ có trạng thái từng tài liệu ---------------------------------------

  static final class Row {
    final UUID requestId;
    SignItem item;
    Instant nextAttemptAt;

    Row(UUID requestId, SignItem item) {
      this.requestId = requestId;
      this.item = item;
    }
  }

  static final class BatchRequests extends EsignServiceTest.MemoryRequests {
    private final Map<UUID, Row> items = new LinkedHashMap<>();

    Row row(UUID itemId) {
      return items.get(itemId);
    }

    @Override
    public void insert(SignRequest request, UUID actor) {
      super.insert(request, actor);
      for (SignItem i : request.items()) {
        items.put(i.id(), new Row(request.id(), i));
      }
    }

    /** Yêu cầu đọc lại với trạng thái tài liệu hiện hành, như kho JDBC nạp lại từ CSDL. */
    @Override
    public Optional<SignRequest> findById(UUID id) {
      return super.findById(id).map(this::reload);
    }

    @Override
    public Optional<SignRequest> lockForProcessing(UUID id) {
      return findById(id);
    }

    private SignRequest reload(SignRequest r) {
      List<SignItem> current = r.items().stream().map(i -> items.get(i.id()).item).toList();
      SignRequest copy =
          new SignRequest(
              r.id(),
              r.tenantId(),
              r.signerPersonId(),
              r.purpose(),
              r.sourceModule(),
              r.sourceRefId(),
              r.provider(),
              current,
              r.providerTxnId(),
              r.status(),
              r.expiresAt(),
              r.completedAt(),
              r.certificateRefId(),
              r.createdAt(),
              r.rowVersion());
      return r.batchMode() ? copy.asBatch(r.batchAuthorization(), r.batchAuthorizedAt()) : copy;
    }

    @Override
    public Optional<PendingItem> lockPendingItem(UUID itemId) {
      Row row = items.get(itemId);
      if (row == null || row.item.status() != SignItem.Status.TAO) {
        return Optional.empty();
      }
      return Optional.of(new PendingItem(row.requestId, row.item));
    }

    private static SignItem with(
        SignItem i,
        byte[] signature,
        String key,
        UUID cert,
        Instant at,
        SignItem.Status status,
        int attempts,
        String error) {
      return new SignItem(
          i.id(),
          i.documentKind(),
          i.documentRef(),
          i.documentSha256(),
          i.format(),
          signature,
          key,
          cert,
          at,
          status,
          attempts,
          error);
    }

    @Override
    public boolean itemSigned(
        UUID itemId, byte[] signature, String objectKey, UUID certificateId, Instant at) {
      Row row = items.get(itemId);
      if (row.item.signed()) {
        return false;
      }
      SignItem i = row.item;
      row.item =
          with(
              i,
              signature,
              objectKey,
              certificateId,
              at,
              SignItem.Status.DA_KY,
              i.attempts(),
              null);
      return true;
    }

    @Override
    public void itemRetry(UUID itemId, int attempts, Instant nextAttemptAt, String error) {
      Row row = items.get(itemId);
      row.item = with(row.item, null, null, null, null, SignItem.Status.TAO, attempts, error);
      row.nextAttemptAt = nextAttemptAt;
    }

    @Override
    public void itemFailed(UUID itemId, int attempts, String error) {
      Row row = items.get(itemId);
      row.item = with(row.item, null, null, null, null, SignItem.Status.LOI, attempts, error);
    }

    @Override
    public int pendingItems(UUID requestId) {
      return (int)
          items.values().stream()
              .filter(r -> r.requestId.equals(requestId))
              .filter(r -> r.item.status() == SignItem.Status.TAO)
              .count();
    }

    @Override
    public Optional<BatchHeader> batchHeader(UUID requestId) {
      return findById(requestId)
          .map(
              r ->
                  new BatchHeader(
                      r.id(),
                      r.tenantId(),
                      r.signerPersonId(),
                      r.purpose(),
                      r.sourceModule(),
                      r.sourceRefId(),
                      r.provider(),
                      r.providerTxnId(),
                      r.status(),
                      r.batchAuthorizedAt()));
    }
  }
}
