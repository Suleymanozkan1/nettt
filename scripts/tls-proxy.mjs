// CI only: HTTPS front for the local API so the emulator/simulator can exercise certificate pinning.
// Usage: node scripts/tls-proxy.mjs <key.pem> <cert.pem> [port=8443] [target=http://127.0.0.1:3000]
// Logs (stdout) are the test evidence:
//   "SNI <host>"               a client started a TLS handshake for <host> (it reached the server)
//   "<METHOD> <host> <path>"   an HTTP request arrived over a completed TLS connection
// A wrong-pin host therefore shows an SNI line but never a request line.
import { readFileSync } from 'node:fs';
import { createServer } from 'node:https';
import { request } from 'node:http';
import { createSecureContext } from 'node:tls';

const [key, cert, port = '8443', target = 'http://127.0.0.1:3000'] = process.argv.slice(2);
const up = new URL(target);
const context = createSecureContext({ key: readFileSync(key), cert: readFileSync(cert) });

// RFC 9110 §7.6.1: hop-by-hop fields (fixed ones and any named in Connection) are not forwarded.
const HOP = new Set(['connection', 'keep-alive', 'proxy-connection', 'te', 'trailer', 'transfer-encoding', 'upgrade', 'proxy-authenticate', 'proxy-authorization']);
function endToEnd(headers) {
  const named = new Set(String(headers.connection ?? '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean));
  return Object.fromEntries(Object.entries(headers).filter(([k]) => !HOP.has(k.toLowerCase()) && !named.has(k.toLowerCase())));
}
const safeDecode = (s) => { try { return decodeURIComponent(s.replace(/\+/g, ' ')); } catch { return s; } };

createServer({
  key: readFileSync(key),
  cert: readFileSync(cert),
  SNICallback: (servername, cb) => { console.log(`SNI ${servername}`); cb(null, context); },
}, (req, res) => {
  console.log(`${req.method} ${req.headers.host} ${safeDecode(req.url ?? '')}`);
  const fwd = request({ host: up.hostname, port: up.port, path: req.url, method: req.method, headers: { ...endToEnd(req.headers), host: up.host } }, (r) => {
    res.writeHead(r.statusCode ?? 502, endToEnd(r.headers));
    r.on('error', () => res.destroy()); // upstream died mid-body: headers are already sent, so just drop the connection
    r.pipe(res);
  });
  fwd.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
  req.pipe(fwd);
}).listen(Number(port), '0.0.0.0', () => console.log(`tls-proxy :${port} → ${target}`));
