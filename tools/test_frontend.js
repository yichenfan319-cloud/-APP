/*
 * 前端取课逻辑的离线回归。
 *
 * 做法：把 index.html 里真正的 <script> 取出来，在最小的 DOM 垫片上跑，
 * 然后按真实载荷调用 showToday / showTomorrow / showWeek，检查渲染结果。
 *
 * 为什么要这样测：这几段逻辑只在"特定日期 + 特定周次"下才露馅
 * （比如周日点明天、双周课）。等真机上撞到再修，代价是整个回归周期。
 * 用假日期跑，几毫秒就能覆盖一整个学期。
 */
const fs = require('fs');
const path = require('path');

const HTML = process.argv[2];
const html = fs.readFileSync(HTML, 'utf8');

// ---- 取出 <script> 内容 ----
const m = html.match(/<script>([\s\S]*)<\/script>/);
if (!m) { console.error('没找到 <script> 块'); process.exit(1); }
const code = m[1];

// ---- 最小 DOM 垫片 ----
function makeEl(id) {
  return {
    id,
    innerHTML: '',
    textContent: '',
    value: '',
    disabled: false,
    checked: false,
    style: {},
    classList: {
      _s: new Set(),
      add(c) { this._s.add(c); },
      remove(c) { this._s.delete(c); },
      toggle(c, on) { on ? this._s.add(c) : this._s.delete(c); },
      contains(c) { return this._s.has(c); }
    },
    querySelector() { return makeEl('child'); },
    addEventListener() {},
    appendChild() {},
    insertBefore() {},
    removeChild() {},
    firstChild: null,
    scrollTop: 0,
    scrollHeight: 0
  };
}

const els = {};
['board','clearCredentialsBtn','loginBtn','loginBusy','loginBusyText','loginForm',
 'loginLog','main-page','logoutBtn','password','refreshBtn','rememberCheckbox',
 'runLog','settingsBtn','todayBtn','tomorrowBtn','username','weekLabel','weekPicker','weekBtn']
  .forEach(id => { els[id] = makeEl(id); });
els['login-page'] = makeEl('login-page');

const document = {
  getElementById(id) { return els[id] || null; },
  addEventListener(ev, fn) { if (ev === 'DOMContentLoaded') global.__domReady = fn; },
  querySelector() { return makeEl('q'); },
  createElement() { return makeEl('new'); },
  createTextNode(t) { return { nodeValue: t }; }
};

// 前端用到的 Android 桥，测试里全部空实现
const Android = new Proxy({}, { get: () => () => {} });

const sandbox = {
  document, Android, console,
  setTimeout: (fn) => { try { fn(); } catch (e) {} return 0; },
  clearTimeout: () => {},
  Date, Math, JSON, parseInt, parseFloat, isNaN, String, Number, Object, Array, RegExp, Error
};
sandbox.window = sandbox;
sandbox.global = sandbox;
sandbox.addEventListener = function (ev, fn) { if (ev === 'DOMContentLoaded') sandbox.__domReady = fn; };
sandbox.removeEventListener = function () {};
sandbox.requestSavedCredentials = function () {};
sandbox.requestAnimationFrame = function (fn) { try { fn(); } catch (e) {} return 0; };

// 把"今天"钉死在 2026-11-20（周五，第 12 周）。
// 必须在执行脚本之前装好：脚本里 showToday 之类的函数会自己 new Date()。
const FIXED = new Date(2026, 10, 20, 9, 0, 0);
const RealDate = Date;
class FakeDate extends RealDate {
  constructor(...args) {
    if (args.length === 0) super(FIXED.getTime());
    else super(...args);
  }
  static now() { return FIXED.getTime(); }
}
sandbox.Date = FakeDate;

const vm = require('vm');
vm.createContext(sandbox);
try {
  vm.runInContext(code, sandbox, { filename: 'index.html:script' });
} catch (e) {
  console.error('脚本执行失败:', e.message);
  process.exit(1);
}

// ---- 构造载荷 ----
// 数据必须照抄真机实测的 15 行（2026-11-20 12:44 从设备上 coursesData 导出）。
// 我最初凭印象手写，凭空给"数据结构实验"多造了一条周日 1-16 周的记录，
// 结果离线测试绿、真机却是 12 门 —— 手造数据把 bug 藏起来了。
function c(name, dayIndex, kind, from, to, list, extra) {
  return Object.assign({
    name, teacher: 'T', location: 'L', sections: '1-2', sectionFrom: 1, sectionTo: 2,
    weekRange: '', dayIndex, dayOfWeek: ['','周一','周二','周三','周四','周五','周六','周日'][dayIndex],
    date: '', weekKind: kind, weekFrom: from, weekTo: to, weekList: list || []
  }, extra || {});
}

