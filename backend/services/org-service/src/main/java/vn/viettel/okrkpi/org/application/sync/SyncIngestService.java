package vn.viettel.okrkpi.org.application.sync;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.AssignmentCommandService;
import vn.viettel.okrkpi.org.application.AssignmentCommandService.AssignCommand;
import vn.viettel.okrkpi.org.application.port.AssignmentStore;
import vn.viettel.okrkpi.org.application.port.DiscrepancyStore;
import vn.viettel.okrkpi.org.application.port.DiscrepancyStore.Row;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor.Encrypted;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore.NewPerson;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore.PersonRow;
import vn.viettel.okrkpi.org.application.port.SyncRunStore;
import vn.viettel.okrkpi.org.application.sync.SourceRecord.SourcePost;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyField;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyRules;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyRules.Finding;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyRules.PersonState;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyRules.Post;
import vn.viettel.okrkpi.org.domain.sync.Resolution;

/**
 * DV-04-07 · QT-01 bước 7 · Bộ so khớp đồng bộ nhân sự: nhận lô bản ghi nguồn đã chuẩn hoá từ integration-gateway
 * (BR-01-11), khớp người bằng chỉ mục mù số định danh và ngày sinh, ghi người mới, ghi dòng lệch cho người đã có.
 * <ul>
 *   <li>Người chưa có ở nền tảng và không trùng họ tên: tạo {@code org.person} và phân công, phát EVT-16, EVT-15.
 *   <li>Người đã khớp: không tự ghi đè; mọi khác biệt thành dòng lệch để quản trị tenant quyết định (suy luận, ghi ở
 *       wiki trang 14).
 *   <li>Trùng số định danh khác ngày sinh, hoặc trùng họ tên khác ngày sinh: lệch Cao "không khớp người" (MSG-01-15).
 *   <li>Dòng đang "giữ dữ liệu nội bộ" chỉ mở lại khi giá trị nguồn đổi (so bằng HMAC {@code source_fingerprint}).
 * </ul>
 * Mỗi bản ghi chạy trong một điểm lưu (savepoint): bản ghi lỗi được đếm vào {@code errors}, không làm hỏng cả lô.
 */
@Service
public class SyncIngestService {

  private static final Logger LOG = LoggerFactory.getLogger(SyncIngestService.class);

  /** Kết quả xử lý một lô. */
  public record BatchResult(int records, int changes, int errors, int discrepancies) {
  }

  private final SyncPersonStore persons;
  private final AssignmentStore assignments;
  private final AssignmentCommandService assignmentCommands;
  private final DiscrepancyStore discrepancies;
  private final SyncRunStore runs;
  private final SyncSupport support;
  private final TransactionTemplate perRecord;

