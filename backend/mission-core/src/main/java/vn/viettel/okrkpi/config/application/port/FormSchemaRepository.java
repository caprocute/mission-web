package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.Optional;
import vn.viettel.okrkpi.config.domain.FormSchema;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.form_schema} (CORE-08-13). */
public interface FormSchemaRepository {

  /** Phiên bản mới nhất (theo {@code id} UUIDv7) của mỗi khoá, khoá sau {@code afterKey}. */
  List<FormSchema> listLatest(TenantId tenantId, String query, String afterKey, int limit);

  Optional<FormSchema> find(TenantId tenantId, String formKey, String version);

  /** Phiên bản {@code DA_PHAT_HANH} cập nhật gần nhất của khoá. */
  Optional<FormSchema> findCurrent(TenantId tenantId, String formKey);

  /**
   * @return {@code false} khi trùng {@code (form_key, version)}
   */
  boolean insert(TenantId tenantId, FormSchema form);

  /**
   * @return {@code false} khi dòng đã bị người khác đổi
   */
  boolean update(TenantId tenantId, FormSchema form, int expectedVersion);
}
