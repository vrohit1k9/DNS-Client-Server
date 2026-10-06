/**
 * Custom DNS GUI Application Logic
 * Replicates exact behaviour and UI of DNSGui.java desktop application
 */

// ── Authoritative Records (from dns_records.txt) ──────────────────────────────
const STATIC_RECORDS = {
  'google.com': '142.250.195.14',
  'example.com': '93.184.216.34',
  'openai.com': '104.18.32.47',
  'github.com': '140.82.121.4',
  'stackoverflow.com': '151.101.193.69',
  'wikipedia.org': '208.80.154.224',
  'amazon.com': '176.32.103.205',
  'microsoft.com': '20.112.52.29',
  'apple.com': '17.253.144.10',
  'facebook.com': '157.240.221.35',
  'localhost': '127.0.0.1',
  'server.local': '192.168.1.10',
  'db.local': '192.168.1.20',
  'api.local': '192.168.1.30',
  'mail.local': '192.168.1.40',
  'mywebsite.com': '203.0.113.10',
  'testserver.com': '198.51.100.5',
  'college.edu': '10.0.0.1',
  'premierleague.com': '212.58.237.252'
};

// ── Persistent History (from MongoDB Atlas / CSV) ────────────────────────────
const INITIAL_HISTORY = [
  { domain: 'google.com', ip: '142.250.195.14', count: 5, last: '2026-10-06 21:26:04', source: 'MONGODB' },
  { domain: 'vtop.vit.ac.in', ip: '122.184.65.23', count: 11, last: '2026-10-05 22:40:06', source: 'MONGODB' },
  { domain: 'www.google.com', ip: '142.251.151.119', count: 21, last: '2026-10-05 17:22:52', source: 'MONGODB' },
  { domain: 'github.com', ip: '140.82.121.4', count: 3, last: '2026-10-05 17:22:53', source: 'MONGODB' },
  { domain: 'wimt.confirmtkt.com', ip: '18.161.246.45', count: 38, last: '2026-10-06 20:04:00', source: 'MONGODB' },
  { domain: 'www.delhivery.com', ip: '18.161.246.23', count: 25, last: '2026-08-04 10:12:35', source: 'MONGODB' },
  { domain: 'thehindu.com', ip: '104.18.8.4', count: 2, last: '2026-10-06 20:05:38', source: 'DNS_LOOKUP' },
  { domain: 'docs.google.com', ip: '172.217.24.174', count: 26, last: '2026-09-08 12:31:20', source: 'MONGODB' },
  { domain: 'posh.balmerlawrie.com', ip: '164.100.221.162', count: 11, last: '2026-10-05 16:33:54', source: 'MONGODB' },
  { domain: 'www.sastasafar.com', ip: '104.26.0.91', count: 14, last: '2026-10-05 16:33:54', source: 'MONGODB' },
  { domain: 'payments.juspay.in', ip: '108.159.15.55', count: 12, last: '2026-10-05 16:33:54', source: 'MONGODB' },
  { domain: 'www.goindigo.in', ip: '23.199.69.145', count: 10, last: '2026-10-05 16:33:54', source: 'MONGODB' },
  { domain: 'www.facebook.com', ip: '57.144.54.1', count: 34, last: '2026-08-17 15:06:58', source: 'MONGODB' },
  { domain: 'www.irctc.co.in', ip: '184.51.192.146', count: 6, last: '2026-10-05 17:22:52', source: 'MONGODB' },
  { domain: 'irctc.co.in', ip: '103.252.142.27', count: 1, last: '2026-10-05 17:24:56', source: 'MONGODB' }
];

// ── Application State ────────────────────────────────────────────────────────
const state = {
  serverRunning: false,
  queries: 0,
  hits: 0,
  misses: 0,
  // Cache Map: domain -> { ip, expiresAt, ttl, status }
  cache: new Map(),
  history: [...INITIAL_HISTORY],
  queryLogs: []
};

// ── DOM Elements ─────────────────────────────────────────────────────────────
let serverLogBox;
let clientLogBox;
let cacheTbody;
let historyTbody;
let queryLogTbody;

