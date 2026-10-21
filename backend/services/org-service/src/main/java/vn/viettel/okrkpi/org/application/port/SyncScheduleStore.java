package vn.viettel.okrkpi.org.application.port;

import java.util.List;
import vn.viettel.okrkpi.org.application.Actor;

/** DV-04-07 · Bảng {@code org.sync_schedule}; cả bộ lịch của tenant có một phiên bản chung (ETag). */
public interface SyncScheduleStore {

  record Schedule(String connector, boolean enabled, String cron) {
  }

  record ScheduleSet(int version, List<Schedule> items) {
  }

  ScheduleSet load(boolean forUpdate);

  void replace(List<Schedule> items, int newVersion, Actor actor);
}
