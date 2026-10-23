package vn.viettel.okrkpi.integ.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đọc cấu hình kết nối ({@code integ.connector}). */
public interface Connectors {

  /** Kết nối đang bật của mọi tenant (chỉ mã và tenant), cho worker kéo theo lịch. */
  record Active(UUID tenantId, UUID id, String code) {}

  Optional<Connector> findByCode(TenantId tenant, String code);

  Optional<Connector> findById(TenantId tenant, UUID id);

  /** Qua hàm {@code fn_ket_noi_dang_bat}; xử lý từng dòng trong ngữ cảnh tenant của nó. */
  List<Active> enabled();
}
