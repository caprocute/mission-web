package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.CellTransferService;
import vn.viettel.okrkpi.integ.application.CellTransferService.Reasons;
import vn.viettel.okrkpi.integ.application.CellTransferService.RejectedException;
import vn.viettel.okrkpi.integ.application.SourceUnavailableException;
import vn.viettel.okrkpi.integ.application.port.CellChannel;
import vn.viettel.okrkpi.integ.application.port.CellKeyDirectory.CellKeys;
import vn.viettel.okrkpi.integ.application.port.TransferCodec.Part;
import vn.viettel.okrkpi.integ.application.port.TransferPackages;
import vn.viettel.okrkpi.integ.application.port.TransferPackages.Status;
import vn.viettel.okrkpi.integ.domain.RetrySchedule;
import vn.viettel.okrkpi.integ.infrastructure.crypto.JcaPackageCrypto;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Các nhánh lỗi của {@link CellTransferService} (DV-13-10) với hai cell trong bộ nhớ: kênh lỗi,
 * cell không có trong danh bạ, gói sai cell, sai tenant, sai khoá bọc, cổng nhập từ chối hoặc lỗi
 * tạm, báo kết quả lỗi, kết quả giả. Luồng thật hai CSDL ở {@link CellTransferIT}.
 */
class CellTransferServiceTest {

  static final TenantId SRC = TenantId.of(UUID.fromString("0192f1a2-0000-7000-8000-0000001311a1"));
  static final UUID DST = UUID.fromString("0192f1a2-0000-7000-8000-0000001311b1");
  static final UUID PERSON = UUID.fromString("0192f1a2-0000-7000-8000-0000001311c1");

  static KeyPair hnSign;
  static KeyPair hcmSign;
  static KeyPair hnBox;
  static KeyPair hcmBox;

  final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-23T08:00:00Z"));
  final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  final Map<String, CellKeys> directory = new HashMap<>();
  final List<CanonicalEvent> audit = new ArrayList<>();
  final Map<String, byte[]> objects = new HashMap<>();
  final List<Part> parts = new ArrayList<>();
  final List<String> imported = new ArrayList<>();
  final AtomicReference<RuntimeException> importFailure = new AtomicReference<>();
  final AtomicReference<RuntimeException> channelFailure = new AtomicReference<>();
  final AtomicReference<RuntimeException> reportFailure = new AtomicReference<>();
  final AtomicReference<Boolean> hostsTenant = new AtomicReference<>(true);
  MemoryPackages hnRows;
  MemoryPackages hcmRows;
  CellTransferService hn;
  CellTransferService hcm;

  @BeforeAll
  static void keys() throws Exception {
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    hnSign = ec.generateKeyPair();
    hcmSign = ec.generateKeyPair();
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(3072);
    hnBox = rsa.generateKeyPair();
    hcmBox = rsa.generateKeyPair();
  }

  @BeforeEach
  void cells() {
    directory.put(
        "HN", new CellKeys("HN", URI.create("https://hn"), hnSign.getPublic(), hnBox.getPublic()));
    directory.put(
        "HCM",
        new CellKeys("HCM", URI.create("https://hcm"), hcmSign.getPublic(), hcmBox.getPublic()));
    parts.add(
        new Part(
            "ket-qua-xep-loai.json",
            "KET_QUA_XEP_LOAI",
            "application/json",
            "{\"xepLoai\":\"HTT\"}".getBytes(StandardCharsets.UTF_8)));
    hnRows = new MemoryPackages();
    hcmRows = new MemoryPackages();
    hn = cell("HN", hnRows, hnSign, hnBox, () -> hcm);
    hcm = cell("HCM", hcmRows, hcmSign, hcmBox, () -> hn);
  }

