package vn.viettel.okrkpi.org.application.sync;

import java.util.List;
import java.util.Map;

/** Một trang lệch kèm số đếm theo mức và theo trường lệch. */
public record DiscrepancyPage(List<DiscrepancyView> items, String nextCursor, Map<String, Long> counts,
    Map<String, Long> countsByField) {
}
