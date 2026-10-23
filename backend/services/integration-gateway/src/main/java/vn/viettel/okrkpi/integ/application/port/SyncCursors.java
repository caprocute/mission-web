package vn.viettel.okrkpi.integ.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Con trỏ đồng bộ kéo ({@code integ.sync_cursor}). */
public interface SyncCursors {

  Optional<String> find(TenantId tenant, UUID connectorId, String stream);

  void save(
      TenantId tenant,
      UUID connectorId,
      String stream,
      String value,
      Instant succeededAt,
      Integer lagSeconds);
}
