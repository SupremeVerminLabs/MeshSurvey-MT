#!/usr/bin/env python3
"""Line-delimited JSON bridge to SQLite. Used only by tools/verify-core.sh so the
core's SqlStore can be tested against real SQLite on machines without sqlite-jdbc.
Real builds use JdbcSqlDriver (desktop) / AndroidSqlDriver (Android) instead."""
import base64, json, sqlite3, sys
db = sqlite3.connect(sys.argv[1] if len(sys.argv) > 1 else ":memory:", isolation_level=None)
for line in sys.stdin:
    req = json.loads(line)
    try:
        cur = db.execute(req["sql"], req.get("args", []))
        if req["op"] == "query":
            out = {"rows": [[{"b64": base64.b64encode(v).decode()} if isinstance(v, (bytes, bytearray)) else v for v in r]
                            for r in cur.fetchall()]}
        else:
            out = {"changes": cur.rowcount if cur.rowcount is not None else 0,
                   "rowid": cur.lastrowid if (req["op"] == "insert" and cur.rowcount > 0) else -1}
    except Exception as e:
        out = {"error": f"{type(e).__name__}: {e}"}
    sys.stdout.write(json.dumps(out) + "\n"); sys.stdout.flush()
