import 'package:flutter/material.dart';

/// Xếp loại theo điểm (minh hoạ, dùng để có mã cho kiểm thử).
String xepLoai(double diem) => diem >= 90 ? 'Hoàn thành xuất sắc' : 'Hoàn thành';

void main() => runApp(const MaterialApp(home: Scaffold(body: Center(child: Text('Mission')))));
