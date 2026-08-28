import 'package:test/test.dart';

void main() {
  group('group A', () {
    test('test 1', () {
      print('EXECUTED_1 ENV=${const String.fromEnvironment('BATCH_SCOPE')}');
      expect(1 + 1, 2);
    });
    test('test 2', () {
      print('EXECUTED_HIDDEN_2');
      fail('Hidden test must never run');
    });
  });
}
