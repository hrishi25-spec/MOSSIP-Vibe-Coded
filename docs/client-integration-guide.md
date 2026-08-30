# Client Integration Guide: MOSIP Registration Client ↔ Liveness/PAD Engine

## Overview

This guide explains how to wire the **MOSIP Registration Client** (Desktop JavaFX and
Android Flutter) to the face liveness + PAD engine via the REST API or in-process library.

**Two integration models are supported:**

| Model | Description | Best for |
|-------|-------------|----------|
| **HTTP Service** (Option B) | Spring Boot service running locally (`:8000`); clients call REST API | Quick integration, existing deployment |
| **In-Process Library** (Option A) | `face-liveness-engine` JAR added directly to client classpath | Zero-latency, offline-first, production |

---

## 1. REST API Endpoints (HTTP Service)

The Spring Boot backend exposes these endpoints:

### Session Lifecycle
```
POST   /api/v1/sessions                     → Create session
GET    /api/v1/sessions/{id}                → Get session status
POST   /api/v1/sessions/{id}/close          → Close session + get summary
```

### Frame Processing
```
POST   /api/v1/sessions/{id}/frames         → Submit a frame (base64 JPEG)
```

### Challenge Validation
```
POST   /api/v1/sessions/{id}/challenges/validate → Validate challenge frames
```

### Configuration
```
GET    /api/v1/config/{workflowType}         → Get config policy
GET    /api/v1/config/{workflowType}/effective → Get computed effective policy
PUT    /api/v1/config/{workflowType}         → Update config policy
```

### Monitoring
```
GET    /api/v1/metrics                       → Operational metrics
GET    /api/v1/sessions/{id}/audit           → Full audit trail
GET    /health                               → Health check
```

---

## 2. Typical Call Sequence

```
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│  Registration     │     │  Liveness        │     │  Registration    │
│  Client (UI)      │────▶│  Engine (API)    │────▶│  Client (Bio)    │
└──────────────────┘     └──────────────────┘     └──────────────────┘
        │                         │                         │
        │  1. createSession()     │                         │
        │────────────────────────▶│                         │
        │  ← session id           │                         │
        │                         │                         │
        │  2. submitFrame()       │                         │
        │────────────────────────▶│                         │
        │  ← action: "proceed"    │ (passive liveness pass) │
        │         OR              │                         │
        │  ← action: "escalate"   │ (needs active challenge)│
        │     + challenge type    │                         │
        │                         │                         │
        │  3. validateChallenge() │ (if escalated)          │
        │────────────────────────▶│                         │
        │  ← action: "proceed"    │                         │
        │         OR "retry"      │                         │
        │         OR "reject"     │                         │
        │                         │                         │
        │  4. closeSession()      │                         │
        │────────────────────────▶│                         │
        │  ← session summary      │                         │
        │                         │                         │
        │  5. proceed to capture  │────────────────────────▶│
        │     (liveness passed)   │                         │
```

---

## 3. Desktop (JavaFX) Integration

### 3.1 Add dependency to `registration-services/pom.xml`

```xml
<dependency>
    <groupId>io.mosip.liveness</groupId>
    <artifactId>pad-liveness-backend</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 3.2 Wire Streamer to LivenessClient

**File: `Streamer.java` — add frame listener:**

```java
// Add field:
private Consumer<byte[]> frameListener;

// Add setter:
public void setFrameListener(Consumer<byte[]> listener) {
    this.frameListener = listener;
}

// In the frame decode loop, after imageBytes = retrieveNextImage(urlStream):
if (frameListener != null) {
    frameListener.accept(imageBytes);
}
```

### 3.3 Create LivenessConfigAdapter

**File: `LivenessConfigAdapter.java` (new):**

```java
package io.mosip.registration.liveness;

