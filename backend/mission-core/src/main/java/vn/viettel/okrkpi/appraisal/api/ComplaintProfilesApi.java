package vn.viettel.okrkpi.appraisal.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ đánh giá cho khiếu nại của MOD-07 (CORE-07-02, QT-12 bước 1, 2): kết quả đã ký số của một
 * người, thời hạn khiếu nại {@code khieu_nai.thoi_han} theo Policy Pack của hồ sơ và lệnh chuyển hồ
 * sơ sang nhánh {@code KHIEU_NAI}.
 */
public interface ComplaintProfilesApi {

  /** Tham số thời hạn khiếu nại trong Policy Pack (BR-12-01). */
  String DEADLINE_PARAM = "khieu_nai.thoi_han";

  /** Mặc định khi gói không khai tham số (10 ngày làm việc). */
  int DEFAULT_DEADLINE_DAYS = 10;

  /** Cấu phần có thể khiếu nại của ảnh chụp điểm; {@code stage} là bước bị khiếu nại. */
  record Target(String key, String stage, String label) {}

  /**
   * Kết quả đánh giá: trạng thái hồ sơ, mức và điểm hiện hành, thời điểm công bố (ký quyết định),
   * số ngày làm việc của thời hạn khiếu nại và các cấu phần.
   */
  record Profile(
      UUID id,
      UUID personId,
      UUID periodId,
      int periodBucket,
      UUID orgUnitId,
      String status,
      String ratingCode,
      BigDecimal score,
      Instant publishedAt,
      int deadlineWorkingDays,
      List<Target> targets) {
    public Profile {
      targets = targets == null ? List.of() : List.copyOf(targets);
    }
  }

  /** Hồ sơ {@code DA_KY_SO} của người đã cho, mới nhất trước. */
  List<Profile> signedProfilesOf(TenantId tenant, UUID personId);

  /** Một hồ sơ ở mọi trạng thái. */
  Optional<Profile> find(TenantId tenant, UUID profileId);

  /**
   * Chuyển hồ sơ {@code DA_KY_SO} sang {@code KHIEU_NAI} (phát EVT-11). Lặp lại an toàn: hồ sơ đã ở
   * {@code KHIEU_NAI} thì không làm gì.
   *
   * @return {@code true} khi vừa chuyển
   */
  boolean fileComplaint(TenantId tenant, UUID profileId, UUID actorId);

  /**
   * Người đã đề xuất, thẩm định, quyết định hồ sơ ({@code appraisal.rating_proposal} mức {@code
   * DE_XUAT}, {@code THAM_DINH}, {@code QUYET_DINH}), để MOD-07 loại khỏi người xử lý khiếu nại
   * (BR-12-04, CORE-07-03).
   */
  record Participants(Set<UUID> proposers, Set<UUID> appraisers, Set<UUID> deciders) {
    public Participants {
      proposers = Set.copyOf(proposers);
      appraisers = Set.copyOf(appraisers);
      deciders = Set.copyOf(deciders);
    }
  }

  Participants participants(TenantId tenant, UUID profileId);

  /**
   * Khiếu nại được rút hoặc không thụ lý: hồ sơ {@code KHIEU_NAI} trở lại {@code DA_KY_SO} (QT-12
   * "Ngoại lệ và xử lý"). Lặp lại an toàn: hồ sơ không ở {@code KHIEU_NAI} thì không làm gì.
   *
   * @return {@code true} khi vừa chuyển
   */
  boolean returnToSigned(TenantId tenant, UUID profileId, UUID actorId, String reason);
}