document.addEventListener('DOMContentLoaded', () => {
  serverLogBox = document.getElementById('server-log-console');
  clientLogBox = document.getElementById('client-log-console');
  cacheTbody = document.getElementById('cache-table-body');
  historyTbody = document.getElementById('history-table-body');
  queryLogTbody = document.getElementById('query-log-table-body');

  renderHistoryTable();
  renderCacheTable();
  updateStatCards();
  startTtlCountdown();

  // Print initial server readiness
  logServer(`[System] Custom DNS GUI initialized.`);
  logServer(`[System] In-memory cache engine ready (TTL = 60s).`);
  logServer(`[MongoDB] Atlas connection status: ONLINE`);
  logServer(`Click 'START SERVER' to bind UDP socket on port 5454.`);

  logClient(`[Client] DNS Client ready (Server target = 127.0.0.1:5454).`);
});

// ── Tab Navigation ───────────────────────────────────────────────────────────
function switchTab(tabName) {
  const tabs = ['server', 'client', 'querylog'];
  tabs.forEach(t => {
    const btn = document.getElementById(`tab-btn-${t}`);
    const content = document.getElementById(`tab-content-${t}`);
    if (t === tabName) {
      btn.classList.add('active');
      content.classList.add('active');
    } else {
      btn.classList.remove('active');
      content.classList.remove('active');
    }
  });
}

// ── Server Toggle: Start / Stop ──────────────────────────────────────────────
function toggleServer() {
  const btn = document.getElementById('btn-toggle-server');
  const badge = document.getElementById('badge-server-status');

  state.serverRunning = !state.serverRunning;

  if (state.serverRunning) {
    btn.innerText = '  STOP SERVER  ';
    btn.classList.add('running');
    badge.innerText = 'ONLINE';
    badge.className = 'badge-status online';

    logServer(`\n================================================================`);
    logServer(`  CUSTOM DNS SERVER STARTED`);
    logServer(`================================================================`);
    logServer(`Listening on UDP DatagramSocket port 5454`);
    logServer(`Authoritative database loaded: 19 records (dns_records.txt)`);
    logServer(`MongoDB Atlas Search History connected.`);
    logServer(`Ready to receive DNS requests...\n`);
  } else {
    btn.innerText = 'START SERVER';
    btn.classList.remove('running');
    badge.innerText = 'OFFLINE';
    badge.className = 'badge-status offline';

    logServer(`[${getTimestamp()}] DNSServer stopped (Port 5454 released).`);
  }
}

// ── Logger Helpers ───────────────────────────────────────────────────────────
function getTimestamp() {
  const now = new Date();
  return now.toTimeString().split(' ')[0];
}

function logServer(text) {
  if (!serverLogBox) return;
  const p = document.createElement('div');
  p.textContent = text;
  serverLogBox.appendChild(p);
  serverLogBox.scrollTop = serverLogBox.scrollHeight;
}

function logClient(text) {
  if (!clientLogBox) return;
  const p = document.createElement('div');
  p.textContent = text;
  clientLogBox.appendChild(p);
  clientLogBox.scrollTop = clientLogBox.scrollHeight;
}

function clearServerLog() {
  if (serverLogBox) serverLogBox.innerHTML = '';
}

function clearClientLog() {
  if (clientLogBox) clientLogBox.innerHTML = '';
}

function clearAllLogs() {
  state.queryLogs = [];
  renderQueryLogTable();
}

function flushCache() {
  state.cache.clear();
  renderCacheTable();
  updateStatCards();
  logServer(`[${getTimestamp()}] Cache flushed: 0 entries remaining.`);
}

// ── Client DNS Resolve ───────────────────────────────────────────────────────
function setAndResolve(domain) {
  document.getElementById('client-domain-input').value = domain;
  switchTab('client');
  doResolve();
}

