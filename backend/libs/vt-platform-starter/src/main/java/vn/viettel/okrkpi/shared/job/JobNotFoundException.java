package vn.viettel.okrkpi.shared.job;

import java.io.Serial;

/** Tác vụ không tồn tại hoặc người gọi không phải người khởi tạo: 404 ({@code jobs.yaml}). */
public class JobNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public JobNotFoundException() {
    super("JOB-NOT-FOUND", null, false, false);
  }
}
