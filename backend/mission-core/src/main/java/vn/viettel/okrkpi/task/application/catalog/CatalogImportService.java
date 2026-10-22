package vn.viettel.okrkpi.task.application.catalog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob.ErrorGroup;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob.ErrorRow;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItemDraft;
import vn.viettel.okrkpi.task.domain.catalog.CatalogRules;
import vn.viettel.okrkpi.task.domain.catalog.CatalogSheet;
import vn.viettel.okrkpi.task.domain.catalog.CatalogViolationException;
import vn.viettel.okrkpi.task.domain.catalog.CatalogViolationException.Kind;
import vn.viettel.okrkpi.task.domain.catalog.CatalogViolationException.Violation;
import vn.viettel.okrkpi.task.domain.catalog.WorkCatalog;

/**
 * Nhập danh mục công việc từ Excel (CORE-02-04; QT-04 "Ngoại lệ và xử lý", SCR-0401, MSG-04-02):
 * nhận tệp, ghi lần nhập {@code DANG_XU_LY}, xử lý nền sau khi giao dịch nhận tệp đã ghi, đọc theo
 * luồng, kiểm mỗi dòng bằng đúng quy tắc của {@code addCatalogItem}, ghi dòng hợp lệ theo lô (mỗi
 * lô một giao dịch) và lưu dòng lỗi kèm lý do để dựng tệp lỗi.
 *
 * <p>Nhập vào phiên bản đang áp dụng thì ghi vào phiên bản nháp kế tiếp như {@code addCatalogItem}.
 * Mã đã có trong danh mục (kể cả mục kế thừa) hoặc trùng trong tệp là lỗi {@code KHAC}, nên nhập
 * lại cùng tệp chỉ thêm mục chưa có.
 */
public class CatalogImportService {

  /** Số dòng dữ liệu tối đa của một tệp (suy luận, hợp đồng không ghi). */
  public static final int MAX_ROWS = 5_000;

  /** Số dòng hợp lệ ghi trong một giao dịch. */
  static final int BATCH = 200;

  /** Lần nhập {@code DANG_XU_LY} quá thời gian này coi như bị gián đoạn (pod dừng giữa chừng). */
  static final Duration STALE_AFTER = Duration.ofMinutes(15);

  static final String MO_HO_REASON = "Tên công việc chứa từ mơ hồ.";

  private static final Logger LOG = LoggerFactory.getLogger(CatalogImportService.class);

  private final CatalogRepository repository;
  private final CatalogImportJobs jobs;
  private final CatalogService catalogs;
  private final CatalogPolicy policy;
  private final CatalogSpreadsheets sheets;
  private final Executor executor;
  private final TransactionOperations tx;
  private final Clock clock;

