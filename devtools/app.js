"use strict";

const state = {
  schema: [], tuning: {}, defaults: {}, devConfig: {},
  minecraft: {}, jdk: {}, build: {},
  langs: {}, keys: [],
  presets: {}, selectedPreset: null,
  previewTab: "custom", dirty: { tuning: false, lang: false, preset: false },
};

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function toast(msg, isError = false) {
  const el = $("toast");
  el.textContent = msg;
  el.className = "toast show" + (isError ? " err" : "");
  clearTimeout(el._t);
  el._t = setTimeout(() => el.className = "toast", 2600);
}

async function api(path, options = {}) {
  const res = await fetch(path, options);
  let data = null;
  try { data = await res.json(); } catch (_) { data = null; }
  if (!res.ok || (data && data.ok === false)) {
    throw new Error(data?.error || `HTTP ${res.status}`);
  }
  return data;
}

const tun = (key) => {
  if (Object.prototype.hasOwnProperty.call(state.tuning, key)) return state.tuning[key];
  if (Object.prototype.hasOwnProperty.call(state.defaults, key)) return state.defaults[key];
  return 0;
};

/* ---------- ARGB color helpers ---------- */
function argbToParts(v) {
  const u = Number(v) >>> 0;
  return { a: (u >>> 24) & 255, r: (u >>> 16) & 255, g: (u >>> 8) & 255, b: u & 255 };
}
function partsToArgb(hex, alpha) {
  let rgb = parseInt(String(hex).replace("#", ""), 16);
  if (Number.isNaN(rgb)) rgb = 0xFFFFFF;
  const argb = (((Number(alpha) & 255) << 24) | (rgb & 0xFFFFFF)) >>> 0;
  return argb > 0x7FFFFFFF ? argb - 0x100000000 : argb;
}
function cssColor(v) {
  const p = argbToParts(v);
  return `rgba(${p.r},${p.g},${p.b},${(p.a / 255).toFixed(2)})`;
}

/* ---------- navigation ---------- */
document.querySelectorAll(".nav-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".nav-btn").forEach((b) => b.classList.toggle("active", b === btn));
    document.querySelectorAll(".panel").forEach((p) => p.classList.remove("active"));
    $(btn.dataset.panel).classList.add("active");
  });
});

async function loadState() {
  const data = await api("/api/state");
  state.schema = data.schema.items || [];
  state.defaults = data.defaults || {};
  state.tuning = data.tuning || {};
  state.devConfig = data.dev_config || {};
  state.minecraft = data.minecraft || {};
  state.jdk = data.jdk || {};
  state.build = data.build || {};
  renderOverview(data);
  renderTunerGroups();
  if (window.renderPreviewAdvanced) window.renderPreviewAdvanced(); else renderPreview();
  await loadLang();
  await loadPresets();
  if (window.renderPreviewAdvanced) window.renderPreviewAdvanced();
  renderBuildTab();
  $("topStatus").textContent = "已连接 · " + data.time;
}

function renderOverview(data) {
  $("ovProject").textContent = data.project;
  $("ovTuningLayer").textContent = data.tuning_layer ? "✅ GuiTuning 调优层已接入（参数可由 JSON 覆盖）" : "❌ 未接入 GuiTuning";
  $("ovSchemaCount").textContent = `可调参数 ${state.schema.length} 项`;
  $("ovJdk").textContent = data.jdk.version || data.jdk.error || "未知";
  $("ovGameDir").textContent = data.minecraft.root || "-";
  $("ovGameMode").textContent = data.minecraft.mode || "";
  const arts = data.build.all || [];
  $("ovArtifacts").innerHTML = arts.length ? arts.map((a) => {
    const d = new Date(a.mtime * 1000).toLocaleString();
    return `<div class="artifact"><b>${esc(a.name)}</b><br><span class="muted">${esc(a.path)}<br>${(a.size / 1024).toFixed(1)} KB · ${d}</span></div>`;
  }).join("") : "尚无构建产物";
}

