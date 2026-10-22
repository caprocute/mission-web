package vn.viettel.okrkpi.workflow.domain.saga;

/** Việc kế tiếp của saga mà worker phải làm khi nhận lượt chạy từ engine. */
public sealed interface NextWork {

  /** Thực hiện bước {@code stepNo} theo chiều tiến. */
  record Execute(int stepNo) implements NextWork {}

  /** Chạy hành động bù trừ của bước {@code stepNo}. */
  record Compensate(int stepNo) implements NextWork {}

  /** Bước {@code stepNo} không có bù trừ: bỏ qua có ghi vết rồi lùi tiếp. */
  record SkipCompensation(int stepNo) implements NextWork {}

  /** Saga đang chờ sự kiện, lệnh điều khiển hoặc người vận hành. */
  record Wait() implements NextWork {}

  /** Saga đã kết thúc ({@code HOAN_TAT} hoặc {@code DA_BU_TRU}). */
  record Finish() implements NextWork {}
}
