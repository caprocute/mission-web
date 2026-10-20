package vn.viettel.okrkpi.org.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.port.ChangeOrderStore;
import vn.viettel.okrkpi.org.application.port.OrgUnitStore;
import vn.viettel.okrkpi.org.application.port.PersonDirectory;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.application.port.RelationStore;
import vn.viettel.okrkpi.org.application.port.SubjectGroupStore;
import vn.viettel.okrkpi.org.application.port.TreeQueryPort;
import vn.viettel.okrkpi.org.application.port.TreeQueryPort.TreeRow;
import vn.viettel.okrkpi.org.domain.OrgChangeType;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.domain.UnitRelationVersion;

/**
 * Đọc đơn vị và cây theo hai trục thời gian (QT-01, SCR-0103): mặc định là hôm nay theo hiểu biết hiện tại; với
 * {@code asOf} lọc {@code valid_period @> asOf}, với {@code recordedAsOf} lọc {@code recorded_period @> recordedAsOf}.
 */
@Service
@Transactional(readOnly = true)
public class OrgUnitQueryService {

  /** Cây mặc định khi hợp đồng không nêu (suy luận): cây chuyên môn. */
  static final TreeKind DEFAULT_TREE = TreeKind.CHUYEN_MON;

  private final OrgUnitStore units;
  private final RelationStore relations;
  private final TreeQueryPort trees;
  private final SubjectGroupStore groups;
  private final ChangeOrderStore orders;
  private final PersonDirectory persons;
  private final PolicyPort policy;
  private final UnitSupport support;
  private final Clock clock;

  public OrgUnitQueryService(OrgUnitStore units, RelationStore relations, TreeQueryPort trees,
      SubjectGroupStore groups, ChangeOrderStore orders, PersonDirectory persons, PolicyPort policy,
      UnitSupport support, Clock clock) {
    this.units = units;
    this.relations = relations;
    this.trees = trees;
    this.groups = groups;
    this.orders = orders;
    this.persons = persons;
    this.policy = policy;
    this.support = support;
    this.clock = clock;
  }

  public OrgUnitView getUnit(Actor actor, UUID id, LocalDate asOf, OffsetDateTime recordedAsOf) {
    policy.authorize(actor, "getOrgUnit");
    TemporalQuery query = support.resolve(asOf, recordedAsOf);
    OrgUnitVersion version = units.findAt(id, query)
        .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy đơn vị tại thời điểm hỏi."));
    return support.view(version, query);
  }

  public TreeView getTree(Actor actor, LocalDate asOf, OffsetDateTime recordedAsOf, TreeKind tree, int depth) {
    policy.authorize(actor, "getOrgTree");
    TemporalQuery query = support.resolve(asOf, recordedAsOf);
    List<TreeRow> rows = trees.forest(tree == null ? DEFAULT_TREE : tree, query, depth);
    return new TreeView(query.asOf(), recordedAsOfOrNow(recordedAsOf), readOnly(asOf, recordedAsOf),
        build(rows, null));
  }

  public TreeView getSubtree(Actor actor, UUID id, LocalDate asOf, OffsetDateTime recordedAsOf, int depth) {
    policy.authorize(actor, "getOrgSubtree");
    TemporalQuery query = support.resolve(asOf, recordedAsOf);
    OrgUnitVersion root = units.findAt(id, query)
        .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy đơn vị tại thời điểm hỏi."));
    TreeKind tree = support.treeOf(id, root.content().unitType(), query);
    List<TreeRow> rows = trees.subtree(tree, id, query, depth);
    List<TreeView.Node> children = build(rows, id);
    TreeView.Node node = new TreeView.Node(id, root.content().code(), root.content().name(),
        root.content().unitType(), !children.isEmpty(), children);
    return new TreeView(query.asOf(), recordedAsOfOrNow(recordedAsOf), readOnly(asOf, recordedAsOf), List.of(node));
  }

  public List<SearchHit> search(Actor actor, String text, LocalDate asOf, OffsetDateTime recordedAsOf, TreeKind tree,
      int limit) {
    policy.authorize(actor, "searchOrgUnits");
    TemporalQuery query = support.resolve(asOf, recordedAsOf);
    List<SearchHit> hits = new ArrayList<>();
    for (OrgUnitStore.UnitSearchRow row : units.search(text.trim(), query, limit)) {
      TreeKind kind = tree != null ? tree : support.treeOf(row.id(), row.unitType(), query);
      hits.add(new SearchHit(row.id(), row.code(), row.name(), row.unitType(), trees.ancestors(kind, row.id(), query)));
    }
    return hits;
  }

