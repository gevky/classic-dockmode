import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const DockCompanionApp());
}

class DockCompanionApp extends StatelessWidget {
  const DockCompanionApp({super.key});

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFF53D38C);
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Classic DockMode',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: seed,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFF0B0F12),
        cardTheme: const CardThemeData(
          color: Color(0xE8192025),
          margin: EdgeInsets.zero,
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(16)),
            side: BorderSide(color: Color(0xFF263038)),
          ),
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: Colors.transparent,
          surfaceTintColor: Colors.transparent,
        ),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ),
        outlinedButtonTheme: OutlinedButtonThemeData(
          style: OutlinedButton.styleFrom(
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ),
      ),
      home: const DockHomePage(),
    );
  }
}

class DockHomePage extends StatefulWidget {
  const DockHomePage({super.key});

  @override
  State<DockHomePage> createState() => _DockHomePageState();
}

class _DockHomePageState extends State<DockHomePage>
    with WidgetsBindingObserver {
  static const _channel = MethodChannel('displaylink_dock/system');

  Map<String, dynamic>? _status;
  Timer? _pollTimer;
  bool _busy = false;
  String? _error;

  Map<String, dynamic> get _preferences {
    final value = _status?['preferences'];
    return value is Map
        ? Map<String, dynamic>.from(value)
        : <String, dynamic>{};
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _pollTimer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      final value = await _channel.invokeMapMethod<String, dynamic>(
        'getStatus',
      );
      if (!mounted || value == null) return;
      setState(() {
        _status = Map<String, dynamic>.from(value);
        _error = null;
      });
    } on PlatformException catch (error) {
      if (mounted) setState(() => _error = error.message ?? error.code);
    }
  }

  Future<Map<String, dynamic>?> _invoke(
    String method, {
    Map<String, Object?>? arguments,
    bool showResult = true,
  }) async {
    if (_busy) return null;
    setState(() => _busy = true);
    try {
      final value = await _channel.invokeMapMethod<String, dynamic>(
        method,
        arguments,
      );
      if (!mounted) return null;
      final result = value == null
          ? <String, dynamic>{}
          : Map<String, dynamic>.from(value);
      if (showResult) {
        final succeeded =
            result['succeeded'] as bool? ?? result['ready'] as bool? ?? true;
        final message =
            result['message'] as String? ??
            result['detail'] as String? ??
            'Saved';
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(message),
            backgroundColor: succeeded
                ? const Color(0xFF16643C)
                : Theme.of(context).colorScheme.error,
          ),
        );
      }
      return result;
    } on PlatformException catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.message ?? error.code)));
      }
      return null;
    } finally {
      if (mounted) setState(() => _busy = false);
      await _refresh();
    }
  }

  Future<void> _save(Map<String, Object?> values) async {
    await _invoke('savePreferences', arguments: values, showResult: false);
  }

  Future<void> _selectFrontend() async {
    if (_busy) return;
    setState(() => _busy = true);
    List<Map<String, dynamic>> apps = [];
    try {
      final values = await _channel.invokeListMethod<dynamic>(
        'listLaunchableApps',
      );
      apps = (values ?? [])
          .whereType<Map<dynamic, dynamic>>()
          .map((value) => Map<String, dynamic>.from(value))
          .toList();
    } on PlatformException catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(error.message ?? 'Could not load apps. Try again.'),
          ),
        );
      }
      return;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    if (!mounted) return;
    final selected = await showModalBottomSheet<Map<String, dynamic>?>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) => _AppPickerSheet(apps: apps),
    );
    if (selected == null) return;
    final packageName = selected['packageName'] as String?;
    await _save({
      'frontendPackage': packageName,
      'launchEnabled': packageName != null,
    });
  }

  Future<void> _editCustomResolution() async {
    final widthController = TextEditingController(
      text: '${_preferences['customWidth'] ?? 1600}',
    );
    final heightController = TextEditingController(
      text: '${_preferences['customHeight'] ?? 900}',
    );
    final result = await showDialog<(int, int)>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Custom resolution'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: widthController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Landscape width'),
            ),
            TextField(
              controller: heightController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Landscape height'),
            ),
            const SizedBox(height: 12),
            const Text(
              'Allowed: landscape 640×360 through 3840×2160. The mode must pass a 15-second test before automatic use.',
              style: TextStyle(color: Colors.white70, fontSize: 13),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () {
              final width = int.tryParse(widthController.text);
              final height = int.tryParse(heightController.text);
              if (width == null || height == null) return;
              Navigator.pop(context, (width, height));
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
    widthController.dispose();
    heightController.dispose();
    if (result == null) return;
    await _save({
      'profile': 'custom',
      'customWidth': result.$1,
      'customHeight': result.$2,
    });
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    final testActive = status?['testActive'] as bool? ?? false;
    final sessionActive = status?['sessionActive'] as bool? ?? false;
    return Scaffold(
      appBar: AppBar(
        title: const Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Classic DockMode'),
            Text(
              'for Retroid Pocket Classic',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.normal),
            ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: 'Refresh',
            onPressed: _busy ? null : _refresh,
            icon: const Icon(Icons.refresh),
          ),
        ],
        bottom: _busy
            ? const PreferredSize(
                preferredSize: Size.fromHeight(2),
                child: LinearProgressIndicator(minHeight: 2),
              )
            : null,
      ),
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0xFF10191A), Color(0xFF0B0F12)],
          ),
        ),
        child: SafeArea(
          child: status == null
              ? Center(
                  child: _error == null
                      ? const CircularProgressIndicator()
                      : Text(_error!, textAlign: TextAlign.center),
                )
              : RefreshIndicator(
                  onRefresh: _refresh,
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
                    children: [
                      _DockHeroCard(status: status),
                      const SizedBox(height: 12),
                      if (status['backendReady'] != true) ...[
                        const _SectionLabel('One-time setup'),
                        _AccessCard(
                          status: status,
                          onGrantSettings: () =>
                              _channel.invokeMethod<void>('openWriteSettings'),
                          onProbe: _busy ? null : () => _invoke('probeBackend'),
                        ),
                        const SizedBox(height: 12),
                      ],
                      const _SectionLabel('Profile'),
                      _OutputCard(
                        status: status,
                        onSelect: (value) => _save({'profile': value}),
                        onEditCustom: _editCustomResolution,
                        onRotationChanged: (value) =>
                            _save({'rotationMode': value}),
                      ),
                      const SizedBox(height: 10),
                      if (testActive) ...[
                        _TestCountdownCard(status: status),
                        const SizedBox(height: 10),
                        Row(
                          children: [
                            Expanded(
                              child: FilledButton(
                                onPressed: _busy
                                    ? null
                                    : () => _invoke('keepTest'),
                                child: const Text('Keep'),
                              ),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: OutlinedButton(
                                onPressed: _busy
                                    ? null
                                    : () => _invoke('restore'),
                                child: const Text('Revert'),
                              ),
                            ),
                          ],
                        ),
                      ] else
                        OutlinedButton.icon(
                          style: OutlinedButton.styleFrom(
                            padding: const EdgeInsets.symmetric(vertical: 15),
                          ),
                          onPressed:
                              _busy ||
                                  status['backendReady'] != true ||
                                  status['externalDisplay'] == null ||
                                  sessionActive
                              ? null
                              : () => _invoke('testSelectedOutput'),
                          icon: const Icon(Icons.tv),
                          label: Text(
                            status['selectedProfileConfirmed'] == true
                                ? 'Test profile'
                                : 'Test this profile',
                          ),
                        ),
                      if (!testActive)
                        Padding(
                          padding: const EdgeInsets.fromLTRB(4, 6, 4, 0),
                          child: Text(
                            sessionActive
                                ? 'Exit Dock Mode before testing another profile.'
                                : status['externalDisplay'] == null
                                ? 'Connect your dock and start Presenter to test.'
                                : 'Optional for 1080p. Other profiles need one confirmation.',
                            style: const TextStyle(
                              color: Colors.white60,
                              fontSize: 12,
                            ),
                          ),
                        ),
                      const SizedBox(height: 16),
                      const _SectionLabel('When docked'),
                      _BrightnessCard(
                        preferences: _preferences,
                        onModeChanged: (value) =>
                            _save({'brightnessMode': value}),
                        onBrightnessChanged: (value) =>
                            _save({'customBrightness': value}),
                      ),
                      const SizedBox(height: 12),
                      Card(
                        child: ListTile(
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 18,
                            vertical: 8,
                          ),
                          leading: Container(
                            width: 44,
                            height: 44,
                            decoration: BoxDecoration(
                              color: Theme.of(context).colorScheme.primary
                                  .withValues(alpha: 0.12),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: const Icon(Icons.apps_rounded),
                          ),
                          title: const Text('App to open'),
                          subtitle: Text(
                            status['frontendLabel'] as String? ??
                                'None — stay on the current screen',
                          ),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: _busy ? null : _selectFrontend,
                        ),
                      ),
                      const SizedBox(height: 16),
                      const _SectionLabel('Automatic docking'),
                      Card(
                        child: SwitchListTile(
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 18,
                            vertical: 8,
                          ),
                          secondary: const Icon(Icons.settings_input_component),
                          title: const Text('Keep ready in background'),
                          subtitle: Text(
                            _preferences['backgroundDetection'] as bool? ?? true
                                ? 'Starts without opening this app. Android may list it under Active apps.'
                                : 'No idle background task. Keep this app open when connecting the dock.',
                          ),
                          value:
                              _preferences['backgroundDetection'] as bool? ??
                              true,
                          onChanged: _busy
                              ? null
                              : (value) =>
                                    _save({'backgroundDetection': value}),
                        ),
                      ),
                      const SizedBox(height: 16),
                      if (sessionActive && !testActive)
                        FilledButton.tonalIcon(
                          style: FilledButton.styleFrom(
                            padding: const EdgeInsets.symmetric(vertical: 16),
                          ),
                          onPressed: _busy ? null : () => _invoke('restore'),
                          icon: const Icon(Icons.logout),
                          label: const Text('Exit Dock Mode'),
                        ),
                      const SizedBox(height: 8),
                      TextButton.icon(
                        style: TextButton.styleFrom(
                          foregroundColor: Theme.of(context).colorScheme.error,
                        ),
                        onPressed: _busy ? null : () => _invoke('restore'),
                        icon: const Icon(Icons.settings_backup_restore),
                        label: const Text('Restore handheld defaults'),
                      ),
                      const SizedBox(height: 12),
                      _DiagnosticsCard(status: status),
                    ],
                  ),
                ),
        ),
      ),
    );
  }
}

