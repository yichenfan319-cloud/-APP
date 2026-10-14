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
    'refreshBtn', 'logoutBtn', 'board', 'weekPicker', 'settingsBtn'
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
say('=== 周视图渲染检查 ===');
try {
    window.requestWeek();
    const cards = reg('board').innerHTML.match(/class="course"/g) || [];
    say('  周视图卡片数量: ' + cards.length);
} catch (e) {
    say('  周视图异常: ' + e.message);
}

/*
 * 回归测试：切换周次后，界面标签不能被服务端的陈旧值覆盖。
 *
 * 真实缺陷：payload.currentWeek 来自教务系统 <select> 的 selected 属性，
 * 而该属性是服务器渲染时写死的 —— se() 异步加载新表格时不会更新它。
 * 所以用户切到「第一周」后，若用 payload.currentWeek 覆盖，标签会弹回「第七周」。
 */
say('');
say('=== 回归：切周次后标签不应被陈旧值覆盖 ===');
try {
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