/* ---------- tuning controls ---------- */
function renderTunerGroups() {
  const filter = ($("tunerSearch").value || "").toLowerCase();
  const groups = new Map();
  for (const item of state.schema) {
    if (filter && !(item.key + " " + item.label).toLowerCase().includes(filter)) continue;
    if (!groups.has(item.group)) groups.set(item.group, []);
    groups.get(item.group).push(item);
  }
  const root = $("tunerGroups");
  root.innerHTML = "";
  for (const [group, items] of groups) {
    const wrap = document.createElement("div");
    wrap.className = "tuning-group";
    wrap.innerHTML = `<h4>${esc(group)}</h4>`;
    for (const item of items) {
      const row = document.createElement("div");
      row.className = "tuning-row";
      if (item.type === "color") row.classList.add("color-row");
      row.dataset.key = item.key;
      const value = tun(item.key);
      let control = "";
      if (item.type === "color") {
        const p = argbToParts(value);
        control = `<div class="color-controls">
          <input type="color" data-role="color" value="#${p.r.toString(16).padStart(2,"0")}${p.g.toString(16).padStart(2,"0")}${p.b.toString(16).padStart(2,"0")}">
          <input type="number" data-role="alpha" min="0" max="255" step="1" value="${p.a}" title="Alpha">
        </div>`;
      } else {
        control = `<input type="number" data-role="num" value="${value}" min="${item.min ?? 0}" max="${item.max ?? 100000}" step="${item.step ?? 1}">`;
      }
      row.innerHTML = `<div class="label">${esc(item.label)}<span class="key">${esc(item.key)}</span></div>${control}`;
      wrap.appendChild(row);
    }
    root.appendChild(wrap);
  }
  root.querySelectorAll("input[data-role]").forEach((input) => {
    input.addEventListener("input", () => {
      const row = input.closest(".tuning-row");
      const key = row.dataset.key;
      const item = state.schema.find((x) => x.key === key);
      if (!item) return;
      if (item.type === "color") {
        const colorInput = row.querySelector('[data-role="color"]');
        const alphaInput = row.querySelector('[data-role="alpha"]');
        state.tuning[key] = partsToArgb(colorInput.value, alphaInput.value);
      } else {
        state.tuning[key] = item.type === "float" ? parseFloat(input.value) : parseInt(input.value, 10);
      }
      state.dirty.tuning = true;
      renderPreview();
    });
  });
}

$("tunerSearch").addEventListener("input", renderTunerGroups);
$("previewTab").addEventListener("change", (e) => { state.previewTab = e.target.value; const top = window.PV && PV.stack[PV.stack.length - 1]; if (top && top.type === "main") top.tab = e.target.value; renderPreview(); });
$("previewW").addEventListener("input", renderPreview);
$("previewH").addEventListener("input", renderPreview);

$("btnSaveTuning").addEventListener("click", async () => {
  try {
    await api("/api/tuning", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ values: state.tuning }) });
    state.dirty.tuning = false;
    toast("调优参数已保存到项目 devtools/gui-tuning.json");
  } catch (e) { toast(e.message, true); }
});
$("btnResetTuning").addEventListener("click", async () => {
  if (!confirm("恢复全部 GUI 参数为源码默认值？")) return;
  try {
    const data = await api("/api/tuning/reset", { method: "POST" });
    state.tuning = data.tuning || {};
    state.dirty.tuning = false;
    renderTunerGroups(); renderPreview();
    toast("已恢复默认值");
  } catch (e) { toast(e.message, true); }
});
$("btnDeployTuning").addEventListener("click", async () => {
  try {
    await api("/api/tuning", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ values: state.tuning }) });
    const data = await api("/api/deploy", { method: "POST" });
    state.dirty.tuning = false;
    toast("已部署到游戏目录：" + data.written.length + " 个文件");
  } catch (e) { toast(e.message, true); }
});

/* ---------- approximate GUI preview ---------- */
function makeEl(tag, cls, style, text) {
  const el = document.createElement(tag);
  if (cls) el.className = cls;
  if (style) Object.assign(el.style, style);
  if (text != null) el.textContent = text;
  return el;
}
function posStyle(x, y, w, h, extra = {}) {
  return Object.assign({ position: "absolute", left: x + "px", top: y + "px", width: w + "px", height: h + "px" }, extra);
}

