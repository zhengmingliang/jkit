'use strict';
/*
 * QQ 邮箱 xmtls 续期加解密助手（jkit-storage 启动，JSON 行协议走 stdio）。
 * 用法：node qqmail-xmtls-helper.cjs <xmtls 目录（含 xmtls.js 与 xmtls.wasm）>
 * 请求：{"id":1,"op":"encrypt","key":"<pwd_key 或空>","data":"<明文 JSON>"} -> {"id":1,"ok":true,"h":7,"data":"<密文>"}
 *       {"id":2,"op":"decrypt","h":7,"data":"<密文>"} -> {"id":2,"ok":true,"data":"<明文>"}（同时释放会话）
 *       {"id":3,"op":"release","h":7} / {"id":4,"op":"ping"}
 * 安全：wasm 会把明文（含 pwd_key）打到 stdout，这里全部丢弃；协议行带前缀 "@@xmtls "，
 * 错误只回固定文案，绝不回显请求内容。
 * @author 郑明亮
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const PREFIX = '@@xmtls ';
const realWrite = process.stdout.write.bind(process.stdout);
function send(o) { realWrite(PREFIX + JSON.stringify(o) + '\n'); }
// 屏蔽一切其它输出（含 wasm 的 fd_write / console）
process.stdout.write = function () { return true; };
process.stderr.write = function () { return true; };
console.log = console.info = console.warn = console.error = console.debug = function () {};
process.on('uncaughtException', function () { send({ fatal: 'uncaught' }); process.exit(70); });
process.on('unhandledRejection', function () { send({ fatal: 'rejection' }); process.exit(70); });

const dir = process.argv[2];
let wasm;
try { wasm = fs.readFileSync(path.join(dir, 'xmtls.wasm')); } catch (e) { send({ fatal: 'wasm_missing' }); process.exit(66); }
let src;
try { src = fs.readFileSync(path.join(dir, 'xmtls.js'), 'utf8'); } catch (e) { send({ fatal: 'js_missing' }); process.exit(66); }

delete globalThis.fetch; // 让 glue 走 wasmBinary 而不是 fetch(URL)
const noop = function () {};
globalThis.Module = { wasmBinary: wasm, print: noop, printErr: noop, locateFile: function (p) { return path.join(dir, p); } };
globalThis.require = require;
globalThis.__dirname = dir;

const clients = new Map();
let nextH = 1;
let api = null;

globalThis.Module.onRuntimeInitialized = function () {
  const M = globalThis.Module;
  api = {
    free: M.cwrap('free', 'void', ['number']),
    newClient: M.cwrap('NewClient', 'number'),
    newWithSecret: M.cwrap('NewClientWithSecret', 'number', ['number']),
    enc: M.cwrap('ClientEncrypt', 'number', ['number', 'number']),
    dec: M.cwrap('ClientDecrypt', 'number', ['number', 'number'])
  };
  send({ ready: true });
};

function call(ptr, data, fn) {
  const p = allocateUTF8(data);
  const r = fn(ptr, p);
  const out = r ? UTF8ToString(r) : null;
  if (r) api.free(r);
  api.free(p);
  return out;
}

function release(h) {
  const ptr = clients.get(h);
  if (ptr) { clients.delete(h); api.free(ptr); }
}

function handle(req) {
  if (!api) return { ok: false, err: 'not_ready' };
  switch (req.op) {
    case 'ping': return { ok: true };
    case 'encrypt': {
      let ptr;
      if (req.key) { const k = allocateUTF8(String(req.key)); ptr = api.newWithSecret(k); api.free(k); }
      else { ptr = api.newClient(); }
      if (!ptr) return { ok: false, err: 'new_client_failed' };
      const out = call(ptr, String(req.data || ''), api.enc);
      if (!out) { api.free(ptr); return { ok: false, err: 'encrypt_failed' }; }
      const h = nextH++;
      clients.set(h, ptr);
      return { ok: true, h: h, data: out };
    }
    case 'decrypt': {
      const ptr = clients.get(req.h);
      if (!ptr) return { ok: false, err: 'no_session' };
      let out = null;
      try { if (req.data) out = call(ptr, String(req.data), api.dec); } finally { release(req.h); }
      return out == null ? { ok: false, err: 'decrypt_failed' } : { ok: true, data: out };
    }
    case 'release': release(req.h); return { ok: true };
    default: return { ok: false, err: 'bad_op' };
  }
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', function (chunk) {
  buf += chunk;
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i); buf = buf.slice(i + 1);
    if (!line.trim()) continue;
    let req;
    try { req = JSON.parse(line); } catch (e) { send({ ok: false, err: 'bad_json' }); continue; }
    let res;
    try { res = handle(req); } catch (e) { res = { ok: false, err: 'exception' }; }
    res.id = req.id;
    send(res);
  }
});
process.stdin.on('end', function () { process.exit(0); });

try { vm.runInThisContext(src, { filename: 'xmtls.js' }); } catch (e) { send({ fatal: 'load_failed' }); process.exit(70); }
