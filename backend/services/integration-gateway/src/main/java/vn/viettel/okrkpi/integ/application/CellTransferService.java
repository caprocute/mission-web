package vn.viettel.okrkpi.integ.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.integ.application.port.CanonicalEventSink;
import vn.viettel.okrkpi.integ.application.port.CellChannel;
import vn.viettel.okrkpi.integ.application.port.CellKeyDirectory;
import vn.viettel.okrkpi.integ.application.port.CellKeyDirectory.CellKeys;
import vn.viettel.okrkpi.integ.application.port.CellTenants;
import vn.viettel.okrkpi.integ.application.port.PackageCrypto;
import vn.viettel.okrkpi.integ.application.port.TransferCodec;
import vn.viettel.okrkpi.integ.application.port.TransferCodec.Header;
import vn.viettel.okrkpi.integ.application.port.TransferCodec.Outcome;
import vn.viettel.okrkpi.integ.application.port.TransferCodec.Part;
import vn.viettel.okrkpi.integ.application.port.TransferContents;
import vn.viettel.okrkpi.integ.application.port.TransferImporter;
import vn.viettel.okrkpi.integ.application.port.TransferObjects;
import vn.viettel.okrkpi.integ.application.port.TransferPackages;
import vn.viettel.okrkpi.integ.application.port.TransferPackages.Change;
import vn.viettel.okrkpi.integ.application.port.TransferPackages.Direction;
import vn.viettel.okrkpi.integ.application.port.TransferPackages.Row;
import vn.viettel.okrkpi.integ.application.port.TransferPackages.Status;
import vn.viettel.okrkpi.integ.domain.ErrorText;
import vn.viettel.okrkpi.integ.domain.RetrySchedule;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Gói chuyển hồ sơ cán bộ điều động liên cell (DV-13-10, TL-03 "Đồng bộ giữa các cell" luồng thứ
 * ba, baseline mục 8 cơ chế 7). Cell nguồn thu thập phần được phép chuyển, dựng danh mục có băm, mã
 * hoá bằng khoá nội dung ngẫu nhiên, bọc khoá bằng khoá công khai của cell nhận, ký phần đầu, đặt
 * bản mã vào bucket {@code transfer} và lưu {@code DONG_GOI}; worker gửi qua kênh liên cell ({@code
 * DA_GUI}). Cell đích kiểm chữ ký, băm và giải thử trước khi nhận ({@code DA_NHAN}), worker nhập
 * vào SVC-04 và MOD-06 ({@code DA_NHAP}) rồi báo kết quả có ký về cell nguồn; sai chữ ký, băm hay
 * khoá thì {@code TU_CHOI} kèm mã lý do và cell nguồn biết ngay qua phản hồi giao gói. Mỗi bước ghi
 * {@code sys.audit.v1} qua outbox trong cùng giao dịch, nên cả hai phía có vết trong sổ cái.
 */
public class CellTransferService {

  private static final Logger LOG = LoggerFactory.getLogger(CellTransferService.class);

  /** Phiên bản định dạng phần đầu. */
  public static final int VERSION = 1;

  /** Danh tính dịch vụ ghi ở {@code created_by}, {@code updated_by}. */
  public static final UUID SYSTEM_ACTOR = new UUID(0L, 0x13L);

  static final String AUDIT_OBJECT = "TRANSFER_PACKAGE";

  /** Mã lý do từ chối hoặc lỗi gần nhất ({@code transfer_package.reason}). */
  public static final class Reasons {
    public static final String MALFORMED = "GOI_KHONG_HOP_LE";
    public static final String WRONG_CELL = "SAI_CELL_NHAN";
    public static final String UNKNOWN_CELL = "CELL_KHONG_TIN_CAY";
    public static final String TENANT_NOT_HOSTED = "TENANT_KHONG_O_CELL";
    public static final String BAD_SIGNATURE = "CHU_KY_SAI";
    public static final String BAD_DIGEST = "BAM_SAI";
    public static final String BAD_KEY = "KHONG_GIAI_DUOC";
    public static final String BAD_MANIFEST = "DANH_MUC_SAI";
    public static final String IMPORT_FAILED = "NHAP_LOI";
    public static final String IMPORT_REJECTED = "NHAP_BI_TU_CHOI";
    public static final String CHANNEL_DOWN = "KENH_LOI";

    private Reasons() {}
  }

