#!/usr/bin/env python3
"""Regression checks for the exact requested mappings and the theme-ID startup bug."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
theme = (root / 'app/src/main/assets/shared/tongwenfeng.trime.yaml').read_text()
prefs = (root / 'app/src/main/java/com/osfans/trime/data/theme/ThemePrefs.kt').read_text()
assert '"tongwenfeng.trime"' in prefs
assert 'OfflineSymk: {label: "(", commit: "("}' in theme
assert 'OfflineSyml: {label: ")", commit: ")"}' in theme
for layout in ['default', 'letter']:
    block = theme.split('preset_keyboards:', 1)[1].split('  ' + layout + ':', 1)[1]
    block = re.split(r'\n  [A-Za-z0-9_]+:', block)[0]
    for key in 'qwertyuiopasdfghjklzxcvbnm':
        lines = [l for l in block.splitlines() if re.search(r'\{click: ' + key + ',', l)]
        assert len(lines) == 1 and 'swipe_up: OfflineSym' + key in lines[0], (layout, key)
        assert f'popup: [OfflineUpper{key}, OfflineSym{key}, OfflineLower{key}]' in lines[0], (layout, key)
    assert 'swipe_up: OfflineClear' in block
syllables = (root / 'app/src/main/assets/pinyin-syllables.txt').read_text().splitlines()
assert {'xi', 'xian', 'an'}.issubset(syllables)
print('PASS: valid default theme, 52 letter gestures, brackets, clear gesture, syllable inventory')

# Candidate panel must use a color available in the bundled theme.
panel = (root / "app/src/main/java/com/osfans/trime/ime/candidates/unrolled/UnrolledCandidateLayout.kt").read_text()
assert 'getColor("candidate_back_color")' not in panel
assert 'getColor("back_color")' in panel
print("PASS: candidate panel uses supported background color")
