/**
 * {@code MOD-04 · 360 & Appraisal} — đánh giá 360 độ, tổng hợp kết quả và xếp loại cán bộ theo kỳ
 * (TL-01 mục 2.1, ADR-02).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "MOD-04 · 360 & Appraisal",
    allowedDependencies = {
      "shared",
      "config :: api",
      "policy :: api",
      "task :: api",
      "product :: api",
      "okr :: api"
    })
package vn.viettel.okrkpi.appraisal;
