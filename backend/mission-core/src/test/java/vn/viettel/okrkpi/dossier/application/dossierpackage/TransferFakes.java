package vn.viettel.okrkpi.dossier.application.dossierpackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.EdxmlGateway;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageEvents;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageExportRepository;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageFiles;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageTransferRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Bản giả trong bộ nhớ cho kiểm thử xuất gói và gửi gói con (CORE-06-08). */
final class TransferFakes {

  private TransferFakes() {}

  /** Kho tác vụ xuất trong bộ nhớ. */
  static final class Exports implements PackageExportRepository {
    final Map<UUID, ExportRow> rows = new LinkedHashMap<>();
    boolean stale;

    @Override
    public void insert(TenantId tenant, UUID id, int periodBucket, UUID packageId, UUID actor) {
      rows.put(
          id,
          new ExportRow(
              id,
              periodBucket,
              packageId,
              "CHO",
              null,
              null,
              null,
              null,
              actor,
              Instant.now(),
              null));
    }

    @Override
    public Optional<ExportRow> find(TenantId tenant, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public boolean claim(TenantId tenant, UUID id, Duration staleAfter) {
      ExportRow r = rows.get(id);
      if (r == null || !("CHO".equals(r.status()) || "DANG_CHAY".equals(r.status()) && stale)) {
        return false;
      }
      rows.put(id, with(r, "DANG_CHAY", null, null, null, null));
      return true;
    }

    @Override
    public void complete(
        TenantId tenant, UUID id, String objectKey, long sizeBytes, int fileCount) {
      rows.put(id, with(rows.get(id), "HOAN_TAT", objectKey, sizeBytes, fileCount, null));
    }

    @Override
    public void fail(TenantId tenant, UUID id, String errorCode) {
      rows.put(id, with(rows.get(id), "THAT_BAI", null, null, null, errorCode));
    }

    @Override
    public List<UUID> stranded(TenantId tenant, Duration waiting, Duration staleAfter, int limit) {
      return rows.values().stream()
          .filter(r -> "CHO".equals(r.status()) || "DANG_CHAY".equals(r.status()) && stale)
          .map(ExportRow::id)
          .limit(limit)
          .toList();
    }

    private static ExportRow with(
        ExportRow r, String status, String key, Long size, Integer files, String error) {
      return new ExportRow(
          r.id(),
          r.periodBucket(),
          r.packageId(),
          status,
          key,
          size,
          files,
          error,
          r.requestedBy(),
          Instant.now(),
          "HOAN_TAT".equals(status) || "THAT_BAI".equals(status) ? Instant.now() : null);
    }
  }

  /** Kho gói con trong bộ nhớ. */
  static final class Transfers implements PackageTransferRepository {
    final Map<UUID, TransferRow> rows = new LinkedHashMap<>();

    @Override
    public void insert(
        TenantId tenant,
        int periodBucket,
        UUID packageId,
        UUID batchId,
        String destination,
        Collection<NewTransfer> transfers,
        UUID actor) {
      for (NewTransfer t : transfers) {
        rows.put(
            t.id(),
            new TransferRow(
                t.id(),
                periodBucket,
                packageId,
                batchId,
                t.profileId(),
                t.personId(),
                destination,
                "CHUA_GUI",
                0,
                null,
                null,
                null,
                null,
                0,
                actor,
                Instant.now()));
      }
    }

    @Override
    public Optional<TransferRow> find(TenantId tenant, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<TransferRow> ofPackage(TenantId tenant, UUID packageId) {
      return rows.values().stream().filter(r -> r.packageId().equals(packageId)).toList();
    }

    @Override
    public List<TransferRow> ofBatch(TenantId tenant, UUID batchId) {
      return rows.values().stream().filter(r -> r.batchId().equals(batchId)).toList();
    }

    @Override
    public List<TransferRow> open(TenantId tenant, Duration quiet, int limit) {
      return rows.values().stream()
          .filter(r -> OPEN.contains(r.status()) && !r.deadEnd())
          .limit(limit)
          .toList();
    }

    @Override
    public boolean applyRemote(TenantId tenant, UUID id, EdxmlGateway.RemoteState s) {
      TransferRow r = rows.get(id);
      if (r == null || "DA_XAC_NHAN".equals(r.status()) || "HUY".equals(r.status())) {
        return false;
      }
      TransferRow next =
          new TransferRow(
              r.id(),
              r.periodBucket(),
              r.packageId(),
              r.batchId(),
              r.profileId(),
              r.personId(),
              r.destination(),
              s.status(),
              s.attempts(),
              s.nextAttemptAt(),
              s.ackRef(),
              "DA_XAC_NHAN".equals(s.status()) && s.acknowledgedAt() == null
                  ? Instant.now()
                  : s.acknowledgedAt(),
              s.lastError(),
              r.notifiedAttempts(),
              r.requestedBy(),
              Instant.now());
      if (next.status().equals(r.status())
          && next.attempts() == r.attempts()
          && java.util.Objects.equals(next.nextAttemptAt(), r.nextAttemptAt())
          && java.util.Objects.equals(next.ackRef(), r.ackRef())
          && java.util.Objects.equals(next.lastError(), r.lastError())) {
        return false;
      }
      rows.put(id, next);
      return true;
    }

    @Override
    public void close(TenantId tenant, UUID id, String status, String reason) {
      TransferRow r = rows.get(id);
      if (r == null || !OPEN.contains(r.status())) {
        return;
      }
      rows.put(
          id,
          new TransferRow(
              r.id(),
              r.periodBucket(),
              r.packageId(),
              r.batchId(),
              r.profileId(),
              r.personId(),
              r.destination(),
              status,
              r.attempts(),
              null,
              r.ackRef(),
              r.acknowledgedAt(),
              reason,
              r.notifiedAttempts(),
              r.requestedBy(),
              Instant.now()));
    }

    @Override
    public boolean markNotified(TenantId tenant, UUID id, int attempts) {
      TransferRow r = rows.get(id);
      if (r == null || r.notifiedAttempts() >= attempts) {
        return false;
      }
      rows.put(
          id,
          new TransferRow(
              r.id(),
              r.periodBucket(),
              r.packageId(),
              r.batchId(),
              r.profileId(),
              r.personId(),
              r.destination(),
              r.status(),
              r.attempts(),
              r.nextAttemptAt(),
              r.ackRef(),
              r.acknowledgedAt(),
              r.lastError(),
              attempts,
              r.requestedBy(),
              r.updatedAt()));
      return true;
    }
  }

