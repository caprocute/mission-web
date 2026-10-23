package vn.viettel.okrkpi.org.application.sync;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.Cursors;
import vn.viettel.okrkpi.org.application.IdempotencyRequest;
import vn.viettel.okrkpi.org.application.Page;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.application.port.IntegrationGatewayPort;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.application.port.SyncRunStore;
import vn.viettel.okrkpi.org.application.port.SyncRunStore.Run;
import vn.viettel.okrkpi.org.application.port.SyncScheduleStore;
import vn.viettel.okrkpi.org.application.port.SyncScheduleStore.Schedule;
import vn.viettel.okrkpi.org.application.port.SyncScheduleStore.ScheduleSet;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.VersionConflictException;

/**
 * DV-04-07 · SCR-0104 · Lần đồng bộ ("Đồng bộ ngay", 202), danh sách lần chạy, chạy lại thông điệp lỗi và lịch đồng
 * bộ. Lệnh tới nguồn ngoài luôn đi qua integration-gateway (BR-01-11); lỗi gửi lệnh được lưu thành lần chạy {@code LOI}
 * kèm lý do cho MSG-01-14.
 */
@Service
public class SyncRunService {

  private static final Logger LOG = LoggerFactory.getLogger(SyncRunService.class);

  /** Nguồn đồng bộ ({@code SyncSource} của hợp đồng). */
  public enum Connector { CBCCVC, DANG_VIEN, SCIM, HRM }

  /** Cron năm trường, mỗi trường là số, dấu sao, khoảng, danh sách hoặc bước. */
  static final Pattern CRON = Pattern.compile("^\\s*([0-9*/,\\-]+\\s+){4}[0-9*/,\\-]+\\s*$");

  /** Kết quả "Đồng bộ ngay": các lần chạy đã tạo. */
  public record Started(List<UUID> runIds) {
  }

  /**
   * Tác vụ nền {@code Job} của một lệnh "Đồng bộ ngay" (hoặc chạy lại), mã theo lần chạy đầu tiên: còn lần chạy
   * {@code DANG_CHAY} thì đang chạy; xong mà có nguồn thành công thì hoàn tất, mọi nguồn lỗi thì thất bại.
   */
  public record SyncJobView(UUID id, String status, int progress, String message, OffsetDateTime updatedAt) {
  }

  private final SyncRunStore runs;
  private final SyncScheduleStore schedules;
  private final IntegrationGatewayPort gateway;
  private final IdempotencyPort idempotency;
  private final PolicyPort policy;
  private final SyncSupport support;

  public SyncRunService(SyncRunStore runs, SyncScheduleStore schedules, IntegrationGatewayPort gateway,
      IdempotencyPort idempotency, PolicyPort policy, SyncSupport support) {
    this.runs = runs;
    this.schedules = schedules;
    this.gateway = gateway;
    this.idempotency = idempotency;
    this.policy = policy;
    this.support = support;
  }

