/**
 * MeshSurvey community sheet — Google Apps Script (MIT licence). Schema 2.
 *
 * Paste into Extensions → Apps Script of a PRIVATE Google Sheet, run setup() and installTrigger() once, then
 * Deploy → New deployment → Web app (Execute as: Me, Who has access: Anyone). Instructions: docs/COMMUNITY.md.
 * When updating an existing deployment: paste the new code, run setup() again (adds new tabs/columns, keeps data), then
 * Deploy → Manage deployments → edit → Version: New version (the URL stays the same).
 *
 * The Sheet stays private. The web app only: adds checked rows (POST), records withdrawals, and returns the combined,
 * privacy-preserving `aggregate` (GET). Session keys, contributor hashes, batch ids and withdrawal hashes never appear in GET
 * responses.
 *
 * Tabs (row 1 = headers; one record per row; `flags` = semicolon-separated codes):
 *   contributions one row per contributed session (session_key = hash of the app's session UUID)
 *   uploads       one row per accepted upload part (batch)
 *   cells         one row per shared square × day × preset × heard-from node — ORIGINAL VALUES, never edited by scripts
 *   reviews       review decisions and corrections, recorded separately (row_id, review ok/suspect/rejected, reason, corrected values)
 *   oddities      user reports and structurally invalid rows the script refused (original JSON kept)
 *   aggregate     combined per square × protocol × preset: trusted statistics + count of flagged rows awaiting review
 *   meta          key/value settings
 */
var SCHEMA = 2;
var MAX_ROWS_PER_DAY = 20000;       // per contributor
var MAX_ROWS_PER_BATCH = 500;
var MAX_PARTS = 50;
var MAX_BODY_BYTES = 400000;
var HEADERS = {
  contributions: ['session_key', 'start_check', 'first_received_utc', 'submitter_hash', 'parts', 'parts_received', 'cells_accepted',
                  'status', 'delete_hash', 'withdrawn_utc', 'equipment'],
  uploads: ['received_utc', 'submitter_hash', 'app', 'batch_id', 'session_key', 'part', 'parts', 'rows_accepted', 'rows_refused'],
  cells: ['row_id', 'received_utc', 'session_key', 'batch_id', 'submitter_hash', 'cell_id', 'lat', 'lon', 'day', 'protocol', 'preset',
          'heard_from', 'n', 'n_direct', 'snr_median', 'snr_best', 'rssi_median', 'flags', 'equipment'],
  reviews: ['row_id', 'review', 'reason', 'corrected_snr_median', 'corrected_rssi_median', 'reviewer', 'reviewed_utc'],
  oddities: ['received_utc', 'submitter_hash', 'source', 'day', 'lat', 'lon', 'text', 'original', 'review', 'review_note'],
  aggregate: ['cell_id', 'lat', 'lon', 'protocol', 'preset', 'n', 'contributors', 'snr_median', 'snr_best', 'rssi_median', 'days',
              'heard_from_top', 'pending_review', 'updated_utc'],
  meta: ['key', 'value']
};
/** Flags that do not make a row "questionable" (they describe, not doubt, the data). */
var BENIGN_FLAGS = { single_reception: 1, heard_from_ambiguous: 1 };

function setup() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  Object.keys(HEADERS).forEach(function (name) {
    var sh = ss.getSheetByName(name);
    if (sh && sh.getLastRow() > 0) {
      // an older layout (e.g. schema 1): keep it under another name, start a fresh tab
      var head = sh.getRange(1, 1, 1, HEADERS[name].length).getValues()[0];
      if (head.join('|') !== HEADERS[name].join('|')) { sh.setName(name + '_old_' + new Date().getTime()); sh = null; }
    }
    if (!sh) sh = ss.insertSheet(name);
    if (sh.getLastRow() === 0) sh.appendRow(HEADERS[name]);
    sh.setFrozenRows(1);
  });
  setMeta_(ss, 'schema', SCHEMA);
  setMeta_(ss, 'cell_size_m', 36.7);
  setMeta_(ss, 'max_rows_per_contributor_per_day', MAX_ROWS_PER_DAY);
  if (!getMeta_(ss, 'aggregate_version')) setMeta_(ss, 'aggregate_version', '0');
}