  public SyncIngestService(SyncPersonStore persons, AssignmentStore assignments,
      AssignmentCommandService assignmentCommands, DiscrepancyStore discrepancies, SyncRunStore runs,
      SyncSupport support, PlatformTransactionManager tm) {
    this.persons = persons;
    this.assignments = assignments;
    this.assignmentCommands = assignmentCommands;
    this.discrepancies = discrepancies;
    this.runs = runs;
    this.support = support;
    this.perRecord = new TransactionTemplate(tm);
    this.perRecord.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  /** Nhánh phân công mà nguồn quản lý: CSDL đảng viên quản nhánh Đảng, các nguồn còn lại nhánh chính quyền. */
  static String branchOf(String connector) {
    return "DANG_VIEN".equals(connector) ? "DANG" : "CHINH_QUYEN";
  }

  /**
   * Xử lý một lô của lần chạy {@code runId}. Lần chạy chưa có (lịch của gateway tự khởi động) thì được tạo.
   *
   * @param last lô cuối: đóng lần chạy với số thư chết và lý do lỗi (nếu có)
   */
  @Transactional
  public BatchResult ingest(Actor actor, UUID runId, String connector, List<SourceRecord> records, boolean last,
      int deadLetters, String reason) {
    Optional<SyncRunStore.Run> existing = runs.find(runId);
    if (existing.isEmpty()) {
      runs.start(runId, connector, actor);
    } else if (!"DANG_CHAY".equals(existing.get().status())) {
      throw new vn.viettel.okrkpi.org.domain.OrgConflictException("ORG-409-SYNC-RUN-CLOSED",
          "Lần đồng bộ đã kết thúc, không nhận thêm dữ liệu.");
    }
    int changes = 0;
    int errors = 0;
    int found = 0;
    for (SourceRecord r : records) {
      try {
        Outcome o = perRecord.execute(s -> one(actor, runId, connector, r));
        changes += o.changed() ? 1 : 0;
        found += o.discrepancies();
      } catch (RuntimeException e) {
        errors++;
        // Không ghi mã ngoài hay giá trị bản ghi vào nhật ký: chỉ loại lỗi.
        LOG.warn("Bản ghi đồng bộ lỗi ở lần chạy {} ({}): {}", runId, connector, e.getClass().getSimpleName());
      }
    }
    runs.addBatch(runId, records.size(), changes, errors, actor);
    if (last) {
      SyncRunStore.Run run = runs.find(runId).orElseThrow();
      boolean failed = reason != null && !reason.isBlank();
      String status = failed && run.records() == 0 ? "LOI"
          : failed || run.errors() > 0 || deadLetters > 0 ? "THANH_CONG_MOT_PHAN" : "THANH_CONG";
      runs.finish(runId, status, deadLetters, reason, actor);
    }
    return new BatchResult(records.size(), changes, errors, found);
  }

  // ---------- DV-04-06: dùng lại bộ so khớp cho từng lệnh SCIM ----------

  /** Kết quả khớp một bản ghi đơn lẻ: người đã có, hoặc nghi vấn cần đối soát, hoặc chưa có. */
  public record PersonMatch(UUID personId, boolean mismatch, String matchedBy) {
  }

  @Transactional
  public PersonMatch matchPerson(Actor actor, SourceRecord r) {
    Match m = match(actor.tenantId(), r, support.today());
    return new PersonMatch(m.person() == null ? null : m.person().id(), m.mismatch(), m.by());
  }

  /** Tạo người mới từ bản ghi nguồn (số định danh, ngày sinh mã hoá trường), phát EVT-16 và EVT-15 nếu có phân công. */
  @Transactional
  public UUID createPerson(Actor actor, String connector, SourceRecord r) {
    return create(actor, connector, r, support.today());
  }

  /**
   * Đẩy bản ghi không khớp chắc chắn vào bảng lệch (lệch Cao "không khớp người") dưới một lần chạy riêng đã đóng của
   * nguồn; trả mã dòng lệch.
   */
  @Transactional
  public UUID recordUnmatched(Actor actor, String connector, SourceRecord r, String matchedBy) {
    UUID runId = UuidV7.next(support.clock());
    runs.start(runId, connector, actor);
    record(actor, runId, connector, r, null, DiscrepancyRules.personMismatch(r.effectiveFrom()),
        matchedBy == null ? "KHONG_KHOP" : matchedBy, new SyncValues.Identity(r.fullName(), r.birthDate()));
    runs.addBatch(runId, 1, 0, 0, actor);
    runs.finish(runId, "THANH_CONG_MOT_PHAN", 0, null, actor);
    return discrepancies.findLatestActive(connector, r.externalId(), DiscrepancyField.PERSON_MATCH.code())
        .map(Row::id).orElse(null);
  }

  /**
   * Ghi trạng thái công tác, đảng viên từ hôm nay khi khác giá trị hiện hành; phát EVT-16. Trả true khi có thay đổi.
   */
  @Transactional
  public boolean applyPersonState(Actor actor, UUID personId, String employmentStatus, Boolean partyMember) {
    LocalDate today = support.today();
    PersonRow p = persons.findById(personId, today)
        .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy cán bộ."));
    String status = employmentStatus == null || employmentStatus.equals(p.employmentStatus()) ? null
        : employmentStatus;
    Boolean party = partyMember == null || partyMember == p.partyMember() ? null : partyMember;
    if (status == null && party == null) {
      return false;
    }
    persons.changeFrom(personId, today, status, party, actor);
    support.emitPersonChanged(personId, actor);
    return true;
  }

  private record Outcome(boolean changed, int discrepancies) {
  }

  private Outcome one(Actor actor, UUID runId, String connector, SourceRecord r) {
    Objects.requireNonNull(r.externalId(), "externalId");
    UUID tenant = actor.tenantId();
    LocalDate today = support.today();
    Match match = match(tenant, r, today);
    if (match.mismatch()) {
      record(actor, runId, connector, r, null, DiscrepancyRules.personMismatch(r.effectiveFrom()), "KHONG_KHOP",
          new SyncValues.Identity(r.fullName(), r.birthDate()));
      return new Outcome(false, 1);
    }
    if (match.person() == null) {
      create(actor, connector, r, today);
      return new Outcome(true, 0);
    }
    PersonRow p = match.person();
    String branch = branchOf(connector);
    List<Post> current = new ArrayList<>();
    for (AssignmentVersion a : assignments.findCurrentByPersonAndBranch(p.id(), branch)) {
      if (a.validPeriod().to() == null || a.validPeriod().to().isAfter(today)) {
        current.add(new Post(a.id(), a.orgUnitId(), a.jobTitleId(), a.kind(), a.head(), a.validPeriod().from()));
      }
    }
    List<Post> source = r.posts().stream()
        .map(sp -> new Post(null, sp.orgUnitId(), sp.jobTitleId(), sp.kind(), sp.head(), sp.validFrom())).toList();
    List<Finding> findings = DiscrepancyRules.compare(
        new PersonState(r.employmentStatus(), r.partyMember(), source),
        new PersonState(p.employmentStatus(), p.partyMember(), current), today);
    int n = 0;
    for (Finding f : findings) {
      n += record(actor, runId, connector, r, p.id(), f, match.by(), null) ? 1 : 0;
    }
    return new Outcome(false, n);
  }

  private record Match(PersonRow person, boolean mismatch, String by) {
  }

  private Match match(UUID tenant, SourceRecord r, LocalDate today) {
    if (r.mappedPersonId() != null) {
      PersonRow p = persons.findById(r.mappedPersonId(), today)
          .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy cán bộ đã ánh xạ."));
      return new Match(p, false, "ANH_XA");
    }
    if (r.nationalId() != null && !r.nationalId().isBlank()) {
      List<PersonRow> byId = persons.findByNationalIdIndex(support.nationalIdIndex(tenant, r.nationalId()), today);
      for (PersonRow p : byId) {
        if (Objects.equals(support.birthDate(tenant, p), r.birthDate())) {
          return new Match(p, false, "CCCD_NGAY_SINH");
        }
      }
      if (!byId.isEmpty()) {
        return new Match(null, true, "KHONG_KHOP");
      }
    }
    // Không có số định danh khớp: trùng họ tên là nghi vấn cùng người hoặc hai người khác nhau, không tự khớp.
    if (!persons.findByFullName(r.fullName(), today).isEmpty()) {
      return new Match(null, true, "HO_TEN");
    }
    return new Match(null, false, null);
  }

  private UUID create(Actor actor, String connector, SourceRecord r, LocalDate today) {
    UUID tenant = actor.tenantId();
    UUID id = UuidV7.next(support.clock());
    Encrypted birth = support.encrypt(tenant, SyncSupport.COL_BIRTH, id,
        r.birthDate() == null ? null : r.birthDate().toString().getBytes(StandardCharsets.UTF_8));
    String nid = SyncSupport.normalizeNationalId(r.nationalId());
    Encrypted national = support.encrypt(tenant, SyncSupport.COL_NATIONAL, id,
        nid == null || nid.isBlank() ? null : nid.getBytes(StandardCharsets.UTF_8));
    UUID dek = birth != null ? birth.dekId() : national != null ? national.dekId() : null;
    if (birth != null && national != null && !birth.dekId().equals(national.dekId())) {
      throw new IllegalStateException("Hai cột C2 của một phiên bản phải dùng cùng DEK");
    }
    LocalDate from = r.posts().stream().map(SourcePost::validFrom).filter(Objects::nonNull).min(LocalDate::compareTo)
        .orElse(today);
    persons.insert(new NewPerson(UuidV7.next(support.clock()), id, r.fullName(), dek,
        birth == null ? null : birth.ciphertext(), national == null ? null : national.ciphertext(),
        nid == null || nid.isBlank() ? null : support.nationalIdIndex(tenant, nid), r.partyMember(),
        r.employmentStatus(), from), actor);
    support.emitPersonChanged(id, actor);
    String branch = branchOf(connector);
    for (SourcePost sp : r.posts()) {
      assignmentCommands.recordAssignment(actor, new AssignCommand(id, sp.orgUnitId(), null, sp.jobTitleId(),
          sp.kind(), branch, sp.head(), sp.validFrom() == null ? from : sp.validFrom(), null, false, null, null, null,
          null, null));
    }
    return id;
  }

  /** Ghi hoặc làm mới một dòng lệch; trả false khi bỏ qua vì đang giữ nội bộ và nguồn chưa đổi. */
  private boolean record(Actor actor, UUID runId, String connector, SourceRecord r, UUID personId, Finding f,
      String matchedBy, Object sourceOverride) {
    UUID tenant = actor.tenantId();
    String field = f.field().code();
    byte[] source = support.values.encode(sourceOverride != null ? sourceOverride : f.source());
    byte[] fp = support.fingerprint(tenant, field, source);
    Optional<Row> active = discrepancies.findLatestActive(connector, r.externalId(), field);
    if (active.isPresent() && Resolution.GIU_HIEN_TAI.name().equals(active.get().resolution())
        && Arrays.equals(active.get().fingerprint(), fp)) {
      return false;
    }
    boolean reuse = active.isPresent() && active.get().resolution() == null;
    UUID id = reuse ? active.get().id() : UuidV7.next(support.clock());
    Encrypted src = support.encrypt(tenant, SyncSupport.COL_SOURCE, id, source);
    Encrypted cur = support.encrypt(tenant, SyncSupport.COL_CURRENT, id, support.values.encode(f.current()));
    UUID dek = src != null ? src.dekId() : cur != null ? cur.dekId() : null;
    Row row = new Row(id, personId, field, connector, dek, src == null ? null : src.ciphertext(),
        cur == null ? null : cur.ciphertext(), f.severity().name(), f.action().name(), null, null, null, runId,
        r.externalId(), f.effectiveFrom() != null ? f.effectiveFrom() : r.effectiveFrom(), matchedBy, null, null, fp,
        reuse ? active.get().rowVersion() : 0);
    if (reuse) {
      discrepancies.refresh(row, actor);
    } else {
      discrepancies.insert(row, actor);
    }
    return true;
  }
}