  /** Tạo lần chạy cho một nguồn hoặc mọi nguồn; 409 {@code ORG-409-SYNC-RUNNING} nếu nguồn đang chạy. */
  @Transactional
  public Started start(Actor actor, IdempotencyRequest idem, String connector) {
    policy.authorize(actor, "startOrgSyncRun");
    Optional<Started> replay = idempotency.begin(actor, idem, Started.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    Set<Connector> targets = connector == null ? EnumSet.allOf(Connector.class)
        : EnumSet.of(Connector.valueOf(connector));
    List<UUID> ids = new ArrayList<>();
    for (Connector c : targets) {
      UUID id = UuidV7.next(support.clock());
      runs.start(id, c.name(), actor);
      try {
        gateway.requestRun(actor.tenantId(), id, c.name());
      } catch (IntegrationUnavailableException e) {
        LOG.warn("Không gửi được lệnh đồng bộ {} tới integration-gateway", c);
        runs.finish(id, "LOI", 0, "Không gửi được yêu cầu đồng bộ: " + e.getMessage(), actor);
      }
      ids.add(id);
    }
    Started result = new Started(ids);
    idempotency.complete(actor, idem, 202, result, ids.getFirst().toString());
    return result;
  }

  /** {@code GET /api/v1/jobs/org-{id}} cho lệnh đồng bộ; rỗng khi {@code id} không phải lần đồng bộ của tenant. */
  @Transactional(readOnly = true)
  public Optional<SyncJobView> job(Actor actor, UUID id) {
    List<Run> group = runs.startedTogether(id);
    if (group.isEmpty()) {
      return Optional.empty();
    }
    policy.authorize(actor, "listOrgSyncRuns");
    int total = group.size();
    int done = 0;
    int ok = 0;
    int records = 0;
    int changes = 0;
    OffsetDateTime updated = null;
    List<String> reasons = new ArrayList<>();
    for (Run r : group) {
      OffsetDateTime at = r.finishedAt() == null ? r.startedAt() : r.finishedAt();
      updated = updated == null || at.isAfter(updated) ? at : updated;
      if ("DANG_CHAY".equals(r.status())) {
        continue;
      }
      done++;
      if ("LOI".equals(r.status())) {
        if (r.reason() != null && !reasons.contains(r.reason())) {
          reasons.add(r.reason());
        }
      } else {
        ok++;
        records += r.records();
        changes += r.changes();
      }
    }
    if (done < total) {
      return Optional.of(new SyncJobView(id, "DANG_CHAY", done * 100 / total,
          "Đang đồng bộ " + (total - done) + "/" + total + " nguồn.", updated));
    }
    if (ok == 0) {
      return Optional.of(new SyncJobView(id, "THAT_BAI", 100, String.join(" ", reasons), updated));
    }
    String message = "Đã đồng bộ " + ok + "/" + total + " nguồn: " + records + " bản ghi, " + changes + " thay đổi."
        + (ok < total ? " Có " + (total - ok) + " nguồn chưa đồng bộ được, xem lý do trong danh sách lần đồng bộ." : "");
    return Optional.of(new SyncJobView(id, "HOAN_TAT", 100, message, updated));
  }

  @Transactional(readOnly = true)
  public Page<Run> list(Actor actor, String connector, int limit, String cursor) {
    policy.authorize(actor, "listOrgSyncRuns");
    int size = Math.max(1, Math.min(limit, 200));
    OffsetDateTime beforeStarted = null;
    UUID beforeId = null;
    if (cursor != null) {
      List<String> keys = Cursors.decode(cursor, 2);
      try {
        beforeStarted = OffsetDateTime.parse(keys.get(0));
        beforeId = UUID.fromString(keys.get(1));
      } catch (RuntimeException e) {
        throw new OrgRuleViolation("COMMON-422-VALIDATION", null, null, "cursor", "Con trỏ trang không hợp lệ.");
      }
    }
    List<Run> rows = runs.page(connector, beforeStarted, beforeId, size + 1);
    String next = null;
    if (rows.size() > size) {
      rows = rows.subList(0, size);
      Run last = rows.getLast();
      next = Cursors.encode(last.startedAt().toString(), last.id().toString());
    }
    return new Page<>(rows, next);
  }

  /** Chạy lại thư chết của lần chạy qua integration-gateway; mở lại lần chạy để nhận các lô phát lại. */
  @Transactional
  public Run retry(Actor actor, IdempotencyRequest idem, UUID runId) {
    policy.authorize(actor, "retryOrgSyncRun");
    Optional<Run> replay = idempotency.begin(actor, idem, Run.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    Run run = runs.find(runId).orElseThrow(() -> new OrgNotFoundException("Không tìm thấy lần đồng bộ."));
    UUID retryId = UuidV7.next(support.clock());
    runs.start(retryId, run.connector(), actor);
    try {
      gateway.retryDeadLetters(actor.tenantId(), runId, retryId, run.connector());
    } catch (IntegrationUnavailableException e) {
      runs.finish(retryId, "LOI", run.deadLetters(), "Không gửi được yêu cầu chạy lại: " + e.getMessage(), actor);
    }
    Run created = runs.find(retryId).orElseThrow();
    idempotency.complete(actor, idem, 202, created, retryId.toString());
    return created;
  }

  @Transactional(readOnly = true)
  public ScheduleSet schedules(Actor actor) {
    policy.authorize(actor, "listOrgSyncSchedules");
    return schedules.load(false);
  }

  /** Lưu cả bộ lịch; {@code If-Match} là phiên bản bộ lịch (409 khi lệch). */
  @Transactional
  public ScheduleSet saveSchedules(Actor actor, IdempotencyRequest idem, int expectedVersion, List<Schedule> items) {
    policy.authorize(actor, "saveOrgSyncSchedules");
    Optional<ScheduleSet> replay = idempotency.begin(actor, idem, ScheduleSet.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    Set<String> seen = new HashSet<>();
    for (Schedule s : items) {
      if (!seen.add(s.connector())) {
        throw new OrgRuleViolation("COMMON-422-VALIDATION", null, null, "items", "Mỗi nguồn chỉ có một lịch.");
      }
      if (s.cron() == null || !CRON.matcher(s.cron()).matches()) {
        throw new OrgRuleViolation("COMMON-422-VALIDATION", null, null, "cron",
            "Biểu thức lịch phải gồm năm trường, ví dụ 0 2 * * *.");
      }
    }
    ScheduleSet current = schedules.load(true);
    if (current.version() != expectedVersion) {
      throw new VersionConflictException(expectedVersion, current.version());
    }
    schedules.replace(items, expectedVersion + 1, actor);
    ScheduleSet saved = schedules.load(false);
    idempotency.complete(actor, idem, 200, saved, null);
    return saved;
  }
}
