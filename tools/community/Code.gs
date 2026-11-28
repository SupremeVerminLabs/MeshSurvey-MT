/**
 * MeshSurvey community sheet — Google Apps Script (MIT licence).
 *
 * Paste into Extensions → Apps Script of a PRIVATE Google Sheet, run setup() once, then Deploy → New deployment →
 * Web app (Execute as: Me, Who has access: Anyone). Put the web-app URL into MeshSurvey → Settings → Community.
 * The Sheet itself stays private: the app can only add rows through this script and read the combined `aggregate` tab.
 * Full instructions: docs/COMMUNITY.md.
 *
 * Tabs (row 1 = headers, one record per row, snake_case columns; `flags` = semicolon-separated codes):
 *   uploads   one row per accepted upload part
 *   cells     one row per shared square (≈1/3 acre) × day × preset × heard-from node
 *   oddities  user reports and rows the script refused; `review` is for a human or scheduled reviewer (ok/suspect/rejected)
 *   aggregate all contributions combined per square × preset × protocol (rebuilt by rebuildAggregate, hourly trigger)
 *   meta      key/value settings
 */
var SCHEMA = 1;
var MAX_ROWS_PER_DAY = 20000;     // per contributor
var HEADERS = {
  uploads: ['received_utc', 'submitter_hash', 'app', 'batch', 'part', 'parts', 'rows_accepted', 'rows_refused'],
  cells: ['received_utc', 'submitter_hash', 'batch', 'cell_id', 'lat', 'lon', 'day', 'protocol', 'preset', 'heard_from', 'n',
          'n_direct', 'snr_median', 'snr_best', 'rssi_median', 'flags', 'review', 'review_note'],
  oddities: ['received_utc', 'submitter_hash', 'source', 'day', 'lat', 'lon', 'text', 'flags', 'review', 'review_note'],
  aggregate: ['cell_id', 'lat', 'lon', 'protocol', 'preset', 'n', 'contributors', 'snr_median', 'snr_best', 'rssi_median',
              'days', 'heard_from_top', 'flagged', 'updated_utc'],
  meta: ['key', 'value']
};

function setup() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  Object.keys(HEADERS).forEach(function (name) {
    var sh = ss.getSheetByName(name) || ss.insertSheet(name);
    if (sh.getLastRow() === 0) sh.appendRow(HEADERS[name]);
    sh.setFrozenRows(1);
  });
  var meta = ss.getSheetByName('meta');
  if (meta.getLastRow() < 2) {
    meta.appendRow(['schema', SCHEMA]);
    meta.appendRow(['cell_size_m', 36.7]);
    meta.appendRow(['max_rows_per_contributor_per_day', MAX_ROWS_PER_DAY]);
  }
}

/** Run once to rebuild the aggregate every hour. */
function installTrigger() {
  ScriptApp.newTrigger('rebuildAggregate').timeBased().everyHours(1).create();
}

function json_(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
function nowUtc_() { return new Date().toISOString(); }
function hash_(s) {
  var d = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, 'meshsurvey:' + s);
  return d.slice(0, 6).map(function (b) { return ((b + 256) % 256).toString(16).padStart(2, '0'); }).join('');
}
function str_(v, max) { return (v === null || v === undefined) ? '' : String(v).replace(/[\r\n\t]/g, ' ').slice(0, max); }
function num_(v) { return (typeof v === 'number' && isFinite(v)) ? v : null; }

function doPost(e) {
  var p;
  try { p = JSON.parse(e.postData.contents); } catch (err) { return json_({ ok: false, message: 'not JSON' }); }
  if (!p || p.schema !== SCHEMA) return json_({ ok: false, message: 'unsupported schema ' + (p && p.schema) });
  if (typeof p.submitter !== 'string' || p.submitter.length < 8) return json_({ ok: false, message: 'missing submitter' });
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    if (p.type === 'cells') return json_(handleCells_(ss, p));
    if (p.type === 'oddity') return json_(handleOddity_(ss, p));
    return json_({ ok: false, message: 'unknown type' });
  } finally { lock.releaseLock(); }
}