async function doResolve() {
  const input = document.getElementById('client-domain-input');
  const domain = input.value.trim().toLowerCase();
  if (!domain) return;

  const resIp = document.getElementById('client-res-ip');
  const resStatus = document.getElementById('client-res-status');
  const resTime = document.getElementById('client-res-time');
  const resSource = document.getElementById('client-res-source');

  state.queries++;
  const t0 = performance.now();

  logClient(`\n[${getTimestamp()}] ── Querying domain: ${domain} ──`);
  logClient(`[${getTimestamp()}] Building packet: DNS|REQUEST|${domain}`);
  logClient(`[${getTimestamp()}] Sending datagram to 127.0.0.1:5454...`);

  if (!state.serverRunning) {
    logClient(`[${getTimestamp()}] Warning: Server is currently OFFLINE! Auto-starting DNS resolver...`);
    toggleServer();
  }

  logServer(`[${getTimestamp()}] UDP packet received from 127.0.0.1:5454 -> DNS|REQUEST|${domain}`);
  logServer(`[${getTimestamp()}] Thread spawned: DNSHandler-${state.queries}`);

  let resolvedIp = null;
  let status = '';
  let source = '';
  let ttl = 60;

  // 1. Check in-memory Cache
  const cached = state.cache.get(domain);
  const now = Date.now();

  if (cached && now < cached.expiresAt) {
    state.hits++;
    resolvedIp = cached.ip;
    status = 'CACHE HIT';
    source = 'Source: Cache';
    cached.status = 'ACTIVE';

    logServer(`[${getTimestamp()}] [CACHE HIT] Found ${domain} in memory cache (${resolvedIp})`);
  } else {
    state.misses++;
    logServer(`[${getTimestamp()}] [CACHE MISS] Checking persistent storage / upstream...`);

    // 2. Check Static Records or MongoDB
    const staticIp = STATIC_RECORDS[domain];
    const mongoItem = state.history.find(h => h.domain === domain);

    if (staticIp || mongoItem) {
      resolvedIp = staticIp || mongoItem.ip;
      status = mongoItem ? 'MONGO HIT' : 'RESOLVED';
      source = mongoItem ? 'Source: MongoDB Atlas' : 'Source: dns_records.txt';

      if (mongoItem) {
        mongoItem.count++;
        mongoItem.last = new Date().toISOString().replace('T', ' ').substring(0, 19);
        logServer(`[${getTimestamp()}] [MONGODB HIT] Matched in Atlas collection: searchCount=${mongoItem.count}`);
      } else {
        logServer(`[${getTimestamp()}] [RECORDS HIT] Authoritative record matched from dns_records.txt`);
      }

      // Add to Cache
      state.cache.set(domain, {
        ip: resolvedIp,
        expiresAt: Date.now() + 60000,
        ttl: 60,
        status: 'ACTIVE'
      });
    } else {
      // 3. Fallback to Cloudflare DoH live query
      try {
        logServer(`[${getTimestamp()}] Forwarding recursive lookup to upstream DNS resolver...`);
        const dohRes = await fetch(`https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(domain)}&type=A`, {
          headers: { 'Accept': 'application/dns-json' }
        });
        const dohData = await dohRes.json();

        if (dohData && dohData.Answer && dohData.Answer.length > 0) {
          const aRec = dohData.Answer.find(a => a.type === 1) || dohData.Answer[0];
          resolvedIp = aRec.data;
          ttl = Math.min(aRec.TTL || 60, 300);
          status = 'RESOLVED';
          source = 'Source: Authoritative DNS';

          // Add to History
          state.history.unshift({
            domain: domain,
            ip: resolvedIp,
            count: 1,
            last: new Date().toISOString().replace('T', ' ').substring(0, 19),
            source: 'MONGODB'
          });

          // Add to Cache
          state.cache.set(domain, {
            ip: resolvedIp,
            expiresAt: Date.now() + (ttl * 1000),
            ttl: ttl,
            status: 'ACTIVE'
          });

          logServer(`[${getTimestamp()}] [UPSTREAM RESOLVED] ${domain} -> ${resolvedIp} (TTL: ${ttl}s)`);
        } else {
          throw new Error('NXDOMAIN');
        }
      } catch (err) {
        resolvedIp = 'DOMAIN_NOT_FOUND';
        status = 'ERROR';
        source = 'Source: None';
        logServer(`[${getTimestamp()}] [DNS ERROR] Domain not found: ${domain}`);
      }
    }
  }

  const elapsed = (performance.now() - t0).toFixed(1);

  // Update client UI
  resIp.innerText = resolvedIp;
  resStatus.style.display = 'inline-block';
  resStatus.innerText = status;
  resStatus.className = 'badge-res-status ' + (status.includes('CACHE') ? 'cache' : status.includes('MONGO') ? 'mongo' : status.includes('RESOLVED') ? 'upstream' : 'error');
  resTime.innerText = `${elapsed} ms`;
  resSource.innerText = source;

  logClient(`[${getTimestamp()}] Received UDP response: DNS|RESPONSE|${domain}|${resolvedIp}`);
  logClient(`[${getTimestamp()}] Status: ${status} in ${elapsed} ms\n`);

  // Log to Query Log tab
  state.queryLogs.unshift({
    time: getTimestamp(),
    domain: domain,
    ip: resolvedIp,
    tier: status,
    latency: `${elapsed} ms`,
    status: status.includes('ERROR') ? 'FAILED' : 'SUCCESS'
  });

  renderCacheTable();
  renderHistoryTable();
  renderQueryLogTable();
  updateStatCards();
}

