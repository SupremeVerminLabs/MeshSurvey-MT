// Runs Code.gs against an in-memory stand-in for Google Sheets (node tools/community/test.js). Not a Google test.
const assert = require('assert');
const crypto = require('crypto');
function makeSheet() {
  const rows = [];
  return { rows,
    getLastRow: () => rows.length, appendRow: r => rows.push(r.slice()), setFrozenRows: () => {},
    getRange: (r, c, nr, nc) => ({
      getValues: () => rows.slice(r - 1, r - 1 + nr).map(x => { const y = x.slice(c - 1, c - 1 + nc); while (y.length < nc) y.push(''); return y; }),
      setValues: v => { v.forEach((row, i) => { rows[r - 1 + i] = row.slice(); }); },
      clearContent: () => { rows.splice(r - 1, nr); },
    }) };
}
const sheets = {};
global.SpreadsheetApp = { getActiveSpreadsheet: () => ({ getSheetByName: n => sheets[n] || null, insertSheet: n => (sheets[n] = makeSheet()) }) };
global.LockService = { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) };
global.ContentService = { MimeType: { JSON: 'json' }, createTextOutput: s => ({ text: s, setMimeType() { return this; } }) };
global.Utilities = { DigestAlgorithm: { SHA_256: 1 }, computeDigest: (_, s) => Array.from(crypto.createHash('sha256').update(s).digest()).map(b => b > 127 ? b - 256 : b) };
const fs = require('fs');
const mod = { exports: {} };
new Function('module', fs.readFileSync(__dirname + '/Code.gs', 'utf8'))(mod);
const gs = mod.exports;
const post = o => JSON.parse(gs.doPost({ postData: { contents: JSON.stringify(o) } }).text);

gs.setup();
const row = (lat, snr, n, flags) => ({ cell_id: 'c1_2', lat, lon: -122.03, day: '2026-11-27', protocol: 'meshtastic', preset: 'LONG_FAST',
  heard_from: 'mt:!1234abcd', n, n_direct: 1, snr_median: snr, snr_best: snr + 2, rssi_median: -100, flags });
let r = post({ schema: 1, type: 'cells', submitter: 'abcdef0123', app: 't', batch: 'b1', part: 0, parts: 1,
  rows: [row(37.97, -3, 4, ''), row(37.97, 55, 1, 'single_reception'), row(999, 0, 1, '')] });
assert.strictEqual(r.ok, true); assert.match(r.message, /2 rows added, 1 refused/);
assert.strictEqual(sheets.cells.rows.length, 3);                                  // header + 2
assert.match(sheets.cells.rows[2][15], /script_snr_out_of_range/);                // flagged, not dropped
assert.strictEqual(sheets.oddities.rows.length, 2);                               // refused row recorded
r = post({ schema: 1, type: 'cells', submitter: 'abcdef0123', batch: 'b1', part: 0, parts: 1, rows: [row(37.97, -3, 4, '')] });
assert.strictEqual(r.message, 'already received'); assert.strictEqual(sheets.cells.rows.length, 3);
post({ schema: 1, type: 'cells', submitter: 'zzzzzzzz99', batch: 'b9', part: 0, parts: 1, rows: [row(37.97, 1, 2, '')] });
assert.strictEqual(post({ schema: 2, type: 'cells', submitter: 'abcdef0123', rows: [] }).ok, false);
assert.strictEqual(post({ schema: 1, type: 'oddity', submitter: 'abcdef0123', text: 'RSSI -20 at 5 km?', day: '2026-11-27' }).ok, true);
assert.strictEqual(gs.rebuildAggregate(), 1);
const g = JSON.parse(gs.doGet({ parameter: { action: 'aggregate', lat0: 37, lat1: 38, lon0: -123, lon1: -122 } }).text);
assert.strictEqual(g.cells.length, 1);
assert.strictEqual(g.cells[0].n, 7); assert.strictEqual(g.cells[0].contributors, 2); assert.strictEqual(g.cells[0].flagged, true);
assert.strictEqual(gs.median_([[1, 1], [5, 3], [9, 1]]), 5);
// a reviewer marking a row rejected removes it from the aggregate
sheets.cells.rows[2][16] = 'rejected'; gs.rebuildAggregate();
assert.strictEqual(JSON.parse(gs.doGet({ parameter: { action: 'aggregate', lat0: 37, lat1: 38, lon0: -123, lon1: -122 } }).text).cells[0].flagged, false);
console.log('community script tests passed');