  /**
   * Đơn vị theo phạm vi người dùng: {@code unit} là đơn vị của ngữ cảnh và đơn vị ngang cấp, {@code children} là
   * đơn vị cấp dưới trực tiếp. Không có đơn vị trong ngữ cảnh thì trả rỗng.
   */
  public Page<UnitRef> listUnits(Actor actor, String scope, String text, int limit, String cursor) {
    policy.authorize(actor, "listOrgUnits");
    if (actor.unitId() == null) {
      return new Page<>(List.of(), null);
    }
    TemporalQuery query = support.resolve(null, null);
    OrgUnitVersion own = units.findAt(actor.unitId(), query).orElse(null);
    if (own == null) {
      return new Page<>(List.of(), null);
    }
    TreeKind tree = support.treeOf(own.id(), own.content().unitType(), query);
    List<UnitRef> refs = new ArrayList<>();
    if ("children".equals(scope)) {
      for (TreeRow r : trees.subtree(tree, own.id(), query, 1)) {
        refs.add(new UnitRef(r.id(), r.name(), own.id()));
      }
    } else {
      Optional<UnitRelationVersion> parent = relations.parentAt(own.id(), tree, query);
      if (parent.isPresent()) {
        UUID parentId = parent.get().parentUnitId();
        for (TreeRow r : trees.subtree(tree, parentId, query, 1)) {
          refs.add(new UnitRef(r.id(), r.name(), parentId));
        }
      } else {
        refs.add(new UnitRef(own.id(), own.content().name(), null));
      }
    }
    String folded = text == null || text.isBlank() ? null : TextFold.fold(text);
    List<UnitRef> filtered = refs.stream()
        .filter(r -> folded == null || TextFold.fold(r.name()).contains(folded))
        .sorted(Comparator.comparing(UnitRef::name).thenComparing(UnitRef::id))
        .toList();
    return paginate(filtered, limit, cursor, 2, r -> new String[] {r.name(), r.id().toString()},
        (r, keys) -> compare(r.name(), r.id().toString(), keys) > 0);
  }

  public List<SubjectGroupRecord> listSubjectGroups(Actor actor, LocalDate asOf) {
    policy.authorize(actor, "listSubjectGroups");
    return groups.list(asOf);
  }

