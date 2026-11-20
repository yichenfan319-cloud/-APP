// 通过 Chrome DevTools Protocol 在 App 的 WebView 里执行 JS。
//
// 为什么需要它：adb shell input tap 打不进 WebView 里的 HTML 按钮
// （截图时间戳完全不变），而直接调前端函数既能精确触发、又能把
// 返回值带回来，比"点一下再看图"可靠得多。
//
// 用法：
//   node cdp.js list
//   node cdp.js eval "<js 表达式>" [urlKeyword]

const HOST = '127.0.0.1:9222';

async function listTargets() {
  const res = await fetch(`http://${HOST}/json/list`);
  return res.json();
}

async function evalIn(urlKeyword, expression) {
  const targets = (await listTargets()).filter(
    (t) => t.type === 'page' && (!urlKeyword || t.url.includes(urlKeyword))
  );
  if (!targets.length) {
    throw new Error('没有匹配的页面，可用目标：' + JSON.stringify((await listTargets()).map((t) => t.url)));
  }
  const target = targets[0];
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((ok, bad) => {
    ws.onopen = ok;
    ws.onerror = () => bad(new Error('WebSocket 连接失败'));
  });

  let id = 0;
  const pending = new Map();
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  };
  const send = (method, params) =>
    new Promise((ok) => {
      const myId = ++id;
      pending.set(myId, ok);
      ws.send(JSON.stringify({ id: myId, method, params }));
    });

  const out = await send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
    userGesture: true,
  });
  ws.close();
  if (out.result && out.result.exceptionDetails) {
    throw new Error('JS 异常: ' + JSON.stringify(out.result.exceptionDetails));
  }
  return out.result?.result?.value;
}

(async () => {
  const [cmd, arg, kw] = process.argv.slice(2);
  try {
    if (cmd === 'list') {
      const list = await listTargets();
      for (const t of list) {
        console.log(`${t.type}\t${t.url}`);
      }
    } else if (cmd === 'eval') {
      const v = await evalIn(kw, arg);
      console.log(typeof v === 'string' ? v : JSON.stringify(v));
    } else {
      console.log('用法: node cdp.js list | node cdp.js eval "<js>" [urlKeyword]');
      process.exit(2);
    }
  } catch (e) {
    console.error(String(e && e.message ? e.message : e));
    process.exit(1);
  }
})();