  /** Yêu cầu điều động đã duyệt (từ org-service); {@code packageId} là khoá chống trùng. */
  public record Request(
      UUID packageId,
      UUID personId,
      String sourceTenantCode,
      String targetCell,
      UUID targetTenantId,
      String targetTenantCode) {}

  /** Trạng thái gói cho bên gọi. */
  public record View(
      UUID packageId,
      UUID personId,
      Direction direction,
      String peerCell,
      String peerTenantCode,
      Status status,
      String reason,
      int attempts,
      Instant nextAttemptAt,
      Instant updatedAt) {

    static View of(Row r) {
      return new View(
          r.id(),
          r.personId(),
          r.direction(),
          r.peerCell(),
          r.peerTenantCode(),
          r.status(),
          r.reason(),
          r.attempts(),
          r.nextAttemptAt(),
          r.updatedAt());
    }
  }

  /** Yêu cầu bị từ chối, kèm mã lý do. */
  public static final class RejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    public RejectedException(String reason, String message) {
      super(message);
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }

  /** Thiết lập của cell này. */
  public record Settings(
      String cellCode, RetrySchedule sendRetry, RetrySchedule importRetry, long maxBytes) {
    public Settings {
      Objects.requireNonNull(cellCode, "cellCode");
      Objects.requireNonNull(sendRetry, "sendRetry");
      Objects.requireNonNull(importRetry, "importRetry");
      if (maxBytes < 1) {
        throw new IllegalArgumentException("Trần dung lượng gói phải dương");
      }
    }
  }

  private final TransferPackages packages;
  private final TransferObjects objects;
  private final TransferContents contents;
  private final TransferImporter importer;
  private final CellKeyDirectory directory;
  private final CellTenants tenants;
  private final CellChannel channel;
  private final PackageCrypto crypto;
  private final TransferCodec codec;
  private final CanonicalEventSink audit;
  private final TransactionOperations tx;
  private final Clock clock;
  private final Settings settings;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public CellTransferService(
      TransferPackages packages,
      TransferObjects objects,
      TransferContents contents,
      TransferImporter importer,
      CellKeyDirectory directory,
      CellTenants tenants,
      CellChannel channel,
      PackageCrypto crypto,
      TransferCodec codec,
      CanonicalEventSink audit,
      TransactionOperations tx,
      Clock clock,
      Settings settings) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.objects = Objects.requireNonNull(objects, "objects");
    this.contents = Objects.requireNonNull(contents, "contents");
    this.importer = Objects.requireNonNull(importer, "importer");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.channel = Objects.requireNonNull(channel, "channel");
    this.crypto = Objects.requireNonNull(crypto, "crypto");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  // ------------------------------------------------------------------ cell nguồn

  /** Đóng gói ở cell nguồn; gọi lại cùng {@code packageId} trả trạng thái đang có. */
  public View pack(TenantId tenant, Request r) {
    Objects.requireNonNull(r, "request");
    if (r.packageId() == null || r.personId() == null || r.targetTenantId() == null) {
      throw new IllegalArgumentException("Thiếu mã gói, cán bộ hoặc tenant đích");
    }
    if (blank(r.sourceTenantCode()) || blank(r.targetTenantCode()) || blank(r.targetCell())) {
      throw new IllegalArgumentException("Thiếu mã tenant hoặc cell");
    }
    if (r.targetTenantCode().length() > 32 || r.targetCell().length() > 32) {
      throw new IllegalArgumentException("Mã tenant hoặc cell quá dài");
    }
    Optional<View> existing = status(tenant, r.packageId());
    if (existing.isPresent()) {
      return existing.get();
    }
    if (r.targetCell().equals(settings.cellCode())) {
      throw new RejectedException(
          Reasons.WRONG_CELL, "Tenant đích cùng cell, không cần gói chuyển");
    }
    CellKeys peer =
        directory
            .find(r.targetCell())
            .orElseThrow(
                () ->
                    new RejectedException(
                        Reasons.UNKNOWN_CELL, "Cell đích không có trong danh bạ"));
    List<Part> parts = contents.collect(tenant, r.personId());
    if (parts.isEmpty()) {
      throw new RejectedException(Reasons.MALFORMED, "Không có phần hồ sơ nào để chuyển");
    }
    byte[] bundle = codec.bundle(parts);
    if (bundle.length > settings.maxBytes()) {
      throw new RejectedException(Reasons.MALFORMED, "Gói vượt trần dung lượng");
    }
    String manifest = codec.manifest(parts);
    PackageCrypto.Sealed sealed = crypto.seal(r.packageId(), bundle, peer.encryptionKey());
    byte[] digest = sha256(sealed.ciphertext());
    String digestHex = HexFormat.of().formatHex(digest);
    // Băm trong khoá đối tượng: hai lần đóng gói đồng thời cùng id không ghi đè bản mã của nhau.
    String key = "DI/" + tenant.value() + "/" + r.packageId() + "/" + digestHex;
    objects.put(key, sealed.ciphertext());
    byte[] header =
        codec.header(
            new Header(
                VERSION,
                r.packageId(),
                settings.cellCode(),
                tenant.value(),
                r.sourceTenantCode(),
                r.targetCell(),
                r.targetTenantId(),
                r.targetTenantCode(),
                r.personId(),
                manifest,
                digestHex,
                HexFormat.of().formatHex(sha256(sealed.wrappedKey()))));
    byte[] signature = TenantScope.call(tenant, () -> crypto.sign(header));
    Instant now = clock.instant();
    Row row =
        new Row(
            r.packageId(),
            r.personId(),
            Direction.DI,
            r.targetCell(),
            r.targetTenantCode(),
            r.targetTenantId(),
            manifest,
            key,
            digest,
            sealed.wrappedKey(),
            signature,
            header,
            Status.DONG_GOI,
            null,
            0,
            now,
            null,
            0,
            now,
            now);
    return inTenant(
        tenant,
        () -> {
          if (packages.insert(tenant, row, SYSTEM_ACTOR)) {
            record(tenant, "DONG_GOI_HO_SO_LIEN_CELL", r.packageId(), "THANH_CONG", r.targetCell());
          }
          return packages.find(tenant, r.packageId()).map(View::of).orElseThrow();
        });
  }

