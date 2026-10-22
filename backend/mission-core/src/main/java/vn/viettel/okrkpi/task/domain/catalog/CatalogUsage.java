package vn.viettel.okrkpi.task.domain.catalog;

/**
 * Mức sử dụng của một mục: số nhiệm vụ và dòng kế hoạch tham chiếu, số kỳ và việc có kỳ đã khoá
 * (BR-04-13).
 */
public record CatalogUsage(int count, int periods, boolean usedInLockedPeriod) {

  public static final CatalogUsage NONE = new CatalogUsage(0, 0, false);
}