function renderPreview() {
  const screen = $("previewScreen");
  const W = parseInt($("previewW").value || 960, 10);
  const H = parseInt($("previewH").value || 540, 10);
  screen.style.width = W + "px";
  screen.style.height = H + "px";
  screen.innerHTML = "";

  const tab = state.previewTab;
  const P = tun("CommandGUIScreen.PADDING");
  const RM = tun("CommandGUIScreen.RIGHT_MARGIN");
  const SW = tun("CommandGUIScreen.SCROLLBAR_WIDTH");
  const FH = tun("CommandGUIScreen.FOOTER_HEIGHT");
  const topGap = tun("CommandGUIScreen.TAB_AREA_TOP_GAP");
  const botGap = tun("CommandGUIScreen.TAB_AREA_BOTTOM_GAP");

  // 顶部标签栏（近似）
  const tabNames = [["custom", "快捷指令"], ["fake", "假人"], ["machine", "机器开关"]];
  tabNames.forEach(([id, name], i) => {
    screen.appendChild(makeEl("div", "mc-tab" + (tab === id ? " active" : ""), posStyle(10 + i * 86, 8, 82, 24), name));
  });

  // 内容区与底栏几何
  const tabBarBottom = 34;
  const listTop = tabBarBottom + topGap;
  const areaLeft = P;
  const areaTop = listTop;
  const areaW = Math.max(80, W - P - RM - SW);
  const areaH = Math.max(60, H - FH - areaTop - botGap);
  const areaBottom = areaTop + areaH;
  const footerY = H - FH;

  // 底栏分隔线
  screen.appendChild(makeEl("div", "mc-sep", posStyle(0, footerY, W, 1)));
  const closeW = tun("CommandGUIScreen.CLOSE_BUTTON_WIDTH");
  screen.appendChild(makeEl("div", "mc-btn", posStyle(W - P - closeW, footerY + 12, closeW, 20), "关闭"));

  if (tab === "settings") return renderSettingsPreview(screen, W, H, P, RM, SW, areaLeft, areaTop, areaW, areaH, footerY);
  if (tab === "fake") return renderFakePreview(screen, W, H, P, RM, SW, areaLeft, areaTop, areaW, areaH, footerY);
  if (tab === "machine") return renderMachinePreview(screen, W, H, P, RM, SW, areaLeft, areaTop, areaW, areaH, footerY);

  // ---------- custom tab ----------
  renderCommandTabPreview(screen, W, H, P, RM, SW, areaLeft, areaTop, areaW, areaH, footerY, {
    prefix: "CustomCommandTab",
    rows: 14, title: "快捷指令", addButtonText: "+ 指令", footerExtra: "添加假人指令",
  });
}

