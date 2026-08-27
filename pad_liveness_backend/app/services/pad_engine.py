"""
Presentation Attack Detection (PAD) engine interface.

This module defines the vendor-agnostic contract the rest of the system
codes against (BasePADEngine), plus a heuristic MockPADEngine that stands
in until a real ISO/IEC 30107-3 evaluated model/SDK is integrated.

To integrate a real PAD model (texture-based CNN, frequency-domain moire
detection, depth/IR sensor fusion, a licensed vendor SDK, etc.), implement
BasePADEngine and wire it up in get_pad_engine() below — nothing else in
the codebase needs to change.
"""
from abc import ABC, abstractmethod
from dataclasses import dataclass

import cv2
import numpy as np

from app.services.image_utils import brightness_score


@dataclass
class PADResult:
    is_attack: bool
    attack_type: str | None      # e.g. "print", "screen_replay", "video_replay"
    confidence: float            # 0..1 confidence in the verdict


class BasePADEngine(ABC):
    @abstractmethod
    def detect(self, frame: np.ndarray) -> PADResult:
        """Analyse a single frame and return a presentation-attack verdict."""
        raise NotImplementedError


class MockPADEngine(BasePADEngine):
    """
    Heuristic placeholder PAD engine. Uses two cheap, well-known signals as
    stand-ins for real PAD models:

      1. Moire / screen-replay proxy: high-frequency FFT energy ratio, which
         tends to spike when photographing a digital screen.
      2. Print-attack proxy: local color/texture variance, which tends to be
         lower and flatter on a printed photo than on live skin.

    These are NOT production-grade PAD signals — replace with a properly
    trained and ISO/IEC 30107-3 evaluated model before go-live.
    """

    _FREQ_ENERGY_ATTACK_THRESHOLD = 0.35
    _TEXTURE_VARIANCE_ATTACK_THRESHOLD = 15.0

    def detect(self, frame: np.ndarray) -> PADResult:
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY).astype(np.float32)

        # --- Screen-replay / moire proxy via high-frequency FFT energy ---
        fft = np.fft.fftshift(np.fft.fft2(gray))
        magnitude = np.log1p(np.abs(fft))
        h, w = magnitude.shape
        cy, cx = h // 2, w // 2
        radius = min(h, w) // 8
        high_freq_energy = magnitude.copy()
        high_freq_energy[cy - radius: cy + radius, cx - radius: cx + radius] = 0
        freq_ratio = float(np.sum(high_freq_energy) / (np.sum(magnitude) + 1e-6))

        # --- Print-attack proxy via local texture variance ---
        texture_variance = float(cv2.Laplacian(gray, cv2.CV_64F).var())

        # --- Sanity: extreme over/under exposure often accompanies a replay attack ---
        brightness = brightness_score(frame)

        if freq_ratio > self._FREQ_ENERGY_ATTACK_THRESHOLD:
            return PADResult(is_attack=True, attack_type="screen_replay", confidence=min(1.0, freq_ratio))

        if texture_variance < self._TEXTURE_VARIANCE_ATTACK_THRESHOLD:
            confidence = min(1.0, 1.0 - (texture_variance / self._TEXTURE_VARIANCE_ATTACK_THRESHOLD))
            return PADResult(is_attack=True, attack_type="print", confidence=confidence)

        if brightness < 0.15:
            return PADResult(is_attack=True, attack_type="video_replay", confidence=0.6)

        # Bona fide verdict; confidence expresses how comfortably it cleared the thresholds.
        margin = min(
            1.0,
            (self._FREQ_ENERGY_ATTACK_THRESHOLD - freq_ratio) / self._FREQ_ENERGY_ATTACK_THRESHOLD,
        )
        return PADResult(is_attack=False, attack_type=None, confidence=max(0.5, margin))


_engine_instance: BasePADEngine | None = None


def get_pad_engine() -> BasePADEngine:
    global _engine_instance
    if _engine_instance is None:
        _engine_instance = MockPADEngine()
    return _engine_instance
