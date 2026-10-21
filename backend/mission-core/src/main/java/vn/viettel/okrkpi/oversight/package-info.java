/**
 * {@code MOD-07 · Inspection & Oversight} — giám sát, kiểm tra và giải quyết khiếu nại đánh giá
 * (TL-01 mục 2.1, ADR-02).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "MOD-07 · Inspection & Oversight",
    allowedDependencies = {
      "shared",
      "config :: api",
      "appraisal :: api",
      "task :: api",
      "product :: api"
    })
package vn.viettel.okrkpi.oversight;