  /** Lịch sử phiên bản trên trục hiệu lực, mới nhất trước (BR-01-06). */
  public Page<OrgUnitHistoryItem> history(Actor actor, UUID id, OffsetDateTime recordedAsOf, int limit,
      String cursor) {
    policy.authorize(actor, "listOrgUnitHistory");
    List<OrgUnitVersion> all = units.allVersions(id).stream()
        .filter(v -> recordedAsOf == null || !v.recordedFrom().isAfter(recordedAsOf))
        .toList();
    if (all.isEmpty()) {
      throw new OrgNotFoundException("Không tìm thấy đơn vị.");
    }
    List<UnitRelationVersion> parentRels = relations.parentHistory(id);
    TreeKind tree = support.treeOf(id, all.get(all.size() - 1).content().unitType(),
        support.resolve(null, recordedAsOf));
    Set<UUID> names = new HashSet<>();
    Set<UUID> orderIds = new HashSet<>();
    Set<UUID> groupIds = new HashSet<>();
    Set<UUID> actorIds = new HashSet<>();
    for (OrgUnitVersion v : all) {
      parentOf(v, parentRels, tree).ifPresent(names::add);
      if (v.changeOrderId() != null) {
        orderIds.add(v.changeOrderId());
      }
      if (v.content().subjectGroupId() != null) {
        groupIds.add(v.content().subjectGroupId());
      }
      if (v.createdBy() != null) {
        actorIds.add(v.createdBy());
      }
    }
    TemporalQuery now = support.resolve(null, null);
    Map<UUID, String> unitNames = new LinkedHashMap<>();
    units.findAllAt(names, now).forEach((k, v) -> unitNames.put(k, v.content().name()));
    for (UUID missing : names) {
      if (!unitNames.containsKey(missing)) {
        units.allVersions(missing).stream().reduce((a, b) -> b)
            .ifPresent(v -> unitNames.put(missing, v.content().name()));
      }
    }
    Map<UUID, ChangeOrderRecord> orderMap = orders.findByIds(orderIds);
    Map<UUID, String> groupNames = groups.names(groupIds);
    Map<UUID, String> actorNames = persons.currentNames(actorIds);

    List<OrgUnitVersion> sorted = new ArrayList<>(all);
    sorted.sort(Comparator.comparing((OrgUnitVersion v) -> v.validPeriod().from()).reversed()
        .thenComparing(Comparator.comparing(OrgUnitVersion::recordedFrom).reversed())
        .thenComparing(OrgUnitVersion::versionId));
    List<OrgUnitHistoryItem> items = new ArrayList<>();
    for (OrgUnitVersion v : sorted) {
      UUID parentId = parentOf(v, parentRels, tree).orElse(null);
      OrgUnitVersion previous = predecessor(v, all);
      List<String> changed = previous == null ? List.of()
          : changedFields(previous, v, parentOf(previous, parentRels, tree).orElse(null), parentId);
      ChangeOrderRecord order = v.changeOrderId() == null ? null : orderMap.get(v.changeOrderId());
      OffsetDateTime recordedTo = v.recordedTo();
      if (recordedTo != null && recordedAsOf != null && recordedTo.isAfter(recordedAsOf)) {
        recordedTo = null;
      }
      items.add(new OrgUnitHistoryItem(v, parentId == null ? null : unitNames.get(parentId),
          v.content().subjectGroupId() == null ? null : groupNames.get(v.content().subjectGroupId()),
          v.createdBy() == null ? null : actorNames.get(v.createdBy()), recordedTo, changeTypeOf(order), order,
          changed));
    }
    return paginate(items, limit, cursor, 3,
        i -> new String[] {i.version().validPeriod().from().toString(), i.version().recordedFrom().toString(),
            i.version().versionId().toString()},
        (i, keys) -> {
          int c = keys.get(0).compareTo(i.version().validPeriod().from().toString());
          if (c != 0) {
            return c > 0;
          }
          int d = OffsetDateTime.parse(keys.get(1)).compareTo(i.version().recordedFrom());
          if (d != 0) {
            return d > 0;
          }
          return i.version().versionId().toString().compareTo(keys.get(2)) > 0;
        });
  }

  private static OrgChangeType changeTypeOf(ChangeOrderRecord order) {
    if (order == null || order.payload() == null || order.payload().get("changeType") == null) {
      return null;
    }
    return OrgChangeType.valueOf(order.payload().get("changeType").toString());
  }

  /** Cấp trên của phiên bản: quan hệ chứa ngày bắt đầu hiệu lực, như đã biết lúc phiên bản được ghi nhận. */
  private static Optional<UUID> parentOf(OrgUnitVersion v, List<UnitRelationVersion> rels, TreeKind tree) {
    return rels.stream()
        .filter(r -> r.tree() == tree && r.validPeriod().contains(v.validPeriod().from()))
        .filter(r -> !r.recordedFrom().isAfter(v.recordedFrom())
            && (r.recordedTo() == null || r.recordedTo().isAfter(v.recordedFrom())))
        .map(UnitRelationVersion::parentUnitId)
        .findFirst();
  }

  /**
   * Phiên bản trước để so trường đổi: ưu tiên phiên bản bị đính chính (đóng ghi nhận đúng lúc phiên bản này bắt đầu
   * và cùng ngày bắt đầu hiệu lực), nếu không thì phiên bản liền trước trên trục hiệu lực theo cùng hiểu biết (suy
   * luận).
   */
  private static OrgUnitVersion predecessor(OrgUnitVersion v, List<OrgUnitVersion> all) {
    LocalDate dayBefore = v.validPeriod().from().minusDays(1);
    OrgUnitVersion temporal = null;
    for (OrgUnitVersion p : all) {
      if (p == v) {
        continue;
      }
      if (Objects.equals(p.recordedTo(), v.recordedFrom())
          && p.validPeriod().from().equals(v.validPeriod().from())) {
        return p;
      }
      boolean knownThen = !p.recordedFrom().isAfter(v.recordedFrom())
          && (p.recordedTo() == null || p.recordedTo().isAfter(v.recordedFrom()));
      boolean knownJustBefore = p.recordedTo() != null && p.recordedTo().equals(v.recordedFrom());
      if (p.validPeriod().contains(dayBefore) && (knownThen || knownJustBefore)) {
        temporal = p;
      }
    }
    return temporal;
  }

