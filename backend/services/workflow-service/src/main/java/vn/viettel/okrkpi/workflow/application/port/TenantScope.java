package vn.viettel.okrkpi.workflow.application.port;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Chạy phần ghi trong ngữ cảnh tenant của tiến trình rồi trả lại ngữ cảnh của người gọi, để quản
 * trị nền tảng (phạm vi N) ghi bảng chiếu và outbox đúng tenant dưới RLS.
 */
public interface TenantScope {

  <T> T within(UUID tenantId, Supplier<T> work);
}
