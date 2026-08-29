import 'package:test/test.dart';

void main() {
  group('suite', () {
    test('edited', () async {
      await Future<void>.delayed(const Duration(seconds: 1));
      print('FIRST_OLD');
      expect(true, isTrue);
    });
    test('hidden', () {
      print('HIDDEN_RAN');
      fail('Excluded test must never execute');
    });
  });
}