function renderCommandTabPreview(screen, W, H, P, RM, SW, areaLeft, areaTop, areaW, areaH, footerY, cfg) {
  const sideOff = tun(`${cfg.prefix}.SIDEBAR_OFFSET`);
  const catMin = tun(`${cfg.prefix}.CATEGORY_MIN_WIDTH`);
  const catDiv = tun(`${cfg.prefix}.CATEGORY_WIDTH_DIVISOR`) || 4;
  const catH = tun("AbstractCommandTab.CATEGORY_TAB_HEIGHT");
  const catGap = tun("AbstractCommandTab.CATEGORY_TAB_GAP");
  const catScrollW = tun("AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH");
  const cmdGap = tun("AbstractCommandTab.CATEGORY_COMMAND_GAP");
  const baseCatW = Math.max(catMin, areaW / catDiv);
  const catW = cfg.prefix === "CustomCommandTab"
    ? Math.max(catMin, baseCatW * tun(`${cfg.prefix}.CATEGORY_WIDTH_PERCENT`) / 100)
    : baseCatW;
  const sidebarLeft = areaLeft + sideOff;
  const sepX = sidebarLeft + catW + catScrollW + 4;
  const cmdLeft = areaLeft + sideOff + baseCatW + catScrollW + cmdGap;
  const cmdW = Math.max(20, areaLeft + areaW - cmdLeft - 6);

  screen.appendChild(makeEl("div", "mc-sep", posStyle(sepX, areaTop, 1, areaH)));
  const catRows = Math.max(1, Math.floor((areaH - tun(`${cfg.prefix}.CATEGORY_BOTTOM_RESERVE`)) / (catH + catGap)));
  for (let i = 0; i < Math.min(catRows, 6); i++) {
    const active = i === 0;
    screen.appendChild(makeEl("div", "mc-btn mc-cat" + (active ? " sel" : ""), posStyle(sidebarLeft + 2, areaTop + 2 + i * (catH + catGap), Math.max(20, catW - 4), catH), i === 0 ? "默认分类" : `分类 ${i + 1}`));
  }
  screen.appendChild(makeEl("div", "mc-btn mc-cat", posStyle(sidebarLeft + 2, areaTop + areaH - 18, Math.max(20, catW - 4), catH), "+"));

  const columns = Math.max(1, tun("AbstractCommandTab.COLUMNS"));
  const itemH = Math.max(10, tun("AbstractCommandTab.ITEM_HEIGHT"));
  let rowStep = itemH;
  if (cfg.prefix === "CustomCommandTab") {
    const visibleRows = Math.max(2, tun(`${cfg.prefix}.VISIBLE_ROWS`));
    rowStep = Math.max(tun(`${cfg.prefix}.MIN_ROW_HEIGHT`), Math.floor(areaH / visibleRows));
  }
  const colGap = cfg.prefix === "CustomCommandTab" ? Math.max(tun(`${cfg.prefix}.MIN_COLUMN_GAP`), 8 * tun(`${cfg.prefix}.BUTTON_SCALE_PERCENT`) / 100) : tun("AbstractCommandTab.COLUMN_GAP");
  const groupW = Math.max(20, (cmdW - colGap * (columns - 1)) / columns);
  const visibleRows = Math.max(1, Math.floor(areaH / rowStep));
  const count = Math.min(cfg.rows, visibleRows * columns);
  for (let i = 0; i < count; i++) {
    const c = i % columns, r = Math.floor(i / columns);
    const bw = Math.max(16, groupW - (cfg.prefix === "CustomCommandTab" ? 4 : tun("AbstractCommandTab.COLUMN_GAP")));
    const bh = cfg.prefix === "CustomCommandTab" ? Math.max(10, rowStep - 2) : Math.max(10, itemH - tun("AbstractCommandTab.ITEM_VERTICAL_PAD"));
    const x = cmdLeft + c * (groupW + colGap) + tun("AbstractCommandTab.ITEM_HORIZONTAL_PAD");
    const y = areaTop + r * rowStep + 2;
    screen.appendChild(makeEl("div", "mc-btn", posStyle(x, y, bw, bh), `命令 ${i + 1}`));
  }

  // 搜索框与添加按钮
  const searchW = tun("CommandGUIScreen.SEARCH_WIDTH");
  const maxCluster = tun(`${cfg.prefix}.MAX_CLUSTER_WIDTH`);
  screen.appendChild(makeEl("div", "mc-input", posStyle(cmdLeft, footerY + 12, searchW, 20), "搜索…"));
  screen.appendChild(makeEl("div", "mc-btn", posStyle(cmdLeft + searchW + 2, footerY + 12, 48, 20), cfg.addButtonText));
  screen.appendChild(makeEl("div", "mc-scrollbar", posStyle(areaLeft + areaW + 2, areaTop, SW, areaH)));
  if (cfg.footerExtra) {
    screen.appendChild(makeEl("div", "mc-btn", posStyle(areaLeft, footerY + 12, 88, 20), cfg.footerExtra));
  }
}