/** Rebuilds the aggregate every hour. Run once. */
function installTrigger() { ScriptApp.newTrigger('rebuildAggregate').timeBased().everyHours(1).create(); }

function getMeta_(ss, k) {
  var sh = ss.getSheetByName('meta'), n = sh.getLastRow();
  var v = n > 1 ? sh.getRange(2, 1, n - 1, 2).getValues() : [];
  for (var i = 0; i < v.length; i++) if (v[i][0] === k) return String(v[i][1]);
  return null;
}
function setMeta_(ss, k, val) {
  var sh = ss.getSheetByName('meta'), n = sh.getLastRow();
  var v = n > 1 ? sh.getRange(2, 1, n - 1, 2).getValues() : [];
  for (var i = 0; i < v.length; i++) if (v[i][0] === k) { sh.getRange(i + 2, 2, 1, 1).setValues([[val]]); return; }
  sh.appendRow([k, val]);
}

function json_(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
function nowUtc_() { return new Date().toISOString(); }
function hex_(bytes) { return bytes.map(function (b) { return ((b + 256) % 256).toString(16).padStart(2, '0'); }).join(''); }
function sha_(s) { return hex_(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, s)); }
function who_(s) { return sha_('meshsurvey:' + s).slice(0, 12); }
function str_(v, max) { return (v === null || v === undefined) ? '' : String(v).replace(/[\r\n\t]/g, ' ').slice(0, max); }
function num_(v) { return (typeof v === 'number' && isFinite(v)) ? v : null; }
function fail_(message, retry) { return { ok: false, message: message, retry: !!retry }; }
function values_(sh, cols) { var n = sh.getLastRow(); return n > 1 ? sh.getRange(2, 1, n - 1, cols).getValues() : []; }

function doPost(e) {
  if (!e || !e.postData || !e.postData.contents) return json_(fail_('empty request'));
  if (e.postData.contents.length > MAX_BODY_BYTES) return json_(fail_('request too large'));
  var p;
  try { p = JSON.parse(e.postData.contents); } catch (err) { return json_(fail_('not JSON')); }
  if (!p || p.schema !== SCHEMA) return json_({ ok: false, code: 'schema_unsupported', message: 'this sheet accepts schema ' + SCHEMA + '; update the app', retry: false });
  if (typeof p.submitter !== 'string' || !/^[0-9a-zA-Z]{8,64}$/.test(p.submitter)) return json_(fail_('missing submitter'));
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(15000)) return json_(fail_('busy, try again later', true));    // concurrency: one writer at a time
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    if (p.type === 'session_batch') return json_(handleBatch_(ss, p));
    if (p.type === 'oddity') return json_(handleOddity_(ss, p));
    if (p.type === 'withdraw') return json_(handleWithdraw_(ss, p));
    return json_(fail_('unknown type'));
  } catch (err) {
    // quota / service errors: tell the app to retry later (it backs off; it never retries in a loop)
    return json_(fail_('sheet error: ' + String(err && err.message || err).slice(0, 200), true));
  } finally { lock.releaseLock(); }
}

/** Structural check of one row: {row} (possibly flagged) or {reason} (invalid: never enters cells or aggregates). */
function checkCell_(r) {
  if (!r || typeof r !== 'object') return { reason: 'not an object' };
  var lat = num_(r.lat), lon = num_(r.lon), n = num_(r.n);
  if (lat === null || lon === null || lat < -90 || lat > 90 || lon < -180 || lon > 180) return { reason: 'bad position' };
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(r.day))) return { reason: 'bad day' };
  if (n === null || n < 1 || n > 100000 || Math.floor(n) !== n) return { reason: 'bad count' };
  if (r.protocol !== 'meshtastic' && r.protocol !== 'meshcore') return { reason: 'bad protocol' };
  if (!/^c-?\d+_-?\d+$/.test(String(r.cell_id))) return { reason: 'bad cell id' };
  var flags = str_(r.flags, 300).split(';').filter(function (f) { return /^[a-z_]{2,40}$/.test(f); });
  if (flags.indexOf('simulated') >= 0) return { reason: 'simulated data' };
  var snr = num_(r.snr_median), best = num_(r.snr_best), rssi = num_(r.rssi_median);
  // statistical outliers are kept with a flag (they may be real); only impossible structure is refused
  if (snr !== null && (snr < -40 || snr > 30)) flags.push('script_snr_out_of_range');
  if (rssi !== null && (rssi < -150 || rssi > 0)) flags.push('script_rssi_out_of_range');
  var nd = num_(r.n_direct); if (nd === null || nd < 0 || nd > n) nd = '';
  return { row: [str_(r.cell_id, 40), Math.round(lat * 1e5) / 1e5, Math.round(lon * 1e5) / 1e5, r.day, r.protocol, str_(r.preset, 40),
                 str_(r.heard_from, 40), n, nd, snr === null ? '' : snr, best === null ? '' : best, rssi === null ? '' : rssi, flags.join(';')] };
}

