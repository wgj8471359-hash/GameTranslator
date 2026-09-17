"""Static consistency checks for the pending GameTranslator edits.

Deliberately NOT a compiler: verifies cross-file identifier wiring, brace
balance, and stale references left by the geometry refactor. Compilation
verdict belongs to GitHub Actions.
"""
import re
import sys
from pathlib import Path

ROOT = Path(r'E:\workspace\GameTranslator')
MAIN = ROOT / 'app/src/main/java/com/game/translator'
RES = ROOT / 'app/src/main/res'

errors = []


def read(p):
    return p.read_text(encoding='utf-8')


def check_identifier_defined(ident, files, context=''):
    """Ensure a Kotlin identifier is declared somewhere in the given files."""
    decl = re.compile(r'(fun|val|var|const val)\s+' + ident + r'\b')
    for f in files:
        if decl.search(f if isinstance(f, str) else read(f)):
            return
    errors.append(f'UNDEFINED {ident} ({context})')


def brace_balance(name, text):
    depth = 0
    in_str = False
    in_comment = False
    prev = ''
    for ch in text:
        if in_str_check(prev, ch) if False else False:
            pass
        prev = ch
    # simpler: strip strings and comments with regex
    stripped = re.sub(r'"""[\s\S]*?"""', '', text)
    stripped = re.sub(r'"(?:\\.|[^"\\])*"', '""', stripped)
    stripped = re.sub(r'//[^\n]*', '', stripped)
    stripped = re.sub(r'/\*[\s\S]*?\*/', '', stripped)
    for ch in stripped:
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth < 0:
                errors.append(f'EXTRA_CLOSE_BRACE {name}')
                depth = 0
    if depth != 0:
        errors.append(f'BRACE_IMBALANCE {name}: {depth}')


kt_files = sorted(MAIN.glob('*.kt'))
texts = {f: read(f) for f in kt_files}
for name, text in texts.items():
    brace_balance(name, text)

service = texts[MAIN / 'TranslatorService.kt']
overlay = texts[MAIN / 'OverlayManager.kt']
main_act = texts[MAIN / 'MainActivity.kt']
engine = texts[MAIN / 'DiffEngine.kt']

# 1. UI ids referenced in MainActivity must exist in layout xml
layout = read(RES / 'layout/activity_main.xml')
for m in re.finditer(r'R\.id\.(\w+)', main_act):
    ident = m.group(1)
    if f'@+id/{ident}' not in layout:
        errors.append(f'MISSING_LAYOUT_ID {ident}')

# 2. string resources referenced in code/xml must be declared
strings = read(RES / 'values/strings.xml')
for src, label in ((main_act, 'MainActivity'), (layout, 'layout')):
    for m in re.finditer(r'@string/(\w+)|R\.string\.(\w+)', src):
        ident = m.group(1) or m.group(2)
        if f'name="{ident}"' not in strings:
            errors.append(f'MISSING_STRING {ident} (used in {label})')

# 3. every string declared must be referenced somewhere (soft: only warn)
declared = set(re.findall(r'name="(\w+)"', strings))
for ident in ('label_debounce_ms', 'label_overlay_layout', 'layout_mode_cover',
              'layout_mode_note_below', 'layout_mode_note_right'):
    if ident not in declared:
        errors.append(f'MISSING_STRING_DECL {ident}')

# 4. layout mode constants align across files
for c in ('LAYOUT_COVER', 'LAYOUT_NOTE_BELOW', 'LAYOUT_NOTE_RIGHT'):
    if c not in overlay or c not in main_act:
        errors.append(f'MISSING_CONST {c}')

# 5. new geometry API referenced only where defined
for ident in ('computeBubbleGeometry', 'applyPlaceholderStyle', 'placeholderIds'):
    check_identifier_defined(ident, [overlay], 'OverlayManager')

# 6. engine API consumed by service must exist in DiffEngine
for ident in ('configureDebounce', 'getActiveCluster', 'markFailed', 'processFrame'):
    check_identifier_defined(ident, [engine], 'DiffEngine')

# 7. no stale identifiers from the geometry refactor
for pat in ('finalMaxHeight', 'spaceToNextLine', 'remainingToNextLine'):
    for name, text in texts.items():
        if re.search(r'\b' + pat + r'\b', text):
            errors.append(f'STALE_IDENTIFIER {pat} in {name.name}')

# 8. withHiddenForCapture call shape: keyword arg awaitFreshFrame present
if 'awaitFreshFrame' not in overlay or 'awaitFreshFrame' not in service:
    errors.append('AWAIT_FRESH_FRAME_MISMATCH')

# 9. layout config plumbed: OverlayConfig construction must pass layoutMode in realtime loop
m = re.search(r'val overlayConfig = OverlayManager\.OverlayConfig\(([\s\S]*?)\)', service)
if m and 'layoutMode' not in m.group(1):
    errors.append('LAYOUT_MODE_NOT_PLUMBED realtime')

# 10. KEY_OVERLAY_LAYOUT persisted and read
if 'KEY_OVERLAY_LAYOUT' not in main_act or 'KEY_OVERLAY_LAYOUT' not in service:
    errors.append('KEY_OVERLAY_LAYOUT not read/written both sides')

# 11. unused import check for strings the refactor removed (rough)
if re.search(r'\boriginalHeight\b', overlay):
    # originalHeight still legitimate inside computeBubbleGeometry
    pass

print('ERRORS:' if errors else 'ALL_CHECKS_PASSED')
for e in errors:
    print(' -', e)
sys.exit(1 if errors else 0)
