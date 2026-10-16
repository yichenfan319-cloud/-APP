/*
 * 离线验证 index.html 的筛选/渲染逻辑。
 *
 * 做法：把 index.html 里真实的 script 抽出来，在最小 DOM 垫片上执行，
 * 再用真实课表数据调用 receiveFromAndroid('courses', json)，
 * 检查筛选与卡片渲染是否正确。完全绕开 WebView。
 *
 * 用法: node test_frontend_render.js [path/to/index.html]
 */
const fs = require('fs');
const path = require('path');

const HTML = process.argv[2]
    ? path.resolve(process.argv[2])
    : path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'www', 'index.html');

if (!fs.existsSync(HTML)) {
    console.error('找不到 index.html: ' + HTML);
    console.error('用法: node test_frontend_render.js [path/to/index.html]');
    process.exit(1);
}

const say = console.log.bind(console);
console.log = () => {};   // 静音页面内部的 console.log

// 与页面同源的星期中文名（getDay(): 0=周日）
const WEEKDAY_CN = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

// ---------- 最小 DOM 垫片 ----------
class El {
    constructor(id) {
        this.id = id || '';
        this.className_ = '';
        this.children = [];        // 元素子节点
        this.nodes = [];           // 全部子节点（含文本节点）
        this.style = {};
        this.value = '';
        this.checked = false;
        this.disabled = false;
        this.textContent = '';
        this._html = '';
        this.scrollTop = 0;
        this.scrollHeight = 0;
        this.firstElementChild = null;
    }
    get classList() {
        const self = this;
        return {
            add(c) { self.className_ = (self.className_ + ' ' + c).trim(); },
            remove(c) { self.className_ = self.className_.split(/\s+/).filter(x => x && x !== c).join(' '); },
            toggle(c, on) { if (on) this.add(c); else this.remove(c); },
            contains(c) { return self.className_.split(/\s+/).includes(c); }
        };
    }
    set className(v) { this.className_ = v; }
    get className() { return this.className_; }
    set innerHTML(v) { this._html = String(v); }
    get innerHTML() { return this._html; }
    appendChild(c) {
        this.nodes.push(c);
        if (c instanceof El) { this.children.push(c); this.firstElementChild = this.children[0]; }
        return c;
    }
    insertBefore(c, ref) {
        this.nodes.unshift(c);
        if (c instanceof El) {
            const i = this.children.indexOf(ref);
            if (i >= 0) this.children.splice(i, 0, c);
            else this.children.unshift(c);
            this.firstElementChild = this.children[0];
        }
        return c;
    }
    removeChild(c) {
        this.nodes = this.nodes.filter(x => x !== c);
        this.children = this.children.filter(x => x !== c);
        this.firstElementChild = this.children[0] || null;
        return c;
    }
    get childElementCount() { return this.children.length; }
    text() {   // 把整行的文本拼出来
        return this.nodes.map(n => (n instanceof El ? n.text() : (n && n.textContent) || '')).join('');
    }
    querySelector(sel) {
        if (sel === '.txt') return this._txt || (this._txt = new El('txt'));
        if (sel === '.loading') return this._spin || (this._spin = new El('loading'));
        return new El(sel);
    }
    addEventListener() {}
    set onclick(f) { this._onclick = f; }
    get onclick() { return this._onclick; }
}

class TextNode {
    constructor(t) { this.textContent = String(t); }
}

const registry = {};
function reg(id) { return registry[id] || (registry[id] = new El(id)); }
[
    'login-page', 'main-page', 'loginLog', 'runLog', 'loginBtn', 'username', 'password',
    'rememberCheckbox', 'clearCredentialsBtn', 'todayBtn', 'tomorrowBtn', 'weekBtn',
    'refreshBtn', 'logoutBtn', 'board', 'weekPicker', 'weekLabel', 'settingsBtn',
    // 登录页两种形态
    'loginForm', 'loginBusy', 'loginBusyText'
].forEach(reg);