class _DockHeroCard extends StatelessWidget {
  const _DockHeroCard({required this.status});
  final Map<String, dynamic> status;

  @override
  Widget build(BuildContext context) {
    final active = status['sessionActive'] == true;
    final testing = status['testActive'] == true;
    final external = status['externalDisplay'] is Map;
    final ready = status['backendReady'] == true;
    final preferences = status['preferences'] is Map
        ? Map<String, dynamic>.from(status['preferences'] as Map)
        : <String, dynamic>{};
    final background = preferences['backgroundDetection'] as bool? ?? true;
    final (title, subtitle, icon) = switch ((
      active,
      testing,
      external,
      ready,
    )) {
      (true, _, _, _) => (
        'Dock Mode active',
        '${status['targetWidth']} × ${status['targetHeight']} landscape. Unplug to restore your handheld.',
        Icons.sports_esports,
      ),
      (_, true, _, _) => (
        'How does it look?',
        'Keep the picture if it fills your TV correctly. It will revert automatically.',
        Icons.tv,
      ),
      (_, _, true, true) => (
        'Display connected',
        'Dock Mode starts automatically when Presenter finishes connecting.',
        Icons.tv,
      ),
      (_, _, _, true) => (
        'Ready when you dock',
        background
            ? 'You can close this app. Plug in the dock and everything starts automatically.'
            : 'Background detection is off. Keep this app open when you connect the dock.',
        Icons.dock,
      ),
      _ => (
        'Let’s finish setup',
        'Grant system access below, then your handheld will be ready to dock.',
        Icons.tune,
      ),
    };
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        color: active ? const Color(0xFF15392D) : const Color(0xFF182C28),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 25, color: const Color(0xFF8FF0B8)),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  title,
                  style: const TextStyle(
                    fontSize: 22,
                    height: 1.2,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            subtitle,
            style: const TextStyle(color: Colors.white70, height: 1.4),
          ),
          const SizedBox(height: 14),
          Text(
            external
                ? '${(status['externalDisplay'] as Map)['name']} · ${_hz((status['externalDisplay'] as Map)['refreshRate'])}'
                : background
                ? 'Automatic detection on'
                : 'Detection while app is open',
            style: const TextStyle(color: Color(0xFF8FF0B8), fontSize: 12),
          ),
          if (external &&
              ((status['externalDisplay'] as Map)['refreshRate'] as num? ?? 0) <
                  50)
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text(
                'Output is below 60 Hz. Presenter controls the refresh rate.',
                style: TextStyle(color: Colors.amber, fontSize: 12),
              ),
            ),
        ],
      ),
    );
  }
}

