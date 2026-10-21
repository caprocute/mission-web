/**
 * {@code MOD-05 · Policy & Formula} — quản lý chính sách quy đổi, công thức tính điểm và quy tắc
 * xếp loại (TL-01 mục 2.1, ADR-02).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "MOD-05 · Policy & Formula",
    allowedDependencies = {"shared", "config :: api"})
package vn.viettel.okrkpi.policy;
