import 'package:test/test.dart';

void main() {
  group('group B', () {
    test('test 3', () {
      print('EXECUTED_3 ENV=${const String.fromEnvironment('BATCH_SCOPE')}');
      expect(true, isTrue);
    });
    test('test 4', () {
      print('EXECUTED_4 ENV=${const String.fromEnvironment('BATCH_SCOPE')}');
      expect(true, isTrue);
    });
  });
}
