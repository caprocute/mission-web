/**
 * {@code MOD-01 · OKR Engine} — quản lý mục tiêu (Objective), kết quả then chốt (Key Result) và
 * liên kết mục tiêu giữa các cấp (TL-01 mục 2.1, ADR-02).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "MOD-01 · OKR Engine",
    allowedDependencies = {"shared", "config :: api", "policy :: api"})
package vn.viettel.okrkpi.okr;
