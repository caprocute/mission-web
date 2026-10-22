package vn.viettel.okrkpi.org.application.orgimport;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.ChangeOrderRecord;
import vn.viettel.okrkpi.org.application.IdempotencyRequest;
import vn.viettel.okrkpi.org.application.SubjectGroupRecord;
import vn.viettel.okrkpi.org.application.UnitSupport;
import vn.viettel.okrkpi.org.application.port.BackgroundJobRunner;
import vn.viettel.okrkpi.org.application.port.ChangeOrderStore;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.application.port.ImportJobStore;
import vn.viettel.okrkpi.org.application.port.ImportJobStore.JobRow;
import vn.viettel.okrkpi.org.application.port.ImportJobStore.NewJob;
import vn.viettel.okrkpi.org.application.port.OrgUnitStore;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.application.port.SpreadsheetReader;
import vn.viettel.okrkpi.org.application.port.SpreadsheetReader.InvalidSpreadsheetException;
import vn.viettel.okrkpi.org.application.port.SubjectGroupStore;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.OrgUnitContent;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.ValidPeriod;

/**
 * DV-04-05 · QT-01, SCR-0103 "Nhập từ Excel": nhận tệp, xếp tác vụ nền (202), đọc theo luồng, kiểm từng dòng (mã
 * duy nhất, cấp trên có trong tệp hoặc trong cây, không vòng, khối đối tượng PL 2A theo BR-01-05, ngày hiệu lực), rồi
 * ghi cả lần nhập dưới một quyết định tổ chức qua đường ghi phiên bản (BR-01-06) và phát EVT-14 cho mỗi đơn vị.
 *
 * <p>Hợp đồng {@code importOrgExcel} không có tham số "bỏ qua dòng lỗi", nên lần nhập là tất cả hoặc không: còn một
 * dòng lỗi thì không ghi đơn vị nào và tác vụ kết thúc {@code THAT_BAI} kèm báo cáo lỗi (suy luận, wiki trang 14).
 */
@Service
public class OrgImportService {

  private static final Logger LOG = LoggerFactory.getLogger(OrgImportService.class);

  /** Giới hạn kích thước tệp tải lên (suy luận; mẫu 1.000 đơn vị khoảng 60 KB). */
  public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

  /** Giới hạn số dòng dữ liệu một lần nhập (suy luận). */
  public static final int MAX_ROWS = 20_000;

  static final String JOB_TYPE = "IMPORT";

  private final ImportJobStore jobs;
  private final SpreadsheetReader reader;
  private final BackgroundJobRunner runner;
  private final OrgUnitStore units;
  private final SubjectGroupStore groups;
  private final ChangeOrderStore orders;
  private final UnitSupport support;
  private final IdempotencyPort idempotency;
  private final PolicyPort policy;
  private final TransactionTemplate tx;
  private final Clock clock;

  public OrgImportService(ImportJobStore jobs, SpreadsheetReader reader, BackgroundJobRunner runner,
      OrgUnitStore units, SubjectGroupStore groups, ChangeOrderStore orders, UnitSupport support,
      IdempotencyPort idempotency, PolicyPort policy, PlatformTransactionManager tm, Clock clock) {
    this.jobs = jobs;
    this.reader = reader;
    this.runner = runner;
    this.units = units;
    this.groups = groups;
    this.orders = orders;
    this.support = support;
    this.idempotency = idempotency;
    this.policy = policy;
    this.tx = new TransactionTemplate(tm);
    this.clock = clock;
  }

  /** Trạng thái tác vụ như lược đồ {@code Job} trả ra. */
  public record ImportJobView(UUID id, String type, String status, int progress, String message, int totalRows,
      int createdUnits, List<ImportError> errors, UUID changeOrderId, java.time.OffsetDateTime updatedAt) {
  }