const courses = [
  c('概率论与数理统计', 2, 'RANGE', 1, 16),
  c('概率论与数理统计', 3, 'RANGE', 1, 16),
  c('马克思主义基本原理', 4, 'RANGE', 1, 16),
  c('大学英语3', 5, 'LIST', 1, 15, [1,3,5,7,9,11,13,15]),   // 单周
  c('大学英语3', 5, 'LIST', 2, 16, [2,4,6,8,10,12,14,16]),  // 双周
  c('线性代数', 1, 'RANGE', 2, 17, null, { sections: '3-4', sectionFrom: 3, sectionTo: 4 }),
  c('马克思主义基本原理', 3, 'EVEN', 1, 16, null, { sections: '3-4', sectionFrom: 3, sectionTo: 4 }),
  c('体育与健康3', 4, 'RANGE', 1, 16, null, { sections: '3-4', sectionFrom: 3, sectionTo: 4 }),
  c('数字逻辑', 1, 'RANGE', 2, 17, null, { sections: '5-6', sectionFrom: 5, sectionTo: 6 }),
  c('线性代数', 2, 'RANGE', 2, 9, null, { sections: '5-6', sectionFrom: 5, sectionTo: 6 }),
  c('数据结构', 3, 'RANGE', 1, 16, null, { sections: '5-6', sectionFrom: 5, sectionTo: 6 }),
  c('数字逻辑', 5, 'RANGE', 11, 14, null, { sections: '5-7', sectionFrom: 5, sectionTo: 7 }),
  c('毛泽东思想和中国特色社会主义理论体系概论', 1, 'RANGE', 2, 17, null, { sections: '7-8', sectionFrom: 7, sectionTo: 8 }),
  c('数据结构实验', 3, 'RANGE', 5, 16, null, { sections: '7-8', sectionFrom: 7, sectionTo: 8 }),
  c('体育与健康3', 7, 'RANGE', 15, 16, null, { sections: '9-10', sectionFrom: 9, sectionTo: 10 })
];

const payload = {
  mode: 'offline',
  currentWeek: 12,
  currentWeekLabel: '第 12 周',
  firstMonday: '2026-08-31',
  weeks: Array.from({ length: 20 }, (_, i) => ({ value: String(i + 1), label: '第 ' + (i + 1) + ' 周' })),
  days: ['11-16','11-17','11-18','11-19','11-20','11-21','11-22'].map((d, i) => ({
    name: ['周一','周二','周三','周四','周五','周六','周日'][i], date: d
  })),
  courses
};

function namesOf(innerHtml) {
  const out = [];
  const re = /<div class="name">([^<]*)<\/div>/g;
  let g;
  while ((g = re.exec(innerHtml)) !== null) out.push(g[1]);
  return out;
}

let pass = 0, fail = 0;
function check(label, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) { pass++; console.log('  ✓ ' + label); }
  else { fail++; console.log('  ✗ ' + label + '\n      期望 ' + e + '\n      实际 ' + a); }
}

console.log('== 载荷注入 ==');
sandbox.window.receiveFromAndroid('courses', JSON.stringify(payload));
check('收到 15 门课', sandbox.window.coursesData.length, 15);
check('当前周标签', els.weekLabel.textContent, '第 12 周');

console.log('== weekOfDate 日期→周次 ==');
check('2026-08-31 第 1 周', sandbox.weekOfDate(new FakeDate(2026, 7, 31)), 1);
check('2026-11-20 第 12 周', sandbox.weekOfDate(new FakeDate(2026, 10, 20)), 12);
check('2026-11-23 第 13 周', sandbox.weekOfDate(new FakeDate(2026, 10, 23)), 13);

console.log('== 今天（周五 第12周）==');
sandbox.showToday();
check('命中 大学英语3 + 数字逻辑', namesOf(els.board.innerHTML), ['大学英语3', '数字逻辑']);

console.log('== 明天（周六）==');
sandbox.showTomorrow();
check('周六没课', namesOf(els.board.innerHTML), []);