function renderMachinePreview(screen, W, H, P, RM, SW, areaLeft, areaTop, areaW, areaH, footerY) {
  renderCommandTabPreview(screen, W, H, P, RM, SW, areaLeft, areaTop, areaW, areaH, footerY, {
    prefix: "MachineSwitchTab", rows: 8, title: "机器开关", addButtonText: "+ 机器", footerExtra: null,
  });
  // 移除泛用的三列命令网格，改为单列机器行
  screen.querySelectorAll(".mc-btn:not(.mc-cat)").forEach((el) => {
    const top = parseInt(el.style.top || "0", 10);
    if (top < footerY) el.remove();
  });
  const sideOff = tun("MachineSwitchTab.SIDEBAR_OFFSET");
  const catMin = tun("MachineSwitchTab.CATEGORY_MIN_WIDTH");
  const catDiv = tun("MachineSwitchTab.CATEGORY_WIDTH_DIVISOR") || 4;
  const baseCatW = Math.max(catMin, areaW / catDiv);
  const cmdLeft = areaLeft + sideOff + baseCatW + tun("AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH") + tun("AbstractCommandTab.CATEGORY_COMMAND_GAP");
  const maxCluster = tun("MachineSwitchTab.MAX_CLUSTER_WIDTH");
  const itemH = tun("AbstractCommandTab.ITEM_HEIGHT");
  const switchW = Math.max(100, areaLeft + areaW - cmdLeft - maxCluster - tun("AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH"));
  const rows = Math.min(8, Math.floor(areaH / itemH));
  for (let i = 0; i < rows; i++) {
    const y = areaTop + i * itemH + 2;
    screen.appendChild(makeEl("div", "mc-btn", posStyle(cmdLeft, y, switchW, Math.max(10, itemH - 2)), `机器 ${i + 1}`));
    const clusterRight = areaLeft + areaW - 4;
    const modesW = tun("MachineSwitchTab.MODES_BTN_W");
    const refreshW = tun("MachineSwitchTab.REFRESH_BTN_W");
    const gap = tun("MachineSwitchTab.CLUSTER_GAP");
    screen.appendChild(makeEl("div", "mc-btn", posStyle(clusterRight - modesW - refreshW - gap, y, modesW, Math.max(10, itemH - 2)), "模式"));
    screen.appendChild(makeEl("div", "mc-btn", posStyle(clusterRight - refreshW, y, refreshW, Math.max(10, itemH - 2)), "检测"));
  }
}

function renderFakePreview(screen, W, H, P, RM, SW, areaLeft, areaTop, areaW, areaH, footerY) {
  const sidePad = tun("FakePlayerTab.PLAYER_LIST_SIDE_PAD");
  const minW = tun("FakePlayerTab.PLAYER_ITEM_MIN_WIDTH");
  const div = tun("FakePlayerTab.PLAYER_ITEM_WIDTH_DIVISOR") || 4;
  const itemH = tun("FakePlayerTab.PLAYER_ITEM_HEIGHT");
  const reserve = tun("FakePlayerTab.LIST_BOTTOM_RESERVE");
  const gap = tun("FakePlayerTab.SEPARATOR_GAP");
  const listW = sidePad + Math.max(minW, areaW / div);
  const listX = areaLeft;
  const sepX = listX + listW + gap;
  const panelX = sepX + gap;

  screen.appendChild(makeEl("div", "mc-sep", posStyle(sepX, areaTop, 1, areaH)));
  screen.appendChild(makeEl("div", "mc-panel", posStyle(panelX, areaTop, Math.max(80, areaLeft + areaW - panelX - 4), areaH)));
  const rows = Math.max(1, Math.floor((areaH - reserve) / itemH));
  for (let i = 0; i < Math.min(rows, 8); i++) {
    const y = areaTop + i * itemH + 2;
    const box = tun("FakePlayerTab.CHECKBOX_SIZE");
    screen.appendChild(makeEl("div", "mc-btn", posStyle(listX + tun("FakePlayerTab.CHECKBOX_X_OFFSET"), y, box, box)));
    screen.appendChild(makeEl("div", "mc-btn", posStyle(listX + tun("FakePlayerTab.CHECKBOX_X_OFFSET") + box + tun("FakePlayerTab.FACE_PAD_LEFT"), y, listW - box - tun("FakePlayerTab.FACE_PAD_LEFT") - 2, itemH - 2), `玩家 ${i + 1}`));
  }
  const actionH = tun("FakePlayerTab.ACTION_BUTTON_HEIGHT");
  const refW = tun("FakePlayerTab.PANEL_WIDTH_REFERENCE");
  const panelW = Math.max(80, areaLeft + areaW - panelX - tun("FakePlayerTab.PANEL_RIGHT_PAD"));
  const actionW = Math.min(refW, panelW / 2);
  for (let i = 0; i < 6; i++) {
    const y = areaTop + 4 + Math.floor(i / 2) * actionH;
    const x = panelX + 4 + (i % 2) * (actionW + 4);
    screen.appendChild(makeEl("div", "mc-btn", posStyle(x, y, actionW, actionH), ["停止", "击杀", "攻击", "使用", "跳跃", "潜行"][i] || "操作"));
  }
  screen.appendChild(makeEl("div", "mc-input", posStyle(listX, footerY + 12, 90, 20), "搜索玩家…"));
  screen.appendChild(makeEl("div", "mc-btn", posStyle(listX + 92, footerY + 12, 88, 20), "批量生成"));
  screen.appendChild(makeEl("div", "mc-scrollbar", posStyle(areaLeft + areaW + 2, areaTop, SW, areaH)));
}

