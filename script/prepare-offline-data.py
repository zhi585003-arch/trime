#!/usr/bin/env python3
"""Bundle a pinned rime-ice checkout; no downloads happen on the phone."""
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(sys.argv[1]).resolve()
EXPECTED = 'da1fbe602e38f26db846fa10120ee64c2b0324c0'
revision = subprocess.check_output(['git', '-C', str(SOURCE), 'rev-parse', 'HEAD'], text=True).strip()
if revision != EXPECTED:
    raise SystemExit(f'Unexpected rime-ice revision: {revision}')
DEST = ROOT / 'app/src/main/assets/shared'
files = ['default.yaml', 'rime_ice.schema.yaml', 'rime_ice.dict.yaml',
         'melt_eng.schema.yaml', 'melt_eng.dict.yaml',
         'radical_pinyin.schema.yaml', 'radical_pinyin.dict.yaml',
         'symbols_v.yaml', 'symbols_caps_v.yaml', 'custom_phrase.txt']
for name in files:
    target = DEST / name
    # The upstream Trime default is a symlink into its prelude submodule.
    if target.is_symlink():
        target.unlink()
    shutil.copy2(SOURCE / name, target)
for name in ['cn_dicts', 'en_dicts', 'lua', 'opencc']:
    shutil.copytree(SOURCE / name, DEST / name, dirs_exist_ok=True)
shutil.copy2(SOURCE / 'LICENSE', DEST / 'rime-ice-LICENSE.txt')
for name in ['default.custom.yaml', 'rime_ice.custom.yaml']:
    shutil.copy2(ROOT / 'offline' / name, DEST / name)
(DEST / 'offline-build.json').write_text(json.dumps({
    'trime_base': '6a10567e27e86c1b9d38f864be94b36c2d9fae30',
    'rime_ice': revision,
    'clipboard_retention_hours': 24,
}, indent=2) + '\n')
for name in files + ['cn_dicts/base.dict.yaml', 'cn_dicts/tencent.dict.yaml', 'lua/date_translator.lua']:
    assert (DEST / name).is_file(), name
print(f'Bundled rime-ice {revision}: {sum(p.stat().st_size for p in DEST.rglob("*") if p.is_file())} bytes')
