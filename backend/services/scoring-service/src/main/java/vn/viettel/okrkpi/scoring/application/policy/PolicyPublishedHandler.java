package vn.viettel.okrkpi.scoring.application.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.scoring.application.port.OpenPeriods;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.OpenPeriod;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * Xử lý EVT-13 {@code policy.pack.published.v1} (DV-07-02, TL-02b QT-15 "Ngoại lệ"): nạp và biên
 * dịch trước phiên bản mới mà không khởi động lại, quên ánh xạ kỳ chưa đóng băng của tenant, rồi
 * với mỗi kỳ chưa chốt mà phân giải lại thấy ghim đúng phiên bản mới thì mở một tác vụ tính chờ
 * ({@code score_job} trạng thái {@code CHO}, {@code trigger_event_id} là mã sự kiện) để tính lại
 * hàng loạt (DV-07-06) theo công thức mới. Sự kiện trùng do inbox chặn; tác vụ trùng do chỉ mục
 * {@code ix_score_job__trigger_event} chặn.
 */
public class PolicyPublishedHandler {

  /** Tài khoản hệ thống ghi {@code created_by} của tác vụ do sự kiện mở. */
  static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  static final String REASON = "PHAT_HANH_POLICY_PACK";

  /** Nội dung cần dùng của EVT-13. */
  public record Published(
      UUID tenantId, UUID eventId, UUID packId, UUID versionId, String contentHash) {

    public Published {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(packId, "packId");
      Objects.requireNonNull(versionId, "versionId");
    }
  }

  private final PolicyPackCache cache;
  private final OpenPeriods periods;
  private final ScoreJobStore jobs;

  public PolicyPublishedHandler(PolicyPackCache cache, OpenPeriods periods, ScoreJobStore jobs) {
    this.cache = Objects.requireNonNull(cache, "cache");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
  }

  /** Trả các tác vụ tính lại đã mở (mỗi kỳ bị ảnh hưởng một tác vụ). */
  public List<UUID> onPublished(Published e) {
    cache.preload(e.tenantId(), e.packId(), e.versionId(), e.contentHash());
    cache.forgetPins(e.tenantId());
    Map<UUID, List<String>> flows = new LinkedHashMap<>();
    Map<UUID, Integer> buckets = new LinkedHashMap<>();
    for (OpenPeriod p : periods.of(e.tenantId())) {
      PinnedPolicy pinned;
      try {
        pinned = cache.pin(PolicyBindingKey.of(e.tenantId(), p.periodId(), p.flow()));
      } catch (BusinessException noBinding) {
        continue;
      }
      if (pinned.frozen() || !pinned.versionId().equals(e.versionId())) {
        continue;
      }
      flows.computeIfAbsent(p.periodId(), k -> new ArrayList<>()).add(p.flow());
      buckets.put(p.periodId(), p.periodBucket());
    }
    List<UUID> opened = new ArrayList<>();
    flows.forEach(
        (period, list) ->
            opened.add(
                jobs.open(
                    new ScoreJob(
                        e.tenantId(),
                        Ids.newUuidV7(),
                        period,
                        buckets.get(period),
                        ScoreJobKind.TAM_TINH,
                        CanonicalDoc.of(Map.of("ly_do", REASON, "luong", List.copyOf(list))),
                        e.versionId(),
                        e.eventId(),
                        0,
                        SYSTEM_ACTOR))));
    return opened;
  }
}