class _AppPickerSheet extends StatefulWidget {
  const _AppPickerSheet({required this.apps});
  final List<Map<String, dynamic>> apps;

  @override
  State<_AppPickerSheet> createState() => _AppPickerSheetState();
}

class _AppPickerSheetState extends State<_AppPickerSheet> {
  String query = '';

  @override
  Widget build(BuildContext context) {
    final filtered = widget.apps.where((app) {
      final label = app['label'] as String? ?? '';
      final packageName = app['packageName'] as String? ?? '';
      final needle = query.toLowerCase();
      return label.toLowerCase().contains(needle) ||
          packageName.toLowerCase().contains(needle);
    }).toList();
    return SafeArea(
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.78,
        child: Column(
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 4, 20, 12),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'What should open?',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    SizedBox(height: 4),
                    Text(
                      'Choose any launcher or game app on your handheld.',
                      style: TextStyle(color: Colors.white60),
                    ),
                  ],
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: TextField(
                autofocus: true,
                onChanged: (value) => setState(() => query = value),
                decoration: const InputDecoration(
                  hintText: 'Search apps',
                  prefixIcon: Icon(Icons.search),
                  filled: true,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.all(Radius.circular(18)),
                    borderSide: BorderSide.none,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 8),
            ListTile(
              leading: const CircleAvatar(
                child: Icon(Icons.do_not_disturb_alt),
              ),
              title: const Text('Don’t open anything'),
              onTap: () => Navigator.pop(context, <String, dynamic>{}),
            ),
            const Divider(height: 1),
            Expanded(
              child: filtered.isEmpty
                  ? const Center(child: Text('No matching apps'))
                  : ListView.builder(
                      itemCount: filtered.length,
                      itemBuilder: (context, index) {
                        final app = filtered[index];
                        final label = app['label'] as String? ?? 'Application';
                        return ListTile(
                          leading: CircleAvatar(
                            backgroundColor: _appColor(label),
                            foregroundColor: Colors.white,
                            child: Text(
                              label.isEmpty
                                  ? '?'
                                  : label.characters.first.toUpperCase(),
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                          title: Text(label),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => Navigator.pop(context, app),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Color _appColor(String label) {
    const colors = [
      Color(0xFF3478F6),
      Color(0xFF9B51E0),
      Color(0xFFFF7A45),
      Color(0xFF00A884),
      Color(0xFFE5484D),
    ];
    return colors[label.hashCode.abs() % colors.length];
  }
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(4, 8, 4, 8),
    child: Text(
      text,
      style: TextStyle(
        color: Theme.of(context).colorScheme.primary,
        fontWeight: FontWeight.w700,
        fontSize: 16,
      ),
    ),
  );
}

class _OutputCard extends StatelessWidget {
  const _OutputCard({
    required this.status,
    required this.onSelect,
    required this.onEditCustom,
    required this.onRotationChanged,
  });

  final Map<String, dynamic> status;
  final ValueChanged<String> onSelect;
  final VoidCallback onEditCustom;
  final ValueChanged<String> onRotationChanged;

  @override
  Widget build(BuildContext context) {
    final preferences = Map<String, dynamic>.from(status['preferences'] as Map);
    final profile = preferences['profile'] as String? ?? '1080p';
    final width = status['selectedTargetWidth'];
    final height = status['selectedTargetHeight'];
    final confirmed = status['selectedProfileConfirmed'] == true;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          children: [
            _ProfileTile(
              icon: Icons.hd,
              title: '1080p — Recommended',
              subtitle: 'Best choice for this handheld',
              selected: profile == '1080p',
              onTap: () => onSelect('1080p'),
            ),
            _ProfileTile(
              icon: Icons.speed,
              title: '720p — Performance',
              subtitle: 'Lighter on the handheld',
              selected: profile == '720p',
              onTap: () => onSelect('720p'),
            ),
            ExpansionTile(
              initiallyExpanded: profile == 'match' || profile == 'custom',
              tilePadding: const EdgeInsets.symmetric(horizontal: 12),
              title: const Text('Advanced output'),
              subtitle: Text(
                profile == 'match'
                    ? 'Match display selected'
                    : profile == 'custom'
                    ? 'Custom resolution selected'
                    : 'Match display, custom size & rotation',
              ),
              children: [
                _ProfileTile(
                  icon: Icons.fit_screen,
                  title: 'Match your display',
                  subtitle: 'Uses the TV’s resolution',
                  selected: profile == 'match',
                  onTap: () => onSelect('match'),
                ),
                _ProfileTile(
                  icon: Icons.tune,
                  title: 'Custom',
                  subtitle:
                      '${preferences['customWidth']} × ${preferences['customHeight']}',
                  selected: profile == 'custom',
                  onTap: onEditCustom,
                ),
                if (width != null && height != null)
                  Container(
                    margin: const EdgeInsets.fromLTRB(6, 8, 6, 8),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
                    decoration: BoxDecoration(
                      color: confirmed
                          ? const Color(0x2214B86E)
                          : const Color(0x22FFB020),
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Row(
                      children: [
                        Icon(
                          confirmed ? Icons.verified : Icons.warning_amber,
                          color: confirmed
                              ? Theme.of(context).colorScheme.primary
                              : Colors.amber,
                          size: 20,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            confirmed
                                ? '$width × $height is ready for automatic docking'
                                : 'Test $width × $height once before automatic use',
                          ),
                        ),
                      ],
                    ),
                  ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
                  child: DropdownButtonFormField<String>(
                    initialValue:
                        preferences['rotationMode'] as String? ?? 'auto',
                    decoration: const InputDecoration(
                      labelText: 'Landscape rotation',
                    ),
                    items: const [
                      DropdownMenuItem(value: 'auto', child: Text('Automatic')),
                      DropdownMenuItem(value: '90', child: Text('90°')),
                      DropdownMenuItem(value: '270', child: Text('270°')),
                    ],
                    onChanged: (value) {
                      if (value != null) onRotationChanged(value);
                    },
                  ),
                ),
              ],
            ),
            if ((width as num? ?? 0) > 1920)
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 0, 16, 16),
                child: Text(
                  'Higher resolutions increase GPU load and may be clamped by the handheld. 1080p is recommended for gaming.',
                  style: TextStyle(color: Colors.amber),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _ProfileTile extends StatelessWidget {
  const _ProfileTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 3),
    child: Material(
      color: selected
          ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.13)
          : Colors.transparent,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 13),
          child: Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: selected
                      ? Theme.of(context).colorScheme.primary
                            .withValues(alpha: 0.18)
                      : Colors.white.withValues(alpha: 0.06),
                  borderRadius: BorderRadius.circular(13),
                ),
                child: Icon(
                  icon,
                  color: selected
                      ? Theme.of(context).colorScheme.primary
                      : Colors.white70,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: const TextStyle(
                        color: Colors.white60,
                        fontSize: 13,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(
                selected ? Icons.check_circle : Icons.circle_outlined,
                color: selected
                    ? Theme.of(context).colorScheme.primary
                    : Colors.white24,
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _BrightnessCard extends StatelessWidget {
  const _BrightnessCard({
    required this.preferences,
    required this.onModeChanged,
    required this.onBrightnessChanged,
  });

  final Map<String, dynamic> preferences;
  final ValueChanged<String> onModeChanged;
  final ValueChanged<int> onBrightnessChanged;

  @override
  Widget build(BuildContext context) {
    final mode = preferences['brightnessMode'] as String? ?? 'minimum';
    final brightness = preferences['customBrightness'] as int? ?? 16;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Handheld brightness while docked'),
            const SizedBox(height: 10),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'minimum', label: Text('Minimum')),
                ButtonSegment(value: 'keep', label: Text('Keep')),
                ButtonSegment(value: 'custom', label: Text('Custom')),
              ],
              selected: {mode},
              onSelectionChanged: (values) => onModeChanged(values.first),
            ),
            if (mode == 'custom') ...[
              const SizedBox(height: 8),
              Text('Custom level: $brightness'),
              Slider(
                min: 1,
                max: 255,
                divisions: 254,
                value: brightness.toDouble(),
                onChanged: (value) => onBrightnessChanged(value.round()),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _AccessCard extends StatelessWidget {
  const _AccessCard({
    required this.status,
    required this.onGrantSettings,
    required this.onProbe,
  });

  final Map<String, dynamic> status;
  final VoidCallback onGrantSettings;
  final VoidCallback? onProbe;

  @override
  Widget build(BuildContext context) {
    final secure = status['writeSecureSettingsGranted'] == true;
    final settings = status['writeSettingsGranted'] == true;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _InlineStatus(label: 'Direct ADB permission', good: secure),
            const SizedBox(height: 10),
            _InlineStatus(label: 'Modify system settings', good: settings),
            const SizedBox(height: 10),
            _InlineStatus(
              label: 'Backend: ${status['backendName']}',
              good: status['backendReady'] == true,
            ),
            const SizedBox(height: 8),
            Text(
              status['backendDetail'] as String? ?? '',
              style: const TextStyle(color: Colors.white70, fontSize: 13),
            ),
            if (!secure) ...[
              const SizedBox(height: 12),
              SelectableText(
                'adb shell pm grant ${status['packageName']} android.permission.WRITE_SECURE_SETTINGS',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
              ),
            ],
            const SizedBox(height: 12),
            if (!settings)
              OutlinedButton(
                onPressed: onGrantSettings,
                child: const Text('Grant Modify Settings'),
              ),
            OutlinedButton(
              onPressed: onProbe,
              child: const Text('Check system access'),
            ),
          ],
        ),
      ),
    );
  }
}

class _InlineStatus extends StatelessWidget {
  const _InlineStatus({required this.label, required this.good});
  final String label;
  final bool good;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Icon(
        good ? Icons.check_circle : Icons.cancel,
        size: 18,
        color: good
            ? Theme.of(context).colorScheme.primary
            : Theme.of(context).colorScheme.error,
      ),
      const SizedBox(width: 8),
      Expanded(child: Text(label)),
    ],
  );
}

class _TestCountdownCard extends StatelessWidget {
  const _TestCountdownCard({required this.status});
  final Map<String, dynamic> status;

  @override
  Widget build(BuildContext context) {
    final deadline = status['testDeadlineMillis'] as int? ?? 0;
    final seconds = ((deadline - DateTime.now().millisecondsSinceEpoch) / 1000)
        .ceil()
        .clamp(0, 15);
    return Card(
      color: const Color(0xFF4A3210),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            const Icon(Icons.timer, color: Colors.amber),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                'Output test • $seconds seconds to keep',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DiagnosticsCard extends StatelessWidget {
  const _DiagnosticsCard({required this.status});
  final Map<String, dynamic> status;

  String _diagnostics() {
    final externalValue = status['externalDisplay'];
    final external = externalValue is Map
        ? Map<String, dynamic>.from(externalValue)
        : null;
    return [
      'Device: ${status['deviceModel']}',
      'OS: ${status['androidVersion']}',
      'Natural display: ${status['naturalWidth']} × ${status['naturalHeight']} • ${_hz(status['naturalRefreshRate'])}',
      'Current logical: ${status['logicalWidth']} × ${status['logicalHeight']}',
      if (external == null)
        'External display: not connected'
      else ...[
        'External display: ${external['name']}',
        'External mode: ${external['width']} × ${external['height']} • ${_hz(external['refreshRate'])}',
        'External display ID: ${external['displayId']}',
      ],
      'Presenter: ${status['presenterInstalled'] == true ? 'installed' : 'not installed'}',
      'Backend: ${status['backendName']} (${status['backendReady'] == true ? 'ready' : 'not ready'})',
      'WRITE_SECURE_SETTINGS: ${status['writeSecureSettingsGranted'] == true ? 'granted' : 'missing'}',
      'WRITE_SETTINGS: ${status['writeSettingsGranted'] == true ? 'granted' : 'missing'}',
      'Dock session: ${status['sessionPhase']}',
      'Automatic docking: ${(status['preferences'] as Map?)?['automaticDocking'] == true ? 'enabled' : 'disabled'}',
    ].join('\n');
  }

  @override
  Widget build(BuildContext context) => Card(
    child: ExpansionTile(
      leading: const Icon(Icons.developer_mode),
      title: const Text('Diagnostics'),
      subtitle: const Text('Hardware and recovery state'),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SelectableText(
                _diagnostics(),
                style: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 12,
                  height: 1.5,
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: _diagnostics()));
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Diagnostics copied')),
                    );
                  }
                },
                icon: const Icon(Icons.copy),
                label: const Text('Copy diagnostics'),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

String _hz(Object? value) {
  final rate = value is num ? value.toDouble() : 0.0;
  return '${rate.toStringAsFixed(1)} Hz';
}
