import 'package:test/test.dart';

void main() {
  group('group C', () {
    test('test 5', () {
      print('EXECUTED_HIDDEN_5');
      fail('Hidden file must never run');
    });
  });
}
