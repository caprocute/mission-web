package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.CentralTitles;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Diện TW theo bản sao tổ chức ({@code replica :: api}): người có phân công hiệu lực mang chức danh
 * thuộc danh sách diện TW. TL-03 đánh dấu chức danh bằng {@code org.job_title.is_central_managed}
 * nhưng {@code org_replica} chưa sao chép bảng chức danh, nên danh sách mã chức danh diện TW đọc từ
 * tham số tenant {@value #SETTING} (mảng UUID; suy luận, wiki trang 14) cho tới khi bản sao có cột
 * này. Chưa cấu hình thì không ai thuộc diện TW (API trả {@code DOSSIER-409-NO-CENTRAL-PROFILES}).
 */
public class ReplicaCentralTitles implements CentralTitles {

  public static final String SETTING = "ho_so_so.chuc_danh_dien_tw";

  private static final Logger LOG = LoggerFactory.getLogger(ReplicaCentralTitles.class);

  private final Supplier<OrgDirectoryApi> directory;
  private final Supplier<TenantSettingApi> settings;

  public ReplicaCentralTitles(
      Supplier<OrgDirectoryApi> directory, Supplier<TenantSettingApi> settings) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  public Set<UUID> centralPersons(TenantId tenant, Collection<UUID> personIds, LocalDate on) {
    Set<UUID> titles = titles(tenant);
    OrgDirectoryApi dir = directory.get();
    Set<UUID> out = new LinkedHashSet<>();
    if (titles.isEmpty() || dir == null) {
      return out;
    }
    for (UUID person : personIds) {
      for (AssignmentView a : dir.assignmentsOf(tenant, person, on)) {
        if (titles.contains(a.jobTitleId())) {
          out.add(person);
          break;
        }
      }
    }
    return out;
  }

  private Set<UUID> titles(TenantId tenant) {
    TenantSettingApi s = settings.get();
    Set<UUID> out = new HashSet<>();
    if (s == null) {
      return out;
    }
    JsonNode value = s.get(tenant, SETTING, null).orElse(null);
    if (value == null || !value.isArray()) {
      return out;
    }
    for (JsonNode n : value) {
      try {
        out.add(UUID.fromString(n.asText()));
      } catch (IllegalArgumentException e) {
        LOG.warn("Bỏ qua mã chức danh diện TW không hợp lệ trong tham số {}", SETTING);
      }
    }
    return out;
  }
}
