package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Người thuộc diện Trung ương quản lý (40 chức danh PL 2B, BR-11-11): có phân công hiệu lực mang
 * chức danh diện TW tại ngày cho trước (CORE-06-08).
 */
public interface CentralTitles {

  /** Những người trong {@code personIds} thuộc diện TW tại ngày {@code on}. */
  Set<UUID> centralPersons(TenantId tenant, Collection<UUID> personIds, LocalDate on);
}
