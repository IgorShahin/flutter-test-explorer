import 'package:test/test.dart';

void main() {
  group('Filtered suite', () {
    test('A.* [one] (x)+? ^| "quoted"', () {
      print('EXECUTED_A ENV=${const String.fromEnvironment('FILTER_SCOPE')}');
      expect(1 + 1, 2);
    });
    test('B - кириллица 😀', () {
      print('EXECUTED_B');
      expect(true, isTrue);
    });
    test('C', () {
      print('EXECUTED_C');
      fail('Excluded C must never execute');
    });
    test('D', () {
      print('EXECUTED_D');
      fail('Excluded D must never execute');
    });
  });
}
