package vn.viettel.okrkpi.task.application.similar;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskCandidates;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskRows;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskRows.Probe;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskRows.Row;
import vn.viettel.okrkpi.task.domain.similar.TaskSimilarity;

/**
 * Nhiệm vụ gần giống cùng tenant, kỳ và đơn vị (BR-05-06, SCR-0501): trùng dấu vân tay qua chỉ mục
 * {@code ix_task__dup} (độ tương đồng 1), cộng ứng viên từ tìm kiếm toàn văn SVC-16 (hoặc nhiệm vụ
 * cùng đơn vị khi chưa cấu hình SVC-16) có tên tương đồng từ 0,7. Bỏ nhiệm vụ hạn chế và đã huỷ.
 * SVC-16 lỗi thì trả kết quả từ dấu vân tay kèm cờ "chưa kiểm được" và không chặn giao (suy luận).
 */
public class SimilarTaskService {

  static final String TX = "taskTransactionManager";
  static final int CANDIDATES = 50;
  static final int LOCAL_CANDIDATES = 500;
  static final int MAX_RESULTS = 20;
  private static final Logger LOG = LoggerFactory.getLogger(SimilarTaskService.class);

  private final SimilarTaskRows rows;
  private final SimilarTaskCandidates remote;

  public SimilarTaskService(SimilarTaskRows rows, SimilarTaskCandidates remote) {
    this.rows = Objects.requireNonNull(rows, "rows");
    this.remote = remote;
  }

  /** Một nhiệm vụ gần giống và độ tương đồng. */
  public record Match(Row row, BigDecimal similarity) {}

  /** Kết quả; {@code checked} sai khi nguồn ứng viên lỗi. */
  public record Result(List<Match> matches, boolean checked) {
    public boolean found() {
      return !matches.isEmpty();
    }
  }

  /** Nhiệm vụ gần giống với nhiệm vụ đã lưu {@code taskId} ({@code listSimilarTasks}). */
  @Transactional(value = TX, readOnly = true)
  public Result similarTo(TenantId tenant, UUID taskId, String authorization) {
    return find(
        tenant, rows.probe(tenant, taskId).orElseThrow(NoSuchElementException::new), authorization);
  }

  /** Nhiệm vụ gần giống với một bản đang soạn hoặc đã lưu. */
  @Transactional(value = TX, readOnly = true)
  public Result find(TenantId tenant, Probe p, String authorization) {
    if (p.periodId() == null || p.title() == null || p.title().isBlank()) {
      return new Result(List.of(), true);
    }
    Map<UUID, Row> pool = new LinkedHashMap<>();
    if (p.fingerprint() != null) {
      rows.byFingerprint(tenant, p.periodId(), p.fingerprint()).forEach(r -> pool.put(r.id(), r));
    }
    boolean checked = true;
    if (remote == null) {
      rows.inUnit(tenant, p.periodId(), p.unitId(), LOCAL_CANDIDATES)
          .forEach(r -> pool.putIfAbsent(r.id(), r));
    } else {
      try {
        List<UUID> ids =
            remote.candidates(
                tenant, p.periodId(), p.unitId(), p.title().strip(), CANDIDATES, authorization);
        if (!ids.isEmpty()) {
          rows.byIds(tenant, p.periodId(), ids).forEach(r -> pool.putIfAbsent(r.id(), r));
        }
      } catch (SimilarTaskCandidates.Unavailable e) {
        LOG.warn("Chưa kiểm được nhiệm vụ gần giống qua tìm kiếm: {}", e.getMessage());
        checked = false;
      }
    }
    List<Match> out = new ArrayList<>();
    for (Row r : pool.values()) {
      if (r.id().equals(p.taskId())
          || r.restricted()
          || "HUY".equals(r.status())
          || !p.periodId().equals(r.periodId())
          || !Objects.equals(p.unitId(), r.orgUnitId())) {
        continue;
      }
      BigDecimal s =
          p.fingerprint() != null && Arrays.equals(p.fingerprint(), r.fingerprint())
              ? BigDecimal.ONE.setScale(2)
              : TaskSimilarity.similarity(p.title(), r.title());
      if (s.compareTo(TaskSimilarity.THRESHOLD) >= 0) {
        out.add(new Match(r, s));
      }
    }
    out.sort(
        Comparator.comparing(Match::similarity)
            .reversed()
            .thenComparing(m -> m.row().code(), Comparator.nullsLast(Comparator.naturalOrder())));
    return new Result(List.copyOf(out.subList(0, Math.min(MAX_RESULTS, out.size()))), checked);
  }
}