  public Optional<View> status(TenantId tenant, UUID packageId) {
    Objects.requireNonNull(packageId, "packageId");
    return inTenant(tenant, () -> packages.find(tenant, packageId).map(View::of));
  }

  /**
   * Kết quả nhập do cell đích ký: kiểm chữ ký bằng khoá ký của cell đích trong danh bạ rồi đóng gói
   * đi ({@code DA_NHAP} hoặc {@code TU_CHOI}). Báo lại lần nữa không đổi gì.
   */
  public View acknowledge(CellChannel.Report report) {
    Outcome o;
    try {
      o = codec.parseOutcome(report.payload());
    } catch (TransferCodec.MalformedException e) {
      throw new RejectedException(Reasons.MALFORMED, "Kết quả nhập không đọc được");
    }
    CellKeys peer =
        directory
            .find(o.cell())
            .orElseThrow(
                () ->
                    new RejectedException(Reasons.UNKNOWN_CELL, "Cell báo kết quả không tin cậy"));
    if (!crypto.verify(report.payload(), report.signature(), peer.signingKey())) {
      throw new RejectedException(Reasons.BAD_SIGNATURE, "Chữ ký kết quả nhập không đúng");
    }
    Status result = parseResult(o.status());
    TenantId tenant = TenantId.of(o.sourceTenantId());
    return inTenant(
        tenant,
        () -> {
          Row row =
              packages
                  .lock(tenant, o.packageId())
                  .filter(p -> p.direction() == Direction.DI && p.peerCell().equals(o.cell()))
                  .orElseThrow(
                      () -> new RejectedException(Reasons.MALFORMED, "Không có gói đi tương ứng"));
          if (row.status() == Status.DONG_GOI || row.status() == Status.DA_GUI) {
            String reason = result == Status.TU_CHOI ? reasonOrDefault(o.reason()) : null;
            Instant now = clock.instant();
            packages.change(
                tenant,
                row.id(),
                new Change(result, reason, row.attempts(), null, now),
                SYSTEM_ACTOR,
                now);
            record(
                tenant,
                result == Status.DA_NHAP ? "CELL_DICH_DA_NHAP_GOI" : "CELL_DICH_TU_CHOI_GOI",
                row.id(),
                result == Status.DA_NHAP ? "THANH_CONG" : "TU_CHOI",
                reason);
          }
          return packages.find(tenant, row.id()).map(View::of).orElseThrow();
        });
  }

  // ------------------------------------------------------------------ cell đích

