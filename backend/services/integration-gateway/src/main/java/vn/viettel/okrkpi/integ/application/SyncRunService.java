package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.InboundConnector.Page;
import vn.viettel.okrkpi.integ.application.InboundPipeline.Receipt;
import vn.viettel.okrkpi.integ.application.InboundPipeline.Result;
import vn.viettel.okrkpi.integ.application.PullService.Pull;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.Connectors.Active;
import vn.viettel.okrkpi.integ.application.port.DeadLetters.Letter;
import vn.viettel.okrkpi.integ.application.port.SyncRuns;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Due;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Kind;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Run;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Status;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.ErrorText;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lần đồng bộ kéo hết trang (DV-13-06, QT-01 bước 7, SCR-0104): SVC-04 yêu cầu "Đồng bộ ngay" hoặc
 * "Chạy lại thông điệp lỗi" dưới mã lần chạy của nó, lịch đêm {@code connector.schedule_cron} tự mở
 * lần chạy. Worker kéo từng trang từ con trỏ, đưa qua luồng vào chung (lưu nguyên văn, chống trùng,
 * chuyển đổi, thư chết) rồi tiến con trỏ, nên gián đoạn giữa chừng thì lần sau chạy tiếp từ trang
 * đang dở, không trùng, không sót. Translator chuyển lô dưới lần chạy đang chạy ({@link
 * #runningRun}); khi xong, {@link RunListener} đóng lần chạy ở SVC-04 kèm số thư chết.
 */
public class SyncRunService {

  private static final Logger LOG = LoggerFactory.getLogger(SyncRunService.class);

  /** Chủ thể ghi khi worker tự chạy lại thư chết. */
  public static final UUID SYSTEM_ACTOR = UUID.fromString("01960000-0000-7000-8000-0000000013f6");

  /** Giờ của lịch đêm. */
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  /** Bên nhận kết thúc lần chạy (SVC-04). */
  @FunctionalInterface
  public interface RunListener {
    void finished(
        TenantId tenant,
        String connectorCode,
        UUID runId,
        Status status,
        int deadLetters,
        String reason);
  }

  /** Chỉ số của lần chạy: độ trễ con trỏ sau mỗi trang, thời gian và kết quả của lần chạy. */
  public interface RunMetrics {
    void cursorLag(String connectorCode, Duration lag);

    void finished(String connectorCode, Status status, Duration elapsed, int messages);
  }

  /** Tham số worker. */
  public record Settings(
      int maxPages,
      int fetchAttempts,
      Duration fetchBackoff,
      int processAttempts,
      int leaseSeconds) {
    public Settings {
      if (maxPages < 1 || fetchAttempts < 1 || processAttempts < 1 || leaseSeconds < 30) {
        throw new IllegalArgumentException("Tham số lần đồng bộ không hợp lệ");
      }
      fetchBackoff = fetchBackoff == null ? Duration.ofSeconds(1) : fetchBackoff;
    }
  }

  private final Connectors connectors;
  private final SyncRuns runs;
  private final DeadLetterService deadLetters;
  private final PullService pulls;
  private final InboundPipeline pipeline;
  private final Map<String, InboundConnector> sources;
  private final RunListener listener;
  private final RunMetrics metrics;
  private final TransactionOperations tx;
  private final Clock clock;
  private final Settings settings;

  public SyncRunService(
      Connectors connectors,
      SyncRuns runs,
      DeadLetterService deadLetters,
      PullService pulls,
      InboundPipeline pipeline,
      List<InboundConnector> sources,
      RunListener listener,
      RunMetrics metrics,
      TransactionOperations tx,
      Clock clock,
      Settings settings) {
    this.connectors = Objects.requireNonNull(connectors, "connectors");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
    this.pulls = Objects.requireNonNull(pulls, "pulls");
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    Map<String, InboundConnector> m = new LinkedHashMap<>();
    for (InboundConnector s : sources) {
      if (m.put(s.code(), s) != null) {
        throw new IllegalStateException("Trùng bộ kéo theo lần chạy cho mã " + s.code());
      }
    }
    this.sources = Map.copyOf(m);
    this.listener = listener == null ? (t, c, r, s, d, e) -> {} : listener;
    this.metrics =
        metrics == null
            ? new RunMetrics() {
              @Override
              public void cursorLag(String connectorCode, Duration lag) {}

              @Override
              public void finished(
                  String connectorCode, Status status, Duration elapsed, int messages) {}
            }
            : metrics;
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  /** Mở lần chạy {@code NGAY} hoặc {@code LICH}; gửi lại cùng mã trả lần chạy đang có. */
  public Run request(TenantId tenant, UUID runId, String code, Kind kind) {
    Objects.requireNonNull(runId, "runId");
    if (kind == Kind.CHAY_LAI) {
      throw new IllegalArgumentException("Chạy lại thư chết dùng retry");
    }
    return open(tenant, runId, code, kind, null);
  }

  /** Mở lần chạy lại thư chết của lần chạy {@code originalRunId} dưới mã {@code retryRunId}. */
  public Run retry(TenantId tenant, UUID originalRunId, UUID retryRunId, String code) {
    Objects.requireNonNull(originalRunId, "originalRunId");
    Objects.requireNonNull(retryRunId, "retryRunId");
    Run original =
        status(tenant, originalRunId)
            .orElseThrow(() -> new NoSuchElementException("Không có lần đồng bộ này"));
    if (original.status().open()) {
      throw new SyncRunRejectedException(
          SyncRunRejectedException.Reason.RUN_NOT_FINISHED, "Lần đồng bộ chưa kết thúc");
    }
    return open(tenant, retryRunId, code, Kind.CHAY_LAI, originalRunId);
  }

  public Optional<Run> status(TenantId tenant, UUID runId) {
    return TenantScope.call(tenant, () -> tx.execute(s -> runs.find(tenant, runId)));
  }

  /**
   * Lần chạy đang chạy của kết nối, cho translator gọi trong giao dịch và ngữ cảnh tenant của luồng
   * vào; rỗng khi thông điệp đến ngoài lần chạy (route kéo theo chu kỳ).
   */
  public static Optional<UUID> runningRun(SyncRuns runs, TenantId tenant, UUID connectorId) {
    return runs.open(tenant, connectorId).filter(r -> r.status() == Status.DANG_CHAY).map(Run::id);
  }

  /** Mở lần chạy theo lịch đêm cho kết nối đã đến giờ; trả số lần chạy đã mở. */
  public int scheduleDue() {
    Instant now = clock.instant();
    int opened = 0;
    for (Active a : connectors.enabled()) {
      if (!sources.containsKey(a.code())) {
        continue;
      }
      TenantId tenant = TenantId.of(a.tenantId());
      try {
        if (scheduleOne(tenant, a, now)) {
          opened++;
        }
      } catch (RuntimeException e) {
        LOG.warn("Không mở được lần đồng bộ theo lịch cho {}: {}", a.code(), ErrorText.code(e));
      }
    }
    return opened;
  }

  private boolean scheduleOne(TenantId tenant, Active a, Instant now) {
    Optional<Connector> c =
        TenantScope.call(tenant, () -> tx.execute(s -> connectors.findById(tenant, a.id())));
    if (c.isEmpty() || c.get().scheduleCron() == null || c.get().scheduleCron().isBlank()) {
      return false;
    }
    CronExpression cron;
    try {
      cron = CronExpression.parse(c.get().scheduleCron());
    } catch (IllegalArgumentException e) {
      LOG.warn("Lịch đồng bộ của {} không hợp lệ", a.code());
      return false;
    }
    // Chưa từng chạy theo lịch thì chỉ bắt lần đến giờ trong một ngày qua.
    Instant base =
        TenantScope.call(
                tenant, () -> tx.execute(s -> runs.lastRequested(tenant, a.id(), Kind.LICH)))
            .orElse(now.minus(Duration.ofDays(1)));
    ZonedDateTime next = cron.next(base.atZone(ZONE));
    if (next == null || next.toInstant().isAfter(now)) {
      return false;
    }
    try {
      open(tenant, Ids.newUuidV7(), a.code(), Kind.LICH, null);
      return true;
    } catch (SyncRunRejectedException e) {
      return false;
    }
  }

  /** Nhận và chạy các lần chạy đang chờ (hoặc mất nhịp); trả số lần đã chạy. */
  public int runDue(int limit) {
    List<Due> due = runs.due(limit, settings.leaseSeconds());
    int done = 0;
    for (Due d : due) {
      try {
        if (execute(TenantId.of(d.tenantId()), d.id())) {
          done++;
        }
      } catch (RuntimeException e) {
        LOG.warn("Lần đồng bộ {} lỗi ngoài dự kiến: {}", d.id(), ErrorText.code(e));
      }
    }
    return done;
  }

  /** Chạy một lần chạy nếu nhận được; trả false khi bản sao khác đã nhận. */
  boolean execute(TenantId tenant, UUID runId) {
    Instant claimedAt = clock.instant();
    boolean claimed =
        Boolean.TRUE.equals(
            TenantScope.call(
                tenant,
                () ->
                    tx.execute(
                        s -> runs.claim(tenant, runId, settings.leaseSeconds(), claimedAt))));
    if (!claimed) {
      return false;
    }
    Run run = status(tenant, runId).orElseThrow();
    Connector connector =
        TenantScope.call(
                tenant, () -> tx.execute(s -> connectors.findById(tenant, run.connectorId())))
            .orElseThrow(() -> new NoSuchElementException("Không có kết nối của lần chạy"));
    Counter counter = new Counter();
    Status status = Status.XONG;
    String error = null;
    int dead;
    try {
      if (run.kind() == Kind.CHAY_LAI) {
        dead = replay(tenant, connector, run, counter);
      } else {
        InboundConnector source = sources.get(connector.code());
        if (source == null) {
          throw new IllegalStateException("Bản triển khai không có bộ kéo cho " + connector.code());
        }
        sweep(tenant, connector, source, runId, counter);
        dead = countOpen(tenant, connector.id(), claimedAt, clock.instant());
      }
    } catch (Exception e) {
      status = Status.LOI;
      error = ErrorText.code(e);
      dead = countOpen(tenant, connector.id(), claimedAt, clock.instant());
      LOG.warn("Lần đồng bộ {} của {} lỗi: {}", runId, connector.code(), error);
    }
    Status s = status;
    String err = error;
    int d = dead;
    TenantScope.call(
        tenant,
        () -> {
          tx.executeWithoutResult(
              x -> {
                runs.progress(
                    tenant,
                    runId,
                    counter.pages,
                    counter.messages,
                    counter.duplicates,
                    clock.instant());
                runs.finish(tenant, runId, s, d, err, clock.instant());
              });
          return null;
        });
    metrics.finished(
        connector.code(), s, Duration.between(claimedAt, clock.instant()), counter.messages);
    try {
      listener.finished(
          tenant,
          connector.code(),
          runId,
          s,
          d,
          s == Status.LOI ? "Không đồng bộ hết được dữ liệu nguồn (" + err + ")" : null);
    } catch (RuntimeException e) {
      LOG.warn("Không báo được kết thúc lần đồng bộ {}: {}", runId, ErrorText.code(e));
    }
    return true;
  }

  /** Số đếm của lần chạy. */
  static final class Counter {
    int pages;
    int messages;
    int duplicates;
  }

  private void sweep(
      TenantId tenant, Connector connector, InboundConnector source, UUID runId, Counter counter)
      throws IOException, InterruptedException {
    Active target = new Active(tenant.value(), connector.id(), connector.code());
    for (int i = 0; i < settings.maxPages(); i++) {
      Pull pull = pulls.prepare(source, target);
      Page page = fetch(source, pull);
      counter.pages++;
      for (var message : page.items()) {
        Receipt r = receive(tenant, connector, message);
        counter.messages++;
        if (r != null && r.result() == Result.DUPLICATE) {
          counter.duplicates++;
        }
      }
      pulls.advance(source, pull, page);
      if (page.sourceTime() != null) {
        Duration lag = Duration.between(page.sourceTime(), clock.instant());
        metrics.cursorLag(connector.code(), lag.isNegative() ? Duration.ZERO : lag);
      }
      int pages = counter.pages;
      int messages = counter.messages;
      int duplicates = counter.duplicates;
      TenantScope.call(
          tenant,
          () -> {
            tx.executeWithoutResult(
                x -> runs.progress(tenant, runId, pages, messages, duplicates, clock.instant()));
            return null;
          });
      if (page.items().isEmpty()
          || page.nextCursor() == null
          || page.nextCursor().equals(pull.cursor())) {
        return;
      }
    }
    LOG.warn("Lần đồng bộ {} dừng ở trần {} trang, lần sau chạy tiếp", runId, settings.maxPages());
  }

  private Page fetch(InboundConnector source, Pull pull) throws IOException, InterruptedException {
    IOException last = null;
    for (int attempt = 1; attempt <= settings.fetchAttempts(); attempt++) {
      try {
        return pulls.fetch(source, pull);
      } catch (IOException e) {
        last = e;
        if (attempt < settings.fetchAttempts()) {
          Thread.sleep(settings.fetchBackoff().multipliedBy(1L << Math.min(attempt - 1, 6)));
        }
      }
    }
    throw last;
  }

  /** Lỗi xử lý một thông điệp thì thử lại rồi chuyển thư chết; các thông điệp khác vẫn chạy. */
  private Receipt receive(
      TenantId tenant, Connector connector, vn.viettel.okrkpi.integ.domain.InboundMessage message) {
    InboundProcessingException failure;
    try {
      return pipeline.receive(tenant, connector.code(), message);
    } catch (InboundProcessingException e) {
      failure = e;
    }
    for (int attempt = 2; attempt <= settings.processAttempts(); attempt++) {
      try {
        return pipeline.reprocess(tenant, failure.inboxId());
      } catch (InboundProcessingException e) {
        failure = e;
      }
    }
    deadLetters.record(
        tenant,
        connector.id(),
        DeadLetterService.INBOUND,
        failure.inboxId(),
        failure.getCause() == null ? failure : failure.getCause());
    return null;
  }

  /** Chạy lại thư chết mở của lần chạy gốc; trả số thư còn mở sau khi chạy lại. */
  private int replay(TenantId tenant, Connector connector, Run retryRun, Counter counter) {
    Run original = status(tenant, retryRun.retryOf()).orElseThrow();
    Instant from = original.startedAt() == null ? original.requestedAt() : original.startedAt();
    Instant to = original.finishedAt() == null ? clock.instant() : original.finishedAt();
    int stillOpen = 0;
    for (Letter l : openLetters(tenant, connector.id(), from, to)) {
      counter.messages++;
      try {
        deadLetters.replay(tenant, l.id(), SYSTEM_ACTOR);
      } catch (RuntimeException e) {
        stillOpen++;
      }
    }
    return stillOpen;
  }

  private int countOpen(TenantId tenant, UUID connectorId, Instant from, Instant to) {
    return openLetters(tenant, connectorId, from, to).size();
  }

  private List<Letter> openLetters(TenantId tenant, UUID connectorId, Instant from, Instant to) {
    List<Letter> out = new ArrayList<>();
    Instant after = null;
    UUID afterId = null;
    while (true) {
      List<Letter> page = deadLetters.list(tenant, connectorId, true, after, afterId, 200);
      for (Letter l : page) {
        if (DeadLetterService.INBOUND.equals(l.direction())
            && !l.createdAt().isBefore(from)
            && !l.createdAt().isAfter(to)) {
          out.add(l);
        }
      }
      if (page.size() < 200) {
        return out;
      }
      Letter last = page.get(page.size() - 1);
      after = last.createdAt();
      afterId = last.id();
    }
  }

  private Run open(TenantId tenant, UUID runId, String code, Kind kind, UUID retryOf) {
    if (code == null || !sources.containsKey(code)) {
      throw new SyncRunRejectedException(
          SyncRunRejectedException.Reason.CONNECTOR_NOT_RUNNABLE,
          "Kết nối không đồng bộ theo lần chạy");
    }
    return TenantScope.call(
        tenant,
        () ->
            tx.execute(
                s -> {
                  Connector c =
                      connectors
                          .findByCode(tenant, code)
                          .filter(x -> x.enabled() && x.direction().inbound())
                          .orElseThrow(
                              () ->
                                  new SyncRunRejectedException(
                                      SyncRunRejectedException.Reason.CONNECTOR_NOT_CONFIGURED,
                                      "Tenant chưa cấu hình kết nối"));
                  Optional<Run> existing = runs.find(tenant, runId);
                  if (existing.isPresent()) {
                    if (!existing.get().connectorId().equals(c.id())) {
                      throw new IllegalArgumentException("Mã lần chạy đã dùng cho kết nối khác");
                    }
                    return existing.get();
                  }
                  if (!runs.insert(tenant, runId, c.id(), kind, retryOf, clock.instant())) {
                    throw new SyncRunRejectedException(
                        SyncRunRejectedException.Reason.RUN_IN_PROGRESS,
                        "Kết nối đang có lần đồng bộ chưa kết thúc");
                  }
                  return runs.find(tenant, runId).orElseThrow();
                }));
  }
}