import io.mosip.liveness.client.LivenessHttpClient;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class LivenessConfigAdapter {

    private LivenessHttpClient client;

    public LivenessConfigAdapter() {
        String livenessUrl = ApplicationContext.map()
            .getOrDefault("mosip.registration.liveness.service.url", "http://localhost:8000");
        this.client = new LivenessHttpClient(livenessUrl);
    }

    public LivenessHttpClient getClient() { return client; }

    public boolean isLivenessEnabled(String workflow) {
        return client.isLivenessEnabled(workflow);
    }

    public String getWorkflowForModality(String modality) {
        return switch (modality) {
            case "FACE" -> "RESIDENT";  // or OPERATOR/SUPERVISOR based on context
            default -> "RESIDENT";
        };
    }
}
```

### 3.4 Gate Face Capture with Liveness

**File: `BiometricFxControl.java` — modify `scanForModality()`:**

```java
// After bioService.captureModality() returns BiometricsDto:
if ("FACE".equals(modality) && livenessConfig.isLivenessEnabled("RESIDENT")) {
    DesktopLivenessAdapter adapter = new DesktopLivenessAdapter(
        () -> livenessConfig.getClient(),
        () -> "RESIDENT",
        () -> currentDeviceId
    );

    adapter.setCallback(new DesktopLivenessAdapter.LivenessStateCallback() {
        @Override
        public void onLivenessPassed(SessionSummary summary) {
            // Accept the captured face
            processAcceptedCapture(biometricsDto);
        }

        @Override
        public void onLivenessFailed(String message, boolean padAttack) {
            // Show error overlay, do not accept capture
            showLivenessError(message);
        }

        @Override
        public void onChallengeIssued(String type, int timeout, UUID id) {
            // Show challenge overlay: "Please blink" / "Please smile" / etc.
            showChallengeOverlay(type, timeout, id);
        }

        @Override
        public void onFrameProcessed(FrameResult result) {
            // Update UI: progress bar, face quality indicator
            updateLivenessIndicator(result);
        }
    });

    adapter.startSession();
    streamer.setFrameListener(adapter::onFrame);
}
```

### 3.5 Gate Operator/Supervisor Face Auth

**File: `SessionContext.java` — modify `validateFace()`:**

```java
// Before calling authService.authValidator():
if (livenessConfig.isLivenessEnabled("OPERATOR")) {
    LivenessHttpClient client = livenessConfig.getClient();
    LivenessClient.SessionInfo session = client.createSession("OPERATOR", deviceId);

    // Submit the captured face frame
    byte[] faceJpeg = biometricsDto.getFaceImage();
    LivenessClient.FrameResult result = client.submitFrame(session.id(), faceJpeg);

    if (!"proceed".equals(result.action())) {
        // Liveness failed — deny authentication
        client.closeSession(session.id());
        return false;
    }

    client.closeSession(session.id());
}

// Proceed with existing authValidator() call
return authService.authValidator(biometrics);
```

### 3.6 New JavaFX FXML Files

**`LivenessChallengeOverlay.fxml`:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.ProgressIndicator?>
<?import javafx.scene.effect.DropShadow?>

<StackPane fx:id="challengeOverlay" styleClass="liveness-overlay"
           visible="false" managed="false">
    <StackPane alignment="CENTER" styleClass="challenge-card">
        < DropShadow/>
        <Label fx:id="challengePrompt" styleClass="challenge-prompt"
               text="Please blink" wrapText="true"/>
        <ProgressIndicator fx:id="challengeProgress" maxWidth="60" maxHeight="60"/>
        <Label fx:id="challengeStatus" styleClass="challenge-status"
               text="Action detected" visible="false"/>
    </StackPane>
</StackPane>
```

---

## 4. Android (Flutter) Integration

### 4.1 Add dependency to `clientmanager/build.gradle`

```groovy
dependencies {
    implementation 'io.mosip.liveness:pad-liveness-backend:1.0.0-SNAPSHOT'
}
```

### 4.2 Create Pigeon Interface

**File: `pigeon/liveness.dart` (new):**

```dart
import 'package:pigeon/pigeon.dart';

@HostApi()
abstract class LivenessHostApi {
  /// Start a liveness session. Returns session ID.
  String startLivenessSession(String workflowType, String deviceId);

  /// Submit a frame for passive liveness + PAD evaluation.
  /// Returns: action, challengeType, challengeId, timeoutMs, message.
  LivenessFrameResult submitLivenessFrame(String sessionId, Uint8List frameJpeg);

  /// Validate challenge frames.
  /// Returns: action (proceed/retry_challenge/reject), message.
  LivenessChallengeResult validateLivenessChallenge(
    String sessionId,
    String challengeId,
    List<Uint8List> frames,
  );

  /// Close the session and get summary.
  LivenessSessionSummary closeLivenessSession(String sessionId);

  /// Check if liveness is enabled for a workflow.
  bool isLivenessEnabled(String workflowType);
}
```

Run `pigeon.sh` to generate bridge code.

### 4.3 Dart State Provider

