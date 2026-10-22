package vn.viettel.okrkpi.evidence.domain;

import java.time.Instant;
import java.util.UUID;

/** Một dòng {@code evidence.scan_result}; {@code detail} là JSON không chứa dữ liệu C2. */
public record ScanRecord(
    UUID id,
    UUID versionId,
    String engine,
    String engineVersion,
    ScanVerdict verdict,
    String detail,
    Instant scannedAt) {}
