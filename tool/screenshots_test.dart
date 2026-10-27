// Captures the real Flutter widgets with illustrative Android channel data.
// Run: flutter test --update-goldens tool/screenshots_test.dart
import 'dart:io';

import 'package:displaylink_dock_companion/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const channel = MethodChannel('displaylink_dock/system');

Map<String, dynamic> exampleStatus() => {
  'packageName': 'com.displaylinkdock.displaylink_dock_companion',
  'deviceModel': 'Retroid Pocket Classic',
  'androidVersion': 'Android 14',
  'presenterInstalled': true,
  'backendName': 'Direct',
  'backendReady': true,
  'writeSecureSettingsGranted': true,
  'writeSettingsGranted': true,
  'sessionActive': false,
  'testActive': false,
  'sessionPhase': 'inactive',
  'naturalWidth': 1080,
  'naturalHeight': 1240,
  'naturalRefreshRate': 60.0,
  'logicalWidth': 1080,
  'logicalHeight': 1240,
  'selectedTargetWidth': 1920,
  'selectedTargetHeight': 1080,
  'selectedProfileConfirmed': true,
  'frontendLabel': 'ES-DE',
  'externalDisplay': null,
  'preferences': {
    'profile': '1080p',
    'customWidth': 1600,
    'customHeight': 900,
    'rotationMode': 'auto',
    'brightnessMode': 'minimum',
    'customBrightness': 16,
    'backgroundDetection': true,
    'automaticDocking': true,
    'frontendPackage': 'org.es_de.frontend',
  },
};

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    // Resolve fonts from the SDK used to run this command; no machine paths
    // or extra font assets are shipped with the app.
    final sdk = Platform.environment['FLUTTER_ROOT'];
    if (sdk == null) throw StateError('Set FLUTTER_ROOT to your Flutter SDK.');
    final font = FontLoader('Roboto');
    font.addFont(
      File('$sdk/engine/src/flutter/txt/third_party/fonts/Roboto-Regular.ttf')
          .readAsBytes()
          .then((bytes) => ByteData.sublistView(bytes)),
    );
    await font.load();
    final icons = FontLoader('MaterialIcons');
    icons.addFont(
      File('$sdk/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf')
          .readAsBytes()
          .then((bytes) => ByteData.sublistView(bytes)),
    );
    await icons.load();
  });

  testWidgets('capture settings and docked UI previews', (tester) async {
    tester.view.devicePixelRatio = 2.5;
    tester.view.physicalSize = const Size(1080, 3000);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final status = exampleStatus();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'getStatus') return status;
      return <String, dynamic>{};
    });
    await tester.pumpWidget(const DockCompanionApp());
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<OutlinedButton>(
            find.widgetWithText(OutlinedButton, 'Test profile'),
          )
          .onPressed,
      isNull,
    );
    await expectLater(
      find.byType(MaterialApp),
      matchesGoldenFile('../docs/screenshots/settings.png'),
    );

    status.addAll({
      'sessionActive': true,
      'sessionPhase': 'active',
      'targetWidth': 1920,
      'targetHeight': 1080,
      'externalDisplay': {
        'displayId': 4,
        'name': 'DisplayLink monitor',
        'width': 2560,
        'height': 1440,
        'refreshRate': 60.0,
      },
    });
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();
    await expectLater(
      find.byType(MaterialApp),
      matchesGoldenFile('../docs/screenshots/docked.png'),
    );
    expect(tester.takeException(), isNull);
    // Exercise the Classic's short portrait viewport and docked landscape.
    for (final size in [const Size(1080, 1240), const Size(1920, 1080)]) {
      tester.view.physicalSize = size;
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }
    await tester.pumpWidget(const SizedBox());
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
}
