package vn.viettel.okrkpi.task.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob.ErrorGroup;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob.ErrorRow;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItemDraft;
import vn.viettel.okrkpi.task.domain.catalog.CatalogSheet;
import vn.viettel.okrkpi.task.domain.catalog.CatalogStatus;
import vn.viettel.okrkpi.task.domain.catalog.CatalogViolationException.Kind;
import vn.viettel.okrkpi.task.domain.catalog.WorkCatalog;

/** Nhập danh mục từ Excel (CORE-02-04) trên kho giả: theo lô, nhóm lỗi, tệp lỗi, lỗi tệp. */
class CatalogImportServiceTest {

  private static final List<String> HEADER =
      List.of(
          "Mã công việc",
          "Tên công việc",
          "Sản phẩm",
          "Đơn vị tính",
          "Mức khó (1-5)",
          "Điểm chuẩn",
          "Tiêu chuẩn chất lượng",
          "Minh chứng yêu cầu",
          "Nhóm mặc định",
          "Thời hạn chuẩn");

  @TempDir Path dir;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID actor = UUID.randomUUID();
  private final MutableClock clock = new MutableClock();

  private InMemoryCatalogRepository repo;
  private Jobs jobs;
  private Sheets sheets;
  private Executor executor;
  private CatalogImportService service;
  private WorkCatalog draft;

  @BeforeEach
  void setUp() {
    repo = new InMemoryCatalogRepository();
    jobs = new Jobs();
    sheets = new Sheets();
    executor = Runnable::run;
    draft = catalog(CatalogStatus.NHAP);
    service = service();
  }

  private CatalogImportService service() {
    CatalogService catalogs =
        new CatalogService(
            repo, CatalogPolicy.defaults(), mock(PeriodApi.class), mock(OrgDirectoryApi.class));
    return new CatalogImportService(
        repo,
        jobs,
        catalogs,
        CatalogPolicy.defaults(),
        sheets,
        r -> executor.execute(r),
        TransactionOperations.withoutTransaction(),
        clock);
  }

