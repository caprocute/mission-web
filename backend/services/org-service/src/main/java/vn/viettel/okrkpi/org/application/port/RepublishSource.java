package vn.viettel.okrkpi.org.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Consumer;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * DV-04-08 · Đọc trạng thái hiện hành của tenant trong ngữ cảnh để phát lại, trong giao dịch chỉ đọc đang mở (ảnh
 * chụp nhất quán). Mỗi bản ghi đã mang đủ trường của EVT-14/15/16 và {@code sourceVersion} thật.
 */
public interface RepublishSource {

  /**
   * Mọi lát hiệu lực của mọi đơn vị theo hiểu biết tại {@code asOf}; cấp trên của lát đang mở lấy tại {@code
   * asOfDate}, của lát đã đóng lấy tại ngày bắt đầu lát.
   */
  void units(Instant asOf, LocalDate asOfDate, Consumer<DonViDaThayDoi> sink);

  /** Một bản ghi mỗi nhân sự: phiên bản hiệu lực tại {@code asOfDate} (hoặc phiên bản muộn nhất). */
  void persons(Instant asOf, LocalDate asOfDate, Consumer<NhanSuDaThayDoi> sink);

  /** Mọi lát hiệu lực của mọi phân công theo hiểu biết tại {@code asOf}. */
  void assignments(Instant asOf, Consumer<PhanCongDaThayDoi> sink);
}