**File: `lib/provider/liveness_capture_control_provider.dart` (new):**

```dart
import 'package:flutter/foundation.dart';

enum LivenessState {
  idle,
  scoring,
  challengeActive,
  passed,
  failed,
  padAttackDetected,
}

class LivenessCaptureControlProvider extends ChangeNotifier {
  LivenessState _state = LivenessState.idle;
  String? _sessionId;
  String? _challengeType;
  String? _challengePrompt;
  String? _errorMessage;
  UUID? _currentChallengeId;

  LivenessState get state => _state;
  String? get challengeType => _challengeType;
  String? get challengePrompt => _challengePrompt;
  String? get errorMessage => _errorMessage;

  Future<void> startLivenessCheck(String workflow, String deviceId) async {
    _state = LivenessState.scoring;
    _sessionId = await LivenessHostApi.startLivenessSession(workflow, deviceId);
    notifyListeners();
  }

  Future<void> onFrame(Uint8List frameJpeg) async {
    if (_sessionId == null) return;

    final result = await LivenessHostApi.submitLivenessFrame(_sessionId!, frameJpeg);

    switch (result.action) {
      case 'proceed':
        _state = LivenessState.passed;
        break;
      case 'escalate_to_active':
        _state = LivenessState.challengeActive;
        _challengeType = result.challengeType;
        _challengePrompt = _promptForChallenge(result.challengeType);
        _currentChallengeId = result.challengeId;
        break;
      case 'reject':
        _state = result.padFlag
            ? LivenessState.padAttackDetected
            : LivenessState.failed;
        _errorMessage = result.message;
        break;
    }
    notifyListeners();
  }

  Future<void> submitChallengeFrames(List<Uint8List> frames) async {
    if (_sessionId == null || _currentChallengeId == null) return;

    final result = await LivenessHostApi.validateLivenessChallenge(
      _sessionId!, _currentChallengeId!, frames,
    );

    switch (result.action) {
      case 'proceed':
        _state = LivenessState.passed;
        break;
      case 'retry_challenge':
        // New challenge will be issued on next frame submission
        break;
      case 'reject':
        _state = LivenessState.failed;
        _errorMessage = result.message;
        break;
    }
    notifyListeners();
  }

  String _promptForChallenge(String type) {
    return switch (type) {
      'BLINK' => 'Please blink',
      'SMILE' => 'Please smile',
      'TURN_LEFT' => 'Please turn your head to the left',
      'TURN_RIGHT' => 'Please turn your head to the right',
      'LOOK_DIRECTION' => 'Please look in the indicated direction',
      _ => 'Please follow the instruction',
    };
  }
}
```

### 4.4 Flutter Challenge Overlay Widget

**File: `lib/ui/widgets/liveness_challenge_overlay.dart` (new):**

```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../provider/liveness_capture_control_provider.dart';

class LivenessChallengeOverlay extends StatelessWidget {
  const LivenessChallengeOverlay({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<LivenessCaptureControlProvider>(
      builder: (context, provider, _) {
        if (provider.state != LivenessState.challengeActive) {
          return const SizedBox.shrink();
        }

        return Container(
          color: Colors.black54,
          child: Center(
            child: Card(
              margin: const EdgeInsets.all(24),
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      _iconForChallenge(provider.challengeType),
                      size: 64,
                      color: Theme.of(context).primaryColor,
                    ),
                    const SizedBox(height: 16),
                    Text(
                      provider.challengePrompt ?? '',
                      style: Theme.of(context).textTheme.headlineSmall,
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 16),
                    const LinearProgressIndicator(),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  IconData _iconForChallenge(String? type) {
    return switch (type) {
      'BLINK' => Icons.visibility,
      'SMILE' => Icons.sentiment_satisfied_alt,
      'TURN_LEFT' => Icons.arrow_back,
      'TURN_RIGHT' => Icons.arrow_forward,
      'LOOK_DIRECTION' => Icons.center_focus_strong,
      _ => Icons.face,
    };
  }
}
```

### 4.5 Wire into Existing Capture Flow

**File: `biometric_scan_middle_block.dart` — add liveness overlay:**

```dart
// In the existing build method, wrap the capture area with Stack:
Stack(
  children: [
    // Existing capture UI
    _buildCaptureArea(),

    // Liveness challenge overlay (shows when challenge is active)
    if (livenessProvider.state == LivenessState.challengeActive)
      const LivenessChallengeOverlay(),

    // Liveness status indicator
    if (livenessProvider.state != LivenessState.idle)
      LivenessStatusIndicator(state: livenessProvider.state),
  ],
)
```

