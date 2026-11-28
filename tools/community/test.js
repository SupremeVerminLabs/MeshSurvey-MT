// Runs Code.gs against an in-memory stand-in for Google Sheets: node tools/community/test.js
// This checks the script's logic only; it is not a test against Google's servers.
const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');

function makeSheet() {
  const rows = [];
  return { rows,
    getLastRow: () => rows.length, appendRow: r => rows.push(r.slice()), setFrozenRows: () => {}, setName: n => {},
    getRange: (r, c, nr, nc) => ({
      getValues: () => rows.slice(r - 1, r - 1 + nr).map(x => { const y = x.slice(c - 1, c - 1 + nc); while (y.length < nc) y.push(''); return y; }),
      setValues: v => v.forEach((row, i) => { const t = rows[r - 1 + i] || (rows[r - 1 + i] = []); row.forEach((x, j) => { t[c - 1 + j] = x; }); }),
      clearContent: () => { for (let i = r - 1; i < r - 1 + nr; i++) rows[i] = []; while (rows.length && rows[rows.length - 1].length === 0) rows.pop(); },
    }) };
}
const sheets = {};
let lockFree = true;
global.SpreadsheetApp = { getActiveSpreadsheet: () => ({ getSheetByName: n => sheets[n] || null, insertSheet: n => (sheets[n] = makeSheet()) }) };
// a schema-1 sheet from the first version: setup() keeps it aside and starts fresh tabs
sheets.cells = makeSheet(); sheets.cells.appendRow(['received_utc', 'submitter_hash', 'batch']); sheets.cells.setName = n => { sheets[n] = sheets.cells; delete sheets.cells; };
global.LockService = { getScriptLock: () => ({ tryLock() { return lockFree; }, releaseLock() {} }) };
global.ContentService = { MimeType: { JSON: 'json' }, createTextOutput: s => ({ text: s, setMimeType() { return this; } }) };
global.Utilities = { DigestAlgorithm: { SHA_256: 1 }, computeDigest: (_, s) => Array.from(crypto.createHash('sha256').update(s).digest()).map(b => b > 127 ? b - 256 : b) };
const mod = { exports: {} };
new Function('module', fs.readFileSync(__dirname + '/Code.gs', 'utf8'))(mod);
const gs = mod.exports;
const post = o => JSON.parse(gs.doPost({ postData: { contents: JSON.stringify(o) } }).text);
const get = q => JSON.parse(gs.doGet({ parameter: q }).text);
const sha = s => crypto.createHash('sha256').update(s).digest('hex');

gs.setup();
assert.ok(Object.keys(sheets).some(n => n.startsWith('cells_old_'))); assert.strictEqual(sheets.cells.rows[0][0], 'row_id');
const row = (snr, n, flags, extra) => Object.assign({ cell_id: 'c1035_-3322', lat: 37.97, lon: -122.03, day: '2026-11-27', protocol: 'meshtastic',
  preset: 'LONG_FAST', heard_from: 'mt:!1234abcd', n, n_direct: 1, snr_median: snr, snr_best: snr + 2, rssi_median: -100, flags }, extra || {});
const key = 'a'.repeat(24), token = 'secret-token-1';
const batch = (part, parts, rows, k) => ({ schema: 2, type: 'session_batch', contribution_tag: 'meshsurvey-session', provenance: 'local_rf',
  submitter: 'abcdef0123', app: 't', session_key: k || key, start_check: 'abc123', batch_id: (k || key) + '-' + (part + 1) + 'of' + parts,
  part, parts, delete_hash: sha(token), equipment: { radio_model: 'TBEAM', callsign: 'K1ABC' }, rows });

