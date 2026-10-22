package vn.viettel.okrkpi.platform.retention;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tệp kê khai của một lần lưu trữ (TL-03 "Cơ chế lưu trữ"): băm SHA-256 và số dòng của từng tệp
 * Parquet, kiểu cột gốc để khôi phục, hạn Object Lock. Một tệp kê khai cho một schema, một {@code
 * period_bucket} và một lớp lưu giữ.
 */
public record Manifest(
    String schema,
    int periodBucket,
    RetentionClass retentionClass,
    Instant createdAt,
    LocalDate retainUntil,
    List<TableEntry> tables) {

  public record TableEntry(
      String table, long rows, List<ParquetCodec.Column> columns, List<FileEntry> files) {}

  public record FileEntry(UUID tenant, String key, long rows, String sha256, boolean legalHold) {}

  static String key(String prefix, String schema, int bucket, RetentionClass c) {
    return prefix + "/" + schema + "/" + bucket + "/" + c.name() + "/manifest.json";
  }

  static String fileKey(String prefix, String schema, int bucket, String table, UUID tenant) {
    return prefix + "/" + schema + "/" + bucket + "/" + table + "/" + tenant + ".parquet";
  }
}