  /** Nhận tệp và xếp tác vụ; trả ngay (NFR-03), xử lý chạy sau khi giao dịch này commit. */
  @Transactional
  public ImportJobView accept(Actor actor, IdempotencyRequest idem, String fileName, byte[] content,
      LocalDate effectiveFrom) {
    policy.authorize(actor, "importOrgExcel");
    Optional<ImportJobView> replay = idempotency.begin(actor, idem, ImportJobView.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (content == null || content.length == 0) {
      throw new OrgRuleViolation("ORG-422-IMPORT-FILE", null, null, "file", "Chưa chọn tệp để nhập.");
    }
    if (content.length > MAX_FILE_BYTES) {
      throw new OrgRuleViolation("ORG-422-IMPORT-FILE", null, null, "file",
          "Tệp vượt quá dung lượng cho phép (10 MB).");
    }
    // Tệp .xlsx là gói ZIP: kiểm chữ ký đầu tệp để báo sai định dạng ngay, không đợi tác vụ nền.
    if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
      throw new OrgRuleViolation("ORG-422-IMPORT-FILE", null, null, "file",
          "Tệp không đúng định dạng Excel (.xlsx) theo mẫu.");
    }
    UUID id = UuidV7.next(clock);
    LocalDate eff = effectiveFrom != null ? effectiveFrom : support.today();
    jobs.insert(new NewJob(id, fileName == null ? null : fileName.substring(0, Math.min(255, fileName.length())),
        content, sha256(content), eff), actor);
    ImportJobView view = view(jobs.find(id).orElseThrow());
    idempotency.complete(actor, idem, 202, view, id.toString());
    runner.submitAfterCommit(actor.tenantId(), () -> process(actor, id));
    return view;
  }

  /** {@code GET /api/v1/jobs/{id}} cho tác vụ nhập của tenant. */
  @Transactional(readOnly = true)
  public ImportJobView job(Actor actor, UUID id) {
    policy.authorize(actor, "importOrgExcel");
    return jobs.find(id).map(OrgImportService::view)
        .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy tác vụ."));
  }

  /** Thân tác vụ nền; mọi lỗi được ghi vào tác vụ, không lan ra luồng chạy nền. */
  public void process(Actor actor, UUID jobId) {
    try {
      JobRow job = tx.execute(s -> jobs.find(jobId).orElse(null));
      byte[] content = job == null || !"CHO".equals(job.status()) ? null : tx.execute(s -> {
        jobs.progress(jobId, "DANG_CHAY", 5, "Đang đọc tệp", 0, actor);
        return jobs.content(jobId).orElse(null);
      });
      if (content == null) {
        return;
      }
      LocalDate eff = job.effectiveFrom();
      Parsed parsed = parse(content);
      if (!parsed.errors().isEmpty()) {
        fail(actor, jobId, parsed.rows().size(), parsed.errors());
        return;
      }
      tx.executeWithoutResult(s -> jobs.progress(jobId, "DANG_CHAY", 30, "Đang kiểm tra dữ liệu",
          parsed.rows().size(), actor));
      List<ImportError> errors = new ArrayList<>();
      List<Candidate> candidates = tx.execute(s -> validate(parsed.rows(), eff, errors));
      if (!errors.isEmpty()) {
        fail(actor, jobId, parsed.rows().size(), errors);
        return;
      }
      tx.executeWithoutResult(s -> jobs.progress(jobId, "DANG_CHAY", 60, "Đang ghi cây tổ chức",
          parsed.rows().size(), actor));
      try {
        tx.executeWithoutResult(s -> write(actor, jobId, candidates, eff, parsed.rows().size()));
      } catch (RowViolation e) {
        fail(actor, jobId, parsed.rows().size(), List.of(e.error));
      }
    } catch (RuntimeException e) {
      LOG.warn("Tác vụ nhập cây tổ chức {} lỗi: {}", jobId, e.getClass().getSimpleName());
      tx.executeWithoutResult(s -> jobs.finish(jobId, "THAT_BAI", 0, 0, List.of(), null,
          "Không xử lý được tệp. Vui lòng thử lại hoặc liên hệ quản trị hệ thống.", actor));
    }
  }

  private void fail(Actor actor, UUID jobId, int rows, List<ImportError> errors) {
    tx.executeWithoutResult(s -> jobs.finish(jobId, "THAT_BAI", rows, 0, errors, null,
        "Tệp có " + errors.size() + " lỗi, chưa đơn vị nào được nhập. Sửa các dòng lỗi rồi nhập lại.", actor));
  }

  // ---------- đọc tệp ----------

  record Row(int number, Map<String, String> values) {
    String get(String column) {
      return values.getOrDefault(column, "").trim();
    }
  }

  record Parsed(List<Row> rows, List<ImportError> errors) {
  }

