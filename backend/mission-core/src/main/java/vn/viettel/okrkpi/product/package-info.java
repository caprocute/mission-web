/**
 * {@code MOD-03 · Product Catalog & Scoring} — danh mục sản phẩm đầu ra và quy đổi điểm KPI sản
 * phẩm (TL-01 mục 2.1, ADR-02).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "MOD-03 · Product Catalog & Scoring",
    allowedDependencies = {"shared", "config :: api", "policy :: api", "task :: api"})
package vn.viettel.okrkpi.product;
