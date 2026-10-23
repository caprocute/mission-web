import 'dart:async';
import 'dart:io';

import 'package:ngoai_tuyen/ngoai_tuyen.dart';

class MemoryKeyStore implements SecureKeyStore {
  final values = <String, String>{};

  @override
  Future<void> delete(String name) async => values.remove(name);

  @override
  Future<String?> read(String name) async => values[name];

  @override
  Future<void> write(String name, String value) async => values[name] = value;
}

class FakeNetwork implements NetworkMonitor {
  FakeNetwork({bool online = true}) : _online = online;

  bool _online;
  final _changes = StreamController<bool>.broadcast(sync: true);

  @override
  bool get isOnline => _online;

  @override
  Stream<bool> get changes => _changes.stream;

  set online(bool v) {
    _online = v;
    _changes.add(v);
  }
}

/// Trả lần lượt các phản hồi đã định; hết danh sách thì trả 201. Ghi lại mọi lệnh đã nhận.
class ScriptedSender implements RequestSender {
  ScriptedSender([List<Object> script = const []]) : _script = [...script];

  final List<Object> _script;
  final received = <SyncRequest>[];

  @override
  Future<SyncResponse> send(SyncRequest request) async {
    received.add(request);
    if (_script.isEmpty) return const SyncResponse(status: 201);
    final next = _script.removeAt(0);
    if (next is TransportException) throw next;
    return next as SyncResponse;
  }
}

class ManualClock {
  ManualClock(this.now);

  DateTime now;
  DateTime call() => now;
  void advance(Duration d) => now = now.add(d);
}

Future<Directory> tempDir() => Directory.systemTemp.createTemp('ngoai_tuyen_');