  CellTransferService cell(
      String code,
      MemoryPackages rows,
      KeyPair sign,
      KeyPair box,
      java.util.function.Supplier<CellTransferService> peer) {
    ObjectMapper json = new ObjectMapper();
    return new CellTransferService(
        rows,
        new vn.viettel.okrkpi.integ.application.port.TransferObjects() {
          @Override
          public void put(String key, byte[] content) {
            objects.put(code + key, content.clone());
          }

          @Override
          public byte[] get(String key) {
            return objects.get(code + key).clone();
          }
        },
        (tenant, person) -> List.copyOf(parts),
        (tenant, packageId, person, sourceTenant, part) -> {
          if (importFailure.get() != null) {
            throw importFailure.get();
          }
          imported.add(packageId + ":" + part.name());
        },
        c -> Optional.ofNullable(directory.get(c)),
        t -> hostsTenant.get(),
        new CellChannel() {
          @Override
          public Optional<String> deliver(CellKeys target, Envelope e) {
            if (channelFailure.get() != null) {
              throw channelFailure.get();
            }
            try {
              peer.get().receive(e);
              return Optional.empty();
            } catch (RejectedException ex) {
              return Optional.of(ex.reason());
            }
          }

          @Override
          public void report(CellKeys target, Report r) {
            if (reportFailure.get() != null) {
              throw reportFailure.get();
            }
            peer.get().acknowledge(r);
          }
        },
        new JcaPackageCrypto(sign.getPrivate(), box.getPrivate()),
        new JsonTransferCodec(json, 1024 * 1024),
        (tenant, e) -> {
          audit.add(e);
          return UUID.randomUUID();
        },
        TransactionOperations.withoutTransaction(),
        clock,
        new CellTransferService.Settings(
            code,
            new RetrySchedule(Duration.ofSeconds(30), 2, Duration.ofHours(1), 3),
            new RetrySchedule(Duration.ofSeconds(30), 2, Duration.ofHours(1), 2),
            1024 * 1024));
  }

  CellTransferService.Request request(UUID id) {
    return new CellTransferService.Request(id, PERSON, "HN-A", "HCM", DST, "HCM-A");
  }