global.document = {
    getElementById: (id) => reg(id),
    createElement: () => new El(),
    createDocumentFragment: () => new El('frag'),
    createTextNode: (t) => new TextNode(t)
};
global.requestAnimationFrame = (fn) => { fn(); return 1; };
global.addEventListener = () => {};
// 浏览器里 window 就是全局对象；在 Node 里显式对齐，
// 否则页面脚本第一句 window.xxx 就会 ReferenceError。
globalThis.window = globalThis;
global.Android = {
    login() {}, refresh() {}, relogin() {}, getSavedCredentials() {},
    clearSavedCredentials() {}, openSettings() {}, openWeekView() {},
    // 周次相关（新增的桥接口，垫片需同步，否则 onWeekChosen 会抛错）
    selectWeek() {}, requestWeekPicker() {}
};

// 关键：用 vm 在真正的全局上下文里执行页面脚本，
// 这样 window 就是同一个 globalThis，和浏览器语义一致。
const vm = require('vm');
const html = fs.readFileSync(HTML, 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
if (scripts.length === 0) { say('没找到 script 块'); process.exit(1); }

const code = scripts.join('\n').replace(/\bconst /g, 'var ');
vm.runInThisContext(code, { filename: 'index.html-inline.js' });

say('=== 环境自检 ===');
say('  typeof window             : ' + typeof window);
say('  window === globalThis     : ' + (window === globalThis));
say('  typeof receiveFromAndroid : ' + typeof receiveFromAndroid);

// ---------- 真实数据（自模拟器 logcat 抓取） ----------
const COURSES = [
 {name:"概率论与数理统计",teacher:"方政蕊",location:"瑞樟4瑞樟4-101",weekRange:"",dayOfWeek:"周二",dayDate:"10-13",timeSlot:"第一二节",sections:"第一二节"},
 {name:"概率论与数理统计",teacher:"方政蕊",location:"瑞樟4瑞樟4-101",weekRange:"",dayOfWeek:"周三",dayDate:"10-14",timeSlot:"第一二节",sections:"第一二节"},
 {name:"马克思主义基本原理",teacher:"龚敏芳",location:"瑞樟11瑞樟11-301[报告型智慧教室]",weekRange:"",dayOfWeek:"周四",dayDate:"10-15",timeSlot:"第一二节",sections:"第一二节"},
 {name:"大学英语3",teacher:"林莉",location:"瑞樟6瑞樟6-509[计算机基础实验室14]",weekRange:"",dayOfWeek:"周五",dayDate:"10-16",timeSlot:"第一二节",sections:"第一二节"},
 {name:"线性代数",teacher:"叶丽霞",location:"瑞樟10瑞樟10-106",weekRange:"",dayOfWeek:"周一",dayDate:"10-12",timeSlot:"第三四节",sections:"第三四节"},
 {name:"体育与健康3",teacher:"黄伟雄",location:"体育馆体育馆[篮球场]",weekRange:"",dayOfWeek:"周四",dayDate:"10-15",timeSlot:"第三四节",sections:"第三四节"},
 {name:"数字逻辑",teacher:"孙晓倩,张明文,章安良",location:"瑞樟4瑞樟4-101",weekRange:"",dayOfWeek:"周一",dayDate:"10-12",timeSlot:"第五六节",sections:"第五六节"},
 {name:"线性代数",teacher:"叶丽霞",location:"瑞樟10瑞樟10-308",weekRange:"",dayOfWeek:"周二",dayDate:"10-13",timeSlot:"第五六节",sections:"第五六节"},
 {name:"数据结构",teacher:"吴清寿",location:"瑞樟11瑞樟11-104[基础型智慧教室]",weekRange:"",dayOfWeek:"周三",dayDate:"10-14",timeSlot:"第五六节",sections:"第五六节"},
 {name:"毛泽东思想和中国特色社会主义理论体系概论",teacher:"张燕斌",location:"瑞樟11瑞樟11-208[报告型智慧教室]",weekRange:"",dayOfWeek:"周一",dayDate:"10-12",timeSlot:"第七八节",sections:"第七八节"},
 {name:"数据结构实验",teacher:"吴清寿",location:"屏山7屏山7-403[智慧教育技术实验室]",weekRange:"",dayOfWeek:"周三",dayDate:"10-14",timeSlot:"第七八节",sections:"第七八节"}
];

const jsonStr = JSON.stringify({
    courses: COURSES,
    currentWeek: '第七周',
    weeks: [
        { value: '6', label: '第六周' },
        { value: '7', label: '第七周' },
        { value: '8', label: '第八周' }
    ]
});
say('');
say('=== 输入 ===');
say('  课程总数        : ' + COURSES.length);
say('  JSON 字符数     : ' + jsonStr.length);

// 直接先验证 JSON 本身没问题
try {
    const back = JSON.parse(jsonStr);
    say('  JSON.parse 自检 : 成功，' + back.courses.length + ' 条课程，'
        + back.weeks.length + ' 个周次选项，当前 ' + back.currentWeek);
} catch (e) {
    say('  JSON.parse 自检 : 失败 -> ' + e.message);
}

say('');
say('=== 调用 receiveFromAndroid("courses", json) ===');
receiveFromAndroid('courses', jsonStr);

say('  globalThis.coursesData 长度 : ' + ((globalThis.coursesData || []).length));
say('  board 内容前 120 字符        : ' + reg('board').innerHTML.slice(0, 120));

say('');
say('=== 日志区输出 ===');
reg('runLog').children.forEach(function (c, i) {
    say('  [' + i + '] ' + c.text());
});

say('');
say('=== 逐个星期筛选 ===');
const WEEKDAYS = ['周日','周一','周二','周三','周四','周五','周六'];
for (let d = 0; d < 7; d++) {
    const day = WEEKDAYS[d];
    const n = (globalThis.coursesData || []).filter(c => c.dayOfWeek === day).length;
    say('  ' + day + ': ' + n + ' 门');
}

say('');
say('=== 周视图：每天是否都有大标题 ===');
try {
    window.requestWeek();
    const htmlW = reg('board').innerHTML;
    const headings = htmlW.match(/class="day-heading">([^<]+)</g) || [];
    say('  日标题数量: ' + headings.length);
    headings.forEach(function (h) {
        say('    ' + h.replace(/class="day-heading">/, '').replace(/<$/, ''));
    });
    say('  课程卡片数量: ' + (htmlW.match(/class="course"/g) || []).length);
} catch (e) {
    say('  周视图异常: ' + e.message);
}

/*
 * 彩蛋：多行 ASCII 画必须在日志里按原样换行显示。
 * 背景：appendLog 原先把 \n 替换成空格，会把猫猫头压成一行。
 */
say('');
say('=== 彩蛋：是否真的落在日志区的首行 ===');
try {
    // 重新走一遍首次加载流程（会 clearLogs 然后插入彩蛋）
    window.receiveFromAndroid('courses', JSON.stringify({
        courses: COURSES, currentWeek: '第七周',
        weeks: [{ value: '7', label: '第七周' }]
    }));
    const box = reg('runLog');
    const first = box.children[0];
    if (!first) {
        say('  !! 日志区为空');
    } else {
        const isEgg = String(first.className).indexOf('egg') >= 0;
        const txt = first.text();
        say('  首行 class      : ' + first.className);
        say('  首行含猫        : ' + (txt.indexOf('o.o') >= 0 ? '是' : '否'));
        say('  首行是彩蛋      : ' + (isEgg ? '是  OK' : '否  !! 期望 class 含 egg'));
        say('  日志总行数      : ' + box.children.length);
        say('  首行内容:');
        txt.split('\n').forEach(function (l) { say('    |' + l); });
        // 彩蛋后面应紧跟真实的进度日志，确认它没把别的行挤掉
        const second = box.children[1];
        say('  第二行          : ' + (second ? second.text().slice(0, 40) : '(无)'));
    }
} catch (e) {
    say('  彩蛋首行测试异常: ' + e.message);
}

/*
 * 回归测试：切换周次后，界面标签不能被服务端的陈旧值覆盖。
 *
 * 真实缺陷：payload.currentWeek 来自教务系统 <select> 的 selected 属性，
 * 而该属性是服务器渲染时写死的 —— se() 异步加载新表格时不会更新它。
 * 所以用户切到「第一周」后，若用 payload.currentWeek 覆盖，标签会弹回「第七周」。
 */
/*
 * 回归测试：周日点「明天课表」必须跨到下一周。
 *
 * 真实缺陷：原先「明天」只是把星期几往后推一天
 * （WEEKDAYS[getDay()+1]），没有跨周概念。周日那天会把本周周一的课
 * 显示成"明天"，也就是一周前的课表，而且界面上完全看不出来。
 *
 * 这个 bug 只在周日出现，靠人工等一周才发现太慢，
 * 所以这里用假日期直接测判据。
 */
say('');
say('=== 回归：查询进行中应收起登录表单 ===');
try {
    // 初始应是表单形态
    window.receiveFromAndroid('loginState', 'form');
    say('  form  -> loginForm 隐藏? ' + reg('loginForm').classList.contains('hidden')
        + '   期望 false');
    say('           loginBusy 隐藏? ' + reg('loginBusy').classList.contains('hidden')
        + '   期望 true');

    // 查询进行中：收起表单，只留状态行
    window.receiveFromAndroid('loginState', 'busy');
    const formHidden = reg('loginForm').classList.contains('hidden');
    const busyShown = !reg('loginBusy').classList.contains('hidden');
    say('  busy  -> loginForm 隐藏? ' + formHidden + '   期望 true');
    say('           loginBusy 显示? ' + busyShown + '   期望 true');
    say(formHidden && busyShown
        ? '  ✅ 通过：查询期间不再显示那个禁用的登录按钮'
        : '  ❌ 失败');

    // 终态失败：表单要回来，否则用户无法重填
    window.receiveFromAndroid('loginState', 'form');
    say('  失败后 -> loginForm 隐藏? ' + reg('loginForm').classList.contains('hidden')
        + '   期望 false（必须让用户能重填）');
} catch (e) {
    say('  登录形态测试异常: ' + e.message);
}

say('');
say('=== 回归：周日点「明天」必须跨到下一周 ===');
try {
    let ok = 0, bad = 0;
    function chk(desc, cond) {
        if (cond) { ok++; } else { bad++; say('    ❌ ' + desc); }
    }

    // JS getDay(): 0=周日 1=周一 ... 6=周六
    chk('周日->周一 应跨周', crossesIntoNextWeek(0, 1) === true);
    for (let d = 1; d <= 6; d++) {
        const t = (d + 1) % 7;
        chk('周' + d + '->周' + t + ' 不应跨周', crossesIntoNextWeek(d, t) === false);
    }
    say('  跨周判据: ' + ok + ' 通过 / ' + bad + ' 失败');

    // 2026-10-18 是周日，2026-10-17 是周六
    const sunday = new Date(2026, 9, 18);
    const saturday = new Date(2026, 9, 17);
    say('  假日期: 2026-10-18 是' + WEEKDAY_CN[sunday.getDay()]
        + '，2026-10-17 是' + WEEKDAY_CN[saturday.getDay()]);

    // 用中文数字造周次列表 —— 真实教务系统页面就是「第七周」这种写法
    const CN_D = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九'];
    function cnWeek(n) {
        if (n < 10) return '第' + CN_D[n] + '周';
        if (n === 10) return '第十周';
        if (n < 20) return '第十' + CN_D[n - 10] + '周';
        if (n === 20) return '第二十周';
        return '第二十' + CN_D[n - 20] + '周';
    }
    const weeks20 = [];
    for (let i = 1; i <= 20; i++) weeks20.push({ value: String(i), label: cnWeek(i) });
    window.availableWeeks = weeks20;

    const cases2 = [
        ['第七周',  sunday,   1, '周日应前进一周'],
        ['第十九周', sunday,  1, '周日应前进一周'],
        ['第二十周', sunday,  0, '已是最后一周，不前进'],
        ['第七周',  saturday, 0, '周六不跨周']
    ];
    say('  --- dayOffsetWeeks（假日期）---');
    for (const [label, date, want, desc] of cases2) {
        const got = dayOffsetWeeks(label, date);
        const pass = got === want;
        say('    ' + (pass ? 'OK  ' : '!!  ') + label + ' @' + WEEKDAY_CN[date.getDay()]
            + ' -> ' + got + ' (期望 ' + want + ')  ' + desc);
        if (!pass) bad++;
    }

    // 周次解析：中文与阿拉伯数字都要认
    say('  --- 周次解析 ---');
    const numCases = [['第七周', 7], ['第7周', 7], ['第一周', 1], ['第十周', 10],
                      ['第十五周', 15], ['第二十周', 20], ['全部', NaN]];
    for (const [label, want] of numCases) {
        const got = weekNumberOf(label);
        const pass = (isNaN(want) && isNaN(got)) || got === want;
        if (!pass) bad++;
        say('    ' + (pass ? 'OK  ' : '!!  ') + label + ' -> ' + got + ' (期望 ' + want + ')');
    }

    // 下一周定位：正常前进 / 越界返回 null
    say('  --- 下一周定位 ---');
    const nxt = findWeekStep('第十九周', 1);
    const nxtPass = !!nxt && nxt.label === '第二十周';
    if (!nxtPass) bad++;
    say('    ' + (nxtPass ? 'OK  ' : '!!  ') + '第十九周 +1 -> '
        + (nxt ? nxt.label + ' (value=' + nxt.value + ')' : 'null'));

    const over = findWeekStep('第二十周', 1);
    if (over !== null) bad++;
    say('    ' + (over === null ? 'OK  ' : '!!  ') + '第二十周 +1 -> '
        + (over ? over.label : 'null') + '（期望越界为 null）');

    say('');
    say(bad === 0 ? '  ✅ 周日跨周逻辑全部通过' : '  ❌ 共 ' + bad + ' 项失败');
} catch (e) {
    say('  周日跨周测试异常: ' + e.message);
}

/*
 * 回归测试：刷新按钮应重新加载"当前所选周"，
 * 而不是让服务端把它拉回自己认为的当前周。
 */
say('');
say('=== 回归：刷新应加载"当前所选周" ===');
try {
    window.receiveFromAndroid('courses', JSON.stringify({
        courses: COURSES, currentWeek: '第七周',
        weeks: [{ value: '7', label: '第七周' }, { value: '9', label: '第九周' }]
    }));

    let calledWith = null;
    const origSelect = globalThis.Android.selectWeek;
    globalThis.Android.selectWeek = function (v) { calledWith = v; };

    // 用户切到第九周，然后清掉这次切换自身产生的调用记录
    window.onWeekChosen('9', '第九周');
    calledWith = null;

    reg('refreshBtn').onclick();
    say('  切到第九周后点刷新 -> selectWeek("' + calledWith + '")');
    say(calledWith === '9'
        ? '  ✅ 通过：刷新的是所选周次'
        : '  ❌ 失败：期望 "9"，实际 ' + calledWith);

    globalThis.Android.selectWeek = origSelect;
} catch (e) {
    say('  刷新测试异常: ' + e.message);
}

say('');
say('=== 回归：切周次后标签不应被陈旧值覆盖 ===');
try {
    // 重置上一批测试留下的状态（chosenWeekAt 会影响"是否陈旧"的判断），
    // 否则初始标签断言会读到污染后的值。
    chosenWeek = '';
    chosenWeekLabel = '';
    chosenWeekAt = 0;
    selectedWeekValue = '';

    const weeks = [
        { value: '1', label: '第一周' },
        { value: '7', label: '第七周' }
    ];

    // 初始加载：服务端说当前是第七周
    window.receiveFromAndroid('courses', JSON.stringify({
        courses: COURSES, currentWeek: '第七周', weeks: weeks
    }));
    const afterLoad = reg('weekLabel').textContent;
    say('  初始加载后标签      : ' + afterLoad
        + (afterLoad === '第七周' ? '  OK' : '  !! 期望第七周'));

    // 用户主动选择第一周
    window.onWeekChosen('1', '第一周');
    say('  用户选第一周后标签  : ' + reg('weekLabel').textContent);

    // 服务端返回新数据，但 currentWeek 仍是写死的「第七周」
    window.receiveFromAndroid('courses', JSON.stringify({
        courses: COURSES.filter(function (c) { return c.name === '线性代数'; }),
        currentWeek: '第七周',
        weeks: weeks
    }));
    const finalLabel = reg('weekLabel').textContent;
    say('  收到服务端数据后标签: ' + finalLabel);
    say(finalLabel === '第一周'
        ? '  ✅ 通过：以用户选择为准，未被陈旧值覆盖'
        : '  ❌ 失败：标签被覆盖成了 ' + finalLabel);

    if (globalThis.coursesData === undefined) {
        say('  (提示：coursesData 未定义)');
    }
} catch (e) {
    say('  回归测试异常: ' + e.message);
}