function renderSettingsPreview(screen, W, H, P, RM, SW, areaLeft, areaTop, areaW, areaH, footerY) {
  const titleX = tun("SettingsScreen.TITLE_X"), titleY = tun("SettingsScreen.TITLE_Y");
  screen.appendChild(makeEl("div", "mc-label", posStyle(titleX, titleY, 300, 12), "Command-GUI 设置"));
  const tabY = tun("SettingsScreen.TAB_Y"), tabH = tun("SettingsScreen.TAB_HEIGHT"), tabGap = tun("SettingsScreen.TAB_GAP");
  const tabW = Math.max(72, (W - titleX - tun("SettingsScreen.RIGHT_MARGIN") - 2 * tabGap) / 3);
  ["快捷指令", "假人", "机器开关"].forEach((name, i) => {
    screen.appendChild(makeEl("div", "mc-tab" + (i === 0 ? " active" : ""), posStyle(titleX + i * (tabW + tabGap), tabY, tabW, tabH), name));
  });
  const contentTop = tun("SettingsScreen.CONTENT_TOP");
  const rowH = tun("SettingsScreen.ROW_HEIGHT");
  for (let i = 0; i < 5; i++) {
    const y = contentTop + i * rowH;
    screen.appendChild(makeEl("div", "mc-label", posStyle(titleX + 4, y + 5, 280, 14), `设置项 ${i + 1}`));
    const bw = tun("SettingsScreen.SETTING_BUTTON_WIDTH"), bh = tun("SettingsScreen.SETTING_BUTTON_HEIGHT");
    screen.appendChild(makeEl("div", "mc-btn", posStyle(areaLeft + areaW - tun("SettingsScreen.CONTENT_TOP_GAP") - bw, y, bw, bh), i % 2 ? "是" : "否"));
  }
  screen.appendChild(makeEl("div", "mc-scrollbar", posStyle(W - RM - SW, contentTop, SW, Math.max(20, H - contentTop - P))));
  screen.appendChild(makeEl("div", "mc-btn", posStyle(W - P - 60, H - 44 + 12, 60, 20), "关闭"));
}

/* ---------- lang editor ---------- */
async function loadLang() {
  const data = await api("/api/lang");
  state.langs = data.langs || {};
  state.keys = data.keys || [];
  renderLangTable();
}

