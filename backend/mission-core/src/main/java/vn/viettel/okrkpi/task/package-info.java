/**
 * {@code MOD-02 · Task Management} — quản lý giao việc, cập nhật tiến độ và nghiệm thu nhiệm vụ
 * (TL-01 mục 2.1, ADR-02).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "MOD-02 · Task Management",
    allowedDependencies = {"shared", "config :: api", "policy :: api", "okr :: api"})
package vn.viettel.okrkpi.task;
