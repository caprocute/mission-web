package vn.viettel.okrkpi.dossier.application.dossierpackage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.Pack;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.UnitProfile;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.Ensured;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.Filter;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.FormRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.NewForm;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.NewPackage;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.RenderRequests;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.RenderRequests.RenderUnavailableException;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.RenderRequests.RequestState;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageKind;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.RenderProgress;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Gói hồ sơ số của MOD-06 (CORE-06-06, QT-11 bước 10–12, SCR-1102).
 *
 * <ol>
 *   <li>EVT-12: bảo đảm có gói {@code DANG_LAP} của (đơn vị chốt của hồ sơ, kỳ, loại {@code QUY}/
 *       {@code NAM}), ghi biểu mẫu cá nhân của hồ sơ vào danh mục chờ sinh ({@code CHO_SINH}).
 *   <li>Khi mọi hồ sơ của đơn vị đã có quyết định hoặc thuộc trường hợp đặc thù đã kết luận: thêm
 *       biểu mẫu của đơn vị, gói sang {@code DANG_SINH}, giao dịch kết thúc rồi mới gửi lệnh sinh
 *       tới SVC-11 (không giữ khoá dòng gói khi chờ SVC-11).
 *   <li>EVT-17: biểu mẫu sang {@code DA_SINH}; trạng thái gói tính lại theo lô ({@link #settle}),
 *       vì một gói lớn nhận hàng chục nghìn EVT-17 mà mỗi lần đếm lại đều quét cả gói.
 *   <li>Tệp lỗi: SVC-11 không phát sự kiện lỗi nên {@link #track} đọc trạng thái lệnh sinh; lệnh đã
 *       kết thúc quá thời gian chờ mà biểu mẫu còn {@code CHO_SINH} thì biểu mẫu sang {@code LOI},
 *       gói về {@code DANG_LAP} và giữ tệp đã sinh (MSG-11-07). EVT-17 đến muộn của biểu mẫu đã
 *       đánh lỗi vẫn được nhận.
 *   <li>{@link #regenerate}: chỉ biểu mẫu {@code LOI} về {@code CHO_SINH} và được gửi lại.
 * </ol>
 *
 * <p>Phạm vi: người gọi API chỉ thấy gói của đơn vị thuộc phân công đang chọn (scope Đv).
 */
public class DossierPackageService {

  static final String MODULE = "DOSSIER";

  /** Mã mẫu không ghim phiên bản: SVC-11 dùng phiên bản đang hiệu lực mới nhất (DV-11-04). */
  static final String LATEST_TEMPLATE = "";

  private static final Logger LOG = LoggerFactory.getLogger(DossierPackageService.class);
  private static final Set<FormStatus> UNRENDERED = Set.of(FormStatus.CHO_SINH, FormStatus.LOI);

  private final DossierPackageRepository packages;
  private final Supplier<DossierProfilesApi> profiles;
  private final RenderRequests renders;
  private final Supplier<PeriodApi> periods;
  private final Supplier<OrgDirectoryApi> directory;
  private final TransactionOperations tx;
  private final BooleanSupplier connectionBound;
  private final Clock clock;
  private final Duration failureGrace;
  private final Set<Dirty> dirty = ConcurrentHashMap.newKeySet();
  private final java.util.Queue<Pending> pendingSends =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  /** Gói cần tính lại trạng thái sau EVT-17. */
  record Dirty(TenantId tenant, int periodBucket, UUID packageId) {}

  /** Lệnh sinh chờ gửi sau khi giao dịch đổi trạng thái kết thúc. */
  record Pending(TenantId tenant, PackageRow row, UUID actor) {}

  /** Gói kèm tiến độ sinh tệp và số hồ sơ. */
  public record PackageView(PackageRow row, RenderProgress progress, int profileCount) {}

  /** Lệnh lập gói thủ công ({@code DossierPackageCreate}). */
  public record NewPackageCommand(
      UUID periodId, UUID orgUnitId, PackageKind kind, LocalDate deadline) {}

  /**
   * Một biểu mẫu cần sinh trả cho SVC-11 ({@code HttpRenderInputClient.Form}); {@code
   * reopenRequestId} là yêu cầu mở lại kỳ khi gói là phiên bản lập sau EVT-22 (BR-13-08).
   */
  public record RenderInput(
      UUID formInstanceId,
      String formCode,
      String subjectGroupCode,
      String dataSnapshotHash,
      Integer templateVersion,
      String policyPackCode,
      String policyPackVersion,
      UUID reopenRequestId) {}

  /**
   * Dữ liệu xem trước trả SVC-11 ({@code HttpRenderInputClient.Preview}): {@code data} là tài liệu
   * JSON chuẩn tắc cùng hình dạng với dữ liệu biểu mẫu chính thức.
   */
  public record PreviewInput(
      String subjectGroupCode,
      Integer templateVersion,
      String policyPackCode,
      String policyPackVersion,
      String data) {}

  /** Mã biểu mẫu hợp lệ ({@code ck_form_instance__form_code}). */
  static final Set<String> FORM_CODES =
      Set.of("MAU_1A", "MAU_1B", "MAU_1C", "MAU_1D", "MAU_2", "MAU_3", "BANG_CHAM_DIEM");

  /** Số hồ sơ tối đa của một lệnh sinh nháp (suy luận). */
  static final int MAX_DRAFT_PROFILES = 500;

  /** Nguồn xem trước: hồ sơ đánh giá của MOD-04 và biểu mẫu đã có của MOD-06. */
  static final String SOURCE_APPRAISAL = "appraisal";

  static final String SOURCE_DOSSIER = "dossier";

  public DossierPackageService(
      DossierPackageRepository packages,
      Supplier<DossierProfilesApi> profiles,
      RenderRequests renders,
      Supplier<PeriodApi> periods,
      Supplier<OrgDirectoryApi> directory,
      TransactionOperations tx,
      BooleanSupplier connectionBound,
      Clock clock,
      Duration failureGrace) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.renders = Objects.requireNonNull(renders, "renders");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.connectionBound = Objects.requireNonNull(connectionBound, "connectionBound");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.failureGrace = Objects.requireNonNull(failureGrace, "failureGrace");
  }

  // ---------------------------------------------------------------- EVT-12

  /**
   * EVT-12: hồ sơ vừa có quyết định xếp loại. Sự kiện lặp (cùng hay khác mã sự kiện) không lập gói
   * thứ hai, không thêm biểu mẫu trùng; quyết định lại khi biểu mẫu chưa sinh thì ghi lại băm.
   *
   * @return gói của hồ sơ, rỗng khi hồ sơ không còn hoặc chưa ở trạng thái đã quyết định
   */
  public Optional<PackageRow> onRatingDecided(TenantId tenant, UUID profileId, UUID actor) {
    DossierProfilesApi api = api();
    Optional<UnitProfile> found = api.find(tenant, profileId);
    if (found.isEmpty() || !found.get().decided()) {
      LOG.info("Bỏ qua EVT-12 của hồ sơ {} chưa ở trạng thái đã quyết định", profileId);
      return Optional.empty();
    }
    UnitProfile p = found.get();
    UUID by = actor == null ? p.personId() : actor;
    NewPackage cmd =
        new NewPackage(
            p.periodId(),
            p.periodBucket(),
            p.orgUnitId(),
            PackageKind.ofBucket(p.periodBucket()),
            null);
    PackageRow pkg =
        inTx(
            () -> {
              Ensured e = packages.ensure(tenant, cmd, Ids.newUuidV7(), by);
              // Khoá dòng gói: thêm biểu mẫu tuần tự với lần chuyển DANG_SINH của gói.
              PackageRow locked =
                  packages.lock(tenant, e.row().periodBucket(), e.row().id()).orElse(e.row());
              if (locked.status() == PackageStatus.DANG_LAP) {
                syncPersonal(tenant, locked, p, by);
              }
              return locked;
            });
    maybeStart(tenant, pkg, by);
    return Optional.of(pkg);
  }

  /**
   * Gửi lệnh sinh khi đủ điều kiện (BR-11-02): mọi hồ sơ của đơn vị đã quyết định hoặc thuộc trường
   * hợp đặc thù đã kết luận, gói đang lập và không còn tệp lỗi chờ người dùng sinh lại.
   */
  void maybeStart(TenantId tenant, PackageRow pkg, UUID actor) {
    if (pkg.status() != PackageStatus.DANG_LAP || !ready(tenant, pkg)) {
      return;
    }
    start(tenant, pkg.id(), actor);
  }

  private boolean ready(TenantId tenant, PackageRow pkg) {
    List<UUID> undecided = api().undecidedPersons(tenant, pkg.periodId(), pkg.orgUnitId());
    if (!undecided.isEmpty()) {
      Set<UUID> settled = packages.settledSpecialCases(tenant, pkg.periodBucket(), pkg.periodId());
      if (!settled.containsAll(undecided)) {
        return false;
      }
    }
    return packages.progress(tenant, pkg.periodBucket(), pkg.id()).failed() == 0;
  }

  /**
   * Chuyển gói {@code DANG_LAP} sang {@code DANG_SINH} với đủ biểu mẫu chờ sinh rồi gửi lệnh sinh.
   * Lời gọi đồng thời cho cùng gói chỉ một lời gọi chuyển được (khoá dòng gói trong giao dịch).
   */
  boolean start(TenantId tenant, UUID packageId, UUID actor) {
    PackageRow current = packages.find(tenant, packageId).orElse(null);
    if (current == null || current.status() != PackageStatus.DANG_LAP) {
      return false;
    }
    List<UnitProfile> all = api().unitProfiles(tenant, current.periodId(), current.orgUnitId());
    PackageRow started =
        inTx(
            () -> {
              PackageRow pkg =
                  packages.lock(tenant, current.periodBucket(), packageId).orElse(null);
              if (pkg == null || pkg.status() != PackageStatus.DANG_LAP) {
                return null;
              }
              syncAll(tenant, pkg, all, actor, true);
              RenderProgress progress = packages.progress(tenant, pkg.periodBucket(), pkg.id());
              if (progress.failed() > 0 || progress.total() == 0) {
                return null;
              }
              if (progress.pending() == 0) {
                packages.updateStatus(tenant, pkg, PackageStatus.CHO_KY, null, actor);
                return null;
              }
              long version =
                  packages.updateStatus(tenant, pkg, PackageStatus.DANG_SINH, null, actor);
              return withStatus(pkg, PackageStatus.DANG_SINH, version);
            });
    if (started == null) {
      return false;
    }
    sendWhenFree(tenant, started, actor);
    return true;
  }

  // ---------------------------------------------------------------- EVT-17

  /**
   * EVT-17 của một biểu mẫu chính thức. Mã biểu mẫu lạ (biên bản hội nghị dùng chung chủ đề) và
   * biểu mẫu đã sinh được bỏ qua, nên EVT-17 trùng không đổi băm tệp.
   *
   * @return {@code true} khi biểu mẫu vừa sang {@code DA_SINH}
   */
  public boolean onFormGenerated(
      TenantId tenant,
      UUID formInstanceId,
      UUID renderJobId,
      String objectKey,
      byte[] pdfSha256,
      UUID actor) {
    Optional<FormRow> found = packages.findForm(tenant, formInstanceId);
    if (found.isEmpty() || found.get().status().rendered() || found.get().packageId() == null) {
      return false;
    }
    FormRow f = found.get();
    int n =
        packages.markGenerated(
            tenant, f.periodBucket(), f.id(), renderJobId, objectKey, pdfSha256, actor);
    if (n == 0) {
      return false;
    }
    Dirty d = new Dirty(tenant, f.periodBucket(), f.packageId());
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dirty.add(d);
            }
          });
    } else {
      dirty.add(d);
    }
    return true;
  }

  /**
   * Gửi các lệnh sinh đang chờ rồi tính lại trạng thái các gói vừa nhận EVT-17; trả số gói đã đổi
   * trạng thái.
   */
  public int settle() {
    for (Pending p = pendingSends.poll(); p != null; p = pendingSends.poll()) {
      Pending current = p;
      try {
        TenantContext.run(
            current.tenant(), () -> send(current.tenant(), current.row(), current.actor()));
      } catch (RuntimeException e) {
        LOG.warn("Chưa gửi được lệnh sinh của gói hồ sơ {}", current.row().id(), e);
      }
    }
    int changed = 0;
    for (Dirty d : List.copyOf(dirty)) {
      dirty.remove(d);
      try {
        if (TenantContext.call(
            d.tenant(), () -> recompute(d.tenant(), d.periodBucket(), d.packageId()))) {
          changed++;
        }
      } catch (Exception e) {
        dirty.add(d);
        LOG.warn("Chưa tính lại được trạng thái gói hồ sơ {}", d.packageId(), e);
      }
    }
    return changed;
  }

  /** Số gói đang chờ tính lại hoặc chờ gửi lệnh (kiểm thử, chỉ số). */
  int unsettled() {
    return dirty.size() + pendingSends.size();
  }

  /**
   * Gói đang sinh: còn tệp chờ thì giữ; hết tệp chờ mà có tệp lỗi thì về {@code DANG_LAP}; đủ tệp
   * thì {@code CHO_KY}. Gói đang lập vì lỗi mà EVT-17 muộn làm đủ tệp cũng sang {@code CHO_KY}.
   */
  boolean recompute(TenantId tenant, int periodBucket, UUID packageId) {
    Boolean changed =
        inTx(
            () -> {
              PackageRow pkg = packages.lock(tenant, periodBucket, packageId).orElse(null);
              if (pkg == null) {
                return false;
              }
              RenderProgress progress = packages.progress(tenant, periodBucket, packageId);
              PackageStatus next = progress.next();
              boolean apply =
                  pkg.status() == PackageStatus.DANG_SINH && next != PackageStatus.DANG_SINH
                      || pkg.status() == PackageStatus.DANG_LAP && next == PackageStatus.CHO_KY;
              if (!apply) {
                return false;
              }
              packages.updateStatus(tenant, pkg, next, null, pkg.updatedBy());
              return true;
            });
    return Boolean.TRUE.equals(changed);
  }

  // ---------------------------------------------------------------- theo dõi tệp lỗi

  /**
   * Theo dõi gói đang sinh của một tenant: lệnh sinh đã kết thúc quá {@code failureGrace} mà biểu
   * mẫu còn chờ thì biểu mẫu sang {@code LOI}; gói có biểu mẫu chờ chưa gắn lệnh (pod dừng giữa
   * chừng) thì gửi lại lệnh cùng khoá lặp; cuối cùng tính lại trạng thái gói.
   *
   * @return số biểu mẫu vừa đánh lỗi
   */
  public int track(TenantId tenant, int pageSize) {
    int failed = 0;
    UUID after = null;
    Instant cutoff = clock.instant().minus(failureGrace);
    while (true) {
      List<PackageRow> page = packages.generating(tenant, after, pageSize);
      for (PackageRow pkg : page) {
        try {
          failed += trackOne(tenant, pkg, cutoff);
        } catch (RuntimeException e) {
          LOG.warn("Không theo dõi được gói hồ sơ {}", pkg.id(), e);
        }
      }
      if (page.size() < pageSize) {
        return failed;
      }
      after = page.get(page.size() - 1).id();
    }
  }

  private int trackOne(TenantId tenant, PackageRow pkg, Instant cutoff) {
    int failed = 0;
    List<UUID> requests = packages.pendingRequests(tenant, pkg.periodBucket(), pkg.id());
    for (UUID request : requests) {
      Optional<RequestState> state = renders.state(tenant, request);
      if (state.isPresent()
          && state.get().finished()
          && (state.get().updatedAt() == null || state.get().updatedAt().isBefore(cutoff))) {
        failed +=
            inTx(
                () ->
                    packages.markFailed(
                        tenant, pkg.periodBucket(), pkg.id(), request, pkg.updatedBy()));
      }
    }
    RenderProgress progress = packages.progress(tenant, pkg.periodBucket(), pkg.id());
    if (requests.isEmpty() && progress.pending() > 0) {
      send(tenant, pkg, pkg.updatedBy());
    }
    recompute(tenant, pkg.periodBucket(), pkg.id());
    return failed;
  }

  // ---------------------------------------------------------------- API

  /** {@code createDossierPackage}: lập thủ công; trùng đơn vị, kỳ, loại thì 409. */
  public PackageView create(TenantId tenant, UUID actor, UUID actorUnit, NewPackageCommand cmd) {
    Objects.requireNonNull(cmd, "cmd");
    if (actorUnit == null || !actorUnit.equals(cmd.orgUnitId())) {
      throw outOfScope("Đồng chí chỉ lập gói hồ sơ cho đơn vị mình phụ trách.");
    }
    PeriodSnapshot period =
        periods.get() == null
            ? null
            : periods.get().findById(tenant, PeriodId.of(cmd.periodId())).orElse(null);
    if (period == null) {
      throw invalid("periodId", "Không tìm thấy kỳ đánh giá.");
    }
    int bucket = bucket(period);
    if (cmd.kind() != PackageKind.DIEN_TW && cmd.kind() != PackageKind.ofBucket(bucket)) {
      throw invalid("packageKind", "Loại gói không khớp với loại kỳ đánh giá.");
    }
    if (!api().anyDecided(tenant, cmd.periodId(), cmd.orgUnitId())) {
      throw new BusinessException(
          "DOSSIER-409-PERIOD-NOT-DECIDED", "Đơn vị chưa có hồ sơ nào được quyết định xếp loại.");
    }
    PackageRow pkg =
        inTx(
            () -> {
              Ensured e =
                  packages.ensure(
                      tenant,
                      new NewPackage(
                          cmd.periodId(), bucket, cmd.orgUnitId(), cmd.kind(), cmd.deadline()),
                      Ids.newUuidV7(),
                      actor);
              if (!e.created()) {
                throw new BusinessException(
                    "DOSSIER-409-PACKAGE-EXISTS",
                    "Đơn vị đã có gói hồ sơ " + e.row().code() + " cho kỳ này.");
              }
              return e.row();
            });
    if (pkg.kind() != PackageKind.DIEN_TW) {
      List<UnitProfile> all = api().unitProfiles(tenant, pkg.periodId(), pkg.orgUnitId());
      inTx(
          () -> {
            packages
                .lock(tenant, pkg.periodBucket(), pkg.id())
                .filter(r -> r.status() == PackageStatus.DANG_LAP)
                .ifPresent(r -> syncAll(tenant, r, all, actor, false));
            return null;
          });
      maybeStart(tenant, pkg, actor);
    }
    return view(tenant, packages.find(tenant, pkg.id()).orElse(pkg));
  }

  /** {@code listDossierPackages}: gói của đơn vị thuộc phân công đang chọn. */
  public List<PackageView> list(
      TenantId tenant, UUID actorUnit, Filter filter, UUID after, int limit) {
    if (actorUnit == null || filter.orgUnitId() != null && !filter.orgUnitId().equals(actorUnit)) {
      return List.of();
    }
    Filter scoped = new Filter(filter.periodId(), actorUnit, filter.kind(), filter.status());
    return packages.list(tenant, scoped, after, limit).stream().map(r -> view(tenant, r)).toList();
  }

  /** Gói kèm tiến độ, không kiểm phạm vi (tác vụ dài của lệnh sinh lại). */
  public Optional<PackageView> view(TenantId tenant, UUID id) {
    return packages.find(tenant, id).map(r -> view(tenant, r));
  }

  /** {@code getDossierPackage}. */
  public Optional<PackageView> get(TenantId tenant, UUID actorUnit, UUID id) {
    Optional<PackageRow> found = packages.find(tenant, id);
    found.ifPresent(r -> checkScope(r, actorUnit));
    return found.map(r -> view(tenant, r));
  }

  /** {@code listDossierPackageForms}. */
  public List<FormRow> forms(
      TenantId tenant,
      UUID actorUnit,
      UUID id,
      FormStatus status,
      String formCode,
      UUID after,
      int limit) {
    PackageRow pkg = packages.find(tenant, id).orElseThrow(NoSuchElementException::new);
    checkScope(pkg, actorUnit);
    return packages.forms(tenant, pkg.periodBucket(), pkg.id(), status, formCode, after, limit);
  }

  /**
   * {@code regenerateDossierPackageForms}: chỉ biểu mẫu {@code LOI} (lọc theo {@code formIds}) về
   * {@code CHO_SINH} với băm dữ liệu tính lại, gói sang {@code DANG_SINH}, rồi gửi lệnh sinh.
   */
  public PackageView regenerate(
      TenantId tenant,
      UUID actor,
      UUID actorUnit,
      UUID id,
      long expectedVersion,
      Collection<UUID> formIds) {
    PackageRow current = packages.find(tenant, id).orElseThrow(NoSuchElementException::new);
    checkScope(current, actorUnit);
    List<UnitProfile> all = api().unitProfiles(tenant, current.periodId(), current.orgUnitId());
    PackageRow regenerated =
        inTx(
            () -> {
              PackageRow pkg =
                  packages
                      .lock(tenant, current.periodBucket(), id)
                      .orElseThrow(NoSuchElementException::new);
              if (pkg.status().locked()) {
                throw new BusinessException(
                    "DOSSIER-409-PACKAGE-LOCKED", "Gói hồ sơ đã ký số, không sinh lại được.");
              }
              if (pkg.rowVersion() != expectedVersion) {
                throw VersionConflictException.of(MODULE, expectedVersion, pkg.rowVersion());
              }
              if (pkg.status() == PackageStatus.DANG_SINH) {
                throw new BusinessException(
                    "DOSSIER-409-INVALID-STATE", "Gói hồ sơ đang sinh biểu mẫu.");
              }
              int reset =
                  packages.resetFailed(
                      tenant,
                      pkg.periodBucket(),
                      pkg.id(),
                      formIds == null ? List.of() : formIds,
                      actor);
              if (reset == 0) {
                throw new BusinessException(
                    "DOSSIER-409-NO-FAILED-FORMS", "Gói hồ sơ không có biểu mẫu lỗi cần sinh lại.");
              }
              refreshPending(tenant, pkg, all);
              long version =
                  packages.updateStatus(
                      tenant, pkg, PackageStatus.DANG_SINH, expectedVersion, actor);
              if (version == 0) {
                throw VersionConflictException.of(MODULE, expectedVersion, pkg.rowVersion() + 1);
              }
              return withStatus(pkg, PackageStatus.DANG_SINH, version);
            });
    sendWhenFree(tenant, regenerated, actor);
    return view(tenant, packages.find(tenant, id).orElse(regenerated));
  }

  // ---------------------------------------------------------------- cổng nội bộ cho SVC-11

  /**
   * Biểu mẫu chờ sinh của gói cho SVC-11. Rỗng khi không có gói hoặc người gọi có phân công nhưng
   * không thuộc đơn vị của gói (lời gọi bằng token dịch vụ không có phân công); gói không ở {@code
   * DANG_SINH} thì 409.
   */
  public Optional<List<RenderInput>> renderInputs(
      TenantId tenant, UUID actor, UUID packageId, Collection<String> formCodes) {
    Optional<PackageRow> found = packages.find(tenant, packageId);
    if (found.isEmpty() || outsideUnit(tenant, actor, found.get().orgUnitId())) {
      return Optional.empty();
    }
    PackageRow pkg = found.get();
    if (pkg.status() != PackageStatus.DANG_SINH) {
      throw new BusinessException(
          "DOSSIER-409-INVALID-STATE", "Gói hồ sơ không ở bước sinh biểu mẫu.");
    }
    List<FormRow> pending =
        packages.formsIn(tenant, pkg.periodBucket(), pkg.id(), List.of(FormStatus.CHO_SINH));
    Map<UUID, Pack> packs = new LinkedHashMap<>();
    for (UnitProfile p : api().unitProfiles(tenant, pkg.periodId(), pkg.orgUnitId())) {
      packs.put(p.id(), p.pack());
    }
    UUID reopen = packages.reopenRequest(tenant, pkg.periodBucket(), pkg.id()).orElse(null);
    List<RenderInput> out = new ArrayList<>();
    for (FormRow f : pending) {
      if (formCodes != null && !formCodes.isEmpty() && !formCodes.contains(f.formCode())) {
        continue;
      }
      Pack pack = packs.get(f.profileId());
      out.add(
          new RenderInput(
              f.id(),
              f.formCode(),
              null,
              HexFormat.of().formatHex(f.dataSnapshotHash()),
              templateVersion(f.templateVersion()),
              pack == null ? null : pack.code(),
              pack == null ? null : pack.version(),
              reopen));
    }
    return Optional.of(out);
  }

  /** Tài liệu JSON chuẩn tắc của biểu mẫu, tính lại từ dữ liệu đã quyết định. */
  public Optional<byte[]> renderData(TenantId tenant, UUID formInstanceId) {
    Optional<FormRow> found = packages.findForm(tenant, formInstanceId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    FormRow f = found.get();
    if (f.packageId() == null) {
      return draftData(tenant, f);
    }
    PackageRow pkg = packages.find(tenant, f.packageId()).orElse(null);
    if (pkg == null) {
      return Optional.empty();
    }
    Optional<UnitProfile> owner = api().find(tenant, f.profileId());
    if (owner.isEmpty()) {
      return Optional.empty();
    }
    UnitProfile p = owner.get();
    if (isUnitForm(p.pack(), f.formCode())) {
      List<UnitProfile> all = api().unitProfiles(tenant, pkg.periodId(), pkg.orgUnitId());
      return Optional.of(
          FormDocuments.unit(f.formCode(), pkg.periodId(), pkg.orgUnitId(), p.pack(), all)
              .canonicalJson());
    }
    return Optional.of(FormDocuments.personal(f.formCode(), p).canonicalJson());
  }

  // ---------------------------------------------------------------- bản nháp, xem trước

  /**
   * Biểu mẫu nháp ngoài gói cho các hồ sơ đang chọn (DV-11-03, {@code createDossierDraftForms}):
   * biểu mẫu đơn vị là một biểu mẫu trên đúng tập đã chọn (lưu {@code source_profile_ids}), biểu
   * mẫu cá nhân là một biểu mẫu cho mỗi hồ sơ; dữ liệu là dữ liệu hiện tại, kể cả hồ sơ chưa quyết
   * định. Rỗng khi có hồ sơ không tồn tại, không thuộc kỳ hoặc ngoài đơn vị của người gọi.
   */
  public Optional<List<RenderInput>> draftForms(
      TenantId tenant, UUID actor, String formCode, UUID periodId, List<UUID> profileIds) {
    if (formCode == null || !FORM_CODES.contains(formCode)) {
      throw invalid("formCode", "Mã biểu mẫu không hợp lệ.");
    }
    if (periodId == null) {
      throw invalid("periodId", "Chưa chọn kỳ đánh giá.");
    }
    List<UUID> ids =
        profileIds == null ? List.of() : profileIds.stream().distinct().sorted().toList();
    if (ids.isEmpty() || ids.size() > MAX_DRAFT_PROFILES) {
      throw invalid(
          "profileIds", "Chọn từ 1 đến " + MAX_DRAFT_PROFILES + " hồ sơ để sinh biểu mẫu nháp.");
    }
    List<UnitProfile> selected = new ArrayList<>();
    for (UUID id : ids) {
      Optional<UnitProfile> p = api().find(tenant, id);
      if (p.isEmpty()
          || !periodId.equals(p.get().periodId())
          || outsideUnit(tenant, actor, p.get().orgUnitId())) {
        return Optional.empty();
      }
      selected.add(p.get());
    }
    UnitProfile owner =
        selected.stream().filter(UnitProfile::head).findFirst().orElse(selected.get(0));
    UUID creator = actor == null ? new UUID(0L, 0L) : actor;
    List<RenderInput> out = new ArrayList<>();
    inTx(
        () -> {
          if (isUnitForm(owner.pack(), formCode)) {
            FormDocuments.Document doc =
                FormDocuments.selection(
                    formCode, periodId, commonUnit(selected), owner.pack(), selected);
            out.add(draft(tenant, owner, formCode, doc, ids, creator));
          } else {
            for (UnitProfile p : selected) {
              FormDocuments.Document doc = FormDocuments.personal(formCode, p);
              out.add(draft(tenant, p, formCode, doc, List.of(), creator));
            }
          }
          return null;
        });
    return Optional.of(out);
  }

  /**
   * Dữ liệu hiện tại để xem trước (DV-11-02, {@code previewDossierForm}), không ghi gì: nguồn
   * {@code appraisal} là hồ sơ đánh giá (biểu mẫu đơn vị lấy mọi hồ sơ của đơn vị trong kỳ), nguồn
   * {@code dossier} là biểu mẫu đã có. Nguồn khác ({@code oversight}) chưa có dữ liệu ở MOD-06 nên
   * rỗng (suy luận, wiki trang 14).
   */
  public Optional<PreviewInput> preview(
      TenantId tenant, UUID actor, String formCode, String sourceModule, UUID sourceRefId) {
    if (formCode == null || !FORM_CODES.contains(formCode)) {
      throw invalid("formCode", "Mã biểu mẫu không hợp lệ.");
    }
    if (sourceRefId == null) {
      return Optional.empty();
    }
    if (SOURCE_DOSSIER.equals(sourceModule)) {
      Optional<FormRow> form = packages.findForm(tenant, sourceRefId);
      if (form.isEmpty() || !formCode.equals(form.get().formCode())) {
        return Optional.empty();
      }
      Optional<UnitProfile> owner = api().find(tenant, form.get().profileId());
      if (owner.isEmpty() || outsideUnit(tenant, actor, owner.get().orgUnitId())) {
        return Optional.empty();
      }
      return renderData(tenant, sourceRefId)
          .map(data -> previewOf(owner.get().pack(), formCode, data));
    }
    if (!SOURCE_APPRAISAL.equals(sourceModule)) {
      return Optional.empty();
    }
    Optional<UnitProfile> found = api().find(tenant, sourceRefId);
    if (found.isEmpty() || outsideUnit(tenant, actor, found.get().orgUnitId())) {
      return Optional.empty();
    }
    UnitProfile p = found.get();
    FormDocuments.Document doc =
        isUnitForm(p.pack(), formCode)
            ? FormDocuments.selection(
                formCode,
                p.periodId(),
                p.orgUnitId(),
                p.pack(),
                api().unitProfiles(tenant, p.periodId(), p.orgUnitId()))
            : FormDocuments.personal(formCode, p);
    return Optional.of(previewOf(p.pack(), formCode, doc.canonicalJson()));
  }

  /**
   * Xem trước văn bản từ dữ liệu module khác (văn bản trả lời khiếu nại của MOD-07, SCR-1202): mã
   * mẫu theo Policy Pack của hồ sơ nguồn, phiên bản mẫu ghim theo gói nếu có.
   *
   * @param profileId hồ sơ đánh giá của bản ghi nguồn, có thể rỗng
   * @param data dữ liệu JSON chuẩn tắc do module nguồn dựng
   */
  public PreviewInput previewFromSource(
      TenantId tenant, String formCode, UUID profileId, byte[] data) {
    if (formCode == null || !formCode.matches("[A-Z][A-Z0-9_]{0,15}")) {
      throw invalid("formCode", "Mã mẫu văn bản không hợp lệ.");
    }
    Pack pack =
        profileId == null
            ? null
            : api().find(tenant, profileId).map(UnitProfile::pack).orElse(null);
    return previewOf(pack, formCode, data);
  }

  private RenderInput draft(
      TenantId tenant,
      UnitProfile owner,
      String formCode,
      FormDocuments.Document doc,
      List<UUID> sources,
      UUID actor) {
    String version = pinned(owner.pack(), formCode);
    NewForm form =
        new NewForm(
            Ids.newUuidV7(), owner.id(), formCode, version, doc.sha256(), !sources.isEmpty());
    packages.insertDraft(tenant, owner.periodId(), owner.periodBucket(), form, sources, actor);
    return new RenderInput(
        form.id(),
        formCode,
        null,
        HexFormat.of().formatHex(doc.sha256()),
        templateVersion(version),
        owner.pack() == null ? null : owner.pack().code(),
        owner.pack() == null ? null : owner.pack().version(),
        null);
  }

  /** Dữ liệu biểu mẫu nháp: biểu mẫu đơn vị tính lại trên tập hồ sơ đã chọn lúc tạo. */
  private Optional<byte[]> draftData(TenantId tenant, FormRow f) {
    Optional<UnitProfile> owner = api().find(tenant, f.profileId());
    if (owner.isEmpty()) {
      return Optional.empty();
    }
    UnitProfile p = owner.get();
    List<UUID> sources = packages.sourceProfiles(tenant, f.periodBucket(), f.id());
    if (sources.isEmpty()) {
      return Optional.of(FormDocuments.personal(f.formCode(), p).canonicalJson());
    }
    List<UnitProfile> selected = new ArrayList<>();
    for (UUID id : sources) {
      api().find(tenant, id).ifPresent(selected::add);
    }
    return Optional.of(
        FormDocuments.selection(
                f.formCode(), p.periodId(), commonUnit(selected), p.pack(), selected)
            .canonicalJson());
  }

  private static PreviewInput previewOf(Pack pack, String formCode, byte[] data) {
    return new PreviewInput(
        null,
        templateVersion(pinned(pack, formCode)),
        pack == null ? null : pack.code(),
        pack == null ? null : pack.version(),
        new String(data, java.nio.charset.StandardCharsets.UTF_8));
  }

  /** Đơn vị chung của các hồ sơ; {@code null} khi thuộc nhiều đơn vị. */
  private static UUID commonUnit(List<UnitProfile> profiles) {
    Set<UUID> units = new java.util.HashSet<>();
    profiles.forEach(p -> units.add(p.orgUnitId()));
    return units.size() == 1 ? units.iterator().next() : null;
  }

  // ---------------------------------------------------------------- nội bộ

  /** Biểu mẫu cá nhân của một hồ sơ: thêm khi thiếu, ghi lại băm khi chưa sinh. */
  private void syncPersonal(TenantId tenant, PackageRow pkg, UnitProfile p, UUID actor) {
    List<FormRow> existing = packages.formsOfProfile(tenant, pkg.periodBucket(), pkg.id(), p.id());
    List<NewForm> fresh = new ArrayList<>();
    for (String code : personalForms(p.pack())) {
      FormDocuments.Document doc = FormDocuments.personal(code, p);
      String version = pinned(p.pack(), code);
      Optional<FormRow> same = existing.stream().filter(f -> f.formCode().equals(code)).findFirst();
      if (same.isEmpty()) {
        fresh.add(new NewForm(Ids.newUuidV7(), p.id(), code, version, doc.sha256(), false));
      } else if (UNRENDERED.contains(same.get().status())) {
        packages.refresh(tenant, pkg.periodBucket(), same.get().id(), doc.sha256(), version);
      }
    }
    if (!fresh.isEmpty()) {
      packages.insertForms(tenant, pkg, fresh, actor);
    }
  }

  /**
   * Biểu mẫu của mọi hồ sơ đã quyết định; {@code withUnitForms} thêm biểu mẫu của đơn vị (gắn với
   * hồ sơ người đứng đầu, thiếu thì hồ sơ có mã nhỏ nhất) khi gói sắp sinh.
   */
  private void syncAll(
      TenantId tenant, PackageRow pkg, List<UnitProfile> all, UUID actor, boolean withUnitForms) {
    List<UnitProfile> decided = all.stream().filter(UnitProfile::decided).toList();
    for (UnitProfile p : decided) {
      syncPersonal(tenant, pkg, p, actor);
    }
    if (!withUnitForms || decided.isEmpty()) {
      return;
    }
    UnitProfile owner =
        decided.stream().filter(UnitProfile::head).findFirst().orElse(decided.get(0));
    List<NewForm> unitForms = new ArrayList<>();
    for (String code : unitForms(owner.pack())) {
      FormDocuments.Document doc =
          FormDocuments.unit(code, pkg.periodId(), pkg.orgUnitId(), owner.pack(), all);
      unitForms.add(
          new NewForm(
              Ids.newUuidV7(), owner.id(), code, pinned(owner.pack(), code), doc.sha256(), true));
    }
    if (!unitForms.isEmpty()) {
      packages.insertForms(tenant, pkg, unitForms, actor);
    }
    refreshPending(tenant, pkg, all);
  }

  /** Ghi lại băm của mọi biểu mẫu chờ sinh theo dữ liệu hiện hành. */
  private void refreshPending(TenantId tenant, PackageRow pkg, List<UnitProfile> all) {
    Map<UUID, UnitProfile> byId = new LinkedHashMap<>();
    all.forEach(p -> byId.put(p.id(), p));
    for (FormRow f :
        packages.formsIn(tenant, pkg.periodBucket(), pkg.id(), List.of(FormStatus.CHO_SINH))) {
      UnitProfile owner = byId.get(f.profileId());
      if (owner == null) {
        continue;
      }
      FormDocuments.Document doc =
          isUnitForm(owner.pack(), f.formCode())
              ? FormDocuments.unit(f.formCode(), pkg.periodId(), pkg.orgUnitId(), owner.pack(), all)
              : FormDocuments.personal(f.formCode(), owner);
      if (!java.util.Arrays.equals(doc.sha256(), f.dataSnapshotHash())) {
        packages.refresh(
            tenant, pkg.periodBucket(), f.id(), doc.sha256(), pinned(owner.pack(), f.formCode()));
      }
    }
  }

  /**
   * Gửi lệnh sinh khi không còn giao dịch {@code dossier} nào giữ kết nối: SVC-11 hỏi lại MOD-06
   * ngay trong lời gọi và vùng kết nối của {@code dossier} mặc định chỉ có một kết nối. Gọi trong
   * giao dịch {@code dossier} (consumer EVT-12 chạy trong giao dịch inbox) thì xếp hàng sau khi
   * giao dịch kết thúc thành công để {@link #settle} gửi; ngoài giao dịch đó (API) thì gửi ngay.
   */
  private void sendWhenFree(TenantId tenant, PackageRow pkg, UUID actor) {
    if (connectionBound.getAsBoolean()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      Pending p = new Pending(tenant, pkg, actor);
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              pendingSends.add(p);
            }
          });
    } else {
      send(tenant, pkg, actor);
    }
  }

  /**
   * Gửi lệnh sinh (không có giao dịch nào đang giữ kết nối) rồi ghi mã lệnh cho biểu mẫu chờ;
   * SVC-11 không nhận lệnh thì biểu mẫu chờ sang lỗi và gói về {@code DANG_LAP}.
   */
  private void send(TenantId tenant, PackageRow pkg, UUID actor) {
    String key = "goi-ho-so:" + pkg.id() + ":" + pkg.rowVersion();
    try {
      UUID request = renders.request(tenant, pkg.id(), key);
      inTx(() -> packages.markRequested(tenant, pkg.periodBucket(), pkg.id(), request));
    } catch (RenderUnavailableException e) {
      LOG.warn("SVC-11 chưa nhận lệnh sinh của gói hồ sơ {}: {}", pkg.id(), e.getMessage());
      inTx(() -> packages.markFailed(tenant, pkg.periodBucket(), pkg.id(), null, actor));
      recompute(tenant, pkg.periodBucket(), pkg.id());
    }
  }

  private PackageView view(TenantId tenant, PackageRow r) {
    return new PackageView(
        r,
        packages.progress(tenant, r.periodBucket(), r.id()),
        packages.profileCount(tenant, r.periodBucket(), r.id()));
  }

  private boolean outsideUnit(TenantId tenant, UUID actor, UUID unit) {
    OrgDirectoryApi org = directory.get();
    if (actor == null || org == null) {
      return false;
    }
    LocalDate today =
        LocalDate.now(clock.withZone(vn.viettel.okrkpi.shared.kernel.Clock.BUSINESS_ZONE));
    List<AssignmentView> assignments = org.assignmentsOf(tenant, actor, today);
    return !assignments.isEmpty()
        && assignments.stream().noneMatch(a -> unit.equals(a.orgUnitId()));
  }

  private static void checkScope(PackageRow r, UUID actorUnit) {
    if (actorUnit == null || !actorUnit.equals(r.orgUnitId())) {
      throw outOfScope("Đồng chí không có quyền xem gói hồ sơ này.");
    }
  }

  private static List<String> personalForms(Pack pack) {
    return pack == null
        ? DossierProfilesApi.DEFAULT_PERSONAL_FORMS
        : pack.personalForms().stream().filter(c -> !pack.unitForms().contains(c)).toList();
  }

  private static List<String> unitForms(Pack pack) {
    return pack == null ? DossierProfilesApi.DEFAULT_UNIT_FORMS : pack.unitForms();
  }

  private static boolean isUnitForm(Pack pack, String code) {
    return unitForms(pack).contains(code);
  }

  private static String pinned(Pack pack, String code) {
    Integer v = pack == null ? null : pack.templateVersions().get(code);
    return v == null ? LATEST_TEMPLATE : v.toString();
  }

  static Integer templateVersion(String stored) {
    if (stored == null || !stored.matches("[1-9]\\d{0,8}")) {
      return null;
    }
    return Integer.valueOf(stored);
  }

  /** Khoá phân vùng theo kỳ như {@code ReviewMeetingService.bucket}. */
  static int bucket(PeriodSnapshot p) {
    LocalDate start = p.startDate();
    boolean yearly = ChronoUnit.DAYS.between(start, p.endDate()) > 120;
    return start.getYear() * 10 + (yearly ? 0 : (start.getMonthValue() - 1) / 3 + 1);
  }

  private static PackageRow withStatus(PackageRow r, PackageStatus status, long version) {
    return new PackageRow(
        r.id(),
        r.periodId(),
        r.periodBucket(),
        r.code(),
        r.orgUnitId(),
        r.personId(),
        r.kind(),
        r.versionNo(),
        status,
        r.deadline(),
        r.submittedAt(),
        r.transferredTo(),
        r.signRequestId(),
        r.manifestHash(),
        r.packageSha256(),
        r.updatedBy(),
        version);
  }

  private DossierProfilesApi api() {
    DossierProfilesApi api = profiles.get();
    if (api == null) {
      throw new IllegalStateException("Chưa có cổng hồ sơ đánh giá của MOD-04");
    }
    return api;
  }

  private <T> T inTx(Supplier<T> work) {
    return tx.execute(s -> work.get());
  }

  private static BusinessException outOfScope(String message) {
    return new BusinessException("COMMON-403-OUT-OF-SCOPE", message);
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "DOSSIER-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