function equipment_(eq) {
  if (!eq || typeof eq !== 'object') return '';
  var allowed = { radio_model: 1, antenna_category: 1, antenna: 1, installation: 1, tx_power_dbm: 1 };
  var out = [];
  Object.keys(eq).forEach(function (k) { if (allowed[k]) out.push(k + '=' + str_(eq[k], 40).replace(/[;=]/g, ' ')); });
  return out.join(';');
}

function handleBatch_(ss, p) {
  if (p.contribution_tag !== 'meshsurvey-session') return fail_('not a MeshSurvey session contribution');
  if (p.provenance !== 'local_rf') return fail_('only measurements from real radios are accepted');
  if (!/^[0-9a-f]{24}$/.test(String(p.session_key))) return fail_('bad session key');
  var part = p.part, parts = p.parts;
  if (!(Math.floor(parts) === parts && parts >= 1 && parts <= MAX_PARTS && Math.floor(part) === part && part >= 0 && part < parts)) return fail_('bad part numbers');
  if (p.batch_id !== p.session_key + '-' + (part + 1) + 'of' + parts) return fail_('bad batch id');
  if (!/^[0-9a-f]{64}$/.test(String(p.delete_hash))) return fail_('missing withdrawal hash');
  if (!Array.isArray(p.rows) || p.rows.length === 0 || p.rows.length > MAX_ROWS_PER_BATCH) return fail_('rows missing or too many');
  var who = who_(p.submitter), now = nowUtc_(), today = now.slice(0, 10);

  var contrib = ss.getSheetByName('contributions');
  var cvals = values_(contrib, HEADERS.contributions.length), cidx = -1;
  for (var i = 0; i < cvals.length; i++) if (cvals[i][0] === p.session_key) { cidx = i; break; }
  if (cidx >= 0) {
    var c = cvals[cidx];
    if (c[7] === 'withdrawn') return fail_('this session was withdrawn');
    if (c[7] === 'complete') return { ok: true, message: 'already accepted', receipt: { batch_id: p.batch_id, accepted: 0, already: true, complete: true } };
    if (Number(c[4]) !== parts) return { ok: false, code: 'parts_mismatch', message: 'this session was started with ' + c[4] + ' parts', retry: false };
    if (String(c[1]) !== String(p.start_check)) return fail_('session key conflict');
  }
  // duplicate part?
  var uploads = ss.getSheetByName('uploads');
  var uvals = values_(uploads, HEADERS.uploads.length), used = 0;
  for (var j = 0; j < uvals.length; j++) {
    var u = uvals[j];
    if (u[3] === p.batch_id) return { ok: true, message: 'already accepted', receipt: { batch_id: p.batch_id, accepted: Number(u[7]) || 0, already: true } };
    if (u[1] === who && String(u[0]).slice(0, 10) === today) used += Number(u[7]) || 0;
  }
  if (used + p.rows.length > MAX_ROWS_PER_DAY) return fail_('daily limit reached for this contributor', true);

  var eq = equipment_(p.equipment), good = [], refused = [];
  p.rows.forEach(function (r, k) {
    var ck = checkCell_(r);
    if (ck.row) good.push([p.batch_id + ':' + k, now, p.session_key, p.batch_id, who].concat(ck.row).concat([eq]));
    else refused.push([now, who, 'script', str_(r && r.day, 10), num_(r && r.lat), num_(r && r.lon), 'refused row: ' + ck.reason,
                       str_(JSON.stringify(r), 1000), '', '']);
  });
  var cells = ss.getSheetByName('cells');
  if (good.length) cells.getRange(cells.getLastRow() + 1, 1, good.length, good[0].length).setValues(good);          // one batched write
  if (refused.length) { var o = ss.getSheetByName('oddities'); o.getRange(o.getLastRow() + 1, 1, refused.length, refused[0].length).setValues(refused); }
  uploads.appendRow([now, who, str_(p.app, 40), p.batch_id, p.session_key, part, parts, good.length, refused.length]);

  var received = 1, accepted = good.length;
  if (cidx < 0) {
    contrib.appendRow([p.session_key, str_(p.start_check, 12), now, who, parts, 1, accepted, parts === 1 ? 'complete' : 'partial', p.delete_hash, '', eq]);
  } else {
    received = Number(cvals[cidx][5]) + 1; accepted += Number(cvals[cidx][6]) || 0;
    contrib.getRange(cidx + 2, 6, 1, 3).setValues([[received, accepted, received >= parts ? 'complete' : 'partial']]);
  }
  return { ok: true, message: good.length + ' rows added' + (refused.length ? ', ' + refused.length + ' refused' : ''),
           receipt: { batch_id: p.batch_id, accepted: good.length, refused: refused.length, complete: received >= parts, received_utc: now } };
}

