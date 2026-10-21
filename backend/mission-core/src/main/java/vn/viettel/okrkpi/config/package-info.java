/**
 * {@code MOD-08 · Configuration & Period} — quản lý kỳ đánh giá, tham số cấu hình và bố cục SDUI
 * theo đơn vị (TL-01 mục 2.1, ADR-02).
 *
 * <p>Mô-đun gốc trong đồ thị phụ thuộc của {@code mission-core}: không phụ thuộc vào bất kỳ mô-đun
 * nghiệp vụ nào khác ({@code MOD-01..MOD-07}).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "MOD-08 · Configuration & Period",
    allowedDependencies = {"shared"})
package vn.viettel.okrkpi.config;
