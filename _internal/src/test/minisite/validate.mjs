// Optional real-browser smoke test: Node.js 22+ and installed Google Chrome, no npm dependencies.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdtemp, readFile, readdir, rm, stat, writeFile, mkdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve, join, extname, sep} from 'node:path';
import {once} from 'node:events';

const root = resolve(process.argv[2] || '_internal/target/minisite');
const output = resolve(root, '../minisite-rendering');
const profile = await mkdtemp(join(tmpdir(), 'java-minisite-chrome-'));
const types = {'.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.svg': 'image/svg+xml', '.json': 'application/json', '.png': 'image/png'};
const missing = [];
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://localhost');
    const file = resolve(root, '.' + decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname));
    assert.ok(file.startsWith(root + sep));
    response.setHeader('Content-Type', types[extname(file)] || 'application/octet-stream');
    response.end(await readFile(file));
  } catch (error) {
    missing.push(request.url);
    response.writeHead(404);
    response.end('Not found');
  }
});
server.listen(0, '127.0.0.1');
await once(server, 'listening');
const base = `http://127.0.0.1:${server.address().port}`;
const chrome = spawn(process.env.CHROME_BIN || 'google-chrome', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=0', '--remote-allow-origins=http://localhost', `--user-data-dir=${profile}`, 'about:blank'
], {stdio: ['ignore', 'ignore', 'pipe']});
let socket;
let browserLog = '';
chrome.stderr.on('data', chunk => { browserLog += chunk; });