function handleOddity_(ss, p) {
  var text = str_(p.text, 1000);
  if (!text) return fail_('empty report');
  if (p.provenance !== 'user_report') return fail_('bad report');
  ss.getSheetByName('oddities').appendRow([nowUtc_(), who_(p.submitter), 'user', str_(p.day, 10), num_(p.lat), num_(p.lon), text, '', '', '']);
  return { ok: true, message: 'report received' };
}

/** Removes a session's rows when the caller proves it holds the private token sent (as a hash) with the upload. */
function handleWithdraw_(ss, p) {
  if (!/^[0-9a-f]{24}$/.test(String(p.session_key)) || typeof p.token !== 'string') return fail_('bad request');
  var contrib = ss.getSheetByName('contributions');
  var cvals = values_(contrib, HEADERS.contributions.length);
  for (var i = 0; i < cvals.length; i++) {
    if (cvals[i][0] !== p.session_key) continue;
    if (cvals[i][7] === 'withdrawn') return { ok: true, message: 'already withdrawn' };
    if (sha_(p.token) !== cvals[i][8]) return fail_('token does not match');
    var cells = ss.getSheetByName('cells');
    var all = values_(cells, HEADERS.cells.length);
    var keep = all.filter(function (r) { return r[2] !== p.session_key; });
    var removed = all.length - keep.length;
    if (all.length) cells.getRange(2, 1, all.length, HEADERS.cells.length).clearContent();
    if (keep.length) cells.getRange(2, 1, keep.length, HEADERS.cells.length).setValues(keep);
    contrib.getRange(i + 2, 8, 1, 3).setValues([['withdrawn', '', nowUtc_()]]);
    return { ok: true, message: removed + ' rows removed' };
  }
  return fail_('unknown session');
}

function median_(pairs) {          // [[value, weight]] → weighted median
  var a = pairs.filter(function (x) { return x[0] !== '' && x[0] !== null && isFinite(x[0]); }).sort(function (x, y) { return x[0] - y[0]; });
  if (!a.length) return null;
  var total = a.reduce(function (s, x) { return s + x[1]; }, 0), acc = 0;
  for (var i = 0; i < a.length; i++) { acc += a[i][1]; if (acc * 2 >= total) return Math.round(a[i][0] * 10) / 10; }
  return a[a.length - 1][0];
}

/**
 * Rebuilds `aggregate`. Trusted statistics use rows without questionable flags, or with a latest review of "ok" (with corrected
 * values if a review supplied them). Flagged rows not yet reviewed only add to pending_review. "rejected" rows are left out.
 * Original rows in `cells` are never modified.
 */
