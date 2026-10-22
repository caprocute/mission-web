/**
 * {@code MOD-06 · Digital Dossier} — hồ sơ đánh giá điện tử, ký số và lưu trữ kết quả đánh giá
 * (TL-01 mục 2.1, ADR-02).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "MOD-06 · Digital Dossier",
    allowedDependencies = {"shared", "config :: api", "appraisal :: api", "replica :: api"})
package vn.viettel.okrkpi.dossier;
