package vn.viettel.okrkpi.org.application.sync;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.AssignmentCommandService;
import vn.viettel.okrkpi.org.application.AssignmentCommandService.AssignCommand;
import vn.viettel.okrkpi.org.application.AssignmentCommandService.TransferCommand;
import vn.viettel.okrkpi.org.application.Cursors;
import vn.viettel.okrkpi.org.application.IdempotencyRequest;
import vn.viettel.okrkpi.org.application.port.AssignmentStore;
import vn.viettel.okrkpi.org.application.port.DiscrepancyStore;
import vn.viettel.okrkpi.org.application.port.DiscrepancyStore.Row;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.application.port.IntegrationGatewayPort;
import vn.viettel.okrkpi.org.application.port.PersonDirectory;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore.PersonRow;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.OrgConflictException;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyField;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyRules.Post;
import vn.viettel.okrkpi.org.domain.sync.Resolution;

/**
 * DV-04-07 · SCR-0104 · Danh sách lệch, xử lý hàng loạt (lấy theo nguồn, giữ nội bộ), ánh xạ thủ công, giao xử lý,
 * và số lệch mức Cao cho kiểm tra kích hoạt tenant (BR-01-10, MSG-01-12).
 */
@Service
public class DiscrepancyService {

  /** Giới hạn của service; hợp đồng giới hạn 200 mục một yêu cầu HTTP. */
  public static final int MAX_BATCH = 1_000;

  static final Map<String, String> MATCHED_BY = Map.of(
      "CCCD_NGAY_SINH", "Số định danh và ngày sinh",
      "ANH_XA", "Ánh xạ thủ công",
      "HO_TEN", "Trùng họ tên, không có số định danh khớp",
      "KHONG_KHOP", "Trùng số định danh nhưng khác ngày sinh");

  private final DiscrepancyStore store;
  private final SyncPersonStore persons;
  private final PersonDirectory directory;
  private final AssignmentStore assignments;
  private final AssignmentCommandService assignmentCommands;
  private final IntegrationGatewayPort gateway;
  private final IdempotencyPort idempotency;
  private final PolicyPort policy;
  private final SyncSupport support;
  private final TransactionTemplate perItem;

