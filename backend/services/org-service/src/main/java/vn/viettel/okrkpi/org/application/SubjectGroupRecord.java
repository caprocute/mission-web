package vn.viettel.okrkpi.org.application;

import java.time.LocalDate;
import java.util.UUID;

/** Khối đối tượng; {@code validTo == null} là còn hiệu lực. */
public record SubjectGroupRecord(UUID id, String code, String name, String flow, String description,
    LocalDate validFrom, LocalDate validTo) {
}
