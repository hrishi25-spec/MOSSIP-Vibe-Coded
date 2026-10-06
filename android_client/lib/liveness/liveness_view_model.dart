import 'dart:async';

import 'package:flutter/foundation.dart';

import '../pigeon/liveness.dart';

/// UI states mirrored from the native orchestrator (spec §4). The Dart layer
/// renders state only — every decision stays native.
enum LivenessUiState {
  idle,
  initializing,
  positioning,
  passiveEvaluating,
  challengePrompt,
  challengeVerifying,
  challengePassed,
  attemptFailed,
  retryWait,
  passed,
  terminalFailure,
  deviceError,
  aborted,
}

LivenessUiState uiStateFrom(String? name) => LivenessUiState.values.firstWhere(
      (s) => s.name.toUpperCase() == (name ?? '').toUpperCase(),
      orElse: () => LivenessUiState.deviceError,
    );

/// ViewModel for the resident/operator/supervisor liveness gate
/// (spec §2 LivenessScreen / ViewModel). Sends start/cancel commands only;
/// receives generic events with i18n keys (R5).
class LivenessViewModel extends ChangeNotifier {
  LivenessViewModel({LivenessHostApi? hostApi}) : _hostApi = hostApi;

  final LivenessHostApi? _hostApi;
  StreamSubscription<_NativeEvent>? _events;

  LivenessUiState _state = LivenessUiState.idle;
  String? _uiMessageKey;
  String? _hint;
  String? _challenge;
  int? _challengeIndex;
  int? _challengeTotal;
  double? _progress;
  int _attemptsUsed = 0;
  int _attemptsMax = 3;
  String? _failCategory;
  int? _lockoutSeconds;
  String? _nextAction;
  bool _captureEnabled = false;

  LivenessUiState get state => _state;
  String? get uiMessageKey => _uiMessageKey;
  String? get hint => _hint;
  String? get challenge => _challenge;
  int? get challengeIndex => _challengeIndex;
  int? get challengeTotal => _challengeTotal;
  double? get progress => _progress;
  int get attemptsUsed => _attemptsUsed;
  int get attemptsMax => _attemptsMax;
  String? get failCategory => _failCategory;
  int? get lockoutSeconds => _lockoutSeconds;
  String? get nextAction => _nextAction;

  /// The existing capture button is enabled only on PASSED (spec §14).
  bool get captureEnabled => _captureEnabled;

  /// Wire the Pigeon FlutterApi callback (called once from the app shell).
  void bindFlutterApi(LivenessFlutterApi flutterApi) {
    // The generated dispatcher routes onState/onFinal here; see android glue.
    _events?.cancel();
    _events = _NativeEventBus.instance.attach(flutterApi, _handleEvent);
  }

  Future<void> startSession(LivenessRole role, {String? userId}) async {
    _reset();
    _state = LivenessUiState.initializing;
    notifyListeners();
    final host = _hostApi ?? _DefaultHost.instance;
    final result = await host.startSession(role.name, userId);
    if (result.errorCode != null) {
      _state = LivenessUiState.deviceError;
      _uiMessageKey = 'liveness.device.error';
      notifyListeners();
    }
    // Subsequent updates arrive via onState/onFinal through the FlutterApi.
  }

  Future<void> cancelSession() async {
    final host = _hostApi ?? _DefaultHost.instance;
    await host.cancelSession();
  }

  /// Gate consumption (spec §8): downstream capture/auth must refuse when the
  /// validity window has elapsed.
  Future<bool> ensureGateValidForCapture() async {
    final host = _hostApi ?? _DefaultHost.instance;
    return host.isGateValid();
  }

  void _handleEvent(_NativeEvent event) {
    if (event.isFinal) {
      _nextAction = event.finalResult.nextAction;
      _lockoutSeconds = event.finalResult.lockoutSeconds;
      switch (event.finalResult.outcome) {
        case 'PASSED':
          _state = LivenessUiState.passed;
          _captureEnabled = true; // gate satisfied → capture may proceed
          break;
        case 'ABORTED':
          _state = LivenessUiState.aborted;
          _captureEnabled = false;
          break;
        default:
          _state = LivenessUiState.terminalFailure;
          _captureEnabled = false;
      }
    } else {
      final e = event.stateEvent;
      _state = uiStateFrom(e.state);
      _uiMessageKey = e.uiMessageKey;
      _hint = e.hint;
      _challenge = e.challenge;
      _challengeIndex = e.challengeIndex;
      _challengeTotal = e.challengeTotal;
      _progress = e.progress;
      _attemptsUsed = e.attemptsUsed ?? _attemptsUsed;
      _attemptsMax = e.attemptsMax ?? _attemptsMax;
      _failCategory = e.failCategory;
      if (_state == LivenessUiState.passed) {
        _captureEnabled = true;
      } else if (_state == LivenessUiState.deviceError ||
          _state == LivenessUiState.terminalFailure) {
        _captureEnabled = false;
      }
    }
    notifyListeners();
  }

  void _reset() {
    _state = LivenessUiState.idle;
    _uiMessageKey = null;
    _hint = null;
    _challenge = null;
    _challengeIndex = null;
    _challengeTotal = null;
    _progress = null;
    _attemptsUsed = 0;
    _failCategory = null;
    _lockoutSeconds = null;
    _nextAction = null;
    _captureEnabled = false;
  }
}

enum LivenessRole { RESIDENT, OPERATOR, SUPERVISOR }

/// Internal bridge between the generated Pigeon dispatcher and the view model.
class _NativeEvent {
  final LivenessStateEvent? stateEvent;
  final LivenessFinalResult finalResult;
  final bool isFinal;
  _NativeEvent.state(this.stateEvent)
      : finalResult = LivenessFinalResult(),
        isFinal = false;
  _NativeEvent.final_(this.finalResult)
      : stateEvent = null,
        isFinal = true;
}

class _NativeEventBus {
  _NativeEventBus._();
  static final _NativeEventBus instance = _NativeEventBus._();

  final StreamController<_NativeEvent> _controller =
      StreamController.broadcast();

  StreamSubscription<_NativeEvent> attach(
          LivenessFlutterApi flutterApi, void Function(_NativeEvent) handler) =>
      _controller.stream.listen(handler);

  /// The Android glue's generated LivenessFlutterApiImpl forwards here.
  void dispatchState(LivenessStateEvent e) => _controller.add(_NativeEvent.state(e));
  void dispatchFinal(LivenessFinalResult r) => _controller.add(_NativeEvent.final_(r));
}

/// Fallback host used by widget tests and until the generated Pigeon host is
/// registered by the Android embedding (see android_client/README.md).
class _DefaultHost implements LivenessHostApi {
  static final _DefaultHost instance = _DefaultHost._();
  _DefaultHost._();

  @override
  Future<LivenessStartResult> startSession(String role, String? userId) async =>
      LivenessStartResult()
        ..errorCode = 'DEVICE_UNAVAILABLE';

  @override
  void cancelSession() { }

  @override
  bool isGateValid() => false;

  @override
  Map<String, String> diagnosticsSnapshot() => const {};
}