  /** Kho đối tượng trong bộ nhớ; tệp có thể là luồng sinh ra theo yêu cầu. */
  static final class Files implements PackageFiles {
    final Map<String, Supplier<InputStream>> stored = new LinkedHashMap<>();
    final Map<String, byte[]> written = new LinkedHashMap<>();
    final List<String> aborted = new ArrayList<>();
    boolean failWrite;

    void put(String key, byte[] content) {
      stored.put(key, () -> new ByteArrayInputStream(content));
    }

    @Override
    public InputStream open(TenantId tenant, String objectKey) throws IOException {
      Supplier<InputStream> s = stored.get(objectKey);
      if (s == null) {
        throw new FileNotFoundException(objectKey);
      }
      return s.get();
    }

    @Override
    public long size(TenantId tenant, String objectKey) {
      return -1;
    }

    @Override
    public WritableFile create(TenantId tenant, String objectKey, String contentType) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      return new WritableFile() {
        @Override
        public void write(int b) throws IOException {
          check();
          buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
          check();
          buffer.write(b, off, len);
        }

        private void check() throws IOException {
          if (failWrite) {
            throw new IOException("kho đầy");
          }
        }

        @Override
        public void close() {
          written.put(objectKey, buffer.toByteArray());
        }

        @Override
        public void abort() {
          aborted.add(objectKey);
        }
      };
    }

    @Override
    public String downloadUrl(TenantId tenant, String objectKey, String fileName, Duration ttl) {
      return "https://kho.example/" + objectKey + "?ten=" + fileName + "&han=" + ttl.toMinutes();
    }
  }

  /** Bản giả SVC-13: trả lần lượt các kết quả đã xếp, ghi lại gói con đã nhận. */
  static final class Gateway implements EdxmlGateway {
    final List<Outgoing> sent = new ArrayList<>();
    final Deque<Object> sendResults = new ArrayDeque<>();
    final Map<UUID, RemoteState> remote = new LinkedHashMap<>();

    @Override
    public RemoteState send(TenantId tenant, Outgoing outgoing) {
      Object next = sendResults.poll();
      if (next instanceof RuntimeException e) {
        throw e;
      }
      sent.add(outgoing);
      RemoteState s = new RemoteState(outgoing.transferId(), "CHO", 0, null, null, null, null);
      remote.put(outgoing.transferId(), s);
      return s;
    }

    @Override
    public Optional<RemoteState> state(TenantId tenant, UUID transferId) {
      return Optional.ofNullable(remote.get(transferId));
    }

    void set(UUID id, String status, int attempts, Instant next, String error) {
      remote.put(
          id,
          new RemoteState(
              id,
              status,
              attempts,
              next,
              "DA_XAC_NHAN".equals(status) ? "BN-" + attempts : null,
              "DA_XAC_NHAN".equals(status) ? Instant.parse("2027-01-15T03:00:00Z") : null,
              error));
    }
  }

  /** Ghi lại sự kiện gói ra danh sách chuỗi. */
  static final class Events implements PackageEvents {
    final List<String> log = new ArrayList<>();
    final List<String> messages = new ArrayList<>();

    @Override
    public void submitted(
        TenantId tenant, PackageRow pkg, List<UUID> recipients, long version, UUID actor) {
      log.add("NOP");
    }

    @Override
    public void reopened(TenantId tenant, PackageRow pkg, UUID previousId, UUID actor) {
      log.add("MO_LAI");
    }

    @Override
    public void exported(TenantId tenant, PackageRow pkg, UUID exportId, UUID actor) {
      log.add("XUAT:" + exportId);
    }

    @Override
    public void transferRequested(
        TenantId tenant, PackageRow pkg, UUID batchId, String destination, int count, UUID actor) {
      log.add("GUI:" + destination + ":" + count);
    }

    @Override
    public void transferAcknowledged(
        TenantId tenant, PackageRow pkg, PackageTransferRepository.TransferRow transfer) {
      log.add("BIEN_NHAN:" + transfer.id());
    }

    @Override
    public void transferFailed(
        TenantId tenant,
        PackageRow pkg,
        PackageTransferRepository.TransferRow transfer,
        List<UUID> recipients,
        String message) {
      log.add("LOI:" + transfer.id() + ":" + transfer.attempts() + ":" + recipients.size());
      messages.add(message);
    }
  }
}