  Parsed parse(byte[] content) {
    List<Row> rows = new ArrayList<>();
    List<ImportError> errors = new ArrayList<>();
    Map<String, Integer>[] header = new Map[1];
    try {
      reader.read(content, (number, cells) -> {
        if (header[0] == null) {
          if (cells.stream().allMatch(String::isBlank)) {
            return;
          }
          header[0] = ImportColumns.headerIndex(cells);
          for (String col : ImportColumns.REQUIRED) {
            if (!header[0].containsKey(col)) {
              errors.add(new ImportError(number, col, "Thiếu cột \"" + col + "\" theo mẫu.", null));
            }
          }
          return;
        }
        if (cells.stream().allMatch(String::isBlank) || !errors.isEmpty()) {
          return;
        }
        if (rows.size() >= MAX_ROWS) {
          throw new TooManyRows();
        }
        Map<String, String> values = new HashMap<>();
        header[0].forEach((col, idx) -> values.put(col, idx < cells.size() ? cells.get(idx) : ""));
        rows.add(new Row(number, values));
      });
    } catch (TooManyRows e) {
      errors.add(new ImportError(MAX_ROWS + 1, ImportColumns.CODE,
          "Tệp vượt quá " + MAX_ROWS + " dòng dữ liệu cho một lần nhập.", null));
    } catch (InvalidSpreadsheetException e) {
      errors.add(new ImportError(0, "", "Tệp không đọc được như bảng tính Excel theo mẫu.", null));
    }
    if (header[0] == null && errors.isEmpty()) {
      errors.add(new ImportError(1, ImportColumns.CODE, "Tệp không có dòng tiêu đề theo mẫu.", null));
    }
    if (errors.isEmpty() && rows.isEmpty()) {
      errors.add(new ImportError(2, ImportColumns.CODE, "Tệp chưa có dòng dữ liệu nào.", null));
    }
    return new Parsed(rows, errors);
  }

  private static final class TooManyRows extends RuntimeException {
    TooManyRows() {
      super(null, null, false, false);
    }
  }

  // ---------- kiểm tra ----------

  record Candidate(int row, UUID id, OrgUnitContent content, LocalDate from, String parentCode, UUID existingParent) {
  }

