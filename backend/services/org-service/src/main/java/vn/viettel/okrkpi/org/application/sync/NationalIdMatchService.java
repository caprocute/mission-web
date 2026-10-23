package vn.viettel.okrkpi.org.application.sync;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore.PersonRow;

/**
 * DV-02-04 · QT-00 bước 3: tra bản ghi người của tenant theo số định danh cá nhân mà VNeID trả về, để iam liên kết
 * danh tính ngoài với đúng một người (TL-02a "ánh xạ danh tính ngoài"). So khớp bằng chỉ mục mù
 * {@code org.person.national_id_bidx} tính bằng khoá chỉ mục mù của tenant ngay tại org-service, nên khoá không rời
 * dịch vụ sở hữu cột; số định danh dạng rõ chỉ ở trong bộ nhớ của lời gọi, không ghi bảng hay nhật ký.
 */
@Service
public class NationalIdMatchService {

  /** Kết quả tra: {@code personId} chỉ có khi khớp đúng một người; {@code matches} là số người khớp. */
  public record Match(UUID personId, int matches) {
  }

  private final SyncSupport support;
  private final SyncPersonStore persons;

  public NationalIdMatchService(SyncSupport support, SyncPersonStore persons) {
    this.support = support;
    this.persons = persons;
  }

  @Transactional(readOnly = true)
  public Match match(UUID tenant, String nationalId) {
    String normalized = SyncSupport.normalizeNationalId(nationalId);
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalArgumentException("Thiếu số định danh cá nhân.");
    }
    List<UUID> ids = persons.findByNationalIdIndex(support.nationalIdIndex(tenant, normalized), support.today())
        .stream().map(PersonRow::id).distinct().toList();
    return new Match(ids.size() == 1 ? ids.get(0) : null, ids.size());
  }
}