// ── In-Memory Cache Table Rendering & TTL Countdown ─────────────────────────
function renderCacheTable() {
  if (!cacheTbody) return;
  const entries = Array.from(state.cache.entries());

  if (entries.length === 0) {
    cacheTbody.innerHTML = `<tr><td colspan="4" style="text-align:center; color:var(--text-dim); padding:20px;">Cache is empty. Perform lookups to populate.</td></tr>`;
    return;
  }

  const now = Date.now();
  let html = '';

  for (const [domain, data] of entries) {
    const leftSec = Math.max(0, Math.round((data.expiresAt - now) / 1000));
    const isExpired = leftSec === 0;
    const statusClass = isExpired ? 'status-expired' : 'status-active';
    const statusText = isExpired ? 'EXPIRED' : 'ACTIVE';

    html += `
      <tr>
        <td style="font-weight:700;">${domain}</td>
        <td style="color:var(--accent-cyan);">${data.ip}</td>
        <td class="ttl-cell">${leftSec}s</td>
        <td class="${statusClass}">${statusText}</td>
      </tr>
    `;
  }

  cacheTbody.innerHTML = html;
}

function startTtlCountdown() {
  setInterval(() => {
    let changed = false;
    const now = Date.now();

    for (const [domain, data] of state.cache.entries()) {
      if (now >= data.expiresAt && data.status !== 'EXPIRED') {
        data.status = 'EXPIRED';
        changed = true;
      }
    }

    renderCacheTable();
    if (changed) updateStatCards();
  }, 1000);
}

// ── Query History Table ─────────────────────────────────────────────────────
function renderHistoryTable() {
  if (!historyTbody) return;
  historyTbody.innerHTML = state.history.map(row => `
    <tr>
      <td style="font-weight:700;">${row.domain}</td>
      <td style="color:var(--accent-cyan);">${row.ip}</td>
      <td style="text-align:center; color:var(--accent-green); font-weight:700;">${row.count}</td>
      <td style="color:var(--text-muted); font-size:11px;">${row.last}</td>
    </tr>
  `).join('');
}

function clearHistoryTable() {
  state.history = [];
  renderHistoryTable();
}

function reloadMongoHistory() {
  state.history = [...INITIAL_HISTORY];
  renderHistoryTable();
  logClient(`[MongoDB] Reloaded ${state.history.length} records from Atlas.`);
}

function importChromeDemo() {
  logClient(`\n[Chrome Import] Reading local history SQLite database...`);
  logClient(`[Chrome Import] Imported 24 top domains into MongoDB history.`);
  reloadMongoHistory();
}

// ── Query Log Table ──────────────────────────────────────────────────────────
function renderQueryLogTable() {
  if (!queryLogTbody) return;
  if (state.queryLogs.length === 0) {
    queryLogTbody.innerHTML = `<tr><td colspan="6" style="text-align:center; color:var(--text-dim); padding:24px;">No queries logged yet.</td></tr>`;
    return;
  }

  queryLogTbody.innerHTML = state.queryLogs.map(log => `
    <tr>
      <td style="color:var(--text-dim);">${log.time}</td>
      <td style="font-weight:700;">${log.domain}</td>
      <td style="color:var(--accent-cyan);">${log.ip}</td>
      <td style="text-align:center; color:var(--accent-amber); font-weight:700;">${log.tier}</td>
      <td style="text-align:center; color:var(--accent-green);">${log.latency}</td>
      <td style="text-align:center; font-weight:800; color:${log.status === 'SUCCESS' ? 'var(--accent-green)' : 'var(--accent-red)'};">${log.status}</td>
    </tr>
  `).join('');
}

// ── Update 5 Stat Cards ──────────────────────────────────────────────────────
function updateStatCards() {
  document.getElementById('stat-total-queries').innerText = state.queries;
  document.getElementById('stat-cache-hits').innerText = state.hits;
  document.getElementById('stat-cache-misses').innerText = state.misses;

  const rate = state.queries > 0 ? Math.round((state.hits / state.queries) * 100) : 0;
  document.getElementById('stat-hit-rate').innerText = `${rate}%`;

  const activeCount = Array.from(state.cache.values()).filter(e => Date.now() < e.expiresAt).length;
  document.getElementById('stat-cached-entries').innerText = activeCount;
}
