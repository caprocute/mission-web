import 'package:flutter_test/flutter_test.dart';
import 'package:mau_flutter/main.dart';

void main() {
  test('xếp loại', () {
    expect(xepLoai(95), 'Hoàn thành xuất sắc');
    expect(xepLoai(60), 'Hoàn thành');
  });
}
