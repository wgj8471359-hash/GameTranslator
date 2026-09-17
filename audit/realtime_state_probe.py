"""Reduced fixed-frame probe for DiffEngine.kt, NOT an Android/Kotlin unit test.

Mirrors only the same-box phase-1 branch and phase-4 missing-frame exemption.
Exact text / very different text avoid reliance on a similarity implementation.
Each check confirms CURRENT buggy behavior, not desired behavior or a fix.
Run: python audit\realtime_state_probe.py
"""
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Track:
    original: str
    translated: str | None = None
    displayed: bool = False
    inflight: bool = False
    stable: bool = False
    first_seen: int = 0
    last_ocr: str = ""
    missing: int = 0


def same_box_frame(t, text, now, inside_bubble=False):
    # DiffEngine.kt 382-418. Cases use equality or unrelated scripts only.
    t.missing = 0
    sim_original = 1.0 if text == t.original else 0.0
    sim_trans = 1.0 if text == t.translated else 0.0
    sim_last = 1.0 if text == t.last_ocr else 0.0
    cjk_overlap = bool(t.translated) and any(
        '\u4e00' <= c <= '\u9fff' and c in t.translated for c in text
    )
    substring = bool(t.translated) and len(text.strip()) >= 2 and text.strip() in t.translated
    geometric = (t.displayed or t.inflight) and inside_bubble
    optical = (t.displayed or t.inflight) and (
        sim_trans >= .35 or sim_last >= .85 or cjk_overlap or substring
    )
    if geometric or optical or sim_original >= .85 or t.inflight:
        t.last_ocr = text
        return 'unchanged'
    # DiffEngine.kt 421-443.
    if t.original != text:
        t.original = text
        t.translated = None
        t.last_ocr = text
        t.first_seen = now
        t.stable = False
        t.displayed = False
        return 'reset-without-update-event'
    if now - t.first_seen >= 400:
        t.stable = t.displayed = True
        return 'updated'
    return 'waiting'


def main():
    # Guard against accidentally treating this as a probe of unrelated source.
    source = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/game/translator/DiffEngine.kt').read_text(encoding='utf-8')
    assert 'if (isSelfBubbleCaptured || simOriginal >= similarityThreshold || bestMatch.isInFlight)' in source
    assert 'if (!tracked.isInFlight && tracked.consecutiveMissingFrames >= MAX_MISSING_FRAMES)' in source

    # Phase 3 cold cache creates a waiting track but emits no added item.
    t = Track('Hello world', last_ocr='Hello world')
    events = [same_box_frame(t, 'Hello world', ms) for ms in (1200, 2400, 3600, 10000)]
    assert events == ['unchanged'] * 4 and not t.stable
    print('CONFIRMED [reduced model] cold stationary text: 4 frames, 0 added/updated, 0 translation candidates')

    t = Track('Hello world', translated='你好世界', displayed=True, stable=True, last_ocr='Hello world')
    event = same_box_frame(t, '完全不同的新台词', 1200, inside_bubble=True)
    assert event == 'unchanged' and t.original == 'Hello world' and t.translated == '你好世界'
    print('CONFIRMED [reduced model] new text inside visible bubble: retained old source and translation')

    t = Track('Hello world', inflight=True, last_ocr='Hello world')
    event = same_box_frame(t, '완전히새로운대사', 1200)
    assert event == 'unchanged' and t.original == 'Hello world'
    for _ in range(10):
        t.missing += 1
        removed = not t.inflight and t.missing >= 3
        assert not removed
    print('CONFIRMED [reduced model] in-flight track: ignores changed source and survives 10 missing frames')

    t = Track('Hello world', last_ocr='Hello world')
    first = same_box_frame(t, '완전히새로운대사', 1200)
    second = same_box_frame(t, '완전히새로운대사', 2400)
    assert first == 'reset-without-update-event' and second == 'unchanged' and not t.stable
    print('CONFIRMED [reduced model] genuine content change: reset then unchanged, never stable')
    print('4/4 current-behavior checks passed. This is NOT production execution or fix verification.')


if __name__ == '__main__':
    main()
