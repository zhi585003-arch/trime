#!/usr/bin/env python3
"""Exercise the actual DAO SQL against SQLite, including disk reopen and expiry boundary."""
import re
import sqlite3
import tempfile
from pathlib import Path

source = Path('app/src/main/java/com/osfans/trime/data/db/DatabaseDao.kt').read_text()
def query(method):
    pattern = r'@Query\("([^"\n]+)"\)\s+(?:suspend )?fun ' + method + r'\('
    return re.search(pattern, source).group(1).replace('${DatabaseBean.TABLE_NAME}', 't_data')
with tempfile.TemporaryDirectory() as d:
    dbpath = Path(d) / 'clipboard.db'
    db = sqlite3.connect(dbpath)
    db.execute('CREATE TABLE t_data (id INTEGER PRIMARY KEY, text TEXT, time INTEGER, pinned INTEGER)')
    day = 86400000
    now = day * 3
    db.executemany('INSERT INTO t_data VALUES (?, ?, ?, ?)', [
        (1, 'expired', now - day - 1, 0), (2, 'boundary-pinned', now - day, 1),
        (3, 'still-valid', now - day + 1, 0),
        *[(i, str(i), now - i, 0) for i in range(4, 105)]])
    db.execute(query('deleteExpired'), {'cutoff': now - day})
    assert db.execute('SELECT COUNT(*) FROM t_data').fetchone()[0] == 102
    assert not db.execute('SELECT * FROM t_data WHERE id IN (1,2)').fetchall()
    db.execute(query('updateTime'), {'id': 3, 'timestamp': now})
    assert db.execute(query('clipboardBeans')).fetchone()[0] == 3
    db.commit()
    db.close()
    db = sqlite3.connect(dbpath)
    assert db.execute(query('clipboardBeans')).fetchone()[0] == 3
    db.execute(query('deleteExpired'), {'cutoff': now - 1})
    assert db.execute('SELECT COUNT(*) FROM t_data').fetchone()[0] == 1
    db.execute(query('deleteExpired'), {'cutoff': now})
    assert db.execute('SELECT COUNT(*) FROM t_data').fetchone()[0] == 0
print('PASS: 24h boundary, old pins expire, >100 records, paste ordering, disk persistence')