  public DiscrepancyService(DiscrepancyStore store, SyncPersonStore persons, PersonDirectory directory,
      AssignmentStore assignments, AssignmentCommandService assignmentCommands, IntegrationGatewayPort gateway,
      IdempotencyPort idempotency, PolicyPort policy, SyncSupport support, PlatformTransactionManager tm) {
    this.store = store;
    this.persons = persons;
    this.directory = directory;
    this.assignments = assignments;
    this.assignmentCommands = assignmentCommands;
    this.gateway = gateway;
    this.idempotency = idempotency;
    this.policy = policy;
    this.support = support;
    this.perItem = new TransactionTemplate(tm);
    this.perItem.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  @Transactional(readOnly = true)
  public DiscrepancyPage list(Actor actor, String field, String severity, Boolean unresolved, int limit,
      String cursor) {
    policy.authorize(actor, "listOrgDiscrepancies");
    boolean open = unresolved == null || unresolved;
    int size = Math.max(1, Math.min(limit, 200));
    UUID after = cursor == null ? null : parseCursor(cursor);
    List<Row> rows = store.page(field, severity, open, after, size + 1);
    String next = null;
    if (rows.size() > size) {
      rows = rows.subList(0, size);
      next = Cursors.encode(rows.getLast().id().toString());
    }
    Map<String, Long> counts = new LinkedHashMap<>(Map.of("CAO", 0L, "VUA", 0L, "THAP", 0L));
    counts.putAll(store.countBySeverity(field, open));
    return new DiscrepancyPage(views(actor, rows), next, counts, store.countByField(severity, open));
  }

  private static UUID parseCursor(String cursor) {
    try {
      return UUID.fromString(Cursors.decode(cursor, 1).getFirst());
    } catch (IllegalArgumentException e) {
      throw new OrgRuleViolation("COMMON-422-VALIDATION", null, null, "cursor", "Con trỏ trang không hợp lệ.");
    }
  }

  /** Số lệch mức Cao còn mở của tenant trong ngữ cảnh (BR-01-10). */
  @Transactional(readOnly = true)
  public long countOpenHigh() {
    return store.countOpenHigh();
  }

  @Transactional
  public ResolveResult resolve(Actor actor, IdempotencyRequest idem, List<UUID> ids, String resolution,
      LocalDate effectiveFrom, String note) {
    policy.authorize(actor, "resolveOrgDiscrepancies");
    Optional<ResolveResult> replay = idempotency.begin(actor, idem, ResolveResult.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (ids.isEmpty() || ids.size() > MAX_BATCH) {
      throw new OrgRuleViolation("COMMON-422-VALIDATION", null, null, "ids", "Số mục xử lý không hợp lệ.");
    }
    Resolution how = Resolution.valueOf(resolution);
    Map<UUID, Row> rows = store.findForUpdate(new HashSet<>(ids)).stream()
        .collect(Collectors.toMap(Row::id, Function.identity()));
    List<ResolveResult.Item> results = new ArrayList<>(ids.size());
    int updated = 0;
    for (UUID id : ids) {
      Row row = rows.get(id);
      if (row == null) {
        results.add(new ResolveResult.Item(id.toString(), 404, "ORG-404-NOT-FOUND", "Không tìm thấy dòng lệch."));
        continue;
      }
      if (row.resolution() != null) {
        results.add(new ResolveResult.Item(id.toString(), 409, "ORG-409-ALREADY-RESOLVED",
            "Dòng lệch đã được xử lý."));
        continue;
      }
      try {
        Integer n = perItem.execute(s -> apply(actor, row, how, effectiveFrom, note));
        updated += n == null ? 0 : n;
        results.add(new ResolveResult.Item(id.toString(), 200, null, null));
      } catch (OrgRuleViolation e) {
        results.add(new ResolveResult.Item(id.toString(), 422, e.code(), e.getMessage()));
      } catch (OrgConflictException e) {
        results.add(new ResolveResult.Item(id.toString(), 409, e.code(), e.getMessage()));
      }
    }
    ResolveResult result = new ResolveResult(results, updated);
    idempotency.complete(actor, idem, 200, result, null);
    return result;
  }

  private int apply(Actor actor, Row row, Resolution how, LocalDate effectiveFrom, String note) {
    int updated = 0;
    switch (how) {
      case NHAN_NGUON -> updated = takeSource(actor, row, effectiveFrom);
      case GIU_HIEN_TAI, BO_QUA -> { }
      case SUA_TAY -> throw new OrgRuleViolation("ORG-422-USE-MAPPING", "BR-01-12", null, "resolution",
          "Đối soát thủ công thực hiện bằng ánh xạ bản ghi người.");
      default -> throw new IllegalStateException(how.name());
    }
    store.resolve(row.id(), how.name(), note, actor);
    return updated;
  }

  /** Lấy theo nguồn: ghi phiên bản mới với {@code valid_from} theo quyết định nguồn, phát EVT-15 hoặc EVT-16. */
  private int takeSource(Actor actor, Row row, LocalDate effectiveFrom) {
    UUID tenant = actor.tenantId();
    DiscrepancyField field = DiscrepancyField.ofCode(row.field());
    if (field == DiscrepancyField.PERSON_MATCH || row.personId() == null) {
      throw new OrgRuleViolation("ORG-422-NEED-MAPPING", "BR-01-12", "MSG-01-15", "resolution",
          "Hai hồ sơ trùng tên khác ngày sinh. Vui lòng đối soát thủ công.");
    }
    Object source = support.values.decode(
        support.decrypt(tenant, SyncSupport.COL_SOURCE, row.id(), row.dekId(), row.sourceEnc()));
    Object current = support.values.decode(
        support.decrypt(tenant, SyncSupport.COL_CURRENT, row.id(), row.dekId(), row.currentEnc()));
    LocalDate sourceDate = source instanceof Post p ? p.validFrom() : row.effectiveFrom();
    if (effectiveFrom != null && sourceDate != null && effectiveFrom.isAfter(sourceDate)) {
      throw new OrgRuleViolation("ORG-422-EFFECTIVE-AFTER-SOURCE", "BR-01-12", null, "effectiveFrom",
          "Ngày hiệu lực không được sau ngày hiệu lực ghi trong nguồn.");
    }
    LocalDate eff = effectiveFrom != null ? effectiveFrom : sourceDate != null ? sourceDate : support.today();
    String branch = SyncIngestService.branchOf(row.connector());
    UUID person = row.personId();
    switch (field) {
      case ORG_UNIT, JOB_TITLE -> {
        Post s = (Post) source;
        assignmentCommands.transferPrimaryAssignment(actor, new TransferCommand(person, s.orgUnitId(), null,
            s.jobTitleId(), branch, s.head(), eff, null, null, null, null, null, null));
        return 1;
      }
      case CONCURRENT_ASSIGNMENT -> {
        if (source instanceof Post s) {
          assignmentCommands.recordAssignment(actor, new AssignCommand(person, s.orgUnitId(), null, s.jobTitleId(),
              s.kind(), branch, s.head(), eff, null, false, null, null, null, null, null));
        } else if (current instanceof Post c && c.assignmentId() != null) {
          assignmentCommands.endAssignment(actor, c.assignmentId(), eff, null);
        }
        return 1;
      }
      case EMPLOYMENT_STATUS -> {
        String status = (String) source;
        persons.changeFrom(person, eff, status, null, actor);
        support.emitPersonChanged(person, actor);
        int ended = 0;
        if ("NGHI_HUU".equals(status) || "THOI_VIEC".equals(status)) {
          for (AssignmentVersion a : assignments.findCurrentByPersonAndBranch(person, branch)) {
            if (a.validPeriod().to() == null || a.validPeriod().to().isAfter(eff)) {
              assignmentCommands.endAssignment(actor, a.id(), eff, null);
              ended++;
            }
          }
        }
        return ended;
      }
      case PARTY_MEMBERSHIP -> {
        persons.changeFrom(person, eff, null, (Boolean) source, actor);
        support.emitPersonChanged(person, actor);
        return 0;
      }
      default -> throw new IllegalStateException(field.name());
    }
  }

  /** Ánh xạ thủ công dòng "không khớp người" vào người đúng, ghi {@code integ.mapping_xref}, phát EVT-16. */
  @Transactional
  public DiscrepancyView map(Actor actor, IdempotencyRequest idem, UUID id, UUID personId) {
    policy.authorize(actor, "mapOrgDiscrepancy");
    Optional<DiscrepancyView> replay = idempotency.begin(actor, idem, DiscrepancyView.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    Row row = lockOne(id);
    if (!DiscrepancyField.PERSON_MATCH.code().equals(row.field())) {
      throw new OrgRuleViolation("ORG-422-NOT-PERSON-MATCH", "BR-01-12", null, "id",
          "Chỉ ánh xạ thủ công dòng không khớp người.");
    }
    if (row.resolution() != null) {
      throw new OrgConflictException("ORG-409-ALREADY-RESOLVED", "Dòng lệch đã được xử lý.");
    }
    requirePerson(personId, "personId");
    gateway.mapXref(actor.tenantId(), row.connector(), row.externalId(), personId);
    store.mapPerson(id, personId, actor);
    store.resolve(id, Resolution.SUA_TAY.name(), null, actor);
    support.emitPersonChanged(personId, actor);
    DiscrepancyView view = views(actor, List.of(lockOne(id))).getFirst();
    idempotency.complete(actor, idem, 200, view, id.toString());
    return view;
  }

  /** Giao cán bộ tổ chức đơn vị kiểm tra một dòng lệch, phát EVT-21. */
  @Transactional
  public DiscrepancyView assign(Actor actor, IdempotencyRequest idem, UUID id, UUID assigneeId, String note) {
    policy.authorize(actor, "assignOrgDiscrepancy");
    Optional<DiscrepancyView> replay = idempotency.begin(actor, idem, DiscrepancyView.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    lockOne(id);
    requirePerson(assigneeId, "assigneeId");
    store.assign(id, assigneeId, note, actor);
    support.emitAssignedNotice(id, assigneeId, actor);
    DiscrepancyView view = views(actor, List.of(lockOne(id))).getFirst();
    idempotency.complete(actor, idem, 200, view, id.toString());
    return view;
  }

  private Row lockOne(UUID id) {
    return store.findForUpdate(List.of(id)).stream().findFirst()
        .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy dòng lệch."));
  }

  private void requirePerson(UUID personId, String field) {
    if (persons.findById(personId, support.today()).isEmpty()) {
      throw new OrgRuleViolation("ORG-422-PERSON-NOT-FOUND", null, null, field, "Không tìm thấy cán bộ.");
    }
  }

  private List<DiscrepancyView> views(Actor actor, List<Row> rows) {
    UUID tenant = actor.tenantId();
    Map<UUID, Object[]> decoded = new HashMap<>();
    Set<UUID> unitIds = new HashSet<>();
    Set<UUID> titleIds = new HashSet<>();
    Set<UUID> people = new HashSet<>();
    for (Row r : rows) {
      Object s = support.values.decode(support.decrypt(tenant, SyncSupport.COL_SOURCE, r.id(), r.dekId(),
          r.sourceEnc()));
      Object c = support.values.decode(support.decrypt(tenant, SyncSupport.COL_CURRENT, r.id(), r.dekId(),
          r.currentEnc()));
      decoded.put(r.id(), new Object[] {s, c});
      for (Object o : new Object[] {s, c}) {
        if (o instanceof Post p) {
          unitIds.add(p.orgUnitId());
          titleIds.add(p.jobTitleId());
        }
      }
      if (r.personId() != null) {
        people.add(r.personId());
      }
      if (r.assigneeId() != null) {
        people.add(r.assigneeId());
      }
    }
    Map<UUID, String> units = unitIds.isEmpty() ? Map.of() : store.unitNames(unitIds);
    Map<UUID, String> titles = titleIds.isEmpty() ? Map.of() : store.jobTitleNames(titleIds);
    Map<UUID, String> names = people.isEmpty() ? Map.of() : directory.currentNames(people);
    Map<UUID, String> masked = maskedIds(tenant, rows.stream().map(Row::personId).filter(p -> p != null).toList());
    List<DiscrepancyView> out = new ArrayList<>(rows.size());
    for (Row r : rows) {
      Object[] v = decoded.get(r.id());
      String personName = r.personId() != null ? names.get(r.personId())
          : v[0] instanceof SyncValues.Identity i ? i.fullName() : null;
      out.add(new DiscrepancyView(r.id(), r.personId(), personName, masked.get(r.personId()), r.field(),
          r.connector(), SyncValues.label(v[0], units, titles), SyncValues.label(v[1], units, titles), r.severity(),
          r.suggestedAction(), r.resolution(), r.resolvedAt(),
          r.assigneeId() == null ? null : names.get(r.assigneeId()), r.externalId(),
          r.matchedBy() == null ? null : MATCHED_BY.getOrDefault(r.matchedBy(), r.matchedBy()), r.effectiveFrom(),
          r.syncRunId()));
    }
    return out;
  }

  private Map<UUID, String> maskedIds(UUID tenant, Collection<UUID> personIds) {
    Map<UUID, String> out = new HashMap<>();
    LocalDate today = support.today();
    for (UUID id : new HashSet<>(personIds)) {
      Optional<PersonRow> p = persons.findById(id, today);
      p.map(row -> SyncValues.maskNationalId(support.nationalId(tenant, row))).ifPresent(m -> out.put(id, m));
    }
    return out;
  }
}