/** Checks one shared row; returns {row} or {reason}. Unusual but possible values are flagged, not refused. */
function checkCell_(r) {
  var lat = num_(r.lat), lon = num_(r.lon), n = num_(r.n);
  if (lat === null || lon === null || lat < -90 || lat > 90 || lon < -180 || lon > 180) return { reason: 'bad position' };
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(r.day))) return { reason: 'bad day' };
  if (n === null || n < 1 || n > 100000 || Math.floor(n) !== n) return { reason: 'bad count' };
  if (r.protocol !== 'meshtastic' && r.protocol !== 'meshcore') return { reason: 'bad protocol' };
  var flags = str_(r.flags, 300).split(';').filter(function (f) { return /^[a-z_]{2,40}$/.test(f); });
  var snr = num_(r.snr_median), best = num_(r.snr_best), rssi = num_(r.rssi_median);
  if (snr !== null && (snr < -40 || snr > 30)) { flags.push('script_snr_out_of_range'); }
  if (rssi !== null && (rssi < -150 || rssi > 0)) { flags.push('script_rssi_out_of_range'); }
  var nd = num_(r.n_direct); if (nd === null || nd < 0 || nd > n) nd = null;
  return { row: [str_(r.cell_id, 40), Math.round(lat * 1e5) / 1e5, Math.round(lon * 1e5) / 1e5, r.day, r.protocol, str_(r.preset, 40),
                 str_(r.heard_from, 40), n, nd, snr, best, rssi, flags.join(';')] };
}

function handleCells_(ss, p) {
  if (!Array.isArray(p.rows) || p.rows.length === 0 || p.rows.length > 1000) return { ok: false, message: 'rows missing or too many' };
  var who = hash_(p.submitter), now = nowUtc_(), today = now.slice(0, 10);
  var uploads = ss.getSheetByName('uploads');
  var last = uploads.getLastRow();
  var used = 0;
  if (last > 1) {
    var from = Math.max(2, last - 2000);
    var vals = uploads.getRange(from, 1, last - from + 1, 8).getValues();
    for (var i = 0; i < vals.length; i++) {
      var v = vals[i];
      if (v[1] === who && String(v[3]) === String(p.batch) && Number(v[4]) === Number(p.part) && p.batch)
        return { ok: true, message: 'already received' };                 // a retry of a part we have: accept, do nothing
      if (v[1] === who && String(v[0]).slice(0, 10) === today) used += Number(v[6]) || 0;
    }
  }
  if (used + p.rows.length > MAX_ROWS_PER_DAY) return { ok: false, message: 'daily limit reached for this contributor' };
  var good = [], refused = [];
  p.rows.forEach(function (r) {
    var c = checkCell_(r);
    if (c.row) good.push([now, who, str_(p.batch, 80)].concat(c.row).concat(['', '']));
    else refused.push([now, who, 'script', str_(r.day, 10), num_(r.lat), num_(r.lon), 'refused row: ' + c.reason, '', '', '']);
  });
  var cells = ss.getSheetByName('cells');
  if (good.length) cells.getRange(cells.getLastRow() + 1, 1, good.length, good[0].length).setValues(good);
  if (refused.length) { var o = ss.getSheetByName('oddities'); o.getRange(o.getLastRow() + 1, 1, refused.length, refused[0].length).setValues(refused); }
  uploads.appendRow([now, who, str_(p.app, 40), str_(p.batch, 80), Number(p.part) || 0, Number(p.parts) || 1, good.length, refused.length]);
  return { ok: true, message: good.length + ' rows added' + (refused.length ? ', ' + refused.length + ' refused' : '') };
}

function handleOddity_(ss, p) {
  var text = str_(p.text, 1000);
  if (!text) return { ok: false, message: 'empty report' };
  var lat = num_(p.lat), lon = num_(p.lon);
  ss.getSheetByName('oddities').appendRow([nowUtc_(), hash_(p.submitter), 'user', str_(p.day, 10), lat, lon, text, '', '', '']);
  return { ok: true, message: 'report received' };
}

function median_(pairs) {          // [[value, weight]] → weighted median
  var a = pairs.filter(function (x) { return x[0] !== '' && x[0] !== null && isFinite(x[0]); }).sort(function (x, y) { return x[0] - y[0]; });
  if (!a.length) return null;
  var total = a.reduce(function (s, x) { return s + x[1]; }, 0), acc = 0;
  for (var i = 0; i < a.length; i++) { acc += a[i][1]; if (acc * 2 >= total) return Math.round(a[i][0] * 10) / 10; }
  return a[a.length - 1][0];
}

