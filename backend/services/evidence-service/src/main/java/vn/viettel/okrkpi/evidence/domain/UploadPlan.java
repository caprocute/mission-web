package vn.viettel.okrkpi.evidence.domain;

/**
 * Chia tệp khai báo thành các phần (hợp đồng {@code FileUploadSession.partSize}: 5 đến 16 MB, phần
 * cuối có thể nhỏ hơn).
 */
public record UploadPlan(long declaredSize, int partSize) {

  public static final int MIN_PART = 5 * 1024 * 1024;
  public static final int MAX_PART = 16 * 1024 * 1024;

  public UploadPlan {
    if (declaredSize < 1) {
      throw new IllegalArgumentException("Kích thước khai báo phải dương");
    }
    if (partSize < MIN_PART || partSize > MAX_PART) {
      throw new IllegalArgumentException("Kích thước phần phải từ 5 đến 16 MB");
    }
  }

  public int partCount() {
    return (int) ((declaredSize + partSize - 1) / partSize);
  }

  /** Kích thước đúng của phần {@code n} (1..partCount), hoặc -1 khi {@code n} ngoài phạm vi. */
  public long expectedSize(int n) {
    int count = partCount();
    if (n < 1 || n > count) {
      return -1;
    }
    return n < count ? partSize : declaredSize - (long) partSize * (count - 1);
  }
}