  List<Candidate> validate(List<Row> rows, LocalDate eff, List<ImportError> errors) {
    Map<String, SubjectGroupRecord> groupsByCode = new HashMap<>();
    for (SubjectGroupRecord g : groups.list(eff)) {
      groupsByCode.put(ImportColumns.key(g.code()), g);
      groupsByCode.putIfAbsent(ImportColumns.key(g.name()), g);
    }
    Map<String, Integer> firstRowOfCode = new HashMap<>();
    Map<String, Candidate> byCode = new LinkedHashMap<>();
    for (Row r : rows) {
      int before = errors.size();
      String code = r.get(ImportColumns.CODE);
      String name = r.get(ImportColumns.NAME);
      String shortName = r.get(ImportColumns.SHORT_NAME);
      String level = r.get(ImportColumns.LEVEL);
      if (code.isEmpty()) {
        errors.add(err(r, ImportColumns.CODE, "Chưa nhập mã đơn vị.", null));
      } else if (code.length() > 30) {
        errors.add(err(r, ImportColumns.CODE, "Mã đơn vị dài quá 30 ký tự.", null));
      } else if (firstRowOfCode.containsKey(code)) {
        errors.add(err(r, ImportColumns.CODE, "Mã đơn vị trùng với dòng " + firstRowOfCode.get(code)
            + " trong tệp.", null));
      } else {
        firstRowOfCode.put(code, r.number());
      }
      if (name.isEmpty()) {
        errors.add(err(r, ImportColumns.NAME, "Chưa nhập tên đơn vị.", null));
      } else if (name.length() > 255) {
        errors.add(err(r, ImportColumns.NAME, "Tên đơn vị dài quá 255 ký tự.", null));
      }
      if (shortName.length() > 100) {
        errors.add(err(r, ImportColumns.SHORT_NAME, "Tên viết tắt dài quá 100 ký tự.", null));
      }
      if (level.isEmpty()) {
        errors.add(err(r, ImportColumns.LEVEL, "Chưa nhập cấp của đơn vị.", null));
      } else if (level.length() > 12) {
        errors.add(err(r, ImportColumns.LEVEL, "Cấp của đơn vị dài quá 12 ký tự.", null));
      }
      Optional<String> unitType = ImportColumns.unitType(r.get(ImportColumns.UNIT_TYPE));
      if (unitType.isEmpty()) {
        errors.add(err(r, ImportColumns.UNIT_TYPE, "Loại đơn vị không thuộc danh mục của mẫu.", null));
      }
      LocalDate from = eff;
      String rawDate = r.get(ImportColumns.VALID_FROM);
      if (!rawDate.isEmpty()) {
        Optional<LocalDate> d = ImportColumns.date(rawDate);
        if (d.isEmpty()) {
          errors.add(err(r, ImportColumns.VALID_FROM, "Ngày hiệu lực không đúng định dạng ngày/tháng/năm.", null));
        } else {
          from = d.get();
        }
      }
      SubjectGroupRecord group = groupsByCode.get(ImportColumns.key(r.get(ImportColumns.GROUP)));
      if (group == null || group.validFrom().isAfter(from)
          || (group.validTo() != null && !group.validTo().isAfter(from))) {
        errors.add(err(r, ImportColumns.GROUP,
            "Khối đối tượng không thuộc 9 khối theo Phụ lục 2A hoặc chưa có hiệu lực tại ngày hiệu lực.",
            "BR-01-05"));
      }
      List<String> flows = new ArrayList<>();
      String rawFlows = r.get(ImportColumns.FLOWS);
      if (rawFlows.isEmpty() && group != null) {
        flows.addAll("CA_HAI".equals(group.flow()) ? List.of("DANG", "NHA_NUOC") : List.of(group.flow()));
      } else {
        for (String part : rawFlows.split("[;,]")) {
          if (part.isBlank()) {
            continue;
          }
          Optional<String> f = ImportColumns.flow(part);
          if (f.isEmpty()) {
            errors.add(err(r, ImportColumns.FLOWS, "Luồng đánh giá chỉ nhận \"Đảng\" hoặc \"Nhà nước\".", null));
          } else if (!flows.contains(f.get())) {
            flows.add(f.get());
          }
        }
      }
      if (errors.size() == before && units.codeTakenByOther(code, UUID.randomUUID(), from, null)) {
        errors.add(err(r, ImportColumns.CODE, "Mã đơn vị đã có trong cây tổ chức tại ngày hiệu lực.", null));
      }
      if (errors.size() == before) {
        byCode.put(code, new Candidate(r.number(), UuidV7.next(clock), new OrgUnitContent(code, name,
            shortName.isEmpty() ? null : shortName, unitType.get(), group.id(), true, flows, level), from,
            r.get(ImportColumns.PARENT).isEmpty() ? null : r.get(ImportColumns.PARENT), null));
      }
    }
    if (!errors.isEmpty()) {
      return List.of();
    }
    // Cấp trên: trong tệp, hoặc đơn vị hiện hành có hiệu lực tại ngày hiệu lực của dòng.
    Map<String, Candidate> resolved = new LinkedHashMap<>();
    for (Candidate c : byCode.values()) {
      Candidate out = c;
      if (c.parentCode() != null && !byCode.containsKey(c.parentCode())) {
        Optional<UUID> existing = units.findIdByCode(c.parentCode(), c.from());
        if (existing.isEmpty()) {
          errors.add(new ImportError(c.row(), ImportColumns.PARENT,
              "Không tìm thấy đơn vị cấp trên có mã \"" + c.parentCode() + "\" trong tệp hoặc trong cây tổ chức.",
              null));
        } else {
          out = new Candidate(c.row(), c.id(), c.content(), c.from(), c.parentCode(), existing.get());
        }
      } else if (c.parentCode() != null) {
        Candidate parent = byCode.get(c.parentCode());
        if (parent.from().isAfter(c.from())) {
          errors.add(new ImportError(c.row(), ImportColumns.VALID_FROM,
              "Ngày hiệu lực sớm hơn ngày hiệu lực của đơn vị cấp trên ở dòng " + parent.row() + ".", null));
        }
      }
      resolved.put(c.content().code(), out);
    }
    // Vòng trong tệp: đi ngược theo cấp trên, gặp lại chính mình là vòng.
    for (Candidate c : resolved.values()) {
      Set<String> seen = new HashSet<>();
      String cur = c.content().code();
      while (cur != null && resolved.containsKey(cur) && seen.add(cur)) {
        cur = resolved.get(cur).parentCode();
      }
      if (cur != null && seen.contains(cur) && cur.equals(c.content().code())) {
        errors.add(new ImportError(c.row(), ImportColumns.PARENT,
            "Đơn vị cấp trên tạo thành vòng lặp: đơn vị không được là cấp dưới của chính nó.", null));
      }
    }
    return errors.isEmpty() ? order(resolved) : List.of();
  }

