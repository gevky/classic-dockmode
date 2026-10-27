import 'package:displaylink_dock_companion/main.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('shows dock shell while native status loads', (tester) async {
    await tester.pumpWidget(const DockCompanionApp());
    expect(find.text('Classic DockMode'), findsOneWidget);
    expect(find.text('for Retroid Pocket Classic'), findsOneWidget);
  });
}
