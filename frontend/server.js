const http = require('http');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, 'public');
const port = Number(process.env.FRONTEND_PORT || 8090);
const gateway = new URL(process.env.GATEWAY_URL || 'http://127.0.0.1:8080');
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.ico': 'image/x-icon', '.svg': 'image/svg+xml' };

http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    return res.end('ok');
  }
  if (req.url.startsWith('/api/')) {
    const upstream = http.request({ hostname: gateway.hostname, port: gateway.port || 80, path: req.url.slice(4), method: req.method, headers: { ...req.headers, host: gateway.host } }, response => {
      res.writeHead(response.statusCode || 502, response.headers);
      response.pipe(res);
    });
    upstream.on('error', error => {
      res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ success: false, errorMsg: `Gateway unavailable: ${error.message}` }));
    });
    req.pipe(upstream);
    return;
  }
  let pathname;
  try { pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname); }
  catch { res.writeHead(400); return res.end('Bad request'); }
  if (pathname === '/') pathname = '/index.html';
  const file = path.resolve(root, `.${pathname}`);
  if (file !== root && !file.startsWith(root + path.sep)) { res.writeHead(403); return res.end('Forbidden'); }
  fs.stat(file, (error, stat) => {
    if (error || !stat.isFile()) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'content-type': mime[path.extname(file).toLowerCase()] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
}).listen(port, '127.0.0.1', () => console.log(`Frontend: http://127.0.0.1:${port}  Gateway: ${gateway.href}`));