  /**
   * Nhận gói ở cell đích. Kiểm chữ ký theo khoá ký của cell nguồn, băm bản mã và khoá bọc, giải thử
   * và đối chiếu băm từng phần với danh mục; đạt thì lưu bản mã, ghi {@code DA_NHAN}. Không đạt thì
   * ghi {@code TU_CHOI} (khi đã biết tenant đích) và ném {@link RejectedException} để kênh trả lý
   * do cho cell nguồn. Gửi trùng gói đã nhận trả trạng thái đang có.
   */
  public View receive(CellChannel.Envelope e) {
    Header h;
    try {
      h = codec.parseHeader(e.header());
    } catch (TransferCodec.MalformedException ex) {
      throw new RejectedException(Reasons.MALFORMED, "Phần đầu gói không đọc được");
    }
    if (h.version() != VERSION || !settings.cellCode().equals(h.targetCell())) {
      throw new RejectedException(Reasons.WRONG_CELL, "Gói không gửi cho cell này");
    }
    if (!tenants.hosts(h.targetTenantId())) {
      throw new RejectedException(Reasons.TENANT_NOT_HOSTED, "Tenant đích không thuộc cell này");
    }
    TenantId tenant = TenantId.of(h.targetTenantId());
    Optional<CellKeys> source = directory.find(h.sourceCell());
    if (source.isEmpty()) {
      throw new RejectedException(Reasons.UNKNOWN_CELL, "Cell nguồn không có trong danh bạ");
    }
    String failure = verify(e, h, source.get());
    Optional<Row> existing = inTenant(tenant, () -> packages.find(tenant, h.packageId()));
    if (existing.isPresent()) {
      if (failure == null && existing.get().status() != Status.TU_CHOI) {
        return View.of(existing.get());
      }
      throw new RejectedException(
          failure == null ? existing.get().reason() : failure, "Gói đã bị từ chối");
    }
    Instant now = clock.instant();
    String key = "DEN/" + tenant.value() + "/" + h.packageId() + "/" + h.packageSha256();
    if (failure == null) {
      objects.put(key, e.content());
    }
    Row row =
        new Row(
            h.packageId(),
            h.personId(),
            Direction.DEN,
            h.sourceCell(),
            h.sourceTenantCode(),
            h.sourceTenantId(),
            failure == null ? h.manifest() : "{}",
            failure == null ? key : "",
            failure == null
                ? hex(h.packageSha256())
                : sha256(e.content() == null ? new byte[0] : e.content()),
            e.wrappedKey() == null ? new byte[0] : e.wrappedKey(),
            e.signature() == null ? new byte[0] : e.signature(),
            e.header(),
            failure == null ? Status.DA_NHAN : Status.TU_CHOI,
            failure,
            0,
            failure == null ? now : null,
            failure == null ? null : now,
            0,
            now,
            now);
    View view =
        inTenant(
            tenant,
            () -> {
              if (packages.insert(tenant, row, SYSTEM_ACTOR)) {
                record(
                    tenant,
                    failure == null ? "NHAN_GOI_CHUYEN_LIEN_CELL" : "TU_CHOI_GOI_CHUYEN_LIEN_CELL",
                    h.packageId(),
                    failure == null ? "THANH_CONG" : "TU_CHOI",
                    failure == null ? h.sourceCell() : failure);
              }
              return packages.find(tenant, h.packageId()).map(View::of).orElseThrow();
            });
    if (failure != null) {
      LOG.warn("Từ chối gói chuyển {} từ cell {}: {}", h.packageId(), h.sourceCell(), failure);
      throw new RejectedException(failure, "Gói không hợp lệ");
    }
    return view;
  }

  /** Lý do từ chối, hoặc {@code null} khi chữ ký, băm, khoá và danh mục đều đúng. */
  private String verify(CellChannel.Envelope e, Header h, CellKeys source) {
    if (e.signature() == null || !crypto.verify(e.header(), e.signature(), source.signingKey())) {
      return Reasons.BAD_SIGNATURE;
    }
    if (e.content() == null
        || e.wrappedKey() == null
        || e.content().length > settings.maxBytes() + 1024
        || !HexFormat.of().formatHex(sha256(e.content())).equals(h.packageSha256())
        || !HexFormat.of().formatHex(sha256(e.wrappedKey())).equals(h.wrappedKeySha256())) {
      return Reasons.BAD_DIGEST;
    }
    List<Part> parts;
    try {
      parts = codec.unbundle(crypto.open(h.packageId(), e.content(), e.wrappedKey()));
    } catch (PackageCrypto.InvalidPackageException ex) {
      return Reasons.BAD_KEY;
    } catch (TransferCodec.MalformedException ex) {
      return Reasons.MALFORMED;
    }
    return matches(h.manifest(), parts) ? null : Reasons.BAD_MANIFEST;
  }

