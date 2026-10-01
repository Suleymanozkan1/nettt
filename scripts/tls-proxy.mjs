// CI only: HTTPS front for the local API so the emulator can exercise certificate pinning.
// Usage: node scripts/tls-proxy.mjs <key.pem> <cert.pem> [port=8443] [target=http://127.0.0.1:3000]
import { readFileSync } from 'node:fs';
import { createServer } from 'node:https';
import { request } from 'node:http';

const [key, cert, port = '8443', target = 'http://127.0.0.1:3000'] = process.argv.slice(2);
const up = new URL(target);
createServer({ key: readFileSync(key), cert: readFileSync(cert) }, (req, res) => {
  const fwd = request({ host: up.hostname, port: up.port, path: req.url, method: req.method, headers: { ...req.headers, host: up.host } }, (r) => {
    res.writeHead(r.statusCode ?? 502, r.headers);
    r.pipe(res);
  });
  fwd.on('error', () => { res.writeHead(502).end(); });
  req.pipe(fwd);
}).listen(Number(port), '0.0.0.0', () => console.log(`tls-proxy :${port} → ${target}`));
