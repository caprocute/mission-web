package vn.viettel.okrkpi.search.application;

/** Sự kiện không ghi được vì lỗi vĩnh viễn (dữ liệu sai mapping...), cần chuyển thư chết. */
public record IndexFailure(int seq, String code, String reason) {}