/** Combines all accepted cells per square × protocol × preset. Rows marked review = "rejected" are left out. */
function rebuildAggregate() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var cells = ss.getSheetByName('cells');
  var last = cells.getLastRow();
  var vals = last > 1 ? cells.getRange(2, 1, last - 1, HEADERS.cells.length).getValues() : [];
  var groups = {};
  vals.forEach(function (v) {
    if (String(v[16]).toLowerCase() === 'rejected') return;
    var key = v[3] + '|' + v[7] + '|' + v[8];
    var g = groups[key] || (groups[key] = { cell: v[3], lat: v[4], lon: v[5], protocol: v[7], preset: v[8], n: 0, who: {}, snr: [], rssi: [], best: null, days: {}, heard: {}, flagged: false });
    var n = Number(v[10]) || 0;
    g.n += n; g.who[v[1]] = 1; g.days[v[6]] = 1; g.heard[v[9]] = (g.heard[v[9]] || 0) + n;
    if (v[12] !== '') g.snr.push([Number(v[12]), n]);
    if (v[14] !== '') g.rssi.push([Number(v[14]), n]);
    if (v[13] !== '' && (g.best === null || Number(v[13]) > g.best)) g.best = Number(v[13]);
    var f = String(v[15]).split(';').filter(function (x) { return x && x !== 'single_reception' && x !== 'heard_from_ambiguous'; });
    if (f.length || String(v[16]).toLowerCase() === 'suspect') g.flagged = true;
  });
  var now = nowUtc_();
  var out = Object.keys(groups).map(function (k) {
    var g = groups[k];
    var top = Object.keys(g.heard).sort(function (a, b) { return g.heard[b] - g.heard[a]; })[0] || '';
    return [g.cell, g.lat, g.lon, g.protocol, g.preset, g.n, Object.keys(g.who).length, median_(g.snr), g.best, median_(g.rssi),
            Object.keys(g.days).length, top, g.flagged, now];
  });
  var agg = ss.getSheetByName('aggregate');
  if (agg.getLastRow() > 1) agg.getRange(2, 1, agg.getLastRow() - 1, HEADERS.aggregate.length).clearContent();
  if (out.length) agg.getRange(2, 1, out.length, HEADERS.aggregate.length).setValues(out);
  return out.length;
}

/** GET ?action=aggregate&lat0=..&lat1=..&lon0=..&lon1=..  → combined squares inside the box (max 5000). */
function doGet(e) {
  var q = (e && e.parameter) || {};
  if (q.action === 'ping') return json_({ ok: true, schema: SCHEMA });
  if (q.action !== 'aggregate') return json_({ ok: false, message: 'unknown action' });
  var lat0 = Number(q.lat0), lat1 = Number(q.lat1), lon0 = Number(q.lon0), lon1 = Number(q.lon1);
  if (![lat0, lat1, lon0, lon1].every(isFinite)) return json_({ ok: false, message: 'bounding box required' });
  var agg = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('aggregate');
  var last = agg.getLastRow();
  var vals = last > 1 ? agg.getRange(2, 1, last - 1, HEADERS.aggregate.length).getValues() : [];
  var cells = [];
  for (var i = 0; i < vals.length && cells.length < 5000; i++) {
    var v = vals[i];
    if (v[1] < lat0 || v[1] > lat1 || v[2] < lon0 || v[2] > lon1) continue;
    cells.push({ cell_id: v[0], lat: v[1], lon: v[2], protocol: v[3], preset: v[4], n: v[5], contributors: v[6], snr_median: v[7],
                 snr_best: v[8], rssi_median: v[9], days: v[10], heard_from_top: v[11], flagged: String(v[12]) === 'true' });
  }
  return json_({ ok: true, cells: cells });
}

if (typeof module !== 'undefined') module.exports = { doPost: doPost, doGet: doGet, setup: setup, rebuildAggregate: rebuildAggregate, checkCell_: checkCell_, median_: median_ };
