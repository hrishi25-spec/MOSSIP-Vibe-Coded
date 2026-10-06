# Dart liveness layer

- `pigeon/liveness.dart` (repo root `android_client/pigeon/`) — Pigeon API;
  regenerate with `dart run pigeon --input pigeon/liveness.dart`.
- `liveness_view_model.dart` — `ChangeNotifier` mirroring the native
  orchestrator state machine; exposes `captureEnabled` (PASSED + valid gate).
- `liveness_view.dart` — widget rendering preview, oval overlay, challenge
  prompts, hints, generic failure messages; all text resolved from i18n keys.

Wire-up sketch (resident capture screen):

```dart
final vm = LivenessViewModel();
// after Pigeon registration in the Android embedding:
vm.bindFlutterApi(livenessFlutterApi);

LivenessView(
  viewModel: vm,
  onRetry: () => vm.startSession(LivenessRole.RESIDENT),
  onCancel: () => vm.cancelSession(),
);

// capture button:
onPressed: vm.captureEnabled && await vm.ensureGateValidForCapture()
    ? () => startFaceCapture()   // existing SBI capture flow (orchestration spec §5.1)
    : null,
```

Operator / supervisor login dialogs reuse the same widget with
`LivenessRole.OPERATOR` / `LivenessRole.SUPERVISOR` (analyse.md §6.2/§6.3: supervisor
policy is stricter by default — higher threshold, two challenges).
