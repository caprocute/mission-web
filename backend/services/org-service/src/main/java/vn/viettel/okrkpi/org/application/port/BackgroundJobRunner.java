package vn.viettel.okrkpi.org.application.port;

import java.util.UUID;

/** Chạy tác vụ nền sau khi giao dịch hiện tại commit, trong ngữ cảnh tenant đã cho. */
public interface BackgroundJobRunner {

  void submitAfterCommit(UUID tenantId, Runnable work);
}