  static List<String> changedFields(OrgUnitVersion before, OrgUnitVersion after, UUID parentBefore,
      UUID parentAfter) {
    List<String> fields = new ArrayList<>();
    var a = before.content();
    var b = after.content();
    if (!Objects.equals(a.code(), b.code())) {
      fields.add("code");
    }
    if (!Objects.equals(a.name(), b.name())) {
      fields.add("name");
    }
    if (!Objects.equals(a.shortName(), b.shortName())) {
      fields.add("shortName");
    }
    if (!Objects.equals(a.level(), b.level())) {
      fields.add("level");
    }
    if (!Objects.equals(a.unitType(), b.unitType())) {
      fields.add("unitType");
    }
    if (!Objects.equals(a.subjectGroupId(), b.subjectGroupId())) {
      fields.add("subjectGroupId");
    }
    if (!Objects.equals(a.flows(), b.flows())) {
      fields.add("flows");
    }
    if (!Objects.equals(parentBefore, parentAfter)) {
      fields.add("parentId");
    }
    if (!Objects.equals(before.validPeriod(), after.validPeriod())) {
      fields.add(before.validPeriod().from().equals(after.validPeriod().from()) ? "validTo" : "validFrom");
    }
    return fields;
  }

  private static <T> Page<T> paginate(List<T> sorted, int limit, String cursor, int parts,
      java.util.function.Function<T, String[]> keyOf, java.util.function.BiPredicate<T, List<String>> after) {
    List<T> rest = sorted;
    if (cursor != null && !cursor.isBlank()) {
      List<String> keys = Cursors.decode(cursor, parts);
      rest = sorted.stream().filter(t -> after.test(t, keys)).toList();
    }
    if (rest.size() <= limit) {
      return new Page<>(rest, null);
    }
    List<T> page = rest.subList(0, limit);
    return new Page<>(page, Cursors.encode(keyOf.apply(page.get(page.size() - 1))));
  }

  private static int compare(String name, String id, List<String> keys) {
    int c = name.compareTo(keys.get(0));
    return c != 0 ? c : id.compareTo(keys.get(1));
  }

  private boolean readOnly(LocalDate asOf, OffsetDateTime recordedAsOf) {
    return (asOf != null && !asOf.equals(support.today())) || recordedAsOf != null;
  }

  private OffsetDateTime recordedAsOfOrNow(OffsetDateTime recordedAsOf) {
    return recordedAsOf != null ? recordedAsOf : OffsetDateTime.now(clock);
  }

  /** Dựng cây lồng nhau từ các dòng phẳng của CTE; nút mồ côi (đơn vị hết hiệu lực) bị bỏ. */
  static List<TreeView.Node> build(List<TreeRow> rows, UUID rootParent) {
    Map<UUID, List<TreeRow>> byParent = new LinkedHashMap<>();
    for (TreeRow r : rows) {
      byParent.computeIfAbsent(r.parentId() == null ? NO_PARENT : r.parentId(), k -> new ArrayList<>()).add(r);
    }
    return children(byParent, rootParent == null ? NO_PARENT : rootParent, new HashSet<>());
  }

  private static final UUID NO_PARENT = new UUID(0, 0);

  private static List<TreeView.Node> children(Map<UUID, List<TreeRow>> byParent, UUID parent, Set<UUID> seen) {
    List<TreeRow> rows = byParent.getOrDefault(parent, List.of());
    List<TreeView.Node> nodes = new ArrayList<>(rows.size());
    List<TreeRow> sorted = new ArrayList<>(rows);
    sorted.sort(Comparator.comparing(TreeRow::name).thenComparing(TreeRow::id));
    for (TreeRow r : sorted) {
      if (!seen.add(r.id())) {
        continue;
      }
      List<TreeView.Node> kids = children(byParent, r.id(), seen);
      nodes.add(new TreeView.Node(r.id(), r.code(), r.name(), r.unitType(), !kids.isEmpty() || r.hasMore(), kids));
    }
    return nodes;
  }
}