---

## 5. Configuration

### 5.1 Server-synced global params (both platforms)

```sql
-- Add to reg-global_param.sql:
INSERT INTO reg_global_param (name, val, is_active, lang_code) VALUES
  ('mosip.registration.liveness.enabled', 'true', true, 'eng'),
  ('mosip.registration.liveness.service.url', 'http://localhost:8000', true, 'eng'),
  ('mosip.registration.liveness.passive.threshold', '0.80', true, 'eng'),
  ('mosip.registration.liveness.active.enabled', 'true', true, 'eng'),
  ('mosip.registration.liveness.active.min_challenges', '2', true, 'eng'),
  ('mosip.registration.liveness.active.challenge_timeout_ms', '10000', true, 'eng'),
  ('mosip.registration.liveness.max_retry', '2', true, 'eng'),
  ('mosip.registration.liveness.on_repeated_failure', 'LOCK', true, 'eng');
```

### 5.2 Per-workflow config via API

```bash
# Make supervisor auth stricter:
curl -X PUT localhost:8000/api/v1/config/SUPERVISOR \
  -H "Content-Type: application/json" \
  -d '{
    "passiveThreshold": 0.92,
    "minChallengeCount": 3,
    "maxRetryCount": 1,
    "onRepeatedFailure": "LOCK"
  }'

# Disable active liveness for operators:
curl -X PUT localhost:8000/api/v1/config/OPERATOR \
  -H "Content-Type: application/json" \
  -d '{
    "activeLivenessEnabled": false,
    "passiveThreshold": 0.85
  }'
```

---

## 6. Error Handling

### Client-side error codes

| HTTP Status | Error Code | Meaning | Client Action |
|-------------|------------|---------|---------------|
| 404 | NOT_FOUND | Session/challenge not found | Create new session |
| 409 | CONFLICT | Session not active / challenge not pending | Refresh state |
| 422 | INVALID_FRAME | Frame couldn't be decoded | Re-capture frame |
| 400 | VALIDATION_ERROR | Bad request body | Fix request |
| 500 | INTERNAL_ERROR | Server error | Retry, show generic error |

### User-facing messages (from spec, never leak detection details)

| State | Message |
|-------|---------|
| No face detected | "No face detected. Please look at the camera." |
| Multiple faces | "Multiple faces detected. Only one person may be captured." |
| Poor quality | "Face is not clear. Please improve lighting and hold still." |
| Challenge in progress | "Please continue" |
| Challenge passed | "Action detected" |
| Challenge failed | "Verification action was not completed. Please try again." |
| PAD failure | "Face verification could not be completed. Please try again." |
| Max retries | "Verification could not be completed. Please contact support." |

---

## 7. Testing the Integration

### Quick smoke test (curl)

```bash
# Create session
SESSION_ID=$(curl -s -X POST localhost:8000/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"workflowType":"RESIDENT","deviceId":"L1-CAM-01"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

# Submit a frame
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/frames \
  -H "Content-Type: application/json" \
  -d "{\"frameBase64\":\"$(base64 -w0 test_face.jpg)\"}"

# Check audit trail
curl -s localhost:8000/api/v1/sessions/$SESSION_ID/audit | python3 -m json.tool
```

### Programmatic test (Java)

```java
LivenessClient client = new LivenessHttpClient("http://localhost:8000");
LivenessClient.SessionInfo session = client.createSession("RESIDENT", "L1-CAM-01");

byte[] faceJpeg = Files.readAllBytes(Path.of("test_face.jpg"));
LivenessClient.FrameResult result = client.submitFrame(session.id(), faceJpeg);

System.out.println("Action: " + result.action());
System.out.println("Liveness score: " + result.livenessScore());
System.out.println("PAD flag: " + result.padFlag());

LivenessClient.SessionSummary summary = client.closeSession(session.id());
System.out.println("Duration: " + summary.durationMs() + "ms");
```

---

## 8. Security Notes

- Face frames are processed in-memory, on-device, never leave the device (no cloud call)
- Only the final `LivenessAttemptResult` (scores/decision) is written to audit logs
- PAD failure messages are deliberately generic — no detection method details leaked
- Model files bundled in the app should have integrity checksum verification on load
- Config updates via API should use signed manifests in production