try {
  const endpoint = await new Promise((accept, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Chrome startup timed out: ${browserLog}`)), 20000);
    chrome.once('error', error => { clearTimeout(timeout); reject(error); });
    chrome.once('exit', code => { clearTimeout(timeout); reject(new Error(`Chrome exited ${code}: ${browserLog}`)); });
    chrome.stderr.on('data', () => {
      const match = browserLog.match(/DevTools listening on (ws:\/\/[^\s]+)/);
      if (match) { clearTimeout(timeout); accept(match[1]); }
    });
  });
  socket = new WebSocket(endpoint);
  await once(socket, 'open');
  let sequence = 0;
  const pending = new Map();
  const errors = [];
  socket.addEventListener('message', ({data}) => {
    const message = JSON.parse(data);
    if (message.id) {
      const request = pending.get(message.id);
      if (request) {
        pending.delete(message.id);
        clearTimeout(request.timeout);
        if (message.error) { request.reject(new Error(JSON.stringify(message.error))); }
        else { request.accept(message.result); }
      }
    } else if (message.method === 'Runtime.exceptionThrown') {
      errors.push(JSON.stringify(message.params.exceptionDetails));
    } else if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
      errors.push(JSON.stringify(message.params.args));
    }
  });
  const call = (method, params = {}, sessionId) => new Promise((accept, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 45000);
    pending.set(id, {accept, reject, timeout});
    socket.send(JSON.stringify({id, method, params, ...(sessionId ? {sessionId} : {})}));
  });
  const {targetId} = await call('Target.createTarget', {url: 'about:blank'});
  const {sessionId} = await call('Target.attachToTarget', {targetId, flatten: true});
  const page = (method, params) => call(method, params, sessionId);
  await page('Runtime.enable');
  await page('Page.enable');
  await mkdir(output, {recursive: true});
  const files = (await readdir(root)).filter(file => file.endsWith('.html') && file !== '404.html').sort();
  assert.deepEqual(files, ['flow.html', 'getting-started.html', 'index.html', 'permissions.html']);
  const results = [];
  for (const width of [1440, 390]) {
    await page('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: width < 600});
    for (const file of files) {
      const firstError = errors.length;
      await page('Page.navigate', {url: `${base}/${file}`});
      const rendered = await page('Runtime.evaluate', {
        expression: `new Promise((accept, reject) => {
          const timeout = setTimeout(() => reject(new Error('Diagrams or document did not render')), 30000);
          const check = () => {
            if (document.readyState === 'complete' && document.documentElement.dataset.diagrams) {
              clearTimeout(timeout); accept(document.documentElement.dataset.diagrams);
            } else { requestAnimationFrame(check); }
          }; check();
        })`, awaitPromise: true, returnByValue: true
      });
      assert.equal(rendered.exceptionDetails, undefined, `${file}: ${JSON.stringify(rendered)}`);
      assert.equal(rendered.result.value, 'ready', `${file}: diagram failure: ${errors.join('\n')}`);
      const {result} = await page('Runtime.evaluate', {returnByValue: true, expression: `(() => {
        const content = document.querySelector('.page-content');
        return {
          title: document.querySelector('h1')?.innerText,
          textLength: content?.innerText.length,
          breadcrumbs: [...document.querySelectorAll('nav[aria-label="breadcrumb"] li')].map(item => ({
            label: item.innerText.trim(), current: item.getAttribute('aria-current'),
            path: item.querySelector('a') ? new URL(item.querySelector('a').href).pathname : null
          })),
          menu: [...document.querySelectorAll('.page-navigation-left a[href]')].map(a => ({
            path: new URL(a.href).pathname, label: a.innerText.trim(), current: a.getAttribute('aria-current')
          })),
          diagrams: [...document.querySelectorAll('.mermaid')].map(element => ({
            svg: !!element.querySelector('svg'), processed: element.dataset.processed,
            width: element.querySelector('svg')?.getBoundingClientRect().width,
            height: element.querySelector('svg')?.getBoundingClientRect().height
          })),
          overflow: document.documentElement.scrollWidth > innerWidth + 1,
          links: [...document.querySelectorAll('a[href]')].map(a => a.href).filter(href => href.startsWith(location.origin)),
          code: [...document.querySelectorAll('pre code')].map(element => element.innerText)
        };
      })()`});
      const report = result.value;
      assert.ok(report.title && report.textLength > 1000, `${file}: missing rendered content`);
      const breadcrumbTitles = {'index.html': 'Overview', 'getting-started.html': 'Getting started',
        'flow.html': 'Release flow', 'permissions.html': 'Permissions and tokens'};
      assert.deepEqual(report.breadcrumbs, [
        ...(file === 'index.html' ? [] : [{label: 'Overview', current: null, path: '/index.html'}]),
        {label: breadcrumbTitles[file], current: 'page', path: null}
      ], `${file}: incorrect breadcrumb trail`);
      assert.deepEqual(report.menu.map(({path, label}) => ({path, label})), [
        {path: '/index.html', label: 'Overview'},
        {path: '/getting-started.html', label: 'Getting started'},
        {path: '/flow.html', label: 'Release flow'},
        {path: '/permissions.html', label: 'Permissions and tokens'}
      ], `${file}: missing or incorrect left menu`);
      assert.deepEqual(report.menu.filter(item => item.current === 'page').map(item => item.path),
        ['/' + file], `${file}: incorrect active menu entry`);
      assert.equal(report.overflow, false, `${file}: horizontal page overflow at ${width}px`);
      assert.equal(report.diagrams.length, file === 'flow.html' ? 2 : 0);
      for (const diagram of report.diagrams) {
        assert.ok(diagram.svg && diagram.processed === 'true' && diagram.width > 100 && diagram.height > 100, `${file}: missing visible SVG`);
      }
      if (file === 'getting-started.html') {
        assert.ok(report.code.some(code => code.includes('GIT_CONFIG_KEY_1') && code.includes('gh workflow run')));
      }
      for (const href of report.links) {
        const url = new URL(href);
        const linkedFile = resolve(root, '.' + (url.pathname === '/' ? '/index.html' : decodeURIComponent(url.pathname)));
        assert.ok(linkedFile.startsWith(root + sep));
        assert.ok((await stat(linkedFile)).isFile(), `Broken local link: ${href}`);
        if (url.hash) {
          const html = await readFile(linkedFile, 'utf8');
          assert.ok(html.includes(`id="${decodeURIComponent(url.hash.substring(1))}"`), `Broken anchor: ${href}`);
        }
      }
      assert.deepEqual(errors.slice(firstError), [], `${file}: browser errors`);
      const screenshot = await page('Page.captureScreenshot', {format: 'png', captureBeyondViewport: true});
      await writeFile(join(output, `${file.replace('.html', '')}-${width}.png`), Buffer.from(screenshot.data, 'base64'));
      results.push({page: file, width, title: report.title, diagrams: report.diagrams.length, links: report.links.length});
      console.log(`PASS ${file} ${width}px: ${report.diagrams.length} diagram(s), ${report.links.length} local link(s)`);
    }
  }
  assert.deepEqual(missing, [], 'Missing local assets');
  await writeFile(join(output, 'report.json'), JSON.stringify({browser: browserLog.match(/DevTools listening[^\n]*/)?.[0], results}, null, 2) + '\n');
  console.log(`Google Chrome headless rendering passed. Screenshots and report: ${output}`);
} finally {
  if (socket) { socket.close(); }
  chrome.kill('SIGTERM');
  if (chrome.exitCode === null) { await once(chrome, 'exit'); }
  server.closeAllConnections();
  await new Promise(accept => server.close(accept));
  await rm(profile, {recursive: true, force: true, maxRetries: 5});
}
