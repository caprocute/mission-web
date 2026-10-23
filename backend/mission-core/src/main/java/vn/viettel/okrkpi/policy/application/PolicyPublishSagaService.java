package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.UiPackCompatibilityApi;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackDistribution;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishLauncher;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishSagaRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishSagaRepository.BindingRow;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishedEvents;
import vn.viettel.okrkpi.policy.application.port.PolicyWorkflowActivation;
import vn.viettel.okrkpi.policy.domain.PolicyBinding;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest.Scope;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga.Bound;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga.Context;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga.Status;
import vn.viettel.okrkpi.policy.domain.PolicyTransitionException;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Saga phát hành Policy Pack (CORE-05-10, QT-15 ngày 4–5, TL-01 "Phát hành, ký số và vòng đời"):
 * kiểm tương thích UI Pack hiện hành (X-G1-12), ghi {@code policy_binding} cho kỳ và phạm vi đã
 * gửi, đồng bộ xuống cell và chờ scoring-service nạp, kích hoạt {@code wf_binding}, rồi chuyển
 * {@code DA_PHAT_HANH} và phát EVT-13 qua outbox. Bước lỗi hết lượt thử thì bù trừ ngược: gỡ hoặc
 * trả lại gắn kỳ, gỡ kích hoạt luồng duyệt; phiên bản giữ {@code CHO_PHE_DUYET} có chữ ký để phát
 * hành lại không cần ký lại. Saga chạy trong tiến trình MOD-05, trạng thái bền ở {@code
 * policy.publish_saga} (suy luận, wiki trang 14).
 */
