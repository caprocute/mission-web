/// Mức phân loại dữ liệu C0..C3 (ADR-06, TL-03 "Phân loại dữ liệu").
enum DataClass { c0, c1, c2, c3 }

/// Dữ liệu C3 chỉ đi theo luồng E2EE đã thiết kế, không bao giờ nằm trên máy (MOB-02, quy định di động).
void rejectC3(DataClass dataClass) {
  if (dataClass == DataClass.c3) {
    throw ArgumentError.value(dataClass, 'dataClass', 'Không lưu dữ liệu C3 trên thiết bị');
  }
}