  @Test
  void packRejectsIncompleteOrPointlessRequests() {
    assertThatThrownBy(
            () -> hn.pack(SRC, new CellTransferService.Request(null, PERSON, "A", "HCM", DST, "B")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                hn.pack(
                    SRC,
                    new CellTransferService.Request(
                        UUID.randomUUID(), PERSON, " ", "HCM", DST, "B")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                hn.pack(
                    SRC,
                    new CellTransferService.Request(
                        UUID.randomUUID(), PERSON, "A", "HCM", DST, "B".repeat(33))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                hn.pack(
                    SRC,
                    new CellTransferService.Request(
                        UUID.randomUUID(), PERSON, "A", "HN", DST, "B")))
        .extracting(e -> ((RejectedException) e).reason())
        .isEqualTo(Reasons.WRONG_CELL);
    assertThatThrownBy(
            () ->
                hn.pack(
                    SRC,
                    new CellTransferService.Request(
                        UUID.randomUUID(), PERSON, "A", "DN", DST, "B")))
        .extracting(e -> ((RejectedException) e).reason())
        .isEqualTo(Reasons.UNKNOWN_CELL);
    parts.clear();
    assertThatThrownBy(() -> hn.pack(SRC, request(UUID.randomUUID())))
        .extracting(e -> ((RejectedException) e).reason())
        .isEqualTo(Reasons.MALFORMED);
    byte[] big = new byte[2 * 1024 * 1024];
    new java.util.Random(7).nextBytes(big);
    parts.add(new Part("lon.bin", "HO_SO_SO", "application/pdf", big));
    assertThatThrownBy(() -> hn.pack(SRC, request(UUID.randomUUID())))
        .extracting(e -> ((RejectedException) e).reason())
        .isEqualTo(Reasons.MALFORMED);
    assertThat(hnRows.rows).isEmpty();
  }

  @Test
  void channelOutageAndMissingPeerAreRetriedLater() {
    UUID id = UUID.randomUUID();
    hn.pack(SRC, request(id));
    channelFailure.set(new CellChannel.ChannelUnavailableException("mất kết nối", null));
    assertThat(hn.processDue(5)).isEqualTo(1);
    TransferPackages.Row row = hnRows.rows.get(id);
    assertThat(row.status()).isEqualTo(Status.DONG_GOI);
    assertThat(row.reason()).isEqualTo(Reasons.CHANNEL_DOWN);
    assertThat(row.attempts()).isEqualTo(1);
    assertThat(row.nextAttemptAt()).isAfter(now.get());
    assertThat(hn.processDue(5)).isZero();

    directory.remove("HCM");
    now.set(now.get().plusSeconds(3600));
    assertThat(hn.processDue(5)).isEqualTo(1);
    assertThat(hnRows.rows.get(id).reason()).isEqualTo(Reasons.UNKNOWN_CELL);

    directory.put(
        "HCM",
        new CellKeys("HCM", URI.create("https://hcm"), hcmSign.getPublic(), hcmBox.getPublic()));
    channelFailure.set(null);
    now.set(now.get().plusSeconds(7200));
    assertThat(hn.processDue(5)).isEqualTo(1);
    assertThat(hnRows.rows.get(id).status()).isEqualTo(Status.DA_GUI);
    assertThat(hcmRows.rows.get(id).status()).isEqualTo(Status.DA_NHAN);
    // Giao lại gói đã nhận trả trạng thái đang có, không thêm dòng.
    TransferPackages.Row sent = hnRows.rows.get(id);
    assertThat(
            hcm.receive(
                    new CellChannel.Envelope(
                        sent.signedHeader(),
                        sent.signature(),
                        sent.wrappedKey(),
                        objects.get("HN" + sent.objectKey())))
                .status())
        .isEqualTo(Status.DA_NHAN);
    assertThat(hn.pack(SRC, request(id)).status()).isEqualTo(Status.DA_GUI);
  }

  @Test
  void receiverRefusesPackagesItCannotTrust() {
    UUID id = UUID.randomUUID();
    hn.pack(SRC, request(id));
    TransferPackages.Row row = hnRows.rows.get(id);
    byte[] content = objects.get("HN" + row.objectKey());

    assertThat(
            reason(
                () ->
                    hcm.receive(
                        new CellChannel.Envelope(
                            new byte[] {'{'}, row.signature(), row.wrappedKey(), content))))
        .isEqualTo(Reasons.MALFORMED);
    assertThat(
            reason(
                () ->
                    hn.receive(
                        new CellChannel.Envelope(
                            row.signedHeader(), row.signature(), row.wrappedKey(), content))))
        .isEqualTo(Reasons.WRONG_CELL);
    hostsTenant.set(false);
    assertThat(
            reason(
                () ->
                    hcm.receive(
                        new CellChannel.Envelope(
                            row.signedHeader(), row.signature(), row.wrappedKey(), content))))
        .isEqualTo(Reasons.TENANT_NOT_HOSTED);
    hostsTenant.set(true);
    CellKeys hnKeys = directory.remove("HN");
    assertThat(
            reason(
                () ->
                    hcm.receive(
                        new CellChannel.Envelope(
                            row.signedHeader(), row.signature(), row.wrappedKey(), content))))
        .isEqualTo(Reasons.UNKNOWN_CELL);
    directory.put("HN", hnKeys);
    assertThat(hcmRows.rows).isEmpty();

    // Gói bọc khoá cho cell khác: chữ ký và băm đúng nhưng HCM không giải được.
    directory.put(
        "HCM",
        new CellKeys("HCM", URI.create("https://hcm"), hcmSign.getPublic(), hnBox.getPublic()));
    UUID wrongKey = UUID.randomUUID();
    hn.pack(SRC, request(wrongKey));
    assertThat(hn.processDue(5)).isEqualTo(2);
    assertThat(hnRows.rows.get(wrongKey).status()).isEqualTo(Status.TU_CHOI);
    assertThat(hnRows.rows.get(wrongKey).reason()).isEqualTo(Reasons.BAD_KEY);
    assertThat(hcmRows.rows.get(wrongKey).reason()).isEqualTo(Reasons.BAD_KEY);
    // Giao lại gói đã từ chối vẫn bị từ chối.
    TransferPackages.Row refused = hnRows.rows.get(wrongKey);
    assertThat(
            reason(
                () ->
                    hcm.receive(
                        new CellChannel.Envelope(
                            refused.signedHeader(),
                            refused.signature(),
                            refused.wrappedKey(),
                            objects.get("HN" + refused.objectKey())))))
        .isEqualTo(Reasons.BAD_KEY);
  }

  @Test
  void importerRefusalAndOutagesFollowTheRetrySchedule() {
    UUID refused = UUID.randomUUID();
    hn.pack(SRC, request(refused));
    hn.processDue(5);
    importFailure.set(new IllegalArgumentException("trùng hồ sơ"));
    assertThat(hcm.processDue(5)).isEqualTo(1);
    assertThat(hcmRows.rows.get(refused).status()).isEqualTo(Status.TU_CHOI);
    assertThat(hcmRows.rows.get(refused).reason()).isEqualTo(Reasons.IMPORT_REJECTED);
    assertThat(hnRows.rows.get(refused).status()).isEqualTo(Status.TU_CHOI);
    assertThat(hnRows.rows.get(refused).reason()).isEqualTo(Reasons.IMPORT_REJECTED);

    UUID flaky = UUID.randomUUID();
    hn.pack(SRC, request(flaky));
    hn.processDue(5);
    importFailure.set(
        new SourceUnavailableException(SourceUnavailableException.Reason.ERROR, "lỗi tạm", null));
    assertThat(hcm.processDue(5)).isEqualTo(1);
    assertThat(hcmRows.rows.get(flaky).status()).isEqualTo(Status.DA_NHAN);
    assertThat(hcmRows.rows.get(flaky).reason()).isEqualTo(Reasons.IMPORT_FAILED);
    now.set(now.get().plusSeconds(3600));
    reportFailure.set(new CellChannel.ChannelUnavailableException("cell nguồn lỗi", null));
    assertThat(hcm.processDue(5)).isEqualTo(1);
    assertThat(hcmRows.rows.get(flaky).status()).isEqualTo(Status.TU_CHOI);
    assertThat(hcmRows.rows.get(flaky).peerNotifiedAt()).isNull();
    assertThat(hnRows.rows.get(flaky).status()).isEqualTo(Status.DA_GUI);

    reportFailure.set(null);
    now.set(now.get().plusSeconds(3600));
    assertThat(hcm.processDue(5)).isEqualTo(1);
    assertThat(hcmRows.rows.get(flaky).peerNotifiedAt()).isNotNull();
    assertThat(hnRows.rows.get(flaky).reason()).isEqualTo(Reasons.IMPORT_FAILED);
    assertThat(hcm.processDue(5)).isZero();
    assertThat(imported).isEmpty();
  }

  @Test
  void forgedOrMisdirectedOutcomesAreRefused() {
    UUID id = UUID.randomUUID();
    hn.pack(SRC, request(id));
    JsonTransferCodec codec = new JsonTransferCodec(new ObjectMapper(), 1024);
    JcaPackageCrypto hcmCrypto = new JcaPackageCrypto(hcmSign.getPrivate(), hcmBox.getPrivate());
    byte[] ok =
        codec.outcome(
            new vn.viettel.okrkpi.integ.application.port.TransferCodec.Outcome(
                id, SRC.value(), "HCM", "DA_NHAP", null));
    assertThat(reason(() -> hn.acknowledge(new CellChannel.Report(new byte[] {1}, new byte[] {1}))))
        .isEqualTo(Reasons.MALFORMED);
    assertThat(reason(() -> hn.acknowledge(new CellChannel.Report(ok, new byte[8]))))
        .isEqualTo(Reasons.BAD_SIGNATURE);
    byte[] unknownCell =
        codec.outcome(
            new vn.viettel.okrkpi.integ.application.port.TransferCodec.Outcome(
                id, SRC.value(), "DN", "DA_NHAP", null));
    assertThat(
            reason(
                () ->
                    hn.acknowledge(
                        new CellChannel.Report(unknownCell, hcmCrypto.sign(unknownCell)))))
        .isEqualTo(Reasons.UNKNOWN_CELL);
    byte[] badStatus =
        codec.outcome(
            new vn.viettel.okrkpi.integ.application.port.TransferCodec.Outcome(
                id, SRC.value(), "HCM", "DA_GUI", null));
    assertThat(
            reason(
                () -> hn.acknowledge(new CellChannel.Report(badStatus, hcmCrypto.sign(badStatus)))))
        .isEqualTo(Reasons.MALFORMED);
    byte[] otherPackage =
        codec.outcome(
            new vn.viettel.okrkpi.integ.application.port.TransferCodec.Outcome(
                UUID.randomUUID(), SRC.value(), "HCM", "DA_NHAP", null));
    assertThat(
            reason(
                () ->
                    hn.acknowledge(
                        new CellChannel.Report(otherPackage, hcmCrypto.sign(otherPackage)))))
        .isEqualTo(Reasons.MALFORMED);
    assertThat(hn.acknowledge(new CellChannel.Report(ok, hcmCrypto.sign(ok))).status())
        .isEqualTo(Status.DA_NHAP);
    // Báo lại không đổi gì.
    assertThat(hn.acknowledge(new CellChannel.Report(ok, hcmCrypto.sign(ok))).status())
        .isEqualTo(Status.DA_NHAP);
    assertThat(audit).extracting(CanonicalEvent::topic).containsOnly("sys.audit.v1");
  }

  static String reason(Runnable r) {
    try {
      r.run();
    } catch (RejectedException e) {
      return e.reason();
    }
    throw new AssertionError("Không bị từ chối");
  }

  /** Bảng gói trong bộ nhớ, đủ cho việc đến hạn theo đồng hồ của ca kiểm thử. */
  final class MemoryPackages implements TransferPackages {
    final Map<UUID, Row> rows = new HashMap<>();

    @Override
    public boolean insert(TenantId tenant, Row row, UUID actor) {
      return rows.putIfAbsent(row.id(), row) == null;
    }

    @Override
    public Optional<Row> find(TenantId tenant, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Row> lock(TenantId tenant, UUID id) {
      return find(tenant, id);
    }

    @Override
    public void change(TenantId tenant, UUID id, Change c, UUID actor, Instant at) {
      Row r = rows.get(id);
      rows.put(
          id,
          new Row(
              r.id(),
              r.personId(),
              r.direction(),
              r.peerCell(),
              r.peerTenantCode(),
              r.peerTenantId(),
              r.manifest(),
              r.objectKey(),
              r.packageSha256(),
              r.wrappedKey(),
              r.signature(),
              r.signedHeader(),
              c.status(),
              c.reason(),
              c.attempts(),
              c.nextAttemptAt(),
              c.peerNotifiedAt() == null ? r.peerNotifiedAt() : c.peerNotifiedAt(),
              r.rowVersion() + 1,
              r.createdAt(),
              at));
    }

    @Override
    public List<Due> due(int limit) {
      return rows.values().stream()
          .filter(
              r ->
                  (r.direction() == Direction.DI && r.status() == Status.DONG_GOI)
                      || (r.direction() == Direction.DEN && r.status() == Status.DA_NHAN)
                      || (r.direction() == Direction.DEN
                          && (r.status() == Status.DA_NHAP || r.status() == Status.TU_CHOI)
                          && r.peerNotifiedAt() == null))
          .filter(r -> r.nextAttemptAt() == null || !r.nextAttemptAt().isAfter(now.get()))
          .sorted(Comparator.comparing(Row::createdAt))
          .limit(limit)
          .map(r -> new Due(DST, r.id()))
          .toList();
    }
  }
}