  private boolean matches(String manifest, List<Part> parts) {
    Map<String, String> expected;
    try {
      expected = codec.manifestDigests(manifest);
    } catch (TransferCodec.MalformedException ex) {
      return false;
    }
    if (expected.size() != parts.size()) {
      return false;
    }
    for (Part p : parts) {
      if (!HexFormat.of().formatHex(sha256(p.content())).equals(expected.get(p.name()))) {
        return false;
      }
    }
    return true;
  }

  // ------------------------------------------------------------------ worker

  /** Gửi gói đi chưa gửi, nhập gói đến chưa nhập, báo kết quả chưa báo; trả số việc đã xử lý. */
  public int processDue(int limit) {
    int handled = 0;
    for (TransferPackages.Due d : packages.due(limit)) {
      try {
        if (process(TenantId.of(d.tenantId()), d.id())) {
          handled++;
        }
      } catch (RuntimeException ex) {
        LOG.warn("Không xử lý được gói chuyển {}: {}", d.id(), ErrorText.code(ex));
      }
    }
    return handled;
  }

  /** Xử lý một gói; {@code false} khi bản sao khác đang giữ hoặc gói không còn việc. */
  public boolean process(TenantId tenant, UUID id) {
    return inTenant(
        tenant,
        () -> {
          Optional<Row> locked = packages.lock(tenant, id);
          if (locked.isEmpty()) {
            return false;
          }
          Row row = locked.get();
          if (row.direction() == Direction.DI && row.status() == Status.DONG_GOI) {
            send(tenant, row);
            return true;
          }
          if (row.direction() == Direction.DEN && row.status() == Status.DA_NHAN) {
            Row after = importPackage(tenant, row);
            if (after.status() != Status.DA_NHAN) {
              notifySource(tenant, after);
            }
            return true;
          }
          if (row.direction() == Direction.DEN && row.peerNotifiedAt() == null) {
            notifySource(tenant, row);
            return true;
          }
          return false;
        });
  }

  private void send(TenantId tenant, Row row) {
    Instant now = clock.instant();
    int attempts = row.attempts() + 1;
    Optional<CellKeys> peer = directory.find(row.peerCell());
    if (peer.isEmpty()) {
      retry(tenant, row, attempts, Reasons.UNKNOWN_CELL, settings.sendRetry(), now);
      return;
    }
    Optional<String> refused;
    try {
      refused =
          channel.deliver(
              peer.get(),
              new CellChannel.Envelope(
                  row.signedHeader(),
                  row.signature(),
                  row.wrappedKey(),
                  objects.get(row.objectKey())));
    } catch (CellChannel.ChannelUnavailableException ex) {
      LOG.warn("Kênh liên cell tới {} lỗi: {}", row.peerCell(), ErrorText.code(ex));
      retry(tenant, row, attempts, Reasons.CHANNEL_DOWN, settings.sendRetry(), now);
      return;
    }
    if (refused.isPresent()) {
      String reason = reasonOrDefault(refused.get());
      packages.change(
          tenant,
          row.id(),
          new Change(Status.TU_CHOI, reason, attempts, null, now),
          SYSTEM_ACTOR,
          now);
      record(tenant, "CELL_DICH_TU_CHOI_GOI", row.id(), "TU_CHOI", reason);
      return;
    }
    packages.change(
        tenant, row.id(), new Change(Status.DA_GUI, null, attempts, null, null), SYSTEM_ACTOR, now);
    record(tenant, "GUI_GOI_CHUYEN_LIEN_CELL", row.id(), "THANH_CONG", row.peerCell());
  }

  /** Gửi lại theo lịch; gói đi không tự hết lượt (kênh liên cell sẽ phục hồi), chỉ giãn nhịp. */
  private void retry(
      TenantId tenant, Row row, int attempts, String reason, RetrySchedule schedule, Instant now) {
    Instant next = schedule.next(Math.min(attempts, schedule.maxAttempts() - 1), now).orElse(now);
    packages.change(
        tenant,
        row.id(),
        new Change(row.status(), reason, attempts, next, null),
        SYSTEM_ACTOR,
        now);
  }