console.log('== 周视图 第12周 ==');
sandbox.showWeek();
check('第12周共 12 门', namesOf(els.board.innerHTML).length, 12);
// 逐日核对：按名字统计会把"同一门课不同天"算重，必须按日期取。
const d = (mm, dd) => new FakeDate(2026, mm - 1, dd);
check('周一 11-16', sandbox.coursesOnDate(d(11,16)).map(c => c.name).sort(),
  ['线性代数', '数字逻辑', '毛泽东思想和中国特色社会主义理论体系概论'].sort());
check('周二 11-17 线性代数(2-9周)已结课', sandbox.coursesOnDate(d(11,17)).map(c => c.name),
  ['概率论与数理统计']);
check('周三 11-18 含双周马原', sandbox.coursesOnDate(d(11,18)).map(c => c.name).sort(),
  ['概率论与数理统计', '马克思主义基本原理', '数据结构', '数据结构实验'].sort());
check('周四 11-19', sandbox.coursesOnDate(d(11,19)).map(c => c.name).sort(),
  ['马克思主义基本原理', '体育与健康3'].sort());
check('周五 11-20 单周英语被排除', sandbox.coursesOnDate(d(11,20)).map(c => c.name).sort(),
  ['大学英语3', '数字逻辑'].sort());
check('周日 11-22 体育(15-16周)还没开', sandbox.coursesOnDate(d(11,22)).map(c => c.name), []);

console.log('== 切到第 11 周（双周课应消失）==');
sandbox.onWeekChosen('11', '第 11 周');
// 马原两份：周三双周、周四每周 → 第 11 周周三应没有马原，周四还有。
check('第11周周三没有马原', sandbox.coursesOnDate(d(11,11)).map(c => c.name).sort(),
  ['概率论与数理统计', '数据结构', '数据结构实验'].sort());
check('第11周周四仍有马原', sandbox.coursesOnDate(d(11,12)).map(c => c.name).sort(),
  ['马克思主义基本原理', '体育与健康3'].sort());
check('第11周周五 2 门（数字逻辑 11-14 已开）', sandbox.coursesOnDate(d(11,13)).map(c => c.name).sort(),
  ['大学英语3', '数字逻辑'].sort());

console.log('== 切到第 16 周（周日体育课出现、数字逻辑已结课）==');
// coursesOnDate 是按"传入日期"算周次的，所以必须给第 16 周的真实日期：
// 第 1 周周一 = 2026-08-31，第 16 周周一 = 12-14，周日 = 12-20。
sandbox.onWeekChosen('16', '第 16 周');
check('第16周周日 12-20 有体育', sandbox.coursesOnDate(d(12,20)).map(c => c.name), ['体育与健康3']);
check('第16周周五 12-18 只剩双周英语', sandbox.coursesOnDate(d(12,18)).map(c => c.name), ['大学英语3']);

console.log('== 第 17 周：只剩 2-17 周的三门 ==');
sandbox.onWeekChosen('17', '第 17 周');
check('第17周周一 12-21 共 3 门', sandbox.coursesOnDate(d(12,21)).map(c => c.name).sort(),
  ['线性代数', '数字逻辑', '毛泽东思想和中国特色社会主义理论体系概论'].sort());
check('第17周周三 12-23 已无课', sandbox.coursesOnDate(d(12,23)).map(c => c.name), []);

console.log('== 周日跨周：周的「明天」是下周一 ==');
// 2026-11-22 周日属于第 12 周；它的明天 11-23 属于第 13 周。
const sunday = new FakeDate(2026, 10, 22);
const mondayAfter = sandbox.addDays(sunday, 1);
check('周日属于第 12 周', sandbox.weekOfDate(sunday), 12);
check('次日属于第 13 周', sandbox.weekOfDate(mondayAfter), 13);
check('周日当天没课（体育要 15 周才开）', sandbox.coursesOnDate(sunday).map(c => c.name), []);
// 周一 3 门都在 2-17 周区间，所以第 13 周依旧要上 —— 这正是旧实现会错的地方
check('下一周的周一是 3 门而不是上周数据',
  sandbox.coursesOnDate(mondayAfter).map(c => c.name).sort(),
  ['数字逻辑', '毛泽东思想和中国特色社会主义理论体系概论', '线性代数'].sort());

console.log('\n结果: pass=' + pass + ' fail=' + fail);
process.exit(fail === 0 ? 0 : 1);