function rebuildAggregate() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var reviews = {};
  values_(ss.getSheetByName('reviews'), HEADERS.reviews.length).forEach(function (r) { reviews[r[0]] = r; });   // latest row wins
  var groups = {};
  values_(ss.getSheetByName('cells'), HEADERS.cells.length).forEach(function (v) {
    var rv = reviews[v[0]], verdict = rv ? String(rv[1]).toLowerCase() : '';
    if (verdict === 'rejected') return;
    var key = v[5] + '|' + v[9] + '|' + v[10];
    var g = groups[key] || (groups[key] = { cell: v[5], lat: v[6], lon: v[7], protocol: v[9], preset: v[10], n: 0, who: {}, snr: [], rssi: [], best: null, days: {}, heard: {}, pending: 0 });
    var questionable = String(v[17]).split(';').some(function (f) { return f && !BENIGN_FLAGS[f]; }) || verdict === 'suspect';
    if (questionable && verdict !== 'ok') { g.pending++; return; }
    var n = Number(v[12]) || 0;
    var snr = rv && rv[3] !== '' ? Number(rv[3]) : v[14], rssi = rv && rv[4] !== '' ? Number(rv[4]) : v[16];
    g.n += n; g.who[v[2]] = 1; g.days[v[8]] = 1; g.heard[v[11]] = (g.heard[v[11]] || 0) + n;
    if (snr !== '') g.snr.push([Number(snr), n]);
    if (rssi !== '') g.rssi.push([Number(rssi), n]);
    if (v[15] !== '' && (g.best === null || Number(v[15]) > g.best)) g.best = Number(v[15]);
  });
  var now = nowUtc_();
  var out = Object.keys(groups).map(function (k) {
    var g = groups[k];
    var top = Object.keys(g.heard).sort(function (a, b) { return g.heard[b] - g.heard[a]; })[0] || '';
    return [g.cell, g.lat, g.lon, g.protocol, g.preset, g.n, Object.keys(g.who).length, median_(g.snr), g.best === null ? '' : g.best,
            median_(g.rssi), Object.keys(g.days).length, top, g.pending, now];
  });
  var agg = ss.getSheetByName('aggregate');
  if (agg.getLastRow() > 1) agg.getRange(2, 1, agg.getLastRow() - 1, HEADERS.aggregate.length).clearContent();
  if (out.length) agg.getRange(2, 1, out.length, HEADERS.aggregate.length).setValues(out);
  setMeta_(ss, 'aggregate_version', now);
  return out.length;
}

/**
 * GET ?action=aggregate&lat0=..&lat1=..&lon0=..&lon1=..[&since=<version>]  → combined squares inside the box (max 5000).
 * `contributors` counts contributed sessions. With since = the current version the answer is {unchanged: true}.
 */
function doGet(e) {
  var q = (e && e.parameter) || {};
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (q.action === 'ping') return json_({ ok: true, schema: SCHEMA });
  if (q.action !== 'aggregate') return json_(fail_('unknown action'));
  var lat0 = Number(q.lat0), lat1 = Number(q.lat1), lon0 = Number(q.lon0), lon1 = Number(q.lon1);
  if (![lat0, lat1, lon0, lon1].every(isFinite) || lat1 - lat0 > 5 || lon1 - lon0 > 5) return json_(fail_('bounding box required (at most 5°)'));
  var version = getMeta_(ss, 'aggregate_version') || '0';
  if (q.since && q.since === version) return json_({ ok: true, unchanged: true, version: version });
  var vals = values_(ss.getSheetByName('aggregate'), HEADERS.aggregate.length);
  var cells = [];
  for (var i = 0; i < vals.length && cells.length < 5000; i++) {
    var v = vals[i];
    if (v[1] < lat0 || v[1] > lat1 || v[2] < lon0 || v[2] > lon1) continue;
    cells.push({ cell_id: v[0], lat: v[1], lon: v[2], protocol: v[3], preset: v[4], n: v[5], contributors: v[6], snr_median: v[7],
                 snr_best: v[8], rssi_median: v[9], days: v[10], heard_from_top: v[11], pending_review: v[12] });
  }
  return json_({ ok: true, version: version, cells: cells });
}

if (typeof module !== 'undefined') module.exports = { doPost: doPost, doGet: doGet, setup: setup, rebuildAggregate: rebuildAggregate, checkCell_: checkCell_, median_: median_ };
