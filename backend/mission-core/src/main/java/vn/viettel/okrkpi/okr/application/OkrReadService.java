package vn.viettel.okrkpi.okr.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.BasisRow;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.KeyResultFilter;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.KeyResultRow;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.LinkRow;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveFilter;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveRow;
import vn.viettel.okrkpi.okr.domain.ProgressCalculator;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phía đọc của {@code MOD-01 · OKR Engine} cho SCR-0201 (CORE-01-02): cây mục tiêu, dạng bảng, bản
 * đồ liên kết, chi tiết mục tiêu và KR, thư viện căn cứ. Tiến độ luôn tính lại bằng {@link
 * ProgressCalculator} lúc đọc nên web, di động và bảng điều hành thấy cùng một con số.
 */
public class OkrReadService {

  static final String TX = "okrTransactionManager";

  /** Số mục tiêu khuyến nghị của một đơn vị trong một kỳ khi Policy Pack chưa đặt (BR-02-01). */
  public static final int RECOMMENDED_MAX = 3;

  /** Số tầng phân rã tối đa của bản đồ liên kết (CORE-01-02 mục 3). */
  public static final int MAX_DECOMPOSITION_DEPTH = 3;

  private final OkrReadRepository repository;
  private final PeriodApi periods;
  private final Supplier<LocalDate> today;