  public CatalogImportService(
      CatalogRepository repository,
      CatalogImportJobs jobs,
      CatalogService catalogs,
      CatalogPolicy policy,
      CatalogSpreadsheets sheets,
      Executor executor,
      TransactionOperations tx,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.sheets = Objects.requireNonNull(sheets, "sheets");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * {@code importCatalogExcel}: ghi lần nhập và hẹn xử lý nền sau khi giao dịch hiện tại ghi xong.
   * Tệp tạm {@code file} thuộc quyền dịch vụ từ đây và bị xoá khi xử lý xong.
   */
  @Transactional(CatalogService.TX)
  public CatalogImportJob start(
      TenantId tenant, UUID actor, UUID catalogId, String fileName, Path file) {
    WorkCatalog target = catalogs.writableVersion(tenant, actor, catalogId);
    CatalogImportJob job =
        CatalogImportJob.started(
            Ids.newUuidV7(), catalogId, target.id(), fileName, clock.instant());
    jobs.insert(tenant, actor, job);
    Runnable work = () -> submit(tenant, actor, job, file);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status == STATUS_COMMITTED) {
                work.run();
              } else {
                delete(file);
              }
            }
          });
    } else {
      work.run();
    }
    return job;
  }

  /** {@code getCatalogImport}; lần nhập bị gián đoạn được chốt {@code LOI}. */
  @Transactional(CatalogService.TX)
  public CatalogImportJob find(TenantId tenant, UUID jobId) {
    return jobs.find(tenant, jobId)
        .map(j -> settleStale(tenant, j))
        .orElseThrow(() -> new NoSuchElementException("Không có lần nhập " + jobId));
  }

  /** {@code getLatestCatalogImport}. */
  @Transactional(CatalogService.TX)
  public Optional<CatalogImportJob> latest(TenantId tenant, UUID catalogId) {
    return jobs.latest(tenant, catalogId).map(j -> settleStale(tenant, j));
  }

  /** {@code downloadCatalogImportErrors}: tệp các dòng lỗi theo cột mẫu, thêm cột dòng và lý do. */
  @Transactional(CatalogService.TX)
  public void writeErrors(TenantId tenant, UUID jobId, OutputStream out) throws IOException {
    find(tenant, jobId);
    List<String> header = new ArrayList<>();
    header.add("Dòng");
    for (CatalogSheet.Column c : CatalogSheet.Column.values()) {
      header.add(c.label());
    }
    header.add("Lý do");
    sheets.writeErrors(out, header, jobs.errorRows(tenant, jobId));
  }

  // ---------------------------------------------------------------- xử lý nền

  private void submit(TenantId tenant, UUID actor, CatalogImportJob job, Path file) {
    try {
      executor.execute(() -> TenantContext.run(tenant, () -> process(tenant, actor, job, file)));
    } catch (RejectedExecutionException e) {
      LOG.warn("Hàng đợi nhập danh mục đầy, lần nhập {} bị từ chối", job.id());
      TenantContext.run(
          tenant,
          () ->
              fail(
                  tenant,
                  actor,
                  job,
                  0,
                  "Hệ thống đang xử lý nhiều tệp. Hãy nhập lại sau ít phút."));
      delete(file);
    }
  }

  /** Xử lý một lần nhập; không ném ra ngoài, mọi kết cục đều chốt trạng thái tác vụ. */
  void process(TenantId tenant, UUID actor, CatalogImportJob job, Path file) {
    try {
      int rows = countRows(file);
      if (rows > MAX_ROWS) {
        fail(
            tenant,
            actor,
            job,
            rows,
            "Tệp có " + rows + " dòng, vượt giới hạn " + MAX_ROWS + " dòng mỗi lần nhập.");
        return;
      }
      run(tenant, actor, job, file);
    } catch (CatalogSpreadsheets.UnreadableFileException e) {
      fail(tenant, actor, job, 0, "Tệp không đọc được. Hãy dùng tệp Excel (.xlsx) theo mẫu.");
    } catch (MissingColumnsException e) {
      fail(tenant, actor, job, 0, e.getMessage());
    } catch (RuntimeException e) {
      LOG.error("Nhập danh mục {} lỗi", job.id(), e);
      fail(tenant, actor, job, 0, "Có lỗi khi xử lý tệp. Hãy nhập lại; nếu còn lỗi, báo quản trị.");
    } finally {
      delete(file);
    }
  }

  private int countRows(Path file) {
    int[] count = {0};
    sheets.read(
        file,
        (n, cells) -> {
          if (n > 1 && !CatalogSheet.isBlank(cells)) {
            count[0]++;
          }
          return count[0] <= MAX_ROWS;
        });
    return count[0];
  }

  private void run(TenantId tenant, UUID actor, CatalogImportJob job, Path file) {
    List<String> vague = policy.vagueWords(tenant);
    Set<String> codes =
        tx.execute(
            s -> {
              Set<String> out = new HashSet<>(repository.itemCodes(tenant, job.targetCatalogId()));
              repository
                  .visibleItems(tenant, job.targetCatalogId())
                  .forEach(v -> out.add(v.item().code()));
              return out;
            });
    List<ErrorRow> errors = new ArrayList<>();
    Map<String, ErrorGroup> groups = new LinkedHashMap<>();
    List<Pending> batch = new ArrayList<>();
    int[] totals = {0, 0};
    CatalogSheet.Columns[] columns = {null};

    sheets.read(
        file,
        (n, cells) -> {
          if (columns[0] == null) {
            columns[0] = CatalogSheet.columns(cells);
            List<CatalogSheet.Column> missing = columns[0].missingRequired();
            if (!missing.isEmpty()) {
              throw new MissingColumnsException(missing);
            }
            return true;
          }
          if (CatalogSheet.isBlank(cells)) {
            return true;
          }
          totals[0]++;
          List<String> values = values(cells, columns[0]);
          List<Violation> violations = new ArrayList<>();
          CatalogSheet.Parsed parsed = CatalogSheet.parse(cells, columns[0]);
          violations.addAll(parsed.violations());
          CatalogItemDraft draft = CatalogRules.normalize(parsed.draft());
          try {
            CatalogRules.validate(draft, vague);
          } catch (CatalogViolationException e) {
            // Ô sai kiểu đã báo ở bước chuyển; bỏ lỗi "bắt buộc" trùng trường của chính ô đó.
            Set<String> parsedFields = new HashSet<>();
            parsed.violations().forEach(v -> parsedFields.add(v.field()));
            e.violations().stream()
                .filter(v -> !parsedFields.contains(v.field()))
                .forEach(violations::add);
          }
          if (violations.isEmpty()) {
            violations.addAll(
                catalogs.undeclaredLevels(tenant, draft.scoring().coefficientLevels()));
          }
          String code = draft.code();
          if (violations.isEmpty() && code != null && codes.contains(code)) {
            violations.add(
                new Violation(
                    "code", "Mã công việc " + code + " đã có trong danh mục.", Kind.KHAC));
          }
          if (!violations.isEmpty()) {
            reject(n, values, violations, errors, groups);
            return true;
          }
          if (code == null) {
            code = CatalogRules.nextCode(codes);
          }
          codes.add(code);
          batch.add(
              new Pending(
                  n,
                  values,
                  CatalogItem.create(Ids.newUuidV7(), job.targetCatalogId(), code, draft)));
          if (batch.size() >= BATCH) {
            totals[1] += flush(tenant, actor, batch, errors, groups);
          }
          return true;
        });
    if (columns[0] == null) {
      throw new MissingColumnsException(List.of(CatalogSheet.Column.values()));
    }
    totals[1] += flush(tenant, actor, batch, errors, groups);
    CatalogImportJob done =
        new CatalogImportJob(
            job.id(),
            job.catalogId(),
            job.targetCatalogId(),
            job.fileName(),
            CatalogImportJob.Status.XONG,
            totals[0],
            totals[1],
            sorted(groups),
            null,
            job.createdAt(),
            clock.instant());
    errors.sort(Comparator.comparingInt(ErrorRow::rowNo));
    tx.executeWithoutResult(s -> jobs.finish(tenant, actor, done, errors));
  }

  /** Ghi một lô; lô lỗi (trùng mã do thao tác đồng thời) được ghi lại từng dòng. */
  private int flush(
      TenantId tenant,
      UUID actor,
      List<Pending> batch,
      List<ErrorRow> errors,
      Map<String, ErrorGroup> groups) {
    if (batch.isEmpty()) {
      return 0;
    }
    List<Pending> rows = List.copyOf(batch);
    batch.clear();
    try {
      tx.executeWithoutResult(
          s -> rows.forEach(p -> repository.insertItem(tenant, actor, p.item())));
      return rows.size();
    } catch (CatalogRepository.DuplicateCodeException e) {
      int ok = 0;
      for (Pending p : rows) {
        try {
          tx.executeWithoutResult(s -> repository.insertItem(tenant, actor, p.item()));
          ok++;
        } catch (CatalogRepository.DuplicateCodeException dup) {
          reject(
              p.rowNo(),
              p.values(),
              List.of(
                  new Violation(
                      "code",
                      "Mã công việc " + p.item().code() + " đã có trong danh mục.",
                      Kind.KHAC)),
              errors,
              groups);
        }
      }
      return ok;
    }
  }

  private static void reject(
      int rowNo,
      List<String> values,
      List<Violation> violations,
      List<ErrorRow> errors,
      Map<String, ErrorGroup> groups) {
    Violation primary =
        violations.stream().min(Comparator.comparingInt(v -> v.kind().ordinal())).orElseThrow();
    String reason = primary.kind() == Kind.MO_HO ? MO_HO_REASON : groupReason(primary);
    groups.merge(
        primary.kind() + "|" + reason,
        new ErrorGroup(reason, 1, primary.kind()),
        (a, b) -> new ErrorGroup(a.reason(), a.count() + 1, a.kind()));
    errors.add(
        new ErrorRow(
            rowNo,
            values,
            String.join(" ", violations.stream().map(Violation::message).distinct().toList())));
  }

  /** Lý do nhóm không chứa giá trị riêng của dòng (mã trùng gộp chung một nhóm). */
  private static String groupReason(Violation v) {
    return "code".equals(v.field()) && v.message().endsWith("đã có trong danh mục.")
        ? "Mã công việc đã có trong danh mục."
        : v.message();
  }

  private static List<ErrorGroup> sorted(Map<String, ErrorGroup> groups) {
    return groups.values().stream()
        .sorted(Comparator.comparingInt(ErrorGroup::count).reversed())
        .toList();
  }

  private static List<String> values(List<String> cells, CatalogSheet.Columns columns) {
    List<String> out = new ArrayList<>();
    for (CatalogSheet.Column c : CatalogSheet.Column.values()) {
      Integer at = columns.index().get(c);
      String v = at == null || at >= cells.size() ? null : cells.get(at);
      out.add(v == null ? "" : v);
    }
    return out;
  }

  private void fail(TenantId tenant, UUID actor, CatalogImportJob job, int rows, String detail) {
    CatalogImportJob failed =
        new CatalogImportJob(
            job.id(),
            job.catalogId(),
            job.targetCatalogId(),
            job.fileName(),
            CatalogImportJob.Status.LOI,
            rows,
            0,
            List.of(new ErrorGroup(detail, Math.max(rows, 1), Kind.KHAC)),
            detail,
            job.createdAt(),
            clock.instant());
    tx.executeWithoutResult(s -> jobs.finish(tenant, actor, failed, List.of()));
  }

  private CatalogImportJob settleStale(TenantId tenant, CatalogImportJob job) {
    if (job.status() != CatalogImportJob.Status.DANG_XU_LY
        || job.createdAt() == null
        || job.createdAt().plus(STALE_AFTER).isAfter(clock.instant())) {
      return job;
    }
    fail(tenant, Ids.newUuidV7(), job, job.totalRows(), "Lần nhập bị gián đoạn. Hãy nhập lại tệp.");
    return jobs.find(tenant, job.id()).orElse(job);
  }

  private static void delete(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.warn("Không xoá được tệp tạm của lần nhập danh mục", e);
    }
  }

  private record Pending(int rowNo, List<String> values, CatalogItem item) {}

  /** Tệp thiếu cột bắt buộc của mẫu. */
  static final class MissingColumnsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MissingColumnsException(List<CatalogSheet.Column> missing) {
      super(
          "Tệp thiếu cột "
              + String.join(", ", missing.stream().map(CatalogSheet.Column::label).toList())
              + " của mẫu danh mục.");
    }
  }
}
