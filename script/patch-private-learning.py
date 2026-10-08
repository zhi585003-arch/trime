#!/usr/bin/env python3
from pathlib import Path
p = Path('app/src/main/jni/librime/src/rime/gear/memory.cc')
s = p.read_text()
old = 'void Memory::OnCommit(Context* ctx) {\n'
new = old + '  // Private offline profile: keep existing vocabulary but do not learn new commits.\n  if (ctx && ctx->get_option("_no_learning"))\n    return;\n'
assert s.count(old) == 1, 'Unexpected librime Memory implementation'
if new not in s:
    p.write_text(s.replace(old, new))
print('Verified native no-learning guard')
