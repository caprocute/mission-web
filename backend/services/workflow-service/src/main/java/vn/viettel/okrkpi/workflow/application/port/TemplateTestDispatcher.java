package vn.viettel.okrkpi.workflow.application.port;

/**
 * Chạy lần test ở luồng nền có hàng đợi giới hạn; hàng đợi đầy thì ném {@code
 * RejectedExecutionException}.
 */
public interface TemplateTestDispatcher {

  void dispatch(Runnable work);
}