// part 1 of 2: one good row, one outlier (kept, flagged), one structurally invalid (refused, original kept in oddities)
let r = post(batch(0, 2, [row(-3, 4, ''), row(55, 1, 'single_reception'), row(0, 1, '', { lat: 999 })]));
assert.strictEqual(r.ok, true); assert.strictEqual(r.receipt.accepted, 2); assert.strictEqual(r.receipt.complete, false);
assert.strictEqual(sheets.cells.rows.length, 3);
assert.match(sheets.cells.rows[2][17], /script_snr_out_of_range/);
assert.strictEqual(sheets.cells.rows[2][14], 55);                                 // original value preserved
assert.strictEqual(sheets.cells.rows[1][18], 'radio_model=TBEAM');                 // only allowed equipment fields
assert.match(sheets.oddities.rows[1][7], /999/);
// the same part again (interrupted upload resumed): no duplicate
r = post(batch(0, 2, [row(-3, 4, '')]));
assert.strictEqual(r.receipt.already, true); assert.strictEqual(sheets.cells.rows.length, 3);
// part 2 completes the session
r = post(batch(1, 2, [row(-5, 3, '')]));
assert.strictEqual(r.receipt.complete, true);
assert.strictEqual(sheets.contributions.rows[1][7], 'complete');
// the session imported on another device and uploaded again (different submitter, different split): already accepted
r = post(Object.assign(batch(0, 1, [row(-3, 4, '')]), { submitter: 'otherdevice9' }));
assert.strictEqual(r.ok, true); assert.strictEqual(r.receipt.already, true); assert.strictEqual(sheets.cells.rows.length, 4);
// two sessions started at the same moment are different sessions
r = post(Object.assign(batch(0, 1, [row(1, 2, '')], 'b'.repeat(24))));
assert.strictEqual(r.receipt.accepted, 1);
// refusals
assert.strictEqual(post(Object.assign(batch(0, 1, [row(1, 1, '')], 'c'.repeat(24)), { provenance: 'simulated' })).ok, false);
assert.strictEqual(post(batch(0, 1, [row(1, 1, 'simulated')], 'd'.repeat(24))).receipt.accepted, 0);
assert.strictEqual(post(Object.assign(batch(0, 1, [row(1, 1, '')]), { schema: 1 })).code, 'schema_unsupported');
assert.strictEqual(post(Object.assign(batch(0, 1, [row(1, 1, '')], 'e'.repeat(24)), { batch_id: 'x' })).ok, false);
lockFree = false; assert.strictEqual(post(batch(0, 1, [row(1, 1, '')], 'f'.repeat(24))).retry, true); lockFree = true;
assert.strictEqual(post({ schema: 2, type: 'oddity', provenance: 'user_report', submitter: 'abcdef0123', text: 'RSSI -20 at 5 km?', day: '2026-11-27' }).ok, true);

// aggregate: flagged row only counts as pending review; public answer has no session keys or hashes
gs.rebuildAggregate();
let g = get({ action: 'aggregate', lat0: 37, lat1: 38, lon0: -123, lon1: -122 });
assert.strictEqual(g.cells.length, 1);
assert.strictEqual(g.cells[0].n, 9); assert.strictEqual(g.cells[0].pending_review, 1); assert.strictEqual(g.cells[0].contributors, 2);
assert.ok(!JSON.stringify(g).includes('aaaa') && !JSON.stringify(g).includes(sha(token)));
assert.strictEqual(get({ action: 'aggregate', lat0: 37, lat1: 38, lon0: -123, lon1: -122, since: g.version }).unchanged, true);
// a review accepting the outlier with a corrected value: originals untouched, aggregate uses the correction
sheets.reviews.appendRow([sheets.cells.rows[2][0], 'ok', 'real: hilltop line of sight', 12, '', 'reviewer', '2026-11-28']);
gs.rebuildAggregate();
g = get({ action: 'aggregate', lat0: 37, lat1: 38, lon0: -123, lon1: -122 });
assert.strictEqual(g.cells[0].pending_review, 0); assert.strictEqual(g.cells[0].n, 10);
assert.strictEqual(sheets.cells.rows[2][14], 55);

// withdrawal needs the private token
assert.strictEqual(post({ schema: 2, type: 'withdraw', submitter: 'abcdef0123', session_key: key, token: 'wrong' }).ok, false);
r = post({ schema: 2, type: 'withdraw', submitter: 'abcdef0123', session_key: key, token });
assert.strictEqual(r.ok, true); assert.match(r.message, /3 rows removed/);
assert.strictEqual(post(batch(0, 2, [row(-3, 4, '')])).ok, false);                // withdrawn sessions are not re-added
gs.rebuildAggregate();
assert.strictEqual(get({ action: 'aggregate', lat0: 37, lat1: 38, lon0: -123, lon1: -122 }).cells[0].n, 2);
assert.strictEqual(gs.median_([[1, 1], [5, 3], [9, 1]]), 5);
console.log('community script tests passed');