public class PolicyPublishSagaService implements PolicyPublishLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyPublishSagaService.class);

  static final int MAX_ATTEMPTS = 3;
  static final Duration STALE = Duration.ofMinutes(15);
  private static final int REASON_MIN = 10;
  private static final int REASON_MAX = 2000;

  /** Bước không thể làm tiếp: bù trừ ngay, không thử lại. */
  static final class StepAborted extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StepAborted(String message) {
      super(message);
    }
  }

  private final PolicyPackRepository packs;
  private final PolicyPublishRepository publish;
  private final PolicyPublishSagaRepository sagas;
  private final UiPackCompatibilityApi uiPacks;
  private final PeriodApi periods;
  private final PolicyPackDistribution distribution;
  private final PolicyWorkflowActivation workflow;
  private final PolicyPublishedEvents events;
  private final PolicyAudit audit;
  private final PolicyResolutionService resolution;
  private final ObjectMapper json;
  private final Clock clock;
  private final Executor executor;
  private final TransactionOperations writes;
  private final Duration retryDelay;

  @SuppressWarnings("java:S107")
  public PolicyPublishSagaService(
      PolicyPackRepository packs,
      PolicyPublishRepository publish,
      PolicyPublishSagaRepository sagas,
      UiPackCompatibilityApi uiPacks,
      PeriodApi periods,
      PolicyPackDistribution distribution,
      PolicyWorkflowActivation workflow,
      PolicyPublishedEvents events,
      PolicyAudit audit,
      PolicyResolutionService resolution,
      ObjectMapper json,
      Clock clock,
      Executor executor,
      TransactionOperations writes,
      Duration retryDelay) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.publish = Objects.requireNonNull(publish, "publish");
    this.sagas = Objects.requireNonNull(sagas, "sagas");
    this.uiPacks = Objects.requireNonNull(uiPacks, "uiPacks");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.distribution = Objects.requireNonNull(distribution, "distribution");
    this.workflow = Objects.requireNonNull(workflow, "workflow");
    this.events = Objects.requireNonNull(events, "events");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.resolution = Objects.requireNonNull(resolution, "resolution");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.retryDelay = retryDelay == null ? Duration.ZERO : retryDelay;
  }

  /** Khởi chạy saga trong giao dịch của bên gọi; các bước chạy sau khi giao dịch xác nhận. */
  @Override
  public String launch(
      TenantId tenant, UUID actor, PolicyPackVersion version, PolicyPublishRequest request) {
    PolicyPublishSaga saga =
        PolicyPublishSaga.start(
            Ids.newUuidV7(), version.id(), request.id(), actor, clock.instant());
    sagas.insert(tenant, saga);
    afterCommit(
        () ->
            executor.execute(() -> TenantContext.run(tenant, () -> runSafely(tenant, saga.id()))));
    return saga.id().toString();
  }

  /**
   * Phát hành lại phiên bản đã ký nhưng saga đã bù trừ (TL-02b QT-15 "Ngoại lệ và xử lý"): không ký
   * lại khi {@code content_hash} tính lại vẫn khớp; lần chạy treo quá hạn được bù trừ trước.
   */
  @Transactional(PolicyPackService.TX)
  public String republish(TenantId tenant, UUID actor, UUID packId, String version, long expected) {
    PolicyPackVersion current =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    if (current.rowVersion() != expected) {
      throw new VersionConflictException("POLICY", expected, current.rowVersion());
    }
    if (current.status() == PolicyVersionStatus.DA_PHAT_HANH) {
      throw new BusinessException(
          "POLICY-409-VERSION-CONFLICT", "Phiên bản " + version + " đã được phát hành");
    }
    if (current.status() != PolicyVersionStatus.CHO_PHE_DUYET
        || !current.signed()
        || current.contentHash() == null
        || !current.contentHash().equals(contentHash(tenant, current))) {
      throw new BusinessException(
          "POLICY-409-NOT-SIGNED", "Phiên bản chưa có chữ ký hợp lệ trên nội dung hiện tại");
    }
    PolicyPublishRequest request =
        publish
            .findApproved(tenant, current.id())
            .orElseThrow(
                () ->
                    new BusinessException(
                        "POLICY-409-NOT-SIGNED", "Phiên bản chưa có yêu cầu phát hành đã duyệt"));
    Optional<PolicyPublishSaga> latest = sagas.findLatest(tenant, current.id());
    if (latest.isPresent() && latest.get().status().running()) {
      PolicyPublishSaga running = latest.get();
      if (!running.updatedAt().plus(STALE).isBefore(clock.instant())) {
        throw new BusinessException(
            "POLICY-409-VERSION-CONFLICT", "Phiên bản đang được phát hành, vui lòng chờ");
      }
      compensate(tenant, running, "Lần chạy trước bị gián đoạn");
    }
    audit.changed(
        tenant,
        actor,
        "PHAT_HANH_LAI_POLICY_PACK",
        PolicyPackService.OBJECT_TYPE,
        packKey(tenant, current),
        current.id(),
        current.rowVersion());
    return launch(tenant, actor, current, request);
  }

  /**
   * Thu hồi phiên bản đã phát hành trước kỳ hiệu lực (TL-02b QT-15 "Ngoại lệ và xử lý"): kỳ đã khoá
   * bị chặn, kỳ đã bắt đầu thì phải phát hành phiên bản sửa; gắn kỳ trả về phiên bản trước, phiên
   * bản chuyển {@code NGUNG} và EVT-13 của phiên bản tiếp tục áp dụng được phát lại.
   */
  @Transactional(PolicyPackService.TX)
  public PolicyPackVersion revoke(
      TenantId tenant, UUID actor, UUID packId, String version, long expected, String reason) {
    PolicyPackVersion current =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    if (current.rowVersion() != expected) {
      throw new VersionConflictException("POLICY", expected, current.rowVersion());
    }
    String why = reason == null ? "" : reason.strip();
    if (why.length() < REASON_MIN || why.length() > REASON_MAX) {
      throw invalid("reason", "Lý do thu hồi cần từ 10 đến 2000 ký tự");
    }
    if (current.status() != PolicyVersionStatus.DA_PHAT_HANH) {
      throw new BusinessException(
          "POLICY-409-VERSION-CONFLICT", "Chỉ thu hồi được phiên bản đã phát hành");
    }
    List<PolicyBinding> bound = sagas.bindings(tenant, current.id());
    if (bound.stream().anyMatch(PolicyBinding::frozen)) {
      throw locked();
    }
    Optional<PolicyPublishRequest> request = publish.findApproved(tenant, current.id());
    if (request.isPresent()) {
      PeriodSnapshot period =
          periods
              .findById(tenant, PeriodId.of(request.get().periodId()))
              .orElseThrow(() -> new IllegalStateException("Không còn kỳ hiệu lực"));
      if (period.isLocked()) {
        throw locked();
      }
      if (!period.startDate().isAfter(clock.businessDate())) {
        throw new BusinessException(
            "POLICY-409-PERIOD-STARTED",
            "Kỳ hiệu lực đã bắt đầu, vui lòng phát hành phiên bản sửa thay cho thu hồi");
      }
    }
    Set<UUID> restored = new HashSet<>();
    sagas
        .findLatest(tenant, current.id())
        .filter(s -> s.status() == Status.HOAN_TAT)
        .ifPresent(s -> restored.addAll(undoBindings(tenant, actor, s.context())));
    for (PolicyBinding b : bound) {
      if (!restored.contains(b.id()) && !sagas.unbind(tenant, b.id())) {
        throw locked();
      }
    }
    workflow.deactivate(tenant, current);
    PolicyPackVersion revoked =
        transition(() -> current.moveTo(PolicyVersionStatus.NGUNG, actor, clock.instant()));
    if (!packs.storeStatus(tenant, revoked, expected)) {
      throw new VersionConflictException("POLICY", expected, null);
    }
    audit.changed(
        tenant,
        actor,
        "THU_HOI_POLICY_PACK",
        PolicyPackService.OBJECT_TYPE,
        packKey(tenant, revoked),
        revoked.id(),
        revoked.rowVersion());
    packs.listVersions(tenant, packId, PolicyVersionStatus.DA_PHAT_HANH, null, 200).stream()
        .filter(v -> !v.id().equals(revoked.id()))
        .findFirst()
        .ifPresent(v -> events.published(tenant, v));
    afterCommit(() -> resolution.evict(tenant));
    return revoked;
  }

  private void runSafely(TenantId tenant, UUID sagaId) {
    try {
      run(tenant, sagaId);
    } catch (RuntimeException e) {
      LOG.error("Saga phát hành {} dừng giữa chừng", sagaId, e);
    } finally {
      // Gắn kỳ và trạng thái phiên bản vừa đổi: bỏ kết quả phân giải đã đệm của tenant.
      resolution.evict(tenant);
    }
  }

  /** Chạy các bước còn lại của saga tới khi xong, bù trừ xong hoặc cần người xử lý. */
  void run(TenantId tenant, UUID sagaId) {
    while (true) {
      PolicyPublishSaga saga = writes.execute(tx -> sagas.find(tenant, sagaId).orElse(null));
      if (saga == null || saga.status() != Status.DANG_CHAY) {
        return;
      }
      try {
        writes.executeWithoutResult(tx -> execute(tenant, sagaId));
      } catch (StepAborted e) {
        abort(tenant, sagaId, e.getMessage());
      } catch (RuntimeException e) {
        LOG.warn("Bước {} của saga phát hành {} lỗi", saga.step(), sagaId, e);
        if (saga.attempts() + 1 >= MAX_ATTEMPTS) {
          abort(tenant, sagaId, message(e));
        } else {
          writes.executeWithoutResult(
              tx -> store(tenant, saga.failedAttempt(message(e), clock.instant()), saga));
          pause();
        }
      }
    }
  }

  private void execute(TenantId tenant, UUID sagaId) {
    PolicyPublishSaga saga = sagas.find(tenant, sagaId).orElseThrow();
    if (saga.status() != Status.DANG_CHAY) {
      return;
    }
    PolicyPackVersion version =
        packs
            .findVersionById(tenant, saga.versionId())
            .orElseThrow(() -> new StepAborted("Không còn phiên bản cần phát hành"));
    PolicyPack pack = packs.findPack(tenant, version.packId()).orElseThrow();
    Context next =
        switch (saga.step()) {
          case KIEM_TUONG_THICH_UI -> {
            requireSigned(version);
            if (!uiPacks.isCompatible(tenant, pack.code() + "@" + version.version())) {
              throw new StepAborted(
                  "Giao diện hiện hành của đơn vị chưa tương thích phiên bản "
                      + version.version()
                      + "; cần phát hành giao diện tương thích trước");
            }
            yield saga.context();
          }
          case GAN_KY -> bind(tenant, saga, version);
          case DONG_BO_CELL -> {
            distribution.distribute(tenant, pack, version);
            yield saga.context();
          }
          case KICH_HOAT_LUONG_DUYET -> {
            workflow.activate(tenant, version);
            yield saga.context().withWorkflowActivated(true);
          }
          case PHAT_HANH -> {
            publishVersion(tenant, saga, pack, version);
            yield saga.context();
          }
        };
    store(tenant, saga.advance(next, clock.instant()), saga);
  }

  /** Bước 2: gắn phiên bản cho từng phạm vi của kỳ hiệu lực, nhớ phiên bản cũ để bù trừ. */
  private Context bind(TenantId tenant, PolicyPublishSaga saga, PolicyPackVersion version) {
    PolicyPublishRequest request =
        publish
            .findById(tenant, saga.requestId())
            .orElseThrow(() -> new StepAborted("Không còn yêu cầu phát hành"));
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(request.periodId()))
            .orElseThrow(() -> new StepAborted("Không còn kỳ hiệu lực"));
    if (period.isLocked()) {
      throw new StepAborted("Kỳ hiệu lực đã khoá, không gắn được phiên bản mới");
    }
    Context context = saga.context();
    for (Scope scope : request.scopes()) {
      Optional<BindingRow> existing = sagas.findBinding(tenant, request.periodId(), scope);
      if (existing.isEmpty()) {
        UUID id = Ids.newUuidV7();
        sagas.insertBinding(tenant, id, version.id(), request.periodId(), scope, saga.startedBy());
        context = context.withBinding(new Bound(id, null));
      } else if (existing.get().frozen()) {
        throw new StepAborted("Kỳ hiệu lực đã khoá, không gắn được phiên bản mới");
      } else if (!existing.get().versionId().equals(version.id())) {
        if (!sagas.rebind(tenant, existing.get().id(), version.id(), saga.startedBy())) {
          throw new StepAborted("Kỳ hiệu lực đã khoá, không gắn được phiên bản mới");
        }
        context = context.withBinding(new Bound(existing.get().id(), existing.get().versionId()));
      }
    }
    return context;
  }

  /**
   * Bước 5 (điểm không quay lại): {@code DA_PHAT_HANH}, {@code published_at}, phiên bản cũ của cùng
   * gói sang {@code NGUNG} khi phiên bản mới đã tới ngày hiệu lực, EVT-13 qua outbox.
   */
  private void publishVersion(
      TenantId tenant, PolicyPublishSaga saga, PolicyPack pack, PolicyPackVersion version) {
    Instant now = clock.instant();
    PolicyPackVersion published =
        abortOnTransition(
            () -> version.moveTo(PolicyVersionStatus.DA_PHAT_HANH, saga.startedBy(), now));
    if (!packs.storeStatus(tenant, published, version.rowVersion())) {
      throw new IllegalStateException("Phiên bản vừa bị thay đổi, thử lại");
    }
    LocalDate effective = published.effectiveFrom();
    if (effective != null && !effective.isAfter(clock.businessDate())) {
      for (PolicyPackVersion old :
          packs.listVersions(tenant, pack.id(), PolicyVersionStatus.DA_PHAT_HANH, null, 200)) {
        if (!old.id().equals(published.id())) {
          PolicyPackVersion retired = old.moveTo(PolicyVersionStatus.NGUNG, saga.startedBy(), now);
          if (!packs.storeStatus(tenant, retired, old.rowVersion())) {
            throw new IllegalStateException("Phiên bản cũ vừa bị thay đổi, thử lại");
          }
        }
      }
    }
    events.published(tenant, published);
    audit.changed(
        tenant,
        saga.startedBy(),
        "PHAT_HANH_POLICY_PACK",
        PolicyPackService.OBJECT_TYPE,
        pack.code() + "@" + published.version(),
        published.id(),
        published.rowVersion());
  }

  private void abort(TenantId tenant, UUID sagaId, String error) {
    PolicyPublishSaga saga = writes.execute(tx -> sagas.find(tenant, sagaId).orElseThrow());
    compensate(tenant, saga, error);
  }

  /** Bù trừ theo thứ tự ngược; không xoá dữ liệu khác, phiên bản giữ nguyên trạng thái. */
  private void compensate(TenantId tenant, PolicyPublishSaga saga, String error) {
    PolicyPublishSaga compensating = saga.compensating(error, clock.instant());
    writes.executeWithoutResult(tx -> store(tenant, compensating, saga));
    try {
      writes.executeWithoutResult(
          tx -> {
            if (compensating.context().workflowActivated()) {
              packs
                  .findVersionById(tenant, compensating.versionId())
                  .ifPresent(v -> workflow.deactivate(tenant, v));
            }
            undoBindings(tenant, compensating.startedBy(), compensating.context());
            store(tenant, compensating.compensated(Context.empty(), clock.instant()), compensating);
          });
    } catch (RuntimeException e) {
      LOG.error("Không bù trừ được saga phát hành {}", saga.id(), e);
      writes.executeWithoutResult(
          tx ->
              store(
                  tenant,
                  compensating.stuck(error + "; bù trừ lỗi: " + message(e), clock.instant()),
                  compensating));
    }
  }

  /** Trả các gắn saga đã ghi về như cũ; trả tập mã gắn đã xử lý. */
  private Set<UUID> undoBindings(TenantId tenant, UUID actor, Context context) {
    List<Bound> reversed = new ArrayList<>(context.bindings());
    java.util.Collections.reverse(reversed);
    Set<UUID> done = new HashSet<>();
    for (Bound b : reversed) {
      boolean ok =
          b.previousVersionId() == null
              ? sagas.unbind(tenant, b.bindingId())
              : sagas.rebind(tenant, b.bindingId(), b.previousVersionId(), actor);
      if (!ok) {
        throw locked();
      }
      done.add(b.bindingId());
    }
    return done;
  }

  private void store(TenantId tenant, PolicyPublishSaga next, PolicyPublishSaga previous) {
    if (!sagas.store(tenant, next, previous.rowVersion())) {
      throw new IllegalStateException("Saga phát hành " + previous.id() + " vừa bị thay đổi");
    }
  }

  private static void requireSigned(PolicyPackVersion version) {
    if (version.status() != PolicyVersionStatus.CHO_PHE_DUYET || !version.signed()) {
      throw new StepAborted("Phiên bản không ở trạng thái đã ký chờ phát hành");
    }
  }

  private String contentHash(TenantId tenant, PolicyPackVersion v) {
    return PolicyContentHash.of(
        json, v.manifestJson(), packs.rules(tenant, v.id()), packs.decisionTables(tenant, v.id()));
  }

  private String packKey(TenantId tenant, PolicyPackVersion v) {
    return packs.findPack(tenant, v.packId()).map(PolicyPack::code).orElse("?") + "@" + v.version();
  }

  private void pause() {
    if (retryDelay.isZero()) {
      return;
    }
    try {
      Thread.sleep(retryDelay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static PolicyPackVersion transition(java.util.function.Supplier<PolicyPackVersion> move) {
    try {
      return move.get();
    } catch (PolicyTransitionException e) {
      throw new BusinessException(e.code(), e.getMessage());
    }
  }

  private static PolicyPackVersion abortOnTransition(
      java.util.function.Supplier<PolicyPackVersion> move) {
    try {
      return move.get();
    } catch (PolicyTransitionException e) {
      throw new StepAborted(e.getMessage());
    }
  }

  private static String message(RuntimeException e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private static BusinessException locked() {
    return new BusinessException(
        "POLICY-409-PERIOD-LOCKED", "Kỳ hiệu lực đã khoá, không đổi được phiên bản đang áp dụng");
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static void afterCommit(Runnable work) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              work.run();
            }
          });
    } else {
      work.run();
    }
  }
}