  /** Cấp trên trước cấp dưới (thứ tự dòng giữ nguyên trong cùng tầng). */
  private static List<Candidate> order(Map<String, Candidate> byCode) {
    List<Candidate> out = new ArrayList<>(byCode.size());
    Set<String> done = new HashSet<>();
    for (Candidate c : byCode.values()) {
      visit(c, byCode, done, out);
    }
    return out;
  }

  private static void visit(Candidate c, Map<String, Candidate> byCode, Set<String> done, List<Candidate> out) {
    List<Candidate> chain = new ArrayList<>();
    Candidate cur = c;
    while (cur != null && !done.contains(cur.content().code())) {
      chain.add(cur);
      cur = cur.existingParent() == null && cur.parentCode() != null ? byCode.get(cur.parentCode()) : null;
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
      done.add(chain.get(i).content().code());
      out.add(chain.get(i));
    }
  }

  // ---------- ghi ----------

  private void write(Actor actor, UUID jobId, List<Candidate> candidates, LocalDate eff, int totalRows) {
    UUID orderId = UuidV7.next(clock);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("changeType", "NHAP_EXCEL");
    payload.put("importJobId", jobId.toString());
    payload.put("createdUnitIds", candidates.stream().map(c -> c.id().toString()).toList());
    orders.insert(new ChangeOrderRecord(orderId, "NHAP-EXCEL-" + jobId.toString().substring(0, 18),
        support.today(), "DINH_CHINH", eff, null, payload, "DA_AP_DUNG", null), actor);
    Map<String, UUID> ids = new HashMap<>();
    for (Candidate c : candidates) {
      ids.put(c.content().code(), c.id());
    }
    for (Candidate c : candidates) {
      try {
        support.createUnit(c.id(), c.content(), new ValidPeriod(c.from(), null), orderId, actor);
        UUID parent = c.existingParent() != null ? c.existingParent()
            : c.parentCode() == null ? null : ids.get(c.parentCode());
        if (parent != null) {
          support.reparent(c.id(), TreeKind.defaultFor(c.content().unitType()), parent, c.from(), null, List.of(),
              orderId, actor);
        }
      } catch (OrgRuleViolation e) {
        // Dữ liệu đổi giữa lúc kiểm và lúc ghi (ví dụ mã vừa được dùng): báo đúng dòng, huỷ cả lần nhập.
        throw new RowViolation(new ImportError(c.row(), columnOf(e.field()), e.getMessage(), e.rule()));
      }
    }
    for (Candidate c : candidates) {
      support.emitUnitChanged(c.id(), orderId, actor);
    }
    jobs.finish(jobId, "HOAN_TAT", totalRows, candidates.size(), List.of(), orderId,
        "Đã nhập " + candidates.size() + " đơn vị.", actor);
  }

  private static String columnOf(String field) {
    if (field == null) {
      return ImportColumns.CODE;
    }
    return field.endsWith("parentId") ? ImportColumns.PARENT
        : field.endsWith("subjectGroupId") ? ImportColumns.GROUP : ImportColumns.CODE;
  }

  private static final class RowViolation extends RuntimeException {
    final ImportError error;

    RowViolation(ImportError error) {
      super(error.message(), null, false, false);
      this.error = error;
    }
  }

  private static ImportError err(Row r, String column, String message, String rule) {
    return new ImportError(r.number(), column, message, rule);
  }

  static ImportJobView view(JobRow r) {
    return new ImportJobView(r.id(), JOB_TYPE, r.status(), r.progress(), r.message(), r.totalRows(),
        r.createdUnits(), r.errors(), r.changeOrderId(), r.updatedAt());
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