  private Row importPackage(TenantId tenant, Row row) {
    Instant now = clock.instant();
    int attempts = row.attempts() + 1;
    String failure;
    try {
      List<Part> parts =
          codec.unbundle(crypto.open(row.id(), objects.get(row.objectKey()), row.wrappedKey()));
      for (Part p : parts) {
        importer.importPart(tenant, row.id(), row.personId(), row.peerTenantCode(), p);
      }
      packages.change(
          tenant,
          row.id(),
          new Change(Status.DA_NHAP, null, attempts, null, null),
          SYSTEM_ACTOR,
          now);
      record(tenant, "NHAP_GOI_CHUYEN_LIEN_CELL", row.id(), "THANH_CONG", row.peerCell());
      return packages.find(tenant, row.id()).orElseThrow();
    } catch (IllegalArgumentException ex) {
      failure = Reasons.IMPORT_REJECTED;
    } catch (SourceUnavailableException ex) {
      failure = Reasons.IMPORT_FAILED;
    }
    Optional<Instant> next = settings.importRetry().next(attempts, now);
    if (next.isPresent() && Reasons.IMPORT_FAILED.equals(failure)) {
      packages.change(
          tenant,
          row.id(),
          new Change(Status.DA_NHAN, failure, attempts, next.get(), null),
          SYSTEM_ACTOR,
          now);
      return packages.find(tenant, row.id()).orElseThrow();
    }
    packages.change(
        tenant,
        row.id(),
        new Change(Status.TU_CHOI, failure, attempts, now, null),
        SYSTEM_ACTOR,
        now);
    record(tenant, "TU_CHOI_GOI_CHUYEN_LIEN_CELL", row.id(), "TU_CHOI", failure);
    return packages.find(tenant, row.id()).orElseThrow();
  }

  private void notifySource(TenantId tenant, Row row) {
    Instant now = clock.instant();
    Optional<CellKeys> peer = directory.find(row.peerCell());
    if (peer.isEmpty() || row.peerTenantId() == null) {
      LOG.warn("Chưa báo được kết quả gói {}: cell nguồn không có trong danh bạ", row.id());
      return;
    }
    byte[] payload =
        codec.outcome(
            new Outcome(
                row.id(),
                row.peerTenantId(),
                settings.cellCode(),
                row.status().name(),
                row.reason()));
    try {
      byte[] signature;
      try {
        signature = TenantScope.call(tenant, () -> crypto.sign(payload));
      } catch (IllegalStateException ex) {
        // Bên ký tổ chức chưa sẵn sàng: chưa đánh dấu đã báo, lượt sau báo lại.
        LOG.warn("Chưa ký được kết quả gói {}: {}", row.id(), ErrorText.code(ex));
        return;
      }
      channel.report(peer.get(), new CellChannel.Report(payload, signature));
    } catch (CellChannel.ChannelUnavailableException ex) {
      LOG.warn("Chưa báo được kết quả gói {}: {}", row.id(), ErrorText.code(ex));
      Instant next = settings.sendRetry().next(1, now).orElse(now);
      packages.change(
          tenant,
          row.id(),
          new Change(row.status(), row.reason(), row.attempts(), next, null),
          SYSTEM_ACTOR,
          now);
      return;
    }
    packages.change(
        tenant,
        row.id(),
        new Change(row.status(), row.reason(), row.attempts(), null, now),
        SYSTEM_ACTOR,
        now);
  }

  // ------------------------------------------------------------------ tiện ích

  private void record(TenantId tenant, String action, UUID id, String outcome, String purpose) {
    AuditEvent payload =
        AuditEvent.newBuilder()
            .setAction(action)
            .setObjectType(AUDIT_OBJECT)
            .setObjectId(id.toString())
            .setOutcome(outcome)
            .setPurpose(purpose)
            .setActorAssignmentId(null)
            .setActorRole(null)
            .setSystem(true)
            .setTargets(null)
            .build();
    audit.publish(
        tenant,
        new CanonicalEvent(
            EventTopics.SYS_AUDIT_V1, AUDIT_OBJECT, id, 0, payload, Classification.C1));
  }

  private <T> T inTenant(TenantId tenant, java.util.function.Supplier<T> work) {
    return TenantScope.call(tenant, () -> tx.execute(s -> work.get()));
  }

  private static Status parseResult(String status) {
    if ("DA_NHAP".equals(status)) {
      return Status.DA_NHAP;
    }
    if ("TU_CHOI".equals(status)) {
      return Status.TU_CHOI;
    }
    throw new RejectedException(Reasons.MALFORMED, "Kết quả nhập không hợp lệ");
  }

  private static String reasonOrDefault(String reason) {
    if (reason == null || reason.isBlank()) {
      return Reasons.MALFORMED;
    }
    return reason.length() > 64 ? reason.substring(0, 64) : reason;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static byte[] hex(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
