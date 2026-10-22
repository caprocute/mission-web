package vn.viettel.okrkpi.workflow.infrastructure.template;

/**
 * Tám quy tắc quản trị "chống mạng nhện" của TL-01 G2 (BS-01 mục "Dữ liệu và quy tắc quản trị đã
 * có"). Vi phạm bất kỳ quy tắc nào làm việc đăng ký mẫu thất bại.
 */
public enum LintRule {
  /** 1. Mỗi mẫu là một định nghĩa dùng chung; cấm định nghĩa gắn {@code tenantId}. */
  R1_KHONG_GAN_TENANT("Không triển khai định nghĩa gắn tenant hoặc gắn cứng mã đơn vị"),
  /** 2. Tối đa một tầng call activity. */
  R2_MOT_TANG_CALL_ACTIVITY("Tối đa một tầng call activity"),
  /** 3. Không gọi tiến trình chéo tenant, không dùng signal phát rộng. */
  R3_KHONG_CHEO_TENANT("Không gọi chéo tenant, không dùng signal hay message phát rộng"),
  /** 4. Không có service task gọi đồng bộ dịch vụ ngoài. */
  R4_KHONG_GOI_DONG_BO("Tác vụ máy phải là external worker hoặc tính toán thuần đã đăng ký"),
  /** 5. Biến tiến trình chỉ mang id, mã, tham số nhỏ, tổng dưới 4 KB. */
  R5_BIEN_DUOI_4KB("Tổng ngân sách biến dưới 4 KB, không dùng data object"),
  /** 6. Đa thể hiện song song không quá 20 phần tử. */
  R6_SONG_SONG_TOI_DA_20("Đa thể hiện song song tối đa 20 phần tử"),
  /** 7. Mỗi mẫu có Flowable test, test DMN, mô tả nghiệp vụ, người sở hữu. */
  R7_KIEM_THU_MO_TA_CHU_SO_HUU("Có kiểm thử luồng, kiểm thử DMN, mô tả và người sở hữu"),
  /** 8. Không quá ba phiên bản chính còn tiến trình chạy. */
  R8_TOI_DA_3_PHIEN_BAN("Không quá ba phiên bản chính còn tiến trình chạy");

  private final String description;

  LintRule(String description) {
    this.description = description;
  }

  /** Mô tả quy tắc. */
  public String description() {
    return description;
  }
}