  @Test
  void nhapDongHopLeVaGomNhomLoi() throws IOException {
    item(draft.id(), "TC-CV-01");
    List<List<String>> rows = new ArrayList<>();
    rows.add(HEADER);
    rows.add(row("", "Soạn báo cáo tháng"));
    rows.add(row("TC-CV-01", "Trùng mã đã có"));
    rows.add(row("TC-CV-09", "Soạn tờ trình"));
    rows.add(row("TC-CV-09", "Trùng mã trong tệp"));
    rows.add(row("", "Tăng cường công tác kiểm tra"));
    rows.add(List.of("", "", "", "", "", "", "", "", "", ""));
    List<String> missing = new ArrayList<>(row("", "Thiếu sản phẩm"));
    missing.set(2, "");
    rows.add(missing);
    List<String> badDifficulty = new ArrayList<>(row("", "Mức khó sai"));
    badDifficulty.set(4, "khó");
    rows.add(badDifficulty);

    CatalogImportJob job = start(rows);

    CatalogImportJob done = service.find(tenant, job.id());
    assertThat(done.status()).isEqualTo(CatalogImportJob.Status.XONG);
    assertThat(done.totalRows()).isEqualTo(7);
    assertThat(done.validRows()).isEqualTo(2);
    assertThat(done.errorGroups())
        .extracting(ErrorGroup::reason, ErrorGroup::count, ErrorGroup::kind)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("Mã công việc đã có trong danh mục.", 2, Kind.KHAC),
            org.assertj.core.groups.Tuple.tuple(CatalogImportService.MO_HO_REASON, 1, Kind.MO_HO),
            org.assertj.core.groups.Tuple.tuple("Sản phẩm là bắt buộc.", 1, Kind.THIEU),
            org.assertj.core.groups.Tuple.tuple(
                "Mức khó phải là số nguyên từ 1 đến 5.", 1, Kind.KHAC));
    assertThat(repo.itemCodes(tenant, draft.id())).contains("TC-CV-02", "TC-CV-09");
    assertThat(jobs.errorRows(tenant, job.id()))
        .extracting(ErrorRow::rowNo)
        .containsExactly(3, 5, 6, 8, 9);
    assertThat(service.latest(tenant, draft.id())).map(CatalogImportJob::id).contains(job.id());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.writeErrors(tenant, job.id(), out);
    assertThat(sheets.writtenHeader).first().isEqualTo("Dòng");
    assertThat(sheets.writtenHeader).last().isEqualTo("Lý do");
    assertThat(sheets.writtenRows).hasSize(5);
  }

  @Test
  void nhapVaoBanApDungThiGhiVaoBanNhapKeTiep() throws IOException {
    WorkCatalog applied = catalog(CatalogStatus.HIEU_LUC);
    List<List<String>> rows = List.of(HEADER, row("", "Soạn báo cáo quý"));
    CatalogImportJob job = service.start(tenant, actor, applied.id(), "a.xlsx", file(rows));
    assertThat(job.targetCatalogId()).isNotEqualTo(applied.id());
    assertThat(repo.itemCodes(tenant, job.targetCatalogId())).hasSize(1);
    assertThat(service.latest(tenant, applied.id())).isPresent();
  }

  @Test
  void banNgungApDungKhongNhapDuoc() {
    WorkCatalog stopped = catalog(CatalogStatus.NGUNG);
    assertThatThrownBy(() -> service.start(tenant, actor, stopped.id(), "a.xlsx", dir))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void loiCapTepChotLoi() throws IOException {
    assertFailed(start(List.of(List.of("Tên", "Ghi chú"), row("", "x"))), "thiếu cột");
    assertFailed(start(List.of()), "thiếu cột");
    sheets.unreadable = true;
    assertFailed(start(List.of(HEADER)), "không đọc được");
    sheets.unreadable = false;
    sheets.crash = true;
    assertFailed(start(List.of(HEADER)), "có lỗi khi xử lý");
    sheets.crash = false;

    List<List<String>> many = new ArrayList<>();
    many.add(HEADER);
    for (int i = 0; i <= CatalogImportService.MAX_ROWS; i++) {
      many.add(row("", "Soạn văn bản số " + i));
    }
    assertFailed(start(many), "vượt giới hạn");
  }

  @Test
  void hangDoiDayThiChotLoi() throws IOException {
    executor =
        r -> {
          throw new RejectedExecutionException("đầy");
        };
    Path f = file(List.of(HEADER, row("", "Soạn báo cáo")));
    CatalogImportJob job = service.start(tenant, actor, draft.id(), "a.xlsx", f);
    assertFailed(job, "đang xử lý nhiều tệp");
    assertThat(f).doesNotExist();
  }

  @Test
  void loTrungMaDoDongThoiThiGhiTungDong() throws IOException {
    repo.failNextItemWrite = true;
    CatalogImportJob job =
        start(List.of(HEADER, row("", "Soạn báo cáo"), row("", "Soạn tờ trình")));
    CatalogImportJob done = service.find(tenant, job.id());
    assertThat(done.validRows()).isEqualTo(2);

    repo.failNextItemWrite = true;
    executor = Runnable::run;
    List<List<String>> one = List.of(HEADER, row("", "Soạn công văn"));
    // Lô một dòng: lần ghi lô lỗi, ghi lại từng dòng thành công.
    assertThat(service.find(tenant, start(one).id()).validRows()).isEqualTo(1);
  }

  @Test
  void lanNhapTreoQuaHanBiChotLoi() {
    CatalogImportJob stuck =
        CatalogImportJob.started(UUID.randomUUID(), draft.id(), draft.id(), "a.xlsx", clock.now);
    jobs.insert(tenant, actor, stuck);
    assertThat(service.find(tenant, stuck.id()).status())
        .isEqualTo(CatalogImportJob.Status.DANG_XU_LY);
    clock.now = clock.now.plus(Duration.ofMinutes(16));
    assertFailed(service.find(tenant, stuck.id()), "gián đoạn");
    assertThatThrownBy(() -> service.find(tenant, UUID.randomUUID()))
        .isInstanceOf(NoSuchElementException.class);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private void assertFailed(CatalogImportJob job, String detail) {
    CatalogImportJob j = service.find(tenant, job.id());
    assertThat(j.status()).isEqualTo(CatalogImportJob.Status.LOI);
    assertThat(j.failureDetail()).containsIgnoringCase(detail);
    assertThat(j.errorGroups()).hasSize(1);
  }

  private CatalogImportJob start(List<List<String>> rows) throws IOException {
    Path f = file(rows);
    CatalogImportJob job = service.start(tenant, actor, draft.id(), "danh-muc.xlsx", f);
    assertThat(f).doesNotExist();
    return job;
  }

  private Path file(List<List<String>> rows) throws IOException {
    Path f = Files.createTempFile(dir, "nhap-", ".xlsx");
    sheets.files.put(f, rows);
    return f;
  }

  private static List<String> row(String code, String name) {
    return List.of(
        code,
        name,
        "Báo cáo",
        "Bản",
        "3",
        "15,5",
        "Đúng hạn, đủ số liệu",
        "Văn bản đã ký",
        "Thường xuyên",
        "5");
  }

  private WorkCatalog catalog(CatalogStatus status) {
    WorkCatalog c =
        new WorkCatalog(
            UUID.randomUUID(),
            "CV" + status,
            "Chuyên viên",
            "VI_TRI",
            null,
            UUID.randomUUID(),
            1,
            "TU_TAO",
            status,
            null,
            0);
    repo.catalogs.put(c.id(), c);
    return c;
  }

  private void item(UUID catalogId, String code) {
    CatalogItem i =
        CatalogItem.create(
            UUID.randomUUID(),
            catalogId,
            code,
            new CatalogItemDraft(
                code,
                null,
                "Thẩm định hồ sơ",
                "Hồ sơ",
                "Bộ",
                3,
                new BigDecimal("15"),
                "Đủ thành phần",
                "Biên bản",
                "THUONG_XUYEN",
                10,
                "AP_DUNG"));
    repo.items.put(i.id(), i);
  }

  private static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-22T03:00:00Z");

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
      return now;
    }
  }

  private static final class Sheets implements CatalogSpreadsheets {
    final Map<Path, List<List<String>>> files = new HashMap<>();
    boolean unreadable;
    boolean crash;
    List<String> writtenHeader;
    List<ErrorRow> writtenRows;

    @Override
    public void read(Path file, RowHandler handler) {
      if (unreadable) {
        throw new UnreadableFileException(new IOException("hỏng"));
      }
      if (crash) {
        throw new IllegalStateException("lỗi lạ");
      }
      List<List<String>> rows = files.get(file);
      for (int i = 0; i < rows.size(); i++) {
        if (!handler.row(i + 1, rows.get(i))) {
          return;
        }
      }
    }

    @Override
    public void writeErrors(OutputStream out, List<String> header, List<ErrorRow> rows) {
      writtenHeader = header;
      writtenRows = rows;
    }
  }

  private static final class Jobs implements CatalogImportJobs {
    final Map<UUID, CatalogImportJob> jobs = new HashMap<>();
    final Map<UUID, List<ErrorRow>> rows = new HashMap<>();

    @Override
    public void insert(TenantId tenant, UUID actor, CatalogImportJob job) {
      jobs.put(job.id(), job);
    }

    @Override
    public void finish(TenantId tenant, UUID actor, CatalogImportJob result, List<ErrorRow> err) {
      if (jobs.get(result.id()).status() == CatalogImportJob.Status.DANG_XU_LY) {
        jobs.put(result.id(), result);
        rows.put(result.id(), err);
      }
    }

    @Override
    public Optional<CatalogImportJob> find(TenantId tenant, UUID id) {
      return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public Optional<CatalogImportJob> latest(TenantId tenant, UUID catalogId) {
      return jobs.values().stream()
          .filter(j -> j.catalogId().equals(catalogId))
          .max(
              java.util.Comparator.comparing(CatalogImportJob::createdAt)
                  .thenComparing(CatalogImportJob::id));
    }

    @Override
    public List<ErrorRow> errorRows(TenantId tenant, UUID id) {
      return rows.getOrDefault(id, List.of());
    }
  }

  static {
    // Bảo đảm cột mẫu khớp tiêu đề dùng trong kiểm thử.
    assert CatalogSheet.columns(HEADER).missingRequired().isEmpty();
  }
}
