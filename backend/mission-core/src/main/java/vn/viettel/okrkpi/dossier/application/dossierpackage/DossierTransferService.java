package vn.viettel.okrkpi.dossier.application.dossierpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.UnitProfile;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.CentralTitles;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.FormRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.EdxmlGateway;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.EdxmlGateway.Attachment;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.EdxmlGateway.RemoteState;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageEvents;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageFiles;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageTransferRepository;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageTransferRepository.NewTransfer;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageTransferRepository.TransferRow;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Gửi gói con hồ sơ diện Trung ương quản lý qua EdXML (CORE-06-08, QT-11 bước 14, BR-11-11, {@code
 * createDossierPackageTransfer}): gói đã ký, hồ sơ của người mang chức danh diện TW (40 chức danh
 * PL 2B), mỗi hồ sơ một gói con gửi integration-gateway (SVC-13) với {@code transferId} do MOD-06
 * cấp. Lần gửi ghi {@code dossier.package_transfer} trong giao dịch riêng đã commit trước khi gọi
 * SVC-13 (mẫu "chốt trước, gọi sau", wiki trang 14); gói con đầu gửi ngay trong lời gọi API để trả
 * 422 khi nơi nhận chưa cấu hình, các gói con còn lại gửi ở luồng nền.
 *
 * <p>Kết quả hỏi định kỳ ({@link #poll}) theo thoả thuận với DV-13-05: {@code DA_XAC_NHAN} ghi sổ
 * cái và khi mọi gói con của gói có biên nhận thì gói {@code DA_NOP} sang {@code DA_LUU_TRU} kèm
 * {@code transferred_to}; lần gửi chưa thành công ({@code CHO} có hẹn thử lại, hoặc {@code LOI} khi
 * hết lượt) phát EVT-21 MSG-11-06 một lần cho mỗi lần thử, gói chính giữ nguyên. Hỏi lại và gửi lại
 * đều an toàn khi nhiều pod cùng chạy: SVC-13 chống trùng theo {@code transferId}, thông báo chống
 * trùng theo {@code notified_attempts}.
 */
public class DossierTransferService {

  static final Set<PackageStatus> SIGNED =
      EnumSet.of(PackageStatus.DA_KY, PackageStatus.DA_NOP, PackageStatus.DA_LUU_TRU);

  /** Nhãn nơi nhận cho văn bản giao diện và MSG-11-06. */
  public static final Map<String, String> DESTINATIONS =
      Map.of("BTC_TW", "Ban Tổ chức Trung ương", "VPTW", "Văn phòng Trung ương");

  static final String MSG_11_06 =
      "Gửi gói con tới %s chưa thành công: %s. Hệ thống sẽ tự thử lại lúc %s.";
  static final String MSG_11_06_FINAL = "Gửi gói con tới %s chưa thành công: %s.";
  static final String REASON_TOO_LARGE = "gói con vượt quá 20 MB";
  static final String REASON_NOT_CONFIGURED = "nơi nhận chưa được cấu hình kết nối";
  static final String REASON_UNKNOWN = "bên nhận chưa tiếp nhận gói con";
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy");

  private static final Logger LOG = LoggerFactory.getLogger(DossierTransferService.class);
  private static final Duration QUIET = Duration.ofSeconds(20);

  private final DossierPackageRepository packages;
  private final PackageTransferRepository transfers;
  private final Supplier<DossierProfilesApi> profiles;
  private final CentralTitles titles;
  private final Supplier<EdxmlGateway> gateway;
  private final PackageFiles files;
  private final PackageEvents events;
  private final TransactionOperations tx;
  private final TransactionOperations committed;
  private final Executor runner;
  private final Clock clock;
  private final ZoneId zone;

  public DossierTransferService(
      DossierPackageRepository packages,
      PackageTransferRepository transfers,
      Supplier<DossierProfilesApi> profiles,
      CentralTitles titles,
      Supplier<EdxmlGateway> gateway,
      PackageFiles files,
      PackageEvents events,
      TransactionOperations tx,
      TransactionOperations committed,
      Executor runner,
      Clock clock,
      ZoneId zone) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.titles = Objects.requireNonNull(titles, "titles");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.files = Objects.requireNonNull(files, "files");
    this.events = Objects.requireNonNull(events, "events");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.committed = Objects.requireNonNull(committed, "committed");
    this.runner = Objects.requireNonNull(runner, "runner");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  /** Trạng thái tổng của một lần yêu cầu gửi. */
  public enum BatchStatus {
    DANG_CHAY,
    HOAN_TAT,
    THAT_BAI
  }

  /** Tác vụ gửi: các gói con của một lần yêu cầu. */
  public record TransferJob(
      UUID batchId, UUID packageId, UUID requester, List<TransferRow> rows, String message) {

    public BatchStatus status() {
      if (rows.stream().allMatch(r -> "DA_XAC_NHAN".equals(r.status()))) {
        return BatchStatus.HOAN_TAT;
      }
      boolean open = rows.stream().anyMatch(r -> !"DA_XAC_NHAN".equals(r.status()) && !r.deadEnd());
      return open ? BatchStatus.DANG_CHAY : BatchStatus.THAT_BAI;
    }

    public int acknowledged() {
      return (int) rows.stream().filter(r -> "DA_XAC_NHAN".equals(r.status())).count();
    }

    public Instant updatedAt() {
      return rows.stream()
          .map(TransferRow::updatedAt)
          .filter(Objects::nonNull)
          .max(Comparator.naturalOrder())
          .orElse(null);
    }
  }

  /** SVC-13 chưa cấu hình: lớp REST trả 503. */
  public static final class TransferUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TransferUnavailableException() {
      super("Chưa gửi được gói con, vui lòng thử lại sau.");
    }
  }

  // ---------------------------------------------------------------- yêu cầu gửi

  /**
   * {@code createDossierPackageTransfer}: gói chưa ký trả {@code DOSSIER-409-PACKAGE-NOT-SIGNED},
   * không có hồ sơ diện TW trả {@code DOSSIER-409-NO-CENTRAL-PROFILES}, SVC-13 chưa cấu hình nơi
   * nhận trả {@code DOSSIER-422-DESTINATION-NOT-CONFIGURED}. Hồ sơ đang gửi hoặc đã có biên nhận ở
   * cùng nơi nhận không gửi lại; không còn hồ sơ nào cần gửi thì trả tác vụ gần nhất.
   *
   * @param profileIds hồ sơ cần gửi; rỗng là mọi hồ sơ diện TW của gói
   */
  public TransferJob request(
      TenantId tenant,
      UUID actor,
      UUID actorUnit,
      UUID packageId,
      String destination,
      List<UUID> profileIds) {
    PackageRow pkg = packages.find(tenant, packageId).orElseThrow(NoSuchElementException::new);
    checkScope(pkg, actorUnit);
    if (!DESTINATIONS.containsKey(destination)) {
      throw new IllegalArgumentException("destination");
    }
    if (!SIGNED.contains(pkg.status())) {
      throw new BusinessException(
          "DOSSIER-409-PACKAGE-NOT-SIGNED",
          "Gói hồ sơ chưa được ký số nên chưa gửi được hồ sơ diện Trung ương quản lý.");
    }
    if (gateway.get() == null) {
      throw new TransferUnavailableException();
    }
    Map<UUID, UUID> candidates = centralProfiles(tenant, pkg, profileIds);
    if (candidates.isEmpty()) {
      throw new BusinessException(
          "DOSSIER-409-NO-CENTRAL-PROFILES",
          "Gói hồ sơ không có hồ sơ cán bộ diện Trung ương quản lý cần gửi.");
    }
    List<TransferRow> existing = transfers.ofPackage(tenant, pkg.id());
    Set<UUID> covered = new HashSet<>();
    existing.stream()
        .filter(r -> destination.equals(r.destination()) && !r.deadEnd())
        .forEach(r -> covered.add(r.profileId()));
    List<NewTransfer> fresh = new ArrayList<>();
    candidates.forEach(
        (profile, person) -> {
          if (!covered.contains(profile)) {
            fresh.add(new NewTransfer(Ids.newUuidV7(), profile, person));
          }
        });
    if (fresh.isEmpty()) {
      UUID latest =
          existing.stream()
              .filter(r -> destination.equals(r.destination()) && !r.deadEnd())
              .max(Comparator.comparing(r -> r.id().toString()))
              .map(TransferRow::batchId)
              .orElseThrow();
      return job(tenant, latest).orElseThrow();
    }
    UUID batch = Ids.newUuidV7();
    committed.execute(
        st -> {
          transfers.insert(tenant, pkg.periodBucket(), pkg.id(), batch, destination, fresh, actor);
          events.transferRequested(tenant, pkg, batch, destination, fresh.size(), actor);
          return null;
        });
    TransferRow first = transfers.find(tenant, fresh.get(0).id()).orElseThrow();
    try {
      send(tenant, pkg, first);
    } catch (EdxmlGateway.DestinationNotConfiguredException e) {
      for (NewTransfer t : fresh.subList(1, fresh.size())) {
        transfers.close(tenant, t.id(), "HUY", REASON_NOT_CONFIGURED);
      }
      throw new BusinessException(
          "DOSSIER-422-DESTINATION-NOT-CONFIGURED",
          DESTINATIONS.get(destination) + " chưa được cấu hình kết nối nhận hồ sơ.");
    }
    for (NewTransfer t : fresh.subList(1, fresh.size())) {
      dispatch(tenant, t.id());
    }
    return job(tenant, batch).orElseThrow();
  }

  /** Hồ sơ diện TW của gói (mã hồ sơ → người), theo thứ tự mã hồ sơ. */
  private Map<UUID, UUID> centralProfiles(TenantId tenant, PackageRow pkg, List<UUID> wanted) {
    Set<UUID> inPackage = new HashSet<>();
    packages
        .formsIn(tenant, pkg.periodBucket(), pkg.id(), List.of())
        .forEach(f -> inPackage.add(f.profileId()));
    Set<UUID> filter = wanted == null || wanted.isEmpty() ? null : new HashSet<>(wanted);
    Map<UUID, UUID> persons = new LinkedHashMap<>();
    api().unitProfiles(tenant, pkg.periodId(), pkg.orgUnitId()).stream()
        .filter(p -> inPackage.contains(p.id()))
        .filter(p -> filter == null || filter.contains(p.id()))
        .sorted(Comparator.comparing((UnitProfile p) -> p.id().toString()))
        .forEach(p -> persons.put(p.id(), p.personId()));
    if (persons.isEmpty()) {
      return persons;
    }
    Set<UUID> central =
        titles.centralPersons(
            tenant, new LinkedHashSet<>(persons.values()), LocalDate.now(clock.withZone(zone)));
    persons.values().removeIf(person -> !central.contains(person));
    return persons;
  }

  /** Giao gói con cho luồng nền; hàng đợi đầy thì lượt hỏi định kỳ gửi. */
  void dispatch(TenantId tenant, UUID transferId) {
    try {
      runner.execute(
          () ->
              TenantContext.run(
                  tenant,
                  () -> {
                    try {
                      transfers
                          .find(tenant, transferId)
                          .filter(r -> "CHUA_GUI".equals(r.status()))
                          .ifPresent(r -> sendQuietly(tenant, r));
                    } catch (RuntimeException e) {
                      LOG.warn("Không gửi được gói con {}", transferId, e);
                    }
                  }));
    } catch (RejectedExecutionException e) {
      LOG.warn("Hàng đợi gửi gói con đầy, gói con {} gửi ở lượt sau", transferId);
    }
  }

  // ---------------------------------------------------------------- gửi, theo dõi

  private void sendQuietly(TenantId tenant, TransferRow row) {
    PackageRow pkg = packages.find(tenant, row.packageId()).orElse(null);
    if (pkg == null) {
      return;
    }
    try {
      send(tenant, pkg, row);
    } catch (EdxmlGateway.DestinationNotConfiguredException e) {
      // đã đóng gói con ở send
    }
  }

  /**
   * Gửi một gói con: SVC-13 tạm lỗi thì để {@code CHUA_GUI} cho lượt sau; vượt 20 MB thì {@code
   * LOI} không thử lại và thông báo; nơi nhận chưa cấu hình thì {@code HUY} và ném lại.
   */
  private void send(TenantId tenant, PackageRow pkg, TransferRow row) {
    EdxmlGateway gw = gateway.get();
    if (gw == null) {
      return;
    }
    RemoteState state;
    try {
      state = gw.send(tenant, outgoing(tenant, pkg, row));
    } catch (EdxmlGateway.PayloadTooLargeException e) {
      fail(tenant, pkg, row, REASON_TOO_LARGE);
      return;
    } catch (EdxmlGateway.DestinationNotConfiguredException e) {
      transfers.close(tenant, row.id(), "HUY", REASON_NOT_CONFIGURED);
      throw e;
    } catch (EdxmlGateway.UnavailableException | PackageFiles.UnavailableException e) {
      LOG.info("Chưa gửi được gói con {}, thử lại ở lượt sau: {}", row.id(), e.getMessage());
      return;
    } catch (IOException e) {
      LOG.warn("Không đọc được tệp của gói con {}, thử lại ở lượt sau", row.id(), e);
      return;
    }
    apply(tenant, pkg, row, state);
  }

  private void fail(TenantId tenant, PackageRow pkg, TransferRow row, String reason) {
    transfers.close(tenant, row.id(), "LOI", reason);
    transfers.find(tenant, row.id()).ifPresent(r -> notifyFailure(tenant, pkg, r));
  }

  /**
   * Lượt theo dõi của tenant: gửi gói con chưa gửi, hỏi SVC-13 trạng thái các gói con đang chờ. Trả
   * số gói con đã xử lý.
   */
  public int poll(TenantId tenant, int limit) {
    if (gateway.get() == null) {
      return 0;
    }
    List<TransferRow> open = transfers.open(tenant, QUIET, limit);
    Map<UUID, PackageRow> pkgs = new LinkedHashMap<>();
    int done = 0;
    for (TransferRow row : open) {
      PackageRow pkg =
          pkgs.computeIfAbsent(row.packageId(), id -> packages.find(tenant, id).orElse(null));
      if (pkg == null) {
        continue;
      }
      try {
        if ("CHUA_GUI".equals(row.status())) {
          sendQuietly(tenant, row);
        } else {
          Optional<RemoteState> state = gateway.get().state(tenant, row.id());
          if (state.isPresent()) {
            apply(tenant, pkg, row, state.get());
          }
        }
        done++;
      } catch (RuntimeException e) {
        LOG.warn("Không theo dõi được gói con {}", row.id(), e);
      }
    }
    return done;
  }

  private void apply(TenantId tenant, PackageRow pkg, TransferRow before, RemoteState state) {
    if (!transfers.applyRemote(tenant, before.id(), state)) {
      return;
    }
    TransferRow row = transfers.find(tenant, before.id()).orElseThrow();
    if (failing(row)) {
      notifyFailure(tenant, pkg, row);
    } else if ("DA_XAC_NHAN".equals(row.status()) && !"DA_XAC_NHAN".equals(before.status())) {
      acknowledged(tenant, pkg, row);
    }
  }

  /** EVT-21 MSG-11-06 một lần cho mỗi lần gửi lỗi. */
  private void notifyFailure(TenantId tenant, PackageRow pkg, TransferRow row) {
    int attempt = Math.max(row.attempts(), 1);
    if (attempt <= row.notifiedAttempts()) {
      return;
    }
    tx.execute(
        st -> {
          if (transfers.markNotified(tenant, row.id(), attempt)) {
            List<UUID> to = row.requestedBy() == null ? List.of() : List.of(row.requestedBy());
            events.transferFailed(tenant, pkg, row, to, failureText(row));
          }
          return null;
        });
  }

  /** Biên nhận: sổ cái; mọi gói con có biên nhận thì gói đã nộp sang lưu trữ. */
  private void acknowledged(TenantId tenant, PackageRow pkg, TransferRow row) {
    tx.execute(
        st -> {
          events.transferAcknowledged(tenant, pkg, row);
          PackageRow locked = packages.lock(tenant, pkg.periodBucket(), pkg.id()).orElse(null);
          if (locked == null || locked.status() != PackageStatus.DA_NOP) {
            return null;
          }
          List<TransferRow> all =
              transfers.ofPackage(tenant, pkg.id()).stream()
                  .filter(r -> !"HUY".equals(r.status()))
                  .toList();
          if (!all.isEmpty() && all.stream().allMatch(r -> "DA_XAC_NHAN".equals(r.status()))) {
            packages.archive(tenant, locked, row.destination(), row.requestedBy());
            LOG.info("Gói hồ sơ {} đã chuyển lưu trữ sau biên nhận gói con", pkg.code());
          }
          return null;
        });
  }

  // ---------------------------------------------------------------- tác vụ

  /** Tác vụ gửi theo mã lần yêu cầu. */
  public Optional<TransferJob> job(TenantId tenant, UUID batchId) {
    List<TransferRow> rows = transfers.ofBatch(tenant, batchId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    TransferRow head = rows.get(0);
    return Optional.of(
        new TransferJob(batchId, head.packageId(), head.requestedBy(), rows, message(rows)));
  }

  /** Thông điệp tác vụ: MSG-11-06 của gói con lỗi đầu tiên, ngược lại tiến độ gửi. */
  String message(List<TransferRow> rows) {
    for (TransferRow r : rows) {
      if (failing(r)) {
        return failureText(r);
      }
    }
    long acked = rows.stream().filter(r -> "DA_XAC_NHAN".equals(r.status())).count();
    if (acked == rows.size()) {
      return "Đã gửi " + acked + " gói con và nhận được biên nhận.";
    }
    return "Đang gửi gói con: " + acked + "/" + rows.size() + " đã có biên nhận.";
  }

  /**
   * Gói con đang lỗi: SVC-13 đã hết lượt ({@code LOI}), MOD-06 huỷ ({@code HUY}), hoặc lần gửi
   * trước chưa thành công và SVC-13 hẹn thử lại ({@code CHO} có {@code attempts} và {@code
   * nextAttemptAt}, cách DV-13-05 ghi lỗi tạm thời).
   */
  static boolean failing(TransferRow r) {
    return "LOI".equals(r.status())
        || "HUY".equals(r.status())
        || "CHO".equals(r.status()) && r.attempts() > 0 && r.nextAttemptAt() != null;
  }

  /** Văn bản MSG-11-06 của một gói con lỗi. */
  public String failureText(TransferRow r) {
    String where = DESTINATIONS.getOrDefault(r.destination(), r.destination());
    String reason =
        r.lastError() == null || r.lastError().isBlank() ? REASON_UNKNOWN : r.lastError();
    if (r.nextAttemptAt() == null) {
      return String.format(MSG_11_06_FINAL, where, reason);
    }
    return String.format(MSG_11_06, where, reason, TIME.format(r.nextAttemptAt().atZone(zone)));
  }

  // ---------------------------------------------------------------- gói con

  /**
   * Gói con của một hồ sơ: các biểu mẫu PDF/A đã sinh, manifest gói đã ký, chữ ký gói và tệp thông
   * tin (suy luận, wiki trang 14). Đọc tệp có giới hạn 20 MB tổng.
   */
  EdxmlGateway.Outgoing outgoing(TenantId tenant, PackageRow pkg, TransferRow row)
      throws IOException {
    List<Attachment> attachments = new ArrayList<>();
    long[] total = {0};
    String manifest = packages.manifest(tenant, pkg.periodBucket(), pkg.id());
    attachments.add(
        bytes(
            PackageArchive.MANIFEST,
            "application/json",
            manifest.getBytes(StandardCharsets.UTF_8),
            total));
    Optional<String> signature = packages.signatureKey(tenant, pkg.periodBucket(), pkg.id());
    if (signature.isPresent()) {
      attachments.add(
          read(
              tenant,
              PackageArchive.SIGNATURE,
              "application/pkcs7-signature",
              signature.get(),
              total));
    }
    List<FormRow> forms =
        packages.formsOfProfile(tenant, pkg.periodBucket(), pkg.id(), row.profileId()).stream()
            .filter(
                f -> f.objectKey() != null && DossierExportService.RENDERED.contains(f.status()))
            .sorted(Comparator.comparing(FormRow::formCode))
            .toList();
    for (FormRow f : forms) {
      attachments.add(
          read(
              tenant,
              PackageArchive.entryName(f).replace('/', '_'),
              "application/pdf",
              f.objectKey(),
              total));
    }
    String info =
        "{\"maGoi\":\""
            + pkg.code()
            + "\",\"phienBan\":"
            + pkg.versionNo()
            + ",\"hoSoId\":\""
            + row.profileId()
            + "\",\"soTep\":"
            + forms.size()
            + "}";
    attachments.add(
        bytes(
            "thong-tin-goi-con.json",
            "application/json",
            info.getBytes(StandardCharsets.UTF_8),
            total));
    return new EdxmlGateway.Outgoing(
        row.id(),
        pkg.id(),
        row.destination(),
        "Hồ sơ đánh giá cán bộ diện Trung ương quản lý, gói " + pkg.code(),
        pkg.code(),
        attachments);
  }

  private Attachment read(TenantId tenant, String name, String type, String objectKey, long[] total)
      throws IOException {
    long room = EdxmlGateway.MAX_PAYLOAD_BYTES - total[0];
    byte[] content;
    try (InputStream in = files.open(tenant, objectKey)) {
      content = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, room + 1));
    }
    return bytes(name, type, content, total);
  }

  private static Attachment bytes(String name, String type, byte[] content, long[] total) {
    total[0] += content.length;
    if (total[0] > EdxmlGateway.MAX_PAYLOAD_BYTES) {
      throw new EdxmlGateway.PayloadTooLargeException();
    }
    return new Attachment(name, type, content);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private DossierProfilesApi api() {
    DossierProfilesApi api = profiles.get();
    if (api == null) {
      throw new IllegalStateException("Chưa có cổng hồ sơ đánh giá của MOD-04");
    }
    return api;
  }

  private static void checkScope(PackageRow r, UUID actorUnit) {
    if (actorUnit == null || !actorUnit.equals(r.orgUnitId())) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền với gói hồ sơ này.");
    }
  }
}
