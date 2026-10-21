package vn.viettel.okrkpi.org.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Họ tên hiện hành của người (C1) để hiện "người ghi nhận"; không đọc cột C2. */
public interface PersonDirectory {

  Map<UUID, String> currentNames(Collection<UUID> personIds);
}
