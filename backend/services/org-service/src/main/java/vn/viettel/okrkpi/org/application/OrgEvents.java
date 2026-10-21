package vn.viettel.okrkpi.org.application;

/** Mã sự kiện SVC-04 theo baseline mục 7. */
public enum OrgEvents {
  /** EVT-14 DonViDaThayDoi. */
  UNIT_CHANGED("org.unit.changed.v1", "DonViDaThayDoi", "OrgUnit"),
  /** EVT-15 PhanCongDaThayDoi. */
  ASSIGNMENT_CHANGED("org.assignment.changed.v1", "PhanCongDaThayDoi", "Assignment"),
  /** EVT-16 NhanSuDaThayDoi. */
  PERSON_CHANGED("org.person.changed.v1", "NhanSuDaThayDoi", "Person");

  private final String topic;
  private final String eventType;
  private final String aggregateType;

  OrgEvents(String topic, String eventType, String aggregateType) {
    this.topic = topic;
    this.eventType = eventType;
    this.aggregateType = aggregateType;
  }

  public String topic() {
    return topic;
  }

  public String eventType() {
    return eventType;
  }

  public String aggregateType() {
    return aggregateType;
  }
}
