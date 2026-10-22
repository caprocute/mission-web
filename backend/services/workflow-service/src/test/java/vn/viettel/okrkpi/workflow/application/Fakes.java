package vn.viettel.okrkpi.workflow.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.workflow.application.port.ApprovalAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.BatchDispatcher;
import vn.viettel.okrkpi.workflow.application.port.BatchJobStore;
import vn.viettel.okrkpi.workflow.application.port.CurrentApprover;
import vn.viettel.okrkpi.workflow.application.port.HistoryReader;
import vn.viettel.okrkpi.workflow.application.port.TaskEngine;
import vn.viettel.okrkpi.workflow.application.port.TaskProjectionStore;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BatchJob;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.InboxCounts;
import vn.viettel.okrkpi.workflow.domain.InboxFilter;
import vn.viettel.okrkpi.workflow.domain.InboxPosition;
import vn.viettel.okrkpi.workflow.domain.OpenTask;
import vn.viettel.okrkpi.workflow.domain.ProcessHistory;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;

/** Bản giả của các cổng ra cho kiểm thử đơn vị tầng application. */
final class Fakes {

  private Fakes() {}

  static final class Caller implements CurrentApprover {
    Approver approver;

    Caller(Approver approver) {
      this.approver = approver;
    }

    @Override
    public Approver current() {
      return approver;
    }
  }

  /** Ghi lại lần kiểm quyền; từ chối khi {@code deny} đặt mã lỗi. */
  static final class Policy implements ApprovalAccessPolicy {
    final List<String> calls = new ArrayList<>();
    final List<AuthzResource> resources = new ArrayList<>();
    String deny;

    private void check(String action, AuthzResource resource) {
      calls.add(action);
      resources.add(resource);
      if (deny != null) {
        throw new BusinessException(deny);
      }
    }

    @Override
    public void checkView(AuthzResource resource) {
      check("xem", resource);
    }

    @Override
    public void checkApprove(AuthzResource resource) {
      check("duyet", resource);
    }

    @Override
    public void checkBatchApprove(AuthzResource resource) {
      check("duyet-lo", resource);
    }

    @Override
    public void checkReturn(AuthzResource resource) {
      check("tra-lai", resource);
    }
  }

  static final class Projections implements TaskProjectionStore {
    final Map<String, TaskProjection> rows = new LinkedHashMap<>();
    InboxFilter lastFilter;
    InboxPosition lastAfter;

    @Override
    public void upsert(TaskProjection projection) {
      rows.put(projection.taskId(), projection);
    }

    @Override
    public void reassign(UUID tenantId, String taskId, UUID assigneeId, int rowVersion) {
      TaskProjection p = rows.get(taskId);
      if (p != null && p.tenantId().equals(tenantId)) {
        rows.put(
            taskId,
            new TaskProjection(
                p.taskId(),
                p.tenantId(),
                assigneeId,
                p.candidateRole(),
                p.candidateUnitId(),
                p.businessKey(),
                p.templateCode(),
                p.title(),
                p.periodId(),
                p.dueAt(),
                p.createdAt(),
                rowVersion));
      }
    }

    @Override
    public void delete(UUID tenantId, String taskId) {
      rows.computeIfPresent(taskId, (k, v) -> v.tenantId().equals(tenantId) ? null : v);
    }

    @Override
    public List<TaskProjection> findInbox(InboxFilter filter, InboxPosition after, int fetchSize) {
      lastFilter = filter;
      lastAfter = after;
      Comparator<TaskProjection> order =
          Comparator.comparing(
                  TaskProjection::dueAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
              .thenComparing(TaskProjection::taskId);
      return rows.values().stream()
          .filter(p -> p.tenantId().equals(filter.approver().tenantId()))
          .filter(p -> filter.approver().actorId().equals(p.assigneeId()))
          .sorted(order)
          .filter(p -> after == null || isAfter(p, after))
          .limit(fetchSize)
          .toList();
    }

    private static boolean isAfter(TaskProjection p, InboxPosition after) {
      if (after.dueAt() == null) {
        return p.dueAt() == null && p.taskId().compareTo(after.taskId()) > 0;
      }
      if (p.dueAt() == null) {
        return true;
      }
      int c = p.dueAt().compareTo(after.dueAt());
      return c > 0 || (c == 0 && p.taskId().compareTo(after.taskId()) > 0);
    }

    @Override
    public InboxCounts count(InboxFilter filter, Instant now, Instant endOfToday) {
      List<TaskProjection> mine =
          rows.values().stream()
              .filter(p -> filter.approver().actorId().equals(p.assigneeId()))
              .toList();
      int overdue = (int) mine.stream().filter(p -> p.overdueAt(now)).count();
      int today =
          (int)
              mine.stream()
                  .filter(
                      p ->
                          p.dueAt() != null
                              && !p.dueAt().isBefore(now)
                              && p.dueAt().isBefore(endOfToday))
                  .count();
      return new InboxCounts(mine.size(), overdue, today);
    }
  }

  /** Engine giả: task mở theo mã, hành vi hoàn thành có thể thay. */
  static final class Engine implements TaskEngine {
    final Map<String, OpenTask> open = new HashMap<>();
    final Set<String> completed = new HashSet<>();
    final List<String> calls = new ArrayList<>();
    Function<OpenTask, Completion> onComplete =
        t -> new Completion(Instant.parse("2027-01-07T03:05:00Z"), true);

    @Override
    public Optional<OpenTask> findOpen(UUID tenantId, String taskId) {
      return Optional.ofNullable(open.get(taskId)).filter(t -> t.tenantId().equals(tenantId));
    }

    @Override
    public boolean wasCompleted(UUID tenantId, String taskId) {
      return completed.contains(taskId);
    }

    @Override
    public Completion complete(
        OpenTask task, Approver approver, Decision decision, String comment) {
      calls.add(task.taskId() + ":" + decision + ":" + comment);
      Completion c = onComplete.apply(task);
      open.remove(task.taskId());
      completed.add(task.taskId());
      return c;
    }
  }

  static final class Histories implements HistoryReader {
    final Map<String, ProcessHistory> byKey = new HashMap<>();

    @Override
    public Optional<ProcessHistory> find(UUID tenantId, BusinessKey businessKey) {
      return Optional.ofNullable(byKey.get(tenantId + "|" + businessKey.value()));
    }
  }

  static class Batches implements BatchJobStore {
    final Map<UUID, BatchJob> jobs = new HashMap<>();
    final List<BatchJob.Status> history = new ArrayList<>();

    @Override
    public void create(BatchJob job) {
      jobs.put(job.id(), job);
      history.add(job.status());
    }

    @Override
    public void update(BatchJob job) {
      jobs.put(job.id(), job);
      history.add(job.status());
    }

    @Override
    public Optional<BatchJob> find(UUID tenantId, UUID jobId) {
      return Optional.ofNullable(jobs.get(jobId)).filter(j -> j.tenantId().equals(tenantId));
    }
  }

  /** Chạy việc nền ngay (hoặc giữ lại để chạy sau). */
  static final class Dispatcher implements BatchDispatcher {
    final List<Runnable> pending = new ArrayList<>();
    boolean immediate = true;

    @Override
    public void dispatchAfterCommit(Runnable work) {
      if (immediate) {
        work.run();
      } else {
        pending.add(work);
      }
    }
  }

  /** Trình quản lý giao dịch giả: TransactionTemplate chạy callback không có CSDL. */
  static final class NoTransactions implements PlatformTransactionManager {
    int commits;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commits++;
    }

    @Override
    public void rollback(TransactionStatus status) {
      // Không có gì để huỷ.
    }
  }
}
