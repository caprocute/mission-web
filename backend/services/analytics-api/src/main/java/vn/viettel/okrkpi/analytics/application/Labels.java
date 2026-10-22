package vn.viettel.okrkpi.analytics.application;

import java.util.Map;

/** Nhãn tiếng Việt của trạng thái chuẩn (baseline mục 9.1) cho tệp xuất; không in mã thô. */
final class Labels {

  private static final Map<String, String> STATUS =
      Map.ofEntries(
          Map.entry("NHAP", "Nháp"),
          Map.entry("DA_GIAO", "Đã giao"),
          Map.entry("DANG_THUC_HIEN", "Đang thực hiện"),
          Map.entry("CHO_NGHIEM_THU", "Chờ nghiệm thu"),
          Map.entry("HOAN_THANH", "Hoàn thành"),
          Map.entry("TAM_DUNG", "Tạm dừng"),
          Map.entry("HUY", "Đã huỷ"),
          Map.entry("DA_NOP", "Đã nộp"),
          Map.entry("DANG_NGHIEM_THU", "Đang nghiệm thu"),
          Map.entry("DA_XAC_NHAN", "Đã xác nhận"),
          Map.entry("DA_XAC_NHAN_MOT_PHAN", "Đã xác nhận một phần"),
          Map.entry("YEU_CAU_BO_SUNG", "Yêu cầu bổ sung"),
          Map.entry("TU_CHOI", "Từ chối"),
          Map.entry("CHUA_CO", "Chưa có"),
          Map.entry("CHO_DUYET", "Chờ duyệt"),
          Map.entry("DA_DUYET", "Đã duyệt"),
          Map.entry("TRA_LAI", "Trả lại"),
          Map.entry("DONG", "Đã đóng"));

  private Labels() {}

  static String status(String code) {
    return code == null ? "" : STATUS.getOrDefault(code, "");
  }
}