function renderLangTable() {
  const filter = ($("langSearch").value || "").toLowerCase();
  const tbody = document.querySelector("#langTable tbody");
  tbody.innerHTML = "";
  const keys = state.keys.filter((k) => !filter || k.toLowerCase().includes(filter) || String(state.langs.zh_cn?.[k] || "").toLowerCase().includes(filter) || String(state.langs.en_us?.[k] || "").toLowerCase().includes(filter));
  $("langCount").textContent = `显示 ${keys.length} / ${state.keys.length} 条`;
  for (const key of keys) {
    const tr = document.createElement("tr");
    const tdKey = document.createElement("td");
    tdKey.className = "key"; tdKey.textContent = key;
    const tdZh = document.createElement("td");
    const taZh = document.createElement("textarea");
    taZh.value = state.langs.zh_cn?.[key] || "";
    taZh.dataset.lang = "zh_cn"; taZh.dataset.key = key;
    const tdEn = document.createElement("td");
    const taEn = document.createElement("textarea");
    taEn.value = state.langs.en_us?.[key] || "";
    taEn.dataset.lang = "en_us"; taEn.dataset.key = key;
    [taZh, taEn].forEach((ta) => ta.addEventListener("input", () => {
      const l = ta.dataset.lang, k = ta.dataset.key;
      state.langs[l] = state.langs[l] || {};
      state.langs[l][k] = ta.value;
      state.dirty.lang = true;
    }));
    tdZh.appendChild(taZh); tdEn.appendChild(taEn);
    tr.append(tdKey, tdZh, tdEn);
    tbody.appendChild(tr);
  }
}
$("langSearch").addEventListener("input", renderLangTable);
$("btnSaveLang").addEventListener("click", async () => {
  try {
    for (const lang of ["zh_cn", "en_us"]) {
      await api("/api/lang", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ lang, entries: state.langs[lang] || {} }) });
    }
    state.dirty.lang = false;
    toast("中英文文案已保存到源码 assets/lang");
  } catch (e) { toast(e.message, true); }
});
$("btnDeployLang").addEventListener("click", async () => {
  try {
    for (const lang of ["zh_cn", "en_us"]) {
      await api("/api/lang", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ lang, entries: state.langs[lang] || {} }) });
    }
    await api("/api/deploy", { method: "POST" });
    toast("已生成 Command-GUI-DevTexts 语言资源包并部署");
  } catch (e) { toast(e.message, true); }
});

/* ---------- preset editor ---------- */
async function loadPresets() {
  const data = await api("/api/presets");
  state.presets = data.presets || {};
  const sel = $("presetSelect");
  sel.innerHTML = "";
  for (const id of Object.keys(state.presets)) {
    const opt = document.createElement("option");
    opt.value = id; opt.textContent = id + ".json";
    sel.appendChild(opt);
  }
  state.selectedPreset = sel.value || null;
  renderPresetEditor();
}
function renderPresetEditor() {
  if (!state.selectedPreset) { $("presetEditor").value = ""; return; }
  $("presetEditor").value = JSON.stringify(state.presets[state.selectedPreset], null, 2);
  $("presetError").textContent = "";
}
$("presetSelect").addEventListener("change", () => {
  if (state.dirty.preset && !confirm("当前预设未保存，切换将丢失修改。继续？")) return;
  state.selectedPreset = $("presetSelect").value;
  state.dirty.preset = false;
  renderPresetEditor();
});
$("presetEditor").addEventListener("input", () => {
  state.dirty.preset = true;
  try {
    state.presets[state.selectedPreset] = JSON.parse($("presetEditor").value);
    $("presetError").textContent = "";
  } catch (e) {
    $("presetError").textContent = "JSON 语法错误：" + e.message;
  }
});
$("btnSavePreset").addEventListener("click", async () => {
  try {
    const data = JSON.parse($("presetEditor").value);
    await api("/api/presets", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id: state.selectedPreset, data }) });
    state.presets[state.selectedPreset] = data;
    state.dirty.preset = false;
    toast("预设已保存到源码 assets/presets");
  } catch (e) { toast(e.message, true); }
});
$("btnDeployPreset").addEventListener("click", async () => {
  try {
    const data = JSON.parse($("presetEditor").value);
    await api("/api/presets", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id: state.selectedPreset, data }) });
    const r = await api("/api/deploy", { method: "POST" });
    state.dirty.preset = false;
    toast("预设已写入游戏 config：" + (state.minecraft.config_dir || ""));
  } catch (e) { toast(e.message, true); }
});

/* ---------- build / deploy ---------- */
function renderBuildTab() {
  const m = state.minecraft;
  $("buildPaths").innerHTML = `
    <dt>项目根目录</dt><dd class="mono">${esc(state.project || "")}</dd>
    <dt>游戏目录</dt><dd class="mono">${esc(m.root || "")}</dd>
    <dt>目录模式</dt><dd>${esc(m.mode || "")}</dd>
    <dt>mods 目录</dt><dd class="mono">${esc(m.mods_dir || "")}</dd>
    <dt>config 目录</dt><dd class="mono">${esc(m.config_dir || "")}</dd>
    <dt>resourcepacks</dt><dd class="mono">${esc(m.resourcepacks_dir || "")}</dd>
    <dt>JDK</dt><dd class="mono">${esc(state.jdk.version || state.jdk.error || "")}</dd>`;
  const arts = state.build.all || [];
  $("buildArtifacts").innerHTML = arts.length ? arts.map((a) => `<div class="artifact"><b>${esc(a.name)}</b> · ${(a.size / 1024).toFixed(1)} KB<br><span class="muted">${esc(a.path)}</span></div>`).join("") : `<span class="muted">尚无产物</span>`;
  $("cfgGameDir").value = state.devConfig.minecraft_dir || "";
  $("cfgPackFormat").value = state.devConfig.resourcepack_pack_format || 99;
}