  public OkrReadService(
      OkrReadRepository repository, PeriodApi periods, Supplier<LocalDate> today) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.today = Objects.requireNonNull(today, "today");
  }

  /** KR kèm tiến độ đã tính. */
  public record KeyResultView(KeyResultRow row, BigDecimal progress) {}

  /** Mục tiêu kèm KR, căn cứ và tiến độ đã tính. */
  public record ObjectiveView(
      ObjectiveRow row,
      BigDecimal progress,
      List<KeyResultView> keyResults,
      List<BasisRow> bases) {}

  /** Liên kết ngang của cây: dòng liên kết cùng mục tiêu và KR ở hai đầu (có thể thiếu). */
  public record PeerLinkView(
      LinkRow link, ObjectiveRow localObjective, KeyResultRow localKeyResult, ObjectiveRow peer) {}

  /** Kỳ kèm tuần hiện tại (1..n) và số tuần của kỳ, {@code null} khi hôm nay ngoài kỳ. */
  public record PeriodView(PeriodSnapshot period, Integer weekNo, Integer weekCount) {}

  /** Dữ liệu của {@code getOkrTree}. */
  public record TreeView(
      PeriodView period,
      UUID unitId,
      boolean viewerOwnUnit,
      List<ObjectiveView> objectives,
      List<BasisRow> bases,
      ObjectiveRow parent,
      List<PeerLinkView> peerLinks,
      int recommendedMax) {}

  /** Nút của bản đồ liên kết: đúng một trong ba trường dữ liệu khác {@code null}. */
  public record AlignmentNode(
      String type, UUID id, ObjectiveView objective, KeyResultView keyResult, BasisRow basis) {}

  /** Đồ thị liên kết dọc và ngang. */
  public record AlignmentView(UUID rootId, List<AlignmentNode> nodes, List<LinkRow> edges) {}

  /**
   * Kỳ theo id, hoặc kỳ hiện hành khi không truyền; {@code NoSuchElementException} khi không có.
   */
  public PeriodSnapshot period(TenantId tenant, UUID periodId) {
    Optional<PeriodSnapshot> found =
        periodId == null
            ? periods.findCurrent(tenant)
            : periods.findById(tenant, PeriodId.of(periodId));
    return found.orElseThrow(() -> new NoSuchElementException("Không tìm thấy kỳ"));
  }

  /** Kỳ theo id, hoặc kỳ hiện hành; rỗng khi không có kỳ nào (danh sách khi đó không lọc kỳ). */
  public Optional<PeriodSnapshot> optionalPeriod(TenantId tenant, UUID periodId) {
    try {
      return Optional.of(period(tenant, periodId));
    } catch (NoSuchElementException e) {
      return Optional.empty();
    }
  }

  public PeriodView periodView(PeriodSnapshot p) {
    long days = ChronoUnit.DAYS.between(p.startDate(), p.endDate()) + 1;
    int weekCount = (int) ((days + 6) / 7);
    LocalDate now = today.get();
    Integer weekNo =
        now.isBefore(p.startDate()) || now.isAfter(p.endDate())
            ? null
            : (int) (ChronoUnit.DAYS.between(p.startDate(), now) / 7) + 1;
    return new PeriodView(p, weekNo, weekCount);
  }

  /**
   * Các giá trị {@code period_bucket} có thể của kỳ: năm × 10 + quý của ngày bắt đầu (kỳ quý,
   * tháng) hoặc năm × 10 (kỳ năm, năm học), theo CHECK {@code ck_period__bucket}; truyền cả hai để
   * truy vấn chỉ chạm tối đa hai phân vùng mà không cần biết loại kỳ.
   */
  public static List<Integer> buckets(PeriodSnapshot p) {
    LocalDate start = p.startDate();
    int year = start.getYear() * 10;
    int quarter = (start.getMonthValue() - 1) / 3 + 1;
    return List.of(year + quarter, year);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public List<ObjectiveView> objectives(
      TenantId tenant, ObjectiveFilter filter, String afterCode, UUID afterId, int limit) {
    return withKeyResults(
        tenant, filter.buckets(), repository.objectives(tenant, filter, afterCode, afterId, limit));
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public long countUnitObjectives(TenantId tenant, PeriodSnapshot period, UUID unitId) {
    return repository.countObjectives(tenant, period.id().value(), buckets(period), unitId);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<ObjectiveView> objective(TenantId tenant, UUID id) {
    return repository
        .objective(tenant, id)
        .map(o -> withKeyResults(tenant, List.of(o.periodBucket()), List.of(o)).getFirst());
  }

  /** Liên kết chạm mục tiêu hoặc một KR của nó (cho {@code peerLinks} của chi tiết mục tiêu). */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<LinkRow> linksOf(TenantId tenant, ObjectiveView objective) {
    List<UUID> ids = new ArrayList<>();
    ids.add(objective.row().id());
    objective.keyResults().forEach(k -> ids.add(k.row().id()));
    return repository.linksTouching(tenant, ids);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<ObjectiveRow> objectiveRow(TenantId tenant, UUID id) {
    return repository.objective(tenant, id);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public List<KeyResultView> keyResults(
      TenantId tenant, KeyResultFilter filter, String afterCode, UUID afterId, int limit) {
    return repository.keyResults(tenant, filter, afterCode, afterId, limit).stream()
        .map(OkrReadService::view)
        .toList();
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<KeyResultView> keyResult(TenantId tenant, UUID id) {
    return repository.keyResult(tenant, id).map(OkrReadService::view);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public List<BasisRow> bases(
      TenantId tenant,
      String kind,
      LocalDate validOn,
      String query,
      String afterTitle,
      UUID afterId,
      int limit) {
    return repository.bases(tenant, kind, validOn, query, afterTitle, afterId, limit);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public List<LinkRow> links(
      TenantId tenant,
      String fromType,
      UUID fromId,
      String toType,
      UUID toId,
      String linkKind,
      UUID periodId,
      UUID afterId,
      int limit) {
    return repository.links(
        tenant, fromType, fromId, toType, toId, linkKind, periodId, afterId, limit);
  }

  /** Cây mục tiêu tập thể của một đơn vị trong một kỳ (SCR-0201). */
  @Transactional(transactionManager = TX, readOnly = true)
  public TreeView tree(TenantId tenant, PeriodSnapshot period, UUID unitId, boolean viewerOwnUnit) {
    List<Integer> buckets = buckets(period);
    List<ObjectiveRow> rows = new ArrayList<>();
    String afterCode = null;
    UUID afterId = null;
    ObjectiveFilter filter =
        new ObjectiveFilter(
            period.id().value(), buckets, List.of(unitId), "TAP_THE", null, List.of(), null);
    while (true) {
      List<ObjectiveRow> page = repository.objectives(tenant, filter, afterCode, afterId, 200);
      rows.addAll(page);
      if (page.size() < 200) {
        break;
      }
      afterCode = page.getLast().code();
      afterId = page.getLast().id();
    }
    List<ObjectiveView> objectives = withKeyResults(tenant, buckets, rows);

    Map<UUID, BasisRow> bases = new LinkedHashMap<>();
    objectives.forEach(o -> o.bases().forEach(b -> bases.putIfAbsent(b.id(), b)));

    ObjectiveRow parent =
        rows.stream()
            .map(ObjectiveRow::parentObjectiveId)
            .filter(Objects::nonNull)
            .findFirst()
            .flatMap(id -> repository.objective(tenant, id))
            .orElse(null);

    return new TreeView(
        periodView(period),
        unitId,
        viewerOwnUnit,
        objectives,
        List.copyOf(bases.values()),
        parent,
        peerLinks(tenant, objectives),
        RECOMMENDED_MAX);
  }

  /**
   * Bản đồ liên kết từ một mục tiêu gốc, hoặc từ mọi mục tiêu tập thể của đơn vị trong kỳ khi không
   * có gốc: duyệt theo chiều rộng qua {@code okr.okr_link} lên và xuống {@code depth} tầng (tối đa
   * {@link #MAX_DECOMPOSITION_DEPTH} tầng phân rã), KR của mục tiêu là nút con trực tiếp.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public AlignmentView alignment(
      TenantId tenant, UUID rootId, PeriodSnapshot period, UUID unitId, int depth) {
    int maxDepth = Math.min(Math.max(depth, 1), MAX_DECOMPOSITION_DEPTH);
    List<UUID> start = new ArrayList<>();
    if (rootId != null) {
      repository
          .objective(tenant, rootId)
          .orElseThrow(() -> new NoSuchElementException("Không tìm thấy mục tiêu"));
      start.add(rootId);
    } else if (period != null && unitId != null) {
      repository
          .objectives(
              tenant,
              new ObjectiveFilter(
                  period.id().value(),
                  buckets(period),
                  List.of(unitId),
                  "TAP_THE",
                  null,
                  List.of(),
                  null),
              null,
              null,
              200)
          .forEach(o -> start.add(o.id()));
    }

    Set<UUID> objectiveIds = new LinkedHashSet<>();
    Set<UUID> keyResultIds = new LinkedHashSet<>();
    Set<UUID> basisIds = new LinkedHashSet<>();
    Map<UUID, LinkRow> edges = new LinkedHashMap<>();
    Map<UUID, UUID> krObjective = new HashMap<>();

    objectiveIds.addAll(start);
    Deque<UUID> frontier = new ArrayDeque<>(start);
    Set<UUID> visited = new HashSet<>(start);
    for (int level = 0; level < maxDepth && !frontier.isEmpty(); level++) {
      List<UUID> current = new ArrayList<>(frontier);
      frontier.clear();
      // KR của mục tiêu ở tầng hiện tại cũng là đầu mút của liên kết (DONG_GOP, NGANG).
      List<UUID> touching = new ArrayList<>(current);
      for (ObjectiveView o : withKeyResults(tenant, null, objectives(tenant, current))) {
        o.keyResults()
            .forEach(
                k -> {
                  touching.add(k.row().id());
                  keyResultIds.add(k.row().id());
                  krObjective.put(k.row().id(), o.row().id());
                });
      }
      for (LinkRow l : repository.linksTouching(tenant, touching)) {
        edges.putIfAbsent(l.id(), l);
        for (Endpoint e :
            List.of(new Endpoint(l.fromType(), l.fromId()), new Endpoint(l.toType(), l.toId()))) {
          if (!visited.add(e.id())) {
            continue;
          }
          switch (e.type()) {
            case "OBJECTIVE" -> {
              objectiveIds.add(e.id());
              frontier.add(e.id());
            }
            case "KEY_RESULT" -> keyResultIds.add(e.id());
            case "BASIS" -> basisIds.add(e.id());
            default -> {
              // PROGRAM, TASK: nút thuộc module khác, chỉ giữ cạnh.
            }
          }
        }
      }
      // KR ở đầu kia của liên kết kéo theo mục tiêu cha của nó vào tầng kế tiếp.
      List<UUID> unknownKrs =
          keyResultIds.stream().filter(k -> !krObjective.containsKey(k)).toList();
      for (KeyResultRow k : repository.keyResultsByIds(tenant, unknownKrs)) {
        krObjective.put(k.id(), k.objectiveId());
        if (visited.add(k.objectiveId())) {
          objectiveIds.add(k.objectiveId());
          frontier.add(k.objectiveId());
        }
      }
    }

    List<AlignmentNode> nodes = new ArrayList<>();
    Map<UUID, KeyResultView> krViews = new HashMap<>();
    for (ObjectiveView o : withKeyResults(tenant, null, objectives(tenant, objectiveIds))) {
      nodes.add(new AlignmentNode("OBJECTIVE", o.row().id(), o, null, null));
      o.keyResults().forEach(k -> krViews.put(k.row().id(), k));
    }
    for (KeyResultRow k :
        repository.keyResultsByIds(
            tenant, keyResultIds.stream().filter(id -> !krViews.containsKey(id)).toList())) {
      krViews.put(k.id(), view(k));
    }
    keyResultIds.stream()
        .map(krViews::get)
        .filter(Objects::nonNull)
        .forEach(k -> nodes.add(new AlignmentNode("KEY_RESULT", k.row().id(), null, k, null)));
    repository
        .basesByIds(tenant, basisIds)
        .forEach(b -> nodes.add(new AlignmentNode("BASIS", b.id(), null, null, b)));
    return new AlignmentView(rootId, nodes, List.copyOf(edges.values()));
  }

  private record Endpoint(String type, UUID id) {}

  private List<ObjectiveRow> objectives(TenantId tenant, Collection<UUID> ids) {
    return repository.objectivesByIds(tenant, ids).stream()
        .sorted(java.util.Comparator.comparing(ObjectiveRow::code).thenComparing(ObjectiveRow::id))
        .toList();
  }

  private List<PeerLinkView> peerLinks(TenantId tenant, List<ObjectiveView> objectives) {
    Map<UUID, ObjectiveRow> localObjectives = new HashMap<>();
    Map<UUID, KeyResultRow> localKrs = new HashMap<>();
    Map<UUID, ObjectiveRow> krOwner = new HashMap<>();
    for (ObjectiveView o : objectives) {
      localObjectives.put(o.row().id(), o.row());
      for (KeyResultView k : o.keyResults()) {
        localKrs.put(k.row().id(), k.row());
        krOwner.put(k.row().id(), o.row());
      }
    }
    List<UUID> ids = new ArrayList<>(localObjectives.keySet());
    ids.addAll(localKrs.keySet());
    List<LinkRow> links =
        repository.linksTouching(tenant, ids).stream()
            .filter(l -> "NGANG".equals(l.linkKind()))
            .toList();
    if (links.isEmpty()) {
      return List.of();
    }
    // Đầu kia của liên kết: mục tiêu, hoặc KR thì lấy mục tiêu cha của KR.
    Set<UUID> remoteObjectives = new HashSet<>();
    Set<UUID> remoteKrs = new HashSet<>();
    for (LinkRow l : links) {
      boolean fromLocal =
          localObjectives.containsKey(l.fromId()) || localKrs.containsKey(l.fromId());
      String type = fromLocal ? l.toType() : l.fromType();
      UUID id = fromLocal ? l.toId() : l.fromId();
      if ("OBJECTIVE".equals(type)) {
        remoteObjectives.add(id);
      } else if ("KEY_RESULT".equals(type)) {
        remoteKrs.add(id);
      }
    }
    Map<UUID, UUID> remoteKrObjective = new HashMap<>();
    repository
        .keyResultsByIds(tenant, remoteKrs)
        .forEach(
            k -> {
              remoteKrObjective.put(k.id(), k.objectiveId());
              remoteObjectives.add(k.objectiveId());
            });
    Map<UUID, ObjectiveRow> remote = new HashMap<>();
    repository.objectivesByIds(tenant, remoteObjectives).forEach(o -> remote.put(o.id(), o));

    List<PeerLinkView> result = new ArrayList<>();
    for (LinkRow l : links) {
      boolean fromLocal =
          localObjectives.containsKey(l.fromId()) || localKrs.containsKey(l.fromId());
      UUID localId = fromLocal ? l.fromId() : l.toId();
      UUID remoteId = fromLocal ? l.toId() : l.fromId();
      KeyResultRow localKr = localKrs.get(localId);
      ObjectiveRow localObjective =
          localKr != null ? krOwner.get(localId) : localObjectives.get(localId);
      ObjectiveRow peer = remote.get(remoteKrObjective.getOrDefault(remoteId, remoteId));
      if (peer != null) {
        result.add(new PeerLinkView(l, localObjective, localKr, peer));
      }
    }
    return result;
  }

  private List<ObjectiveView> withKeyResults(
      TenantId tenant, List<Integer> buckets, List<ObjectiveRow> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = rows.stream().map(ObjectiveRow::id).toList();
    Map<UUID, List<KeyResultView>> krs = new HashMap<>();
    for (KeyResultRow k : repository.keyResultsOf(tenant, buckets, ids)) {
      krs.computeIfAbsent(k.objectiveId(), x -> new ArrayList<>()).add(view(k));
    }
    Map<UUID, List<BasisRow>> bases = new HashMap<>();
    for (OkrReadRepository.ObjectiveBasis b : repository.basesOf(tenant, buckets, ids)) {
      bases.computeIfAbsent(b.objectiveId(), x -> new ArrayList<>()).add(b.basis());
    }
    return rows.stream()
        .map(
            o -> {
              List<KeyResultView> own = krs.getOrDefault(o.id(), List.of());
              return new ObjectiveView(
                  o,
                  ProgressCalculator.objective(
                      own.stream()
                          .map(k -> new ProgressCalculator.Weighted(k.row().weight(), k.progress()))
                          .toList()),
                  own,
                  bases.getOrDefault(o.id(), List.of()));
            })
        .toList();
  }

  /** Tiến độ KR từ giá trị hiện tại, hoặc từ check-in đã gửi gần nhất khi chưa có giá trị. */
  static KeyResultView view(KeyResultRow k) {
    BigDecimal current =
        k.currentValue() != null
            ? k.currentValue()
            : k.lastCheckin() == null ? null : k.lastCheckin().value();
    return new KeyResultView(
        k,
        ProgressCalculator.keyResult(
            k.metricType(), k.direction(), k.baselineValue(), k.targetValue(), current));
  }
}
