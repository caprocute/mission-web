package vn.viettel.okrkpi.search.application;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Nguồn phát lại sự kiện để dựng lại chỉ mục (hiện thực: đọc lại các topic Kafka từ đầu kỳ lưu
 * giữ).
 */
public interface EventSourcePort {

  /** Vị trí cuối hiện tại của mọi phân vùng nguồn, khoá {@code <topic>-<phân vùng>}. */
  Map<String, Long> endOffsets();

  /**
   * Đọc lại sự kiện trong khoảng {@code [from, to)} của từng phân vùng và đẩy theo lô vào {@code
   * sink}.
   *
   * @param from vị trí bắt đầu; {@code null} là đầu kỳ lưu giữ
   * @return số sự kiện đã đọc
   */
  long replay(Map<String, Long> from, Map<String, Long> to, Consumer<List<InboundEvent>> sink);
}