$("btnSaveGameDir").addEventListener("click", async () => {
  try {
    const data = await api("/api/dev-config", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ minecraft_dir: $("cfgGameDir").value.trim() }) });
    state.devConfig = data.dev_config; state.minecraft = data.minecraft;
    renderBuildTab(); toast("游戏目录已保存");
  } catch (e) { toast(e.message, true); }
});
$("btnSavePackFormat").addEventListener("click", async () => {
  try {
    const data = await api("/api/dev-config", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ resourcepack_pack_format: $("cfgPackFormat").value }) });
    state.devConfig = data.dev_config;
    toast("pack_format 已保存");
  } catch (e) { toast(e.message, true); }
});

function appendLog(text, cls = "") {
  const log = $("buildLog");
  if (log.dataset.clear !== "1") { log.textContent = ""; log.dataset.clear = "1"; }
  log.textContent += text + "\n";
  log.scrollTop = log.scrollHeight;
}
function setBuildButtons(disabled) {
  ["btnBuildOffline", "btnBuildOnline", "btnBuildInstall", "btnInstallOnly"].forEach((id) => $(id).disabled = disabled);
}
async function startBuild(offline, installAfter) {
  setBuildButtons(true);
  appendLog(offline ? "▶ 开始离线构建…" : "▶ 开始联网构建…");
  const es = new EventSource(`/api/build?offline=${offline ? 1 : 0}&install=${installAfter ? 1 : 0}`);
  es.onmessage = (ev) => {
    try {
      const msg = JSON.parse(ev.data);
      if (msg.type === "info") appendLog("[信息] " + msg.message, "info");
      else if (msg.type === "error") appendLog("[错误] " + msg.message, "err");
      else if (msg.type === "log") appendLog(msg.message);
    } catch (_) { appendLog(ev.data); }
  };
  es.addEventListener("done", async (ev) => {
    const data = JSON.parse(ev.data);
    appendLog(data.message || (data.ok ? "完成" : "失败"), data.ok ? "ok" : "err");
    if (data.error) appendLog("[错误] " + data.error, "err");
    if (data.install) appendLog("[安装] " + JSON.stringify(data.install, null, 2));
    es.close(); setBuildButtons(false);
    try {
      const s = await api("/api/state");
      state.build = s.build; state.minecraft = s.minecraft; state.jdk = s.jdk;
      renderBuildTab(); renderOverview(s);
    } catch (_) {}
    toast(data.ok ? "构建完成" : "构建失败", !data.ok);
  });
  es.onerror = () => {
    appendLog("[连接] 构建通道结束");
    es.close(); setBuildButtons(false);
  };
}
$("btnBuildOffline").addEventListener("click", () => startBuild(true, false));
$("btnBuildOnline").addEventListener("click", () => startBuild(false, false));
$("btnBuildInstall").addEventListener("click", () => startBuild(true, true));
$("btnInstallOnly").addEventListener("click", async () => {
  try {
    const r = await api("/api/install", { method: "POST" });
    toast("已安装：" + r.installed.length + " 个 jar");
    appendLog("[安装] " + JSON.stringify(r, null, 2));
  } catch (e) { toast(e.message, true); }
});

window.addEventListener("beforeunload", (e) => {
  if (state.dirty.tuning || state.dirty.lang || state.dirty.preset) {
    e.preventDefault(); e.returnValue = "";
  }
});

loadState().catch((e) => {
  $("topStatus").textContent = "服务连接失败：" + e.message;
  toast(e.message, true);
});
