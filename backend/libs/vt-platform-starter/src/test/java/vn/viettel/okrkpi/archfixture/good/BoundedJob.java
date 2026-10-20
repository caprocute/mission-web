package vn.viettel.okrkpi.archfixture.good;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Mẫu hợp lệ: executor có hàng đợi giới hạn và chính sách từ chối rõ ràng. */
public class BoundedJob {

  ExecutorService bounded() {
    return new ThreadPoolExecutor(
        2,
        4,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(100),
        new ThreadPoolExecutor.AbortPolicy());
  }
}
