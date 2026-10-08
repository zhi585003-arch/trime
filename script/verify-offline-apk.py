#!/usr/bin/env python3
import os
import subprocess
import zipfile
from pathlib import Path

sdk = Path(os.environ['ANDROID_HOME'])
aapt = sdk / 'build-tools/35.0.0/aapt'
apks = list(Path('app/build/outputs/apk/debug').glob('*.apk'))
assert apks, 'No APK generated'
reports = []
for apk in apks:
    permissions = subprocess.check_output([str(aapt), 'dump', 'permissions', str(apk)], text=True)
    assert 'android.permission.INTERNET' not in permissions, permissions
    with zipfile.ZipFile(apk) as z:
        for name in ['rime_ice.schema.yaml', 'rime_ice.dict.yaml', 'rime_ice.custom.yaml',
                     'default.custom.yaml', 'cn_dicts/base.dict.yaml', 'cn_dicts/tencent.dict.yaml',
                     'melt_eng.schema.yaml', 'radical_pinyin.schema.yaml',
                     'lua/date_translator.lua', 'rime-ice-LICENSE.txt']:
            assert z.getinfo('assets/shared/' + name).file_size > 0, name
        assert any(n.startswith('lib/arm64-v8a/') for n in z.namelist()), 'No ARM64 library'
    reports.append(f'{apk.name}\nOffline dictionaries: present\nINTERNET permission: absent\n{permissions}')
Path('apk-verification.txt').write_text('\n'.join(reports))
print('\n'.join(reports))
