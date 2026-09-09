"use strict";

/* ============================================================
 * Command-GUI 高级预览器
 * 按游戏内源码坐标 1:1 绘制，支持多层 GUI 栈、按钮导航、右键、
 * 滚轮滚动与可拖动滚动条。
 * ============================================================ */

const PV = {
  W: 960, H: 540, zoom: 1,
  stack: [],          // 屏幕栈，栈顶为当前屏幕；空栈 = 游戏画面
  widgets: [],        // 当前帧可点击控件
  scrolls: [],        // 当前帧滚动条
  drag: null,
  hover: null,
  data: {
    categories: [
      { id: "default", name: "默认" },
      { id: "c1", name: "1" }, { id: "c2", name: "2" }, { id: "c3", name: "3" },
      { id: "c4", name: "4" }, { id: "c5", name: "5" }, { id: "c6", name: "6" },
      { id: "c7", name: "7" }, { id: "c3534", name: "3534" },
    ],
    commands: [
      { cat: "default", name: "1", cmd: "/say 1", multi: false },
      { cat: "default", name: "34", cmd: "/say 34", multi: false },
      { cat: "default", name: "45", cmd: "/say 45", multi: false },
      { cat: "default", name: "455", cmd: "/say 455", multi: false },
      { cat: "default", name: "56", cmd: "/say 56", multi: false },
      { cat: "default", name: "457", cmd: "/say 457", multi: false },
      { cat: "default", name: "65746", cmd: "/say 65746", multi: false },
      { cat: "default", name: "456", cmd: "/say 456", multi: false },
      { cat: "default", name: "54747", cmd: "/say 54747", multi: false },
      { cat: "default", name: "4577457", cmd: "/say 4577457", multi: false },
      { cat: "default", name: "4575747", cmd: "/say 4575747", multi: false },
      { cat: "default", name: "6", cmd: "/say 6", multi: false },
      { cat: "default", name: "4", cmd: "/say 4", multi: false },
      { cat: "default", name: "8", cmd: "/say 8", multi: false },
      { cat: "default", name: "3", cmd: "/say 3", multi: false },
      { cat: "default", name: "2", cmd: "/say 2", multi: false },
      { cat: "default", name: "5", cmd: "/say 5", multi: false },
      { cat: "default", name: "7", cmd: "/say 7", multi: false },
      { cat: "default", name: "9", cmd: "/say 9", multi: false },
      { cat: "default", name: "0", cmd: "/say 0", multi: false },
      { cat: "default", name: "11", cmd: "/say 11", multi: false },
    ],
    fakePlayers: ["bot_1", "bot_2", "bot_3", "Steve_fake"],
    selectedFake: "bot_1",
    machines: [
      { name: "云杉树场", category: "甘蔗", detected: "abnormal", desc: "" },
      { name: "甘蔗机", category: "", detected: "abnormal", desc: "" },
      { name: "压榨机", category: "", detected: "abnormal", desc: "" },
    ],
    modes: [
      { name: "常开", single: true, detected: "on", steps: [3, 5] },
      { name: "定时", single: false, detected: "off", steps: [2, 6] },
      { name: "应急停机", single: false, detected: "off", steps: [1, 1] },
    ],
    steps: [
      { desc: "等待", cmd: "/wait 20", delay: true },
      { desc: "开启", cmd: "/setblock ~ ~ ~ redstone_block", delay: false },
      { desc: "延时 40tick", cmd: "", delay: true },
      { desc: "关闭", cmd: "/setblock ~ ~ ~ air", delay: false },
    ],
  },
};

/* 从当前语言文件取文案 */
function tr(key, fallback) {
  return state?.langs?.zh_cn?.[key] || state?.langs?.en_us?.[key] || fallback || key;
}

function pvDefaults() {
  // 保留上方与游戏截图一致的示例数据（默认分类 / 数字分类 / 数字命令），不再覆盖。
}

/* ---------- 屏幕栈 ---------- */
function pvPush(screen) {
  PV.stack.push(screen);
  renderPreview();
}
function pvBack() {
  if (PV.stack.length > 1) PV.stack.pop();
  else if (PV.stack.length === 1) PV.stack = [];
  renderPreview();
}
function pvCloseMain() { PV.stack = []; renderPreview(); }
function pvTop() { return PV.stack[PV.stack.length - 1] || null; }

function pvPath() {
  const names = PV.stack.map((s) => s.title);
  const el = $("previewPath");
  if (!el) return;
  if (!names.length) { el.innerHTML = "游戏画面"; return; }
  el.innerHTML = '<div class="pv-breadcrumb">' + names.map((n, i) => (i === names.length - 1 ? `<b>${esc(n)}</b>` : `<span>${esc(n)}</span> › `)).join("") + "</div>";
}

/* ---------- 基础绘制 ---------- */
function pvSurface() {
  const root = $("previewScreen");
  root.innerHTML = "";
  PV.W = parseInt($("previewW").value || 960, 10);
  PV.H = parseInt($("previewH").value || 540, 10);
  PV.zoom = parseFloat($("previewZoom").value || "1");
  const surface = document.createElement("div");
  surface.className = "preview-surface";
  surface.style.width = PV.W + "px";
  surface.style.height = PV.H + "px";
  surface.style.transform = `scale(${PV.zoom})`;
  root.style.width = (PV.W * PV.zoom) + "px";
  root.style.height = (PV.H * PV.zoom) + "px";
  root.appendChild(surface);
  PV.surface = surface;
  return surface;
}

function pvWidget(surface, x, y, w, h, kind, label, opts = {}) {
  const el = document.createElement(kind === "input" ? "input" : "div");
  el.className = `pv-widget pv-${kind}` + (opts.cls ? " " + opts.cls : "");
  el.style.left = x + "px";
  el.style.top = y + "px";
  el.style.width = w + "px";
  el.style.height = h + "px";
  if (kind === "input") {
    el.type = "text";
    el.value = label ?? "";
    el.spellcheck = false;
    if (opts.hint) el.placeholder = opts.hint;
  } else if (kind !== "label" && kind !== "title") {
    el.textContent = label ?? "";
  } else {
    el.textContent = label ?? "";
  }
  if (opts.color) el.style.color = opts.color;
  if (opts.disabled) el.classList.add("disabled");
  surface.appendChild(el);
  const wgt = { x, y, w, h, el, kind, label, action: opts.action, onRight: opts.onRight, onHover: opts.onHover, data: opts.data, editable: kind === "input", onChange: opts.onChange };
  PV.widgets.push(wgt);
  if (opts.tooltip) {
    el.addEventListener("mousemove", (e) => pvTooltip(e, opts.tooltip));
    el.addEventListener("mouseleave", pvTooltipHide);
  }
  return wgt;
}

function pvBtn(surface, x, y, w, h, label, action, opts = {}) {
  return pvWidget(surface, x, y, w, h, "button", label, Object.assign({ action, cls: opts.dark ? "dark" : "" }, opts));
}
function pvDark(surface, x, y, w, h, label, action, opts = {}) {
  return pvWidget(surface, x, y, w, h, "button", label, Object.assign({ action, cls: "dark" + (opts.sel ? " sel" : "") }, opts));
}
function pvMarkCheckbox(surface, x, y, w, h, label, selected, action, opts = {}) {
  const el = document.createElement("div");
  el.className = "pv-widget pv-checkbox" + (opts.cls ? " " + opts.cls : "");
  Object.assign(el.style, { left: x + "px", top: y + "px", width: w + "px", height: h + "px" });
  const box = document.createElement("span");
  const boxSize = 14;
  box.className = "pv-checkbox-box";
  Object.assign(box.style, { left: "0px", top: ((h - boxSize) / 2) + "px", width: boxSize + "px", height: boxSize + "px", background: selected ? "#3a3a3a" : "#101010", border: "2px solid #000", position: "absolute" });
  if (selected) box.innerHTML = "✓";
  const text = document.createElement("span");
  text.className = "pv-checkbox-text";
  Object.assign(text.style, { position: "absolute", left: (boxSize + 4) + "px", top: "1px", width: (w - boxSize - 6) + "px", height: (h - 2) + "px", lineHeight: (h - 2) + "px", fontSize: "9px", color: "#c0c0c0" });
  text.textContent = label;
  el.appendChild(box); el.appendChild(text);
  surface.appendChild(el);
  const wgt = { x, y, w, h, el, kind: "checkbox", label, action, onRight: opts.onRight, data: opts.data };
  PV.widgets.push(wgt);
  return wgt;
}

function pvInput(surface, x, y, w, h, value, opts = {}) {
  return pvWidget(surface, x, y, w, h, "input", value, opts);
}
function pvLabel(surface, x, y, w, text, color) {
  return pvWidget(surface, x, y, w, 12, "label", text, { color: color || "#b0b0b0" });
}
function pvTitle(surface, text, y) {
  const el = document.createElement("div");
  el.className = "pv-widget pv-title";
  el.style.left = "0px"; el.style.top = (y || 4) + "px"; el.style.width = PV.W + "px"; el.style.height = "14px";
  el.textContent = text;
  surface.appendChild(el);
  return el;
}
function pvSep(surface, x, y, w, h) {
  return pvWidget(surface, x, y, w || 1, h || 1, "sep", "");
}
function pvLine(surface, x, y, w) { return pvSep(surface, x, y, w, 1); }

/* ScrollbarHandle 公式 1:1 移植 */
function pvThumbHeight(h, viewport, content, minThumb) {
  if (content <= viewport) return h;
  return Math.max(minThumb || 10, h * viewport / content);
}
function pvThumbTop(y, h, offset, maxOffset, viewport, content, minThumb) {
  if (maxOffset <= 0) return y;
  const track = h - pvThumbHeight(h, viewport, content, minThumb);
  return y + track * offset / maxOffset;
}
function pvAddScrollbar(surface, key, x, y, w, h, state, opts = {}) {
  const track = document.createElement("div");
  track.className = "pv-scroll-track";
  const trackColor = cssColor(tun("ScrollbarHandle.TRACK_COLOR"));
  const outline = cssColor(tun("ScrollbarHandle.THUMB_OUTLINE"));
  const thumbColor = cssColor(tun("ScrollbarHandle.THUMB_COLOR"));
  Object.assign(track.style, { left: x + "px", top: y + "px", width: w + "px", height: h + "px", background: trackColor, border: "1px solid " + outline });
  surface.appendChild(track);
  const thumb = document.createElement("div");
  thumb.className = "pv-scroll-thumb";
  thumb.style.background = thumbColor;
  thumb.style.border = "1px solid " + outline;
  surface.appendChild(thumb);
  const minThumb = opts.minThumb || tun("ScrollbarHandle.MIN_THUMB_HEIGHT") || 10;
  const hoverColor = cssColor(tun("ScrollbarHandle.THUMB_HOVER_COLOR"));
  thumb.addEventListener("mouseenter", () => { thumb.style.background = hoverColor; });
  thumb.addEventListener("mouseleave", () => { thumb.style.background = thumbColor; });
  const rec = { key, x, y, w, h, state, minThumb, thumb, update() {
    const max = Math.max(0, state.max);
    const th = pvThumbHeight(h, state.viewport, state.content, minThumb);
    const tt = pvThumbTop(y, h, state.offset, max, state.viewport, state.content, minThumb);
    thumb.style.left = x + "px"; thumb.style.top = tt + "px"; thumb.style.width = w + "px"; thumb.style.height = th + "px";
    thumb.style.display = (state.content > state.viewport && max > 0) ? "block" : "block";
  }};
  rec.update();
  PV.scrolls.push(rec);
  return rec;
}

/* ---------- 鼠标/滚轮 ---------- */
function pvAbsOffset(el) {
  let x = 0, y = 0;
  while (el) { x += el.offsetLeft || 0; y += el.offsetTop || 0; el = el.offsetParent; }
  return { x, y };
}
function pvEventPos(e) {
  const rect = PV.surface.getBoundingClientRect();
  let left = rect.left, top = rect.top;
  if (!rect.width || !rect.height) {
    const off = pvAbsOffset(PV.surface);
    left = off.x; top = off.y;
  }
  return { x: (e.clientX - left) / PV.zoom, y: (e.clientY - top) / PV.zoom };
}
function pvHitWidget(x, y) {
  for (let i = PV.widgets.length - 1; i >= 0; i--) {
    const w = PV.widgets[i];
    if (x >= w.x && x < w.x + w.w && y >= w.y && y < w.y + w.h) return w;
  }
  return null;
}
function pvHitThumb(x, y) {
  for (let i = PV.scrolls.length - 1; i >= 0; i--) {
    const s = PV.scrolls[i];
    const max = Math.max(0, s.state.max);
    const th = pvThumbHeight(s.h, s.state.viewport, s.state.content, s.minThumb);
    const tt = pvThumbTop(s.y, s.h, s.state.offset, max, s.state.viewport, s.state.content, s.minThumb);
    if (x >= s.x && x < s.x + s.w && y >= tt && y < tt + th) return { rec: s, top: tt, h: th };
  }
  return null;
}
function pvWheelOver(x, y) {
  for (let i = PV.scrolls.length - 1; i >= 0; i--) {
    const s = PV.scrolls[i];
    if (x >= s.x - 6 && x < s.x + s.w + 6 && y >= s.y - 6 && y < s.y + s.h + 6) return s;
  }
  return null;
}

function pvInstallMouse() {
  const root = $("previewScreen");
  if (root._pvMouse) return;
  root._pvMouse = true;
  root.addEventListener("mousedown", (e) => {
    if (e.button !== 0) return;
    const p = pvEventPos(e);
    const thumb = pvHitThumb(p.x, p.y);
    if (thumb) {
      PV.drag = { kind: "scroll", rec: thumb.rec, grab: p.y - thumb.top };
      e.preventDefault();
      return;
    }
    const w = pvHitWidget(p.x, p.y);
    if (w && w.action) { w.action(w, p); e.preventDefault(); }
    if (w && w.kind === "input") { w.el.focus(); }
  });
  root.addEventListener("contextmenu", (e) => {
    const p = pvEventPos(e);
    const w = pvHitWidget(p.x, p.y);
    if (w && w.onRight) { w.onRight(w, p); e.preventDefault(); }
  });
  root.addEventListener("mousemove", (e) => {
    const p = pvEventPos(e);
    if (PV.drag && PV.drag.kind === "scroll") {
      const rec = PV.drag.rec;
      const track = rec.h - pvThumbHeight(rec.h, rec.state.viewport, rec.state.content, rec.minThumb);
      const max = Math.max(0, rec.state.max);
      let off = 0;
      if (track > 0 && max > 0) off = Math.round((p.y - rec.y - PV.drag.grab) * max / track);
      off = Math.max(0, Math.min(max, off));
      if (off !== rec.state.offset) { rec.state.offset = off; rec.state.onChange && rec.state.onChange(off); renderPreview(); }
      e.preventDefault();
    }
    if (PV.hover && PV.hover !== e.target) pvTooltipHide();
  });
  window.addEventListener("mouseup", () => { PV.drag = null; });
  root.addEventListener("wheel", (e) => {
    const p = pvEventPos(e);
    const s = pvWheelOver(p.x, p.y);
    if (s) {
      const delta = e.deltaY > 0 ? 1 : -1;
      const max = Math.max(0, s.state.max);
      const off = Math.max(0, Math.min(max, s.state.offset + delta));
      if (off !== s.state.offset) { s.state.offset = off; s.state.onChange && s.state.onChange(off); renderPreview(); }
      e.preventDefault();
      return;
    }
    const top = pvTop();
    if (top && top.onWheel) { top.onWheel(p.x, p.y, e.deltaY > 0 ? 1 : -1); e.preventDefault(); }
  }, { passive: false });
}

let tooltipEl = null;
function pvTooltip(e, text) {
  pvTooltipHide();
  tooltipEl = document.createElement("div");
  tooltipEl.className = "pv-tooltip";
  tooltipEl.textContent = text;
  document.body.appendChild(tooltipEl);
  tooltipEl.style.left = Math.min(e.clientX + 14, window.innerWidth - 380) + "px";
  tooltipEl.style.top = Math.min(e.clientY + 14, window.innerHeight - 80) + "px";
}
function pvTooltipHide() { if (tooltipEl) { tooltipEl.remove(); tooltipEl = null; } }

/* ================= 主界面 ================= */
function pvMainTabs() {
  const tabs = [
    { id: "custom", label: tr("screen.command-gui.tab.custom", "快捷指令") },
    { id: "fake", label: tr("screen.command-gui.tab.fakeplayer", "假人控制") },
    { id: "machine", label: tr("screen.command-gui.tab.machine", "服务器机器开关") },
  ];
  const showVanilla = PV.data.showVanilla === true;
  const showCarpet = PV.data.showCarpet === true;
  if (showVanilla) tabs.push({ id: "vanilla", label: "原版" });
  if (showCarpet) tabs.push({ id: "carpet", label: "Carpet" });
  return tabs;
}

function renderMainScreen(s, surface) {
  const W = PV.W, H = PV.H;
  const P = tun("CommandGUIScreen.PADDING");
  const RM = tun("CommandGUIScreen.RIGHT_MARGIN");
  const SW = tun("CommandGUIScreen.SCROLLBAR_WIDTH");
  const FH = tun("CommandGUIScreen.FOOTER_HEIGHT");
  const topGap = tun("CommandGUIScreen.TAB_AREA_TOP_GAP");
  const botGap = tun("CommandGUIScreen.TAB_AREA_BOTTOM_GAP");
  const footerY = H - FH;
  const closeBtnY = footerY + tun("CommandGUIScreen.FOOTER_CONTROL_TOP_OFFSET");
  const tabBarBottom = 24;
  const listTop = tabBarBottom + topGap;
  const areaLeft = P;
  const areaTop = listTop;
  const areaW = Math.max(20, W - P - RM - SW);
  const areaH = Math.max(20, H - FH - listTop - botGap);
  const areaBottom = areaTop + areaH;
  const areaRight = areaLeft + areaW;

  // 菜单标签栏：MenuTabBar.arrangeElements 原样移植
  const tabs = pvMainTabs();
  const tabsWidth = Math.min(400, W) - 28;
  const tabWidth = Math.ceil(tabsWidth / tabs.length / 2) * 2;
  let tx = Math.ceil((W - tabsWidth) / 2 / 2) * 2;
  tabs.forEach((t) => {
    pvWidget(surface, tx, 0, tabWidth, 24, "button", t.label, { cls: "tab" + (s.tab === t.id ? " sel" : ""), action: () => { s.tab = t.id; renderPreview(); } });
    tx += tabWidth;
  });

  pvWidget(surface, 4, 2, 20, 20, "icon", "", { action: () => pvPush({ type: "settings", title: tr("screen.command-gui.settings.title", "设置"), parent: s }), tooltip: tr("screen.command-gui.settings.title", "设置") }).el.style.backgroundImage = "url('/textures/settings.png')";
  pvSep(surface, 0, footerY, W, 1).el.style.background = "#555555";
  pvBtn(surface, W - P - tun("CommandGUIScreen.CLOSE_BUTTON_WIDTH"), closeBtnY, tun("CommandGUIScreen.CLOSE_BUTTON_WIDTH"), 20, tr("screen.command-gui.close", "关闭"), () => pvCloseMain());

  if (s.tab === "custom") renderCustomTab(s, surface, { W, H, P, RM, SW, FH, areaLeft, areaTop, areaW, areaH, areaBottom, areaRight, footerY, closeBtnY });
  else if (s.tab === "machine") renderMachineTab(s, surface, { W, H, P, RM, SW, FH, areaLeft, areaTop, areaW, areaH, areaBottom, areaRight, footerY, closeBtnY });
  else if (s.tab === "fake") renderFakeTab(s, surface, { W, H, P, RM, SW, FH, areaLeft, areaTop, areaW, areaH, areaBottom, areaRight, footerY, closeBtnY });
  else renderPresetTab(s, surface, { W, H, P, RM, SW, FH, areaLeft, areaTop, areaW, areaH, areaBottom, areaRight, footerY, closeBtnY });

  if (s.tab !== "fake") {
    const sx = s.tab === "machine" || s.tab === "custom" ? W - 8 - SW : W - RM - SW;
    pvAddScrollbar(surface, "main", sx, areaTop, SW, areaH, s.mainScroll, {
      onChange: (off) => { s.mainScroll.offset = off; renderPreview(); },
    });
  }
}

function pvCategoryX(ctx, prefix, catW) {
  return Math.max(0, (ctx.areaLeft + tun(`${prefix}.SIDEBAR_OFFSET`) + catW - (catW - tun(`${prefix}.CATEGORY_INNER_MARGIN`))) / 2);
}

function renderCustomTab(s, surface, ctx) {
  const prefix = "CustomCommandTab";
  const sideOff = tun(`${prefix}.SIDEBAR_OFFSET`);
  const catMin = tun(`${prefix}.CATEGORY_MIN_WIDTH`);
  const catDiv = tun(`${prefix}.CATEGORY_WIDTH_DIVISOR`) || 4;
  const baseCatW = Math.max(catMin, Math.floor(ctx.areaW / catDiv));
  const catW = Math.max(catMin, Math.floor(baseCatW * tun(`${prefix}.CATEGORY_WIDTH_PERCENT`) / 100));
  const catH = tun("AbstractCommandTab.CATEGORY_TAB_HEIGHT");
  const catGap = tun("AbstractCommandTab.CATEGORY_TAB_GAP");
  const catScrollW = tun("AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH");
  const cmdGap = tun("AbstractCommandTab.CATEGORY_COMMAND_GAP");
  const catX = pvCategoryX(ctx, prefix, catW);
  const sepX = ctx.areaLeft + sideOff + catW + catScrollW + 4;
  const cmdLeft = ctx.areaLeft + sideOff + baseCatW + catScrollW + cmdGap;
  const maxCluster = tun(`${prefix}.MAX_CLUSTER_WIDTH`);

  pvSep(surface, sepX, ctx.areaTop, 1, ctx.areaH).el.style.background = "#555555";
  const visibleCats = Math.max(1, Math.floor((ctx.areaH - tun(`${prefix}.CATEGORY_BOTTOM_RESERVE`)) / (catH + catGap)));
  const catList = s.catScroll || (s.catScroll = { offset: 0 });
  for (let i = 0; i < visibleCats; i++) {
    const idx = catList.offset + i;
    const cat = PV.data.categories[idx];
    if (!cat) break;
    const y = ctx.areaTop + i * (catH + catGap);
    pvDark(surface, catX, y, Math.max(20, catW - tun(`${prefix}.CATEGORY_INNER_MARGIN`)), catH, cat.name, () => {
      s.selectedCat = cat.id; renderPreview();
    }, { sel: s.selectedCat === cat.id, onRight: cat.id !== "default" ? () => pvPush({ type: "editCategory", title: tr("screen.command-gui.edit_category_title", "编辑分类"), parent: s, category: cat }) : null, tooltip: cat.id !== "default" ? tr("screen.command-gui.category_right_click_edit", "右键编辑") : "" });
  }
  pvBtn(surface, catX, ctx.areaBottom - 16, Math.max(20, catW - tun(`${prefix}.CATEGORY_INNER_MARGIN`)), catH, "+", () => pvPush({ type: "addCategory", title: tr("screen.command-gui.add_category_title", "新增分类"), parent: s }));
  pvAddScrollbar(surface, "cat", sepX - catScrollW - 2, ctx.areaTop, catScrollW, ctx.areaH, {
    get offset() { return catList.offset; }, set offset(v) { catList.offset = v; },
    get max() { return Math.max(0, PV.data.categories.length - visibleCats); },
    viewport: visibleCats, content: PV.data.categories.length,
  }, { onChange: (off) => { catList.offset = off; renderPreview(); } });

  const columns = Math.max(1, tun(`${prefix}.COLUMNS`));
  const visibleRows = Math.max(2, tun(`${prefix}.VISIBLE_ROWS`));
  const rowStep = Math.max(tun(`${prefix}.MIN_ROW_HEIGHT`), Math.floor(ctx.areaH / visibleRows));
  const colGap = Math.max(tun(`${prefix}.MIN_COLUMN_GAP`), 8 * tun(`${prefix}.BUTTON_SCALE_PERCENT`) / 100);
  const groupW = Math.max(10, Math.floor((ctx.areaRight - cmdLeft - colGap * (columns - 1)) / columns));
  const cmds = PV.data.commands.filter((c) => !s.selectedCat || c.cat === s.selectedCat);
  const main = s.mainScroll || (s.mainScroll = { offset: 0 });
  const maxRows = Math.max(1, Math.floor(ctx.areaH / rowStep));
  const maxScroll = Math.max(0, Math.ceil(cmds.length / columns) - maxRows);
  main.offset = Math.min(main.offset, maxScroll);
  for (let i = 0; i < Math.min(cmds.length - main.offset * columns, maxRows * columns); i++) {
    const idx = main.offset * columns + i;
    const cmd = cmds[idx];
    const col = i % columns, row = Math.floor(i / columns);
    const x = cmdLeft + col * (groupW + colGap);
    const y = ctx.areaTop + row * rowStep;
    const w = Math.max(20, groupW * tun(`${prefix}.BUTTON_SCALE_PERCENT`) / 100 - 4);
    const h = Math.max(10, rowStep - 2);
    pvBtn(surface, x, y, w, h, cmd.multi ? cmd.name + ` (${cmd.cmd.split("\n").length})` : cmd.name, () => pvExecuteCommand(cmd), {
      onRight: () => pvPush({ type: "addCommand", title: tr("screen.command-gui.edit_title", "编辑指令"), parent: s, editing: cmd, catId: cmd.cat }),
      tooltip: (cmd.desc || "") + "\n左键执行 · 右键编辑\n" + cmd.cmd,
    });
  }
  main.max = maxScroll; main.viewport = maxRows; main.content = Math.max(1, Math.ceil(cmds.length / columns));
  s._mainInfo = { main, cmds, columns, maxRows };

  // 底栏
  const switchW = Math.max(60, ctx.areaRight - cmdLeft - maxCluster - catScrollW);
  pvInput(surface, cmdLeft, ctx.closeBtnY, switchW, 20, s.search || "", { hint: tr("screen.command-gui.search_hint", "搜索…") });
  pvBtn(surface, ctx.areaRight - maxCluster, ctx.closeBtnY, 48, 20, tr("screen.command-gui.add_command", "添加指令"), () => pvPush({ type: "addCommand", title: tr("screen.command-gui.add_title", "添加快捷指令"), parent: s, catId: s.selectedCat || "default" }));
  pvBtn(surface, catX, ctx.closeBtnY, 88, 20, tr("screen.command-gui.add_fakeplayer", "添加假人指令"), () => pvPush({ type: "addCommand", title: tr("screen.command-gui.add_title", "添加假人指令"), parent: s, fake: true, catId: s.selectedCat || "default" }));
}

function pvExecuteCommand(cmd) {
  PV.lastExec = cmd;
  toast("模拟执行：" + cmd.cmd.split("\n")[0] + (cmd.multi ? " …（多步）" : ""));
  pvCloseMain();
}

function renderMachineTab(s, surface, ctx) {
  const prefix = "MachineSwitchTab";
  const sideOff = tun(`${prefix}.SIDEBAR_OFFSET`);
  const catMin = tun(`${prefix}.CATEGORY_MIN_WIDTH`);
  const catDiv = tun(`${prefix}.CATEGORY_WIDTH_DIVISOR`) || 4;
  const baseCatW = Math.max(catMin, Math.floor(ctx.areaW / catDiv));
  const catW = baseCatW;
  const catH = tun("AbstractCommandTab.CATEGORY_TAB_HEIGHT");
  const catGap = tun("AbstractCommandTab.CATEGORY_TAB_GAP");
  const catScrollW = tun("AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH");
  const cmdGap = tun("AbstractCommandTab.CATEGORY_COMMAND_GAP");
  const catX = pvCategoryX(ctx, prefix, catW);
  const sepX = ctx.areaLeft + sideOff + catW + catScrollW + 4;
  const cmdLeft = ctx.areaLeft + sideOff + baseCatW + catScrollW + cmdGap;
  const maxCluster = tun(`${prefix}.MAX_CLUSTER_WIDTH`);
  pvSep(surface, sepX, ctx.areaTop, 1, ctx.areaH).el.style.background = "#555555";
  pvDark(surface, catX, ctx.areaTop, catW - tun(`${prefix}.CATEGORY_INNER_MARGIN`), catH, tr("screen.command-gui.category.default", "默认分类"), () => { s.machineCat = ""; renderPreview(); }, { sel: s.machineCat === "" });
  const cats = [...new Set(PV.data.machines.map((m) => m.category).filter(Boolean))];
  cats.forEach((c, i) => pvDark(surface, catX, ctx.areaTop + (i + 1) * (catH + catGap), catW - tun(`${prefix}.CATEGORY_INNER_MARGIN`), catH, c, () => { s.machineCat = c; renderPreview(); }, { sel: s.machineCat === c, onRight: () => pvPush({ type: "editMachineCategory", title: tr("screen.command-gui.machine.edit_category_title", "编辑机器分类"), parent: s, category: c }) }));
  pvBtn(surface, catX, ctx.areaBottom - 16, catW - tun(`${prefix}.CATEGORY_INNER_MARGIN`), catH, "+", () => pvPush({ type: "addMachineCategory", title: tr("screen.command-gui.machine.add_category_title", "新增机器分类"), parent: s }));

  const machines = PV.data.machines.filter((m) => !s.machineCat || m.category === s.machineCat);
  const itemH = tun("AbstractCommandTab.ITEM_HEIGHT");
  const main = s.mainScroll || (s.mainScroll = { offset: 0 });
  const maxRows = Math.max(1, Math.floor(ctx.areaH / itemH));
  const maxScroll = Math.max(0, machines.length - maxRows);
  main.offset = Math.min(main.offset, maxScroll);
  const rowStep = Math.floor(ctx.areaH / maxRows);
  const remainder = ctx.areaH % maxRows;
  for (let i = 0; i < Math.min(machines.length - main.offset, maxRows); i++) {
    const m = machines[main.offset + i];
    const y = ctx.areaTop + i * rowStep + Math.min(i, remainder);
    const switchW = Math.max(100, Math.floor(ctx.areaRight - cmdLeft - maxCluster - catScrollW));
    const color = m.detected === "on" ? "#55ff55" : m.detected === "abnormal" ? "#ff5555" : "#ffffff";
    pvBtn(surface, cmdLeft, y, switchW, Math.max(10, itemH - tun("AbstractCommandTab.ITEM_VERTICAL_PAD")), m.name + (m.detected === "on" ? "（已开机）" : m.detected === "abnormal" ? "（异常）" : "（已关机）"), () => {
      m.detected = m.detected === "on" ? "off" : "on"; toast("模拟切换机器：" + m.name); renderPreview();
    }, { color, onRight: () => pvPush({ type: "machineEditor", title: tr("screen.command-gui.machine.edit_machine", "编辑机器"), parent: s, machine: m, config: true }) });
    const gap = tun(`${prefix}.CLUSTER_GAP`);
    const modesW = tun(`${prefix}.MODES_BTN_W`);
    const refreshW = tun(`${prefix}.REFRESH_BTN_W`);
    const right = ctx.areaRight;
    pvBtn(surface, right - modesW - refreshW - gap, y, modesW, Math.max(10, itemH - 2), tr("screen.command-gui.machine.modes_short_btn", "模式"), () => pvPush({ type: "machineModes", title: tr("screen.command-gui.machine.modes_title", "模式选择"), parent: s, machine: m }));
    pvBtn(surface, right - refreshW, y, refreshW, Math.max(10, itemH - 2), tr("screen.command-gui.machine.refresh_short", "检测"), () => { toast("模拟刷新检测：" + m.name); });
  }
  main.max = maxScroll; main.viewport = maxRows; main.content = machines.length;

  pvInput(surface, cmdLeft, ctx.closeBtnY, Math.max(60, ctx.areaRight - cmdLeft - maxCluster - catScrollW), 20, s.search || "", { hint: tr("screen.command-gui.search_hint", "搜索机器…") });
  const modesX = ctx.areaRight - maxCluster;
  pvBtn(surface, modesX, ctx.closeBtnY, 48, 20, tr("screen.command-gui.machine.add_machine", "+机器"), () => pvPush({ type: "machineEditor", title: tr("screen.command-gui.machine.add_machine", "新增机器"), parent: s, machine: null, config: true }));
  pvMarkCheckbox(surface, catX, ctx.closeBtnY - 8, 112, 14, tr("screen.command-gui.machine.filter_on", "显示已开机机器"), !!s.filterOn, () => { s.filterOn = !s.filterOn; renderPreview(); });
  pvMarkCheckbox(surface, catX, ctx.closeBtnY + 6, 112, 14, tr("screen.command-gui.machine.filter_off", "显示已关机机器"), !!s.filterOff, () => { s.filterOff = !s.filterOff; renderPreview(); });
}

function renderFakeTab(s, surface, ctx) {
  const sidePad = tun("FakePlayerTab.PLAYER_LIST_SIDE_PAD");
  const minW = tun("FakePlayerTab.PLAYER_ITEM_MIN_WIDTH");
  const div = tun("FakePlayerTab.PLAYER_ITEM_WIDTH_DIVISOR") || 4;
  const itemH = tun("FakePlayerTab.PLAYER_ITEM_HEIGHT");
  const reserve = tun("FakePlayerTab.LIST_BOTTOM_RESERVE");
  const gap = tun("FakePlayerTab.SEPARATOR_GAP");
  const listW = sidePad + Math.max(minW, Math.floor(ctx.areaW / div));
  const listX = ctx.areaLeft;
  const sepX = listX + listW + gap;
  const panelX = sepX + gap;
  pvSep(surface, sepX, ctx.areaTop, 1, ctx.areaH).el.style.background = "#555555";
  const main = s.mainScroll || (s.mainScroll = { offset: 0 });
  const maxRows = Math.max(1, Math.floor((ctx.areaH - reserve) / itemH));
  const maxScroll = Math.max(0, PV.data.fakePlayers.length - maxRows);
  main.offset = Math.min(main.offset, maxScroll);
  const box = tun("FakePlayerTab.CHECKBOX_SIZE");
  const btnX = listX + tun("FakePlayerTab.CHECKBOX_X_OFFSET") + box + tun("FakePlayerTab.FACE_PAD_LEFT");
  for (let i = 0; i < Math.min(PV.data.fakePlayers.length - main.offset, maxRows); i++) {
    const name = PV.data.fakePlayers[main.offset + i];
    const y = ctx.areaTop + i * itemH;
    pvBtn(surface, listX + tun("FakePlayerTab.CHECKBOX_X_OFFSET"), y, box, box, "", () => { toast("模拟多选：" + name); });
    pvBtn(surface, btnX, y, listW - 18, itemH - 2, name, () => { PV.data.selectedFake = name; s.panel = { offset: 0 }; renderPreview(); }, { sel: PV.data.selectedFake === name });
  }
  main.max = maxScroll; main.viewport = maxRows; main.content = PV.data.fakePlayers.length;
  pvAddScrollbar(surface, "fp", sepX - tun("FakePlayerTab.SCROLLBAR_WIDTH") - 2, ctx.areaTop, tun("FakePlayerTab.SCROLLBAR_WIDTH"), ctx.areaH, {
    get offset() { return main.offset; }, set offset(v) { main.offset = v; }, get max() { return maxScroll; }, viewport: maxRows, content: PV.data.fakePlayers.length,
  }, { onChange: (off) => { main.offset = off; renderPreview(); } });

  const actionH = tun("FakePlayerTab.ACTION_BUTTON_HEIGHT");
  const panelW = Math.max(80, ctx.areaRight - panelX - tun("FakePlayerTab.PANEL_RIGHT_PAD"));
  const refW = tun("FakePlayerTab.PANEL_WIDTH_REFERENCE");
  const actionW = Math.min(refW, Math.floor(panelW / 2));
  const panel = s.panel || (s.panel = { offset: 0 });
  const actions = ["stop", "kill", "attack continuous", "attack once", "use continuous", "use once", "jump continuous", "sneak", "sprint", "drop", "dropStack"];
  const keys = [
    "screen.command-gui.fakeplayer.action.stop", "screen.command-gui.fakeplayer.action.kill",
    "screen.command-gui.fakeplayer.action.attack", "screen.command-gui.fakeplayer.action.attack_once",
    "screen.command-gui.fakeplayer.action.use", "screen.command-gui.fakeplayer.action.use_once",
    "screen.command-gui.fakeplayer.action.jump", "screen.command-gui.fakeplayer.action.sneak",
    "screen.command-gui.fakeplayer.action.sprint", "screen.command-gui.fakeplayer.action.drop",
    "screen.command-gui.fakeplayer.action.dropstack",
  ];
  const fallback = ["停止", "移除", "持续攻击", "攻击一次", "持续使用", "使用一次", "持续跳跃", "潜行", "疾跑", "丢弃", "丢弃整组"];
  const contentY = ctx.areaTop - panel.offset * tun("FakePlayerTab.PANEL_SCROLL_STEP");
  for (let i = 0; i < actions.length; i++) {
    const y = contentY + Math.floor(i / 2) * actionH;
    const x = panelX + (i % 2) * actionW;
    if (y + actionH > ctx.areaTop && y <= ctx.areaBottom) pvBtn(surface, x, y, actionW, actionH, tr(keys[i], fallback[i]), () => toast(`模拟执行假人动作: ${PV.data.selectedFake} ${actions[i]}`));
  }
  const rowsUsed = Math.floor((actions.length + 1) / 2);
  const intervalRowY = contentY + rowsUsed * actionH + 6;
  const intervalLabelW = 52, intervalFieldW = 56, gap4 = 4;
  const intervalFixedW = intervalLabelW + gap4 + intervalFieldW;
  const intervalBtnW = Math.max(70, Math.floor((panelW - intervalFixedW - 3 * gap4) / 2));
  if (intervalRowY + actionH <= ctx.areaBottom && intervalRowY >= ctx.areaTop) {
    pvLabel(surface, panelX, intervalRowY + 6, intervalLabelW, tr("screen.command-gui.fakeplayer.interval_label", "间隔tick"), "#aaaaaa");
    pvInput(surface, panelX + intervalLabelW + gap4, intervalRowY, intervalFieldW, actionH, "20", {});
    const bx = panelX + intervalLabelW + gap4 + intervalFieldW + gap4;
    pvBtn(surface, bx, intervalRowY, intervalBtnW, actionH, tr("screen.command-gui.fakeplayer.action.attack_interval", "攻击间隔"), () => toast("模拟攻击间隔"));
    pvBtn(surface, bx + intervalBtnW + gap4, intervalRowY, intervalBtnW, actionH, tr("screen.command-gui.fakeplayer.action.use_interval", "使用间隔"), () => toast("模拟使用间隔"));
  }
  const timedKillY = intervalRowY + actionH + 6;
  if (timedKillY + actionH <= ctx.areaBottom && timedKillY >= ctx.areaTop) {
    pvBtn(surface, panelX, timedKillY, actionW * 2, actionH, tr("screen.command-gui.fakeplayer.timed.kill.short", "x 定时移除"), () => pvPush({ type: "timedKill", title: tr("screen.command-gui.fakeplayer.timed.kill.title", "定时移除"), parent: s }));
  }
  const panelRows = rowsUsed + 2;
  pvAddScrollbar(surface, "panel", ctx.areaRight + 8, ctx.areaTop, tun("FakePlayerTab.SCROLLBAR_WIDTH"), ctx.areaH, {
    get offset() { return panel.offset; }, set offset(v) { panel.offset = v; }, get max() { return Math.max(0, panelRows - Math.floor(ctx.areaH / actionH)); }, viewport: Math.max(1, Math.floor(ctx.areaH / actionH)), content: Math.max(1, panelRows),
  }, { onChange: (off) => { panel.offset = off; renderPreview(); } });

  const fakeSearchW = Math.max(60, ctx.areaRight - (ctx.areaLeft + tun("CustomCommandTab.SIDEBAR_OFFSET") + Math.max(tun("CustomCommandTab.CATEGORY_MIN_WIDTH"), Math.floor(ctx.areaW / tun("CustomCommandTab.CATEGORY_WIDTH_DIVISOR"))) + tun("AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH") + tun("AbstractCommandTab.CATEGORY_COMMAND_GAP")) - tun("CustomCommandTab.MAX_CLUSTER_WIDTH") - tun("AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH"));
  pvInput(surface, ctx.areaLeft + tun("CustomCommandTab.SIDEBAR_OFFSET") + Math.max(tun("CustomCommandTab.CATEGORY_MIN_WIDTH"), ctx.areaW / tun("CustomCommandTab.CATEGORY_WIDTH_DIVISOR")) + tun("AbstractCommandTab.CATEGORY_SCROLLBAR_WIDTH") + tun("AbstractCommandTab.CATEGORY_COMMAND_GAP"), ctx.closeBtnY, fakeSearchW, 20, s.search || "", { hint: tr("screen.command-gui.search_hint", "搜索假人…") });
  pvBtn(surface, ctx.areaLeft, ctx.closeBtnY, 76, 20, tr("screen.command-gui.fakeplayer.batch.title", "批量生成假人"), () => pvPush({ type: "batchSpawn", title: tr("screen.command-gui.fakeplayer.batch.title", "批量生成假人"), parent: s }));
  pvBtn(surface, ctx.areaRight - tun("MachineSwitchTab.MAX_CLUSTER_WIDTH"), ctx.closeBtnY, 48, 20, tr("screen.command-gui.fakeplayer.timed.spawn.short", "定时添加"), () => pvPush({ type: "timedSpawn", title: tr("screen.command-gui.fakeplayer.timed.spawn.title", "定时生成"), parent: s }));
  pvBtn(surface, listX, ctx.areaBottom - 32, listW, 16, tr("screen.command-gui.fakeplayer.killall", "移除全部"), () => pvPush({ type: "confirm", title: tr("screen.command-gui.fakeplayer.killall.confirm_title", "确认移除全部假人"), parent: s, message: tr("screen.command-gui.fakeplayer.killall.confirm_message", "该操作不可撤销。"), onConfirm: () => { PV.data.fakePlayers = []; pvBack(); } }));
  pvBtn(surface, listX, ctx.areaBottom - 16, listW, 16, tr("screen.command-gui.fakeplayer.remove_selected", "移除所选"), () => {});
}

function renderPresetTab(s, surface, ctx) {
  const itemH = tun("AbstractCommandTab.ITEM_HEIGHT");
  const cols = tun("AbstractCommandTab.COLUMNS");
  const cmds = PV.data.commands.filter((c) => c.cat === "default").concat(PV.data.commands.filter((c) => c.cat !== "default"));
  const main = s.mainScroll || (s.mainScroll = { offset: 0 });
  const maxRows = Math.max(1, Math.floor(ctx.areaH / itemH));
  const maxScroll = Math.max(0, Math.ceil(cmds.length / cols) - maxRows);
  main.offset = Math.min(main.offset, maxScroll);
  const groupW = Math.max(20, Math.floor((ctx.areaW - tun("AbstractCommandTab.COLUMN_GAP") * (cols - 1)) / cols));
  for (let i = 0; i < Math.min(cmds.length - main.offset * cols, maxRows * cols); i++) {
    const idx = main.offset * cols + i, cmd = cmds[idx];
    const x = ctx.areaLeft + (i % cols) * (groupW + tun("AbstractCommandTab.COLUMN_GAP")) + 2;
    const y = ctx.areaTop + Math.floor(i / cols) * itemH + 1;
    pvBtn(surface, x, y, groupW - tun("AbstractCommandTab.COLUMN_GAP"), itemH - tun("AbstractCommandTab.ITEM_VERTICAL_PAD"), cmd.name, () => pvExecuteCommand(cmd), { tooltip: cmd.cmd });
  }
  main.max = maxScroll; main.viewport = maxRows; main.content = Math.max(1, Math.ceil(cmds.length / cols));
  const searchW = tun("CommandGUIScreen.SEARCH_WIDTH");
  const groupWf = searchW + 2 + 20 + 2 + 20;
  const sx = ctx.W / 2 - groupWf / 2;
  pvInput(surface, sx, ctx.closeBtnY, searchW, 20, "", { hint: tr("screen.command-gui.search_hint", "搜索…") });
  pvBtn(surface, sx + searchW + 2, ctx.closeBtnY, 20, 20, "+", () => pvPush({ type: "addCommand", title: tr("screen.command-gui.add_title", "添加快捷指令"), parent: s, catId: "default" }));
}

/* ================= 屏幕分发 ================= */
function pvRenderScreen(s, surface) {
  switch (s.type) {
    case "main": return renderMainScreen(s, surface);
    case "settings": return renderSettingsScreen(s, surface);
    case "confirm": return renderConfirmScreen(s, surface);
    case "unsaved": return renderUnsavedScreen(s, surface);
    case "addCategory": return renderAddCategoryScreen(s, surface);
    case "editCategory": return renderEditCategoryScreen(s, surface);
    case "selectCategory": return renderSelectCategoryScreen(s, surface);
    case "moveCategory": return renderMoveCategoryScreen(s, surface);
    case "addMachineCategory": return renderMachineCategoryScreen(s, surface, true);
    case "editMachineCategory": return renderMachineCategoryScreen(s, surface, false);
    case "addCommand": return renderAddCommandScreen(s, surface);
    case "botSelect": return renderBotSelectScreen(s, surface);
    case "numberInput": return renderNumberInputScreen(s, surface);
    case "spawnOption": return renderSpawnOptionScreen(s, surface);
    case "actionOption": return renderActionOptionScreen(s, surface);
    case "batchSpawn": return renderBatchSpawnScreen(s, surface);
    case "timedSpawn": return renderTimedSpawnScreen(s, surface);
    case "timedKill": return renderTimedKillScreen(s, surface);
    case "machineEditor": return renderMachineEditorScreen(s, surface);
    case "machineModes": return renderMachineModesScreen(s, surface);
    case "modesEditor": return renderModesEditorScreen(s, surface);
    case "modeEditor": return renderModeEditorScreen(s, surface);
    case "stepEditor": return renderStepEditorScreen(s, surface);
    case "timelineEditor": return renderTimelineEditorScreen(s, surface);
    case "detection": return renderDetectionScreen(s, surface);
    default: pvLabel(surface, 10, 10, 200, "未实现的预览页: " + s.type);
  }
}

function renderDesktop(surface) {
  const el = document.createElement("div");
  el.className = "pv-desktop";
  el.innerHTML = `<div class="fake-hud"><span>生命 ❤❤❤❤❤❤❤❤❤❤</span><span style="margin-left:auto">FPS 120</span></div>
    <div style="font-size:22px">Command-GUI 预览器</div>
    <div class="muted" style="color:#cde">按 C 键可打开 Command-GUI（模拟游戏画面）</div>`;
  const btn = document.createElement("button");
  btn.textContent = "打开 Command-GUI";
  btn.onclick = () => pvPush({ type: "main", title: tr("screen.command-gui.title", "Command-GUI"), tab: "custom", mainScroll: { offset: 0 } });
  el.appendChild(btn);
  if (PV.lastExec) {
    const p = document.createElement("div");
    p.style.color = "#aef";
    p.textContent = "上次模拟执行: " + PV.lastExec.cmd.split("\n")[0];
    el.appendChild(p);
  }
  surface.appendChild(el);
}

function renderConfirmScreen(s, surface) {
  const cx = PV.W / 2, cy = PV.H / 2;
  pvTitle(surface, s.title, cy - 55);
  const lines = (s.message || "").split("\n");
  const lineStart = cy - 30 - lines.length * 5;
  lines.forEach((line, i) => pvLabel(surface, 0, lineStart + i * 10, PV.W, line, "#aaaaaa").el.style.textAlign = "center");
  const bw = 100, gap = 8;
  const startX = cx - bw - gap / 2;
  pvBtn(surface, startX, cy + 30, bw, 20, s.confirmLabel || tr("screen.command-gui.confirm", "确认"), () => { if (s.onConfirm) s.onConfirm(); else pvBack(); });
  pvBtn(surface, startX + bw + gap, cy + 30, bw, 20, s.cancelLabel || tr("screen.command-gui.cancel", "取消"), () => { if (s.onCancel) s.onCancel(); else pvBack(); });
}

function renderUnsavedScreen(s, surface) {
  const cx = PV.W / 2, cy = PV.H / 2;
  pvTitle(surface, s.title || tr("screen.command-gui.unsaved.title", "未保存"), cy - 55);
  pvLabel(surface, 0, cy - 20, PV.W, s.message || tr("screen.command-gui.unsaved.message", "有未保存的更改"), "#aaaaaa").el.style.textAlign = "center";
  const bw = 96, gap = 6, total = bw * 3 + gap * 2, sx = cx - total / 2;
  pvBtn(surface, sx, cy + 30, bw, 20, tr("screen.command-gui.save_exit", "保存并退出"), () => { if (s.onSave) s.onSave(); else pvBack(); });
  pvBtn(surface, sx + bw + gap, cy + 30, bw, 20, tr("screen.command-gui.discard", "放弃修改"), () => { if (s.onDiscard) s.onDiscard(); else { PV.stack.pop(); pvBack(); } });
  pvBtn(surface, sx + (bw + gap) * 2, cy + 30, bw, 20, tr("screen.command-gui.cancel", "取消"), () => pvBack());
}

function renderAddCategoryScreen(s, surface) {
  const cx = PV.W / 2, cy = PV.H / 2;
  pvTitle(surface, s.title, cy - 55);
  pvLabel(surface, 0, cy - 40, PV.W, tr("screen.command-gui.add_category_desc", "输入新分类名称"), "#888").el.style.textAlign = "center";
  pvLabel(surface, cx - 100, cy - 32, 200, tr("screen.command-gui.category_name", "分类名称"), "#aaaaaa");
  pvInput(surface, cx - 100, cy - 20, 200, 20, s.name || "", { hint: tr("screen.command-gui.category_name_hint", "例如：农场") });
  pvBtn(surface, cx - 102, cy + 15, 100, 20, tr("screen.command-gui.save", "保存"), () => { PV.data.categories.push({ id: "c" + Date.now(), name: "新分类" }); pvBack(); renderPreview(); });
  pvBtn(surface, cx + 2, cy + 15, 100, 20, tr("screen.command-gui.cancel", "取消"), () => pvBack());
  pvLabel(surface, 0, cy + 45, PV.W, tr("screen.command-gui.enter_to_save", "回车保存"), "#888").el.style.textAlign = "center";
}

function renderEditCategoryScreen(s, surface) {
  const cx = PV.W / 2, cy = PV.H / 2;
  pvTitle(surface, s.title, cy - 55);
  pvLabel(surface, cx - 100, cy - 32, 200, tr("screen.command-gui.category_name", "分类名称"), "#aaaaaa");
  pvInput(surface, cx - 100, cy - 20, 200, 20, s.category?.name || "", {});
  const startX = cx - 154;
  pvBtn(surface, startX, cy + 15, 100, 20, tr("screen.command-gui.save", "保存"), () => { if (s.category) s.category.name = "已重命名"; pvBack(); });
  pvBtn(surface, startX + 104, cy + 15, 100, 20, tr("screen.command-gui.delete_category", "删除分类"), () => pvPush({ type: "confirm", title: tr("screen.command-gui.delete_category_confirm_title", "确认删除分类"), parent: s, message: tr("screen.command-gui.delete_category_confirm_message", "删除后不可恢复。"), onConfirm: () => { PV.data.categories = PV.data.categories.filter((c) => c.id !== s.category.id); PV.stack.pop(); pvBack(); } }));
  pvBtn(surface, startX + 208, cy + 15, 100, 20, tr("screen.command-gui.cancel", "取消"), () => pvBack());
  pvLabel(surface, 0, cy + 45, PV.W, tr("screen.command-gui.enter_to_save", "回车保存"), "#888").el.style.textAlign = "center";
}

function renderSelectCategoryScreen(s, surface) {
  pvTitle(surface, s.title, 12);
  const availW = PV.W - 20, cols = Math.max(1, Math.floor((availW + 4) / 104));
  const totalW = cols * 100 + (cols - 1) * 4, startX = (PV.W - totalW) / 2;
  let col = 0, row = 0;
  PV.data.categories.forEach((cat) => {
    const x = startX + col * 104, y = 30 + row * 24;
    pvDark(surface, x, y, 100, 20, cat.name, () => { s.selected = cat.id; }, { sel: (s.selected || s.current) === cat.id });
    if (++col >= cols) { col = 0; row++; }
  });
  const by = 30 + (row + (col > 0 ? 1 : 0)) * 24 + 10;
  pvBtn(surface, PV.W / 2 - 104, by, 100, 20, tr("screen.command-gui.confirm", "确认"), () => { if (s.onSelected) s.onSelected(s.selected || s.current); pvBack(); });
  pvBtn(surface, PV.W / 2 + 4, by, 100, 20, tr("screen.command-gui.cancel", "取消"), () => pvBack());
}

function renderMoveCategoryScreen(s, surface) {
  pvTitle(surface, s.title || tr("screen.command-gui.move_category_title", "移动分类"), 12);
  let y = 30;
  PV.data.categories.forEach((cat) => {
    pvDark(surface, (PV.W - 100) / 2, y, 100, 20, cat.name, () => { if (s.onSelected) s.onSelected(cat.id); pvBack(); }, { sel: s.current === cat.id });
    y += 24;
  });
  pvBtn(surface, (PV.W - 100) / 2, y + 10, 100, 20, tr("screen.command-gui.cancel", "取消"), () => pvBack());
}

function renderMachineCategoryScreen(s, surface, isAdd) {
  const cx = PV.W / 2, cy = PV.H / 2;
  pvTitle(surface, s.title, cy - 55);
  pvLabel(surface, cx - 100, cy - 32, 200, tr("screen.command-gui.category_name", "分类名称"), "#aaaaaa");
  pvInput(surface, cx - 100, cy - 20, 200, 20, isAdd ? "" : (s.category || ""), {});
  pvBtn(surface, cx - 102, cy + 15, 100, 20, tr("screen.command-gui.save", "保存"), () => pvBack());
  pvBtn(surface, cx + 2, cy + 15, 100, 20, tr("screen.command-gui.cancel", "取消"), () => pvBack());
}

function renderSettingsScreen(s, surface) {
  const W = PV.W, H = PV.H;
  const titleX = tun("SettingsScreen.TITLE_X"), titleY = tun("SettingsScreen.TITLE_Y");
  pvLabel(surface, titleX, titleY, 300, tr("screen.command-gui.settings.title", "设置"), "#ffffff");
  const tabY = tun("SettingsScreen.TAB_Y"), tabH = tun("SettingsScreen.TAB_HEIGHT"), tabGap = tun("SettingsScreen.TAB_GAP");
  const tabW = Math.max(72, (W - titleX - tun("SettingsScreen.RIGHT_MARGIN") - 2 * tabGap) / 3);
  ["screen.command-gui.settings.section_quick", "screen.command-gui.settings.section_fake", "screen.command-gui.settings.section_machine"].forEach((key, i) => {
    pvDark(surface, titleX + i * (tabW + tabGap), tabY, tabW, tabH, tr(key, ["快捷指令", "假人", "机器开关"][i]), () => { s.section = i; renderPreview(); }, { sel: (s.section || 0) === i });
  });
  const contentTop = tun("SettingsScreen.CONTENT_TOP");
  const rows = [
    [tr("screen.command-gui.settings.keep_open", "执行后保持打开"), "quick_command_keep_open_default"],
    [tr("screen.command-gui.settings.remember_view", "记住视图"), "quick_command_remember_view"],
    [tr("screen.command-gui.settings.keep_open", "执行后保持打开"), "fakeplayer_keep_open_default"],
    [tr("screen.command-gui.settings.remember_fakeplayer_view", "记住假人页视图"), "fakeplayer_remember_view"],
  ];
  rows.forEach((row, i) => {
    const y = contentTop + i * tun("SettingsScreen.ROW_HEIGHT");
    pvLabel(surface, titleX + 2, y + 5, 260, row[0], "#e0e0e0");
    const bw = tun("SettingsScreen.SETTING_BUTTON_WIDTH"), bh = tun("SettingsScreen.SETTING_BUTTON_HEIGHT");
    pvBtn(surface, titleX + (W - titleX - tun("SettingsScreen.RIGHT_MARGIN") - tun("SettingsScreen.SCROLLBAR_WIDTH") - tun("SettingsScreen.CONTENT_TOP_GAP") - bw), y, bw, bh, s.toggles?.[row[1]] ? "是" : "否", () => { s.toggles = s.toggles || {}; s.toggles[row[1]] = !s.toggles[row[1]]; renderPreview(); });
  });
  const sw = tun("SettingsScreen.SCROLLBAR_WIDTH"), rm = tun("SettingsScreen.RIGHT_MARGIN");
  pvAddScrollbar(surface, "settings", W - rm - sw, contentTop, sw, Math.max(20, H - contentTop - titleX), { offset: 0, max: 0, viewport: 1, content: 1 });
  }

/* ================= AddCommandScreen ================= */
function renderAddCommandScreen(s, surface) {
  const W = PV.W, H = PV.H;
  const fieldX = (W - 360) / 2;
  const fake = !!s.fake;
  pvTitle(surface, s.title + (s.dirty ? " *" : ""), 4);
  pvLabel(surface, fieldX, 14, 90, tr("screen.command-gui.name", "名称"), "#aaaaaa");
  pvLabel(surface, fieldX + 98, 14, 120, tr("screen.command-gui.description", "描述"), "#aaaaaa");
  if (fake) pvLabel(surface, fieldX + 212, 14, 120, tr("screen.command-gui.shortcut", "快捷键"), "#aaaaaa");
  else pvLabel(surface, fieldX + 360 - 42 - 4 - 100, 52, 120, tr("screen.command-gui.shortcut", "快捷键"), "#aaaaaa");

  pvInput(surface, fieldX, 26, 94, 20, s.name || "", { hint: tr("screen.command-gui.name_hint", "名称") });
  const descW = fake ? 110 : 262;
  pvInput(surface, fieldX + 98, 26, descW, 20, s.desc || "", { hint: tr("screen.command-gui.description_hint", "描述") });
  if (fake) {
    pvBtn(surface, fieldX + 212, 26, 100, 20, s.shortcut || tr("screen.command-gui.shortcut.none", "未设置(点击录入)"), () => {});
    pvBtn(surface, fieldX + 316, 26, 42, 20, tr("screen.command-gui.shortcut.reset", "重置"), () => {});
  }

  if (fake) {
    pvLabel(surface, fieldX, 52, 90, tr("screen.command-gui.machine.step_bot", "假人"), "#aaaaaa");
    pvInput(surface, fieldX, 64, 110, 20, s.bot || "", {});
    pvBtn(surface, fieldX + 116, 64, 45, 20, tr("screen.command-gui.machine.step_pick", "选择"), () => pvPush({ type: "botSelect", title: tr("screen.command-gui.machine.bot_select_title", "选择假人"), parent: s }));
  }
  const labelDelayX = fake ? fieldX + 360 - 45 - 4 - 110 : fieldX;
  pvLabel(surface, labelDelayX, 52, 90, tr("screen.command-gui.machine.step_delay_label", "延迟 tick"), "#aaaaaa");
  if (fake) {
    pvInput(surface, fieldX + 360 - 45 - 4 - 110, 64, 110, 20, "1", {});
    pvBtn(surface, fieldX + 360 - 45, 64, 45, 20, tr("screen.command-gui.machine.step_pick", "选择"), () => pvPush({ type: "numberInput", title: tr("screen.command-gui.command_delay_short", "延迟"), parent: s }));
  } else {
    pvInput(surface, fieldX, 64, 110, 20, "1", {});
    pvBtn(surface, fieldX + 114, 64, 45, 20, tr("screen.command-gui.machine.step_pick", "选择"), () => pvPush({ type: "numberInput", title: tr("screen.command-gui.command_delay_short", "延迟"), parent: s }));
    const shortcutX = fieldX + 360 - 42 - 4 - 100;
    pvBtn(surface, shortcutX, 64, 100, 20, s.shortcut || tr("screen.command-gui.shortcut.none", "未设置(点击录入)"), () => {});
    pvBtn(surface, shortcutX + 104, 64, 42, 20, tr("screen.command-gui.shortcut.reset", "重置"), () => {});
  }

  if (fake) {
    const base = 56, gap = 4;
    const labels = [tr("screen.command-gui.machine.spawn_short", "生成"), tr("screen.command-gui.machine.kill_short", "移除"), tr("screen.command-gui.machine.step_attack_use", "攻击/使用"), tr("screen.command-gui.machine.step_sneak", "潜行"), tr("screen.command-gui.machine.step_mount", "骑乘"), tr("screen.command-gui.machine.step_stop", "停止")];
    let x = fieldX;
    labels.forEach((label, i) => {
      const w = i < 4 ? 57 : 56;
      const action = i === 0 ? () => pvPush({ type: "spawnOption", title: tr("screen.command-gui.machine.spawn_title", "生成假人"), parent: s })
        : i === 2 ? () => pvPush({ type: "actionOption", title: tr("screen.command-gui.machine.action_title", "攻击/使用"), parent: s })
        : () => toast("模拟插入: " + label);
      pvBtn(surface, x, 92, w, 20, label, action);
      x += w + gap;
    });
  } else {
    const phKeys = ["screen.command-gui.type.player_all_full", "screen.command-gui.type.player_other_full", "screen.command-gui.type.player_fake_full", "screen.command-gui.type.text_full", "screen.command-gui.type.number_full", "screen.command-gui.type.time_full", "screen.command-gui.type.coord_full"];
    const placeholders = ["{player_all}", "{player}", "{player_fake}", "{name}", "{number}", "{time}", "{coords}"];
    const phLabels = ["玩家全部", "其他玩家", "假人", "文本输入", "数字输入", "时间输入", "坐标输入"];
    placeholders.forEach((ph, i) => pvBtn(surface, fieldX + i * 52, 92, 48, 20, tr(phKeys[i], phLabels[i]), () => toast("模拟插入占位符: " + ph), { tooltip: ph }));
  }

  pvLabel(surface, fieldX, 116, 200, tr("screen.command-gui.machine.command_input", "命令行"), "#aaaaaa");
  pvInput(surface, fieldX, 128, 280, 20, s.current || "", {});
  pvBtn(surface, fieldX + 286, 128, 74, 20, tr("screen.command-gui.add_command_line", "加入列表"), () => { s.list = s.list || []; s.list.push((s.current || "/say hello").trim()); s.current = ""; renderPreview(); });

  pvLabel(surface, fieldX, 158, 160, tr("screen.command-gui.commands_label", "命令列表"), "#aaaaaa");
  pvLabel(surface, fieldX + 110, 158, 140, tr("screen.command-gui.machine.cmd_legend_green", "绿=合法"), "#55ff55");
  pvLabel(surface, fieldX + 220, 158, 120, tr("screen.command-gui.machine.cmd_legend_red", "红=非法"), "#ff5555");

  const list = s.list || [];
  const scroll = s.listScroll || (s.listScroll = { offset: 0 });
  const maxRows = Math.max(1, Math.floor((H - 24 - 4 - 170) / 12));
  const maxScroll = Math.max(0, list.length - maxRows);
  scroll.offset = Math.min(scroll.offset, maxScroll);
  for (let i = 0; i < Math.min(list.length - scroll.offset, maxRows); i++) {
    const idx = scroll.offset + i, y = 170 + i * 12;
    pvLabel(surface, fieldX + 4, y, 16, "#" + (idx + 1), "#aaaaaa");
    pvLabel(surface, fieldX + 22, y, 155, list[idx] || "", "#55ff55");
    const removeX = fieldX + 360 - 16 - 30, copyX = removeX - 31, downX = copyX - 49, upX = downX - 49;
    pvBtn(surface, upX, y, 48, 12, tr("screen.command-gui.step_up_short", "上移"), () => { if (idx > 0) { [list[idx], list[idx - 1]] = [list[idx - 1], list[idx]]; renderPreview(); } });
    pvBtn(surface, downX, y, 48, 12, tr("screen.command-gui.step_down_short", "下移"), () => { if (idx < list.length - 1) { [list[idx], list[idx + 1]] = [list[idx + 1], list[idx]]; renderPreview(); } });
    pvBtn(surface, copyX, y, 30, 12, tr("screen.command-gui.step_copy_short", "复制"), () => toast("模拟复制"));
    pvBtn(surface, removeX, y, 30, 12, tr("screen.command-gui.delete", "删除"), () => { list.splice(idx, 1); renderPreview(); });
  }
  const sbX = fieldX + 360 - 12;
  pvAddScrollbar(surface, "cmdlist", sbX, 170, 12, (H - 24 - 4) - 170, {
    get offset() { return scroll.offset; }, set offset(v) { scroll.offset = v; }, get max() { return maxScroll; }, viewport: maxRows, content: Math.max(1, list.length),
  }, { onChange: (off) => { scroll.offset = off; renderPreview(); } });

  const barY = H - 24;
  if (s.editing) {
    const bw = 76, gap = 4, sx = fieldX + (360 - bw * 4 - gap * 3) / 2;
    pvBtn(surface, sx, barY, bw, 20, tr("screen.command-gui.save", "保存"), () => { s.editing.cmd = (list.join("\n") || s.editing.cmd); PV.data.commands = PV.data.commands.map((c) => c === s.editing ? Object.assign(c, { name: s.name || c.name }) : c); PV.stack.pop(); pvBack(); });
    pvBtn(surface, sx + 80, barY, bw, 20, tr("screen.command-gui.action.move", "移动"), () => pvPush({ type: "moveCategory", title: tr("screen.command-gui.move_category_title", "移动分类"), parent: s, current: s.catId, onSelected: () => {} }));
    pvBtn(surface, sx + 160, barY, bw, 20, tr("screen.command-gui.action.delete", "删除"), () => pvPush({ type: "confirm", title: tr("screen.command-gui.delete_command_confirm_title", "确认删除命令"), parent: s, message: tr("screen.command-gui.delete_command_confirm_message", "删除后不可恢复。"), onConfirm: () => { PV.data.commands = PV.data.commands.filter((c) => c !== s.editing); PV.stack.pop(); pvBack(); } }));
    pvBtn(surface, sx + 240, barY, bw, 20, tr("screen.command-gui.cancel", "取消"), () => pvPush({ type: "unsaved", title: tr("screen.command-gui.unsaved.title", "未保存"), parent: s, message: tr("screen.command-gui.unsaved.message", "有未保存的更改"), onDiscard: () => { PV.stack.pop(); pvBack(); } }));
  } else {
    const multi = PV.data.categories.length > 1, total = multi ? 3 : 2, bw = multi ? 104 : 140, gap = 8;
    const sx = fieldX + (360 - bw * total - gap * (total - 1)) / 2;
    pvBtn(surface, sx, barY, bw, 20, tr("screen.command-gui.save", "保存"), () => { PV.data.commands.push({ cat: s.catId || "default", name: s.name || "新命令", desc: s.desc || "", cmd: list.join("\n") || "/say 新命令", multi: list.length > 1 }); PV.stack.pop(); pvBack(); });
    let nx = sx + bw + gap;
    if (multi) { pvBtn(surface, nx, barY, bw, 20, tr("screen.command-gui.save_to_category_short", "存到分类"), () => pvPush({ type: "selectCategory", title: tr("screen.command-gui.select_category_title", "选择分类"), parent: s, current: s.catId, onSelected: (id) => { s.catId = id; } })); nx += bw + gap; }
    pvBtn(surface, nx, barY, bw, 20, tr("screen.command-gui.cancel", "取消"), () => pvPush({ type: "unsaved", title: tr("screen.command-gui.unsaved.title", "未保存"), parent: s, message: tr("screen.command-gui.unsaved.message", "有未保存的更改"), onDiscard: () => { PV.stack.pop(); pvBack(); } }));
  }
}

/* ================= 小工具页 ================= */
function renderBotSelectScreen(s, surface) {
  pvTitle(surface, s.title, 4);
  const fx = (PV.W - 200) / 2;
  PV.data.fakePlayers.forEach((name, i) => pvBtn(surface, fx, 30 + i * 20, 200, 18, name, () => { s.onPick && s.onPick(name); pvBack(); }));
  pvBtn(surface, fx, PV.H - 22, 80, 18, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderNumberInputScreen(s, surface) {
  const cx = PV.W / 2, cy = PV.H / 2;
  pvTitle(surface, s.title, cy - 70);
  pvInput(surface, cx - 50, cy - 40, 100, 20, s.value || "", { hint: (s.min || 1) + " - " + (s.max || 72000) });
  const values = s.values || [1, 5, 10, 20, 40, 100, 200, 400, 600, 1200];
  const rows = Math.ceil(values.length / 5), totalW = Math.min(values.length, 5) * 54 - 4, sx = cx - totalW / 2, sy = cy - 5;
  values.forEach((v, i) => pvBtn(surface, sx + (i % 5) * 54, sy + Math.floor(i / 5) * 24, 50, 20, String(v), () => { if (s.onPick) s.onPick(v); pvBack(); }));
  pvBtn(surface, cx - 50, Math.min(cy + 10 + rows * 24, PV.H - 24), 100, 20, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderSpawnOptionScreen(s, surface) {
  pvTitle(surface, s.title, 4);
  const fx = (PV.W - 180) / 2;
  pvBtn(surface, fx, 58, 87, 16, tr("screen.command-gui.machine.spawn_own_feet", "脚下生成"), () => toast("模拟插入生成命令"));
  pvBtn(surface, fx + 91, 58, 87, 16, tr("screen.command-gui.machine.spawn_here", "此处生成"), () => toast("模拟插入生成命令"));
  [["X", fx, 94, 56], ["Y", fx + 62, 94, 56], ["Z", fx + 124, 94, 56], ["Yaw", fx, 130, 87], ["Pitch", fx + 93, 130, 87], ["LX", fx, 190, 56], ["LY", fx + 62, 190, 56], ["LZ", fx + 124, 190, 56]].forEach(([label, x, y, w]) => {
    pvLabel(surface, x, y - 12, 30, label, "#888");
    pvInput(surface, x, y, w, 16, "", {});
  });
  const bw = Math.min(70, 100), sx = fx + (180 - bw * 2 - 8) / 2, by = PV.H - 22;
  pvBtn(surface, sx, by, bw, 18, tr("screen.command-gui.save", "保存"), () => { toast("模拟保存生成选项"); pvBack(); });
  pvBtn(surface, sx + bw + 8, by, bw, 18, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderActionOptionScreen(s, surface) {
  pvTitle(surface, s.title, 4);
  const fx = (PV.W - 180) / 2;
  pvLabel(surface, fx, 22, 180, tr("screen.command-gui.machine.action_label", "动作"), "#aaaaaa");
  const actions = ["attack", "use"];
  actions.forEach((a, i) => pvDark(surface, fx + i * 92, 34, 88, 16, a, () => {}, { sel: i === 0 }));
  pvLabel(surface, fx, 58, 180, tr("screen.command-gui.machine.action_mode", "模式"), "#aaaaaa");
  const modes = ["once", "continuous", "interval"];
  modes.forEach((m, i) => pvDark(surface, fx + i * 61, 70, 57, 16, m, () => {}, { sel: i === 0 }));
  pvLabel(surface, fx, 94, 180, tr("screen.command-gui.machine.action_interval_label", "间隔"), "#aaaaaa");
  pvInput(surface, fx, 106, 80, 16, "20", {});
  pvLabel(surface, fx + 100, 94, 80, tr("screen.command-gui.machine.tick_hint", "tick"), "#888");
  const bw = Math.min(70, 100), sx = fx + (180 - bw * 2 - 8) / 2, by = PV.H - 22;
  pvBtn(surface, sx, by, bw, 18, tr("screen.command-gui.save", "保存"), () => { toast("模拟插入攻击/使用命令"); pvBack(); });
  pvBtn(surface, sx + bw + 8, by, bw, 18, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderBatchSpawnScreen(s, surface) {
  const cx = PV.W / 2;
  const english = !!s.english;
  const rows = english ? 2 : 4;
  const contentH = 24 + rows * 28 + 20 + 12 + 9;
  const top = Math.max(20, (PV.H - contentH) / 2 - 10);
  let y = top + 24;
  pvBtn(surface, cx - 75, y, 150, 20, english ? tr("screen.command-gui.fakeplayer.batch.type.english", "英文名") : tr("screen.command-gui.fakeplayer.batch.type.numbered", "编号名"), () => { s.english = !english; renderPreview(); });
  y += 28;
  if (!english) {
    pvInput(surface, cx - 75, y, 150, 20, s.prefix || "Bot_", {}); y += 28;
    pvInput(surface, cx - 75, y, 150, 20, s.start || "1", {}); y += 28;
  }
  pvInput(surface, cx - 75, y, 150, 20, s.count || "3", {}); y += 40;
  pvBtn(surface, cx - 102, y, 100, 20, tr("screen.command-gui.fakeplayer.batch.spawn", "生成"), () => { for (let i = 1; i <= 3; i++) PV.data.fakePlayers.push("bot_" + i); pvBack(); });
  pvBtn(surface, cx + 2, y, 100, 20, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderTimedSpawnScreen(s, surface) {
  const cx = PV.W / 2;
  const titleY = Math.max(15, (PV.H - 205) / 2 - 10);
  const nameFieldY = titleY + 34;
  const timeFieldY = nameFieldY + 45;
  const posToggleY = timeFieldY + 42;
  const coordFieldY = posToggleY + 28;
  const saveCancelY = coordFieldY + 36;
  pvTitle(surface, s.title, titleY);
  pvInput(surface, cx - 75, nameFieldY, 150, 20, s.playerName || "Bot_1", {});
  const totalTimeWidth = 163, timeStartX = cx - totalTimeWidth / 2;
  pvInput(surface, timeStartX, timeFieldY, 45, 20, s.hours || "0", {});
  pvInput(surface, timeStartX + 45 + 14, timeFieldY, 45, 20, s.minutes || "0", {});
  pvInput(surface, timeStartX + 118, timeFieldY, 45, 20, s.seconds || "10", {});
  pvLabel(surface, timeStartX + 22, timeFieldY - 12, 10, "H", "#888").el.style.textAlign = "center";
  pvLabel(surface, timeStartX + 45 + 14 + 22, timeFieldY - 12, 10, "M", "#888").el.style.textAlign = "center";
  pvLabel(surface, timeStartX + 118 + 22, timeFieldY - 12, 10, "S", "#888").el.style.textAlign = "center";
  pvBtn(surface, cx - 90, posToggleY, 180, 20, s.usePos === false ? tr("screen.command-gui.fakeplayer.timed.spawn.pos.custom", "自定义坐标") : tr("screen.command-gui.fakeplayer.timed.spawn.pos.current", "使用自己位置"), () => { s.usePos = !(s.usePos !== false); renderPreview(); });
  if (s.usePos === false) {
    const coordTotalWidth = 186, coordStartX = cx - coordTotalWidth / 2;
    pvInput(surface, coordStartX, coordFieldY, 58, 20, "0.0", {});
    pvInput(surface, coordStartX + 64, coordFieldY, 58, 20, "64.0", {});
    pvInput(surface, coordStartX + 128, coordFieldY, 58, 20, "0.0", {});
    pvLabel(surface, coordStartX + 29, coordFieldY - 10, 10, "X", "#888").el.style.textAlign = "center";
    pvLabel(surface, coordStartX + 93, coordFieldY - 10, 10, "Y", "#888").el.style.textAlign = "center";
    pvLabel(surface, coordStartX + 157, coordFieldY - 10, 10, "Z", "#888").el.style.textAlign = "center";
  }
  pvBtn(surface, cx - 102, saveCancelY, 100, 20, tr("screen.command-gui.save", "保存"), () => { toast("模拟创建定时生成"); pvBack(); });
  pvBtn(surface, cx + 2, saveCancelY, 100, 20, tr("screen.command-gui.cancel", "取消"), () => pvBack());
}

function renderTimedKillScreen(s, surface) {
  const cx = PV.W / 2, cy = PV.H / 2;
  const y = cy - 25;
  const totalTimeWidth = 163, timeStartX = cx - totalTimeWidth / 2;
  pvTitle(surface, s.title, cy - 78);
  pvLabel(surface, cx, cy - 62, 200, s.player || PV.data.selectedFake, "#55ff55").el.style.textAlign = "center";
  pvLabel(surface, cx, y - 24, 150, tr("screen.command-gui.fakeplayer.timed.time", "时间"), "#aaaaaa").el.style.textAlign = "center";
  pvInput(surface, timeStartX, y, 45, 20, "0", {});
  pvInput(surface, timeStartX + 59, y, 45, 20, "1", {});
  pvInput(surface, timeStartX + 118, y, 45, 20, "0", {});
  pvLabel(surface, timeStartX + 22, y - 12, 10, "H", "#888").el.style.textAlign = "center";
  pvLabel(surface, timeStartX + 81, y - 12, 10, "M", "#888").el.style.textAlign = "center";
  pvLabel(surface, timeStartX + 140, y - 12, 10, "S", "#888").el.style.textAlign = "center";
  pvLabel(surface, timeStartX + 50, y + 6, 10, ":", "#333").el.style.textAlign = "center";
  pvLabel(surface, timeStartX + 109, y + 6, 10, ":", "#333").el.style.textAlign = "center";
  pvBtn(surface, cx - 102, y + 52, 100, 20, tr("screen.command-gui.save", "保存"), () => { toast("模拟创建定时移除"); pvBack(); });
  pvBtn(surface, cx + 2, y + 52, 100, 20, tr("screen.command-gui.cancel", "取消"), () => pvBack());
}

/* ================= 机器编辑器 ================= */
function renderMachineEditorScreen(s, surface) {
  const W = PV.W, H = PV.H;
  const fx = (W - 300) / 2;
  const cfg = s.config !== false, rowGap = cfg ? 36 : 44;
  const fieldsEnd = cfg ? 4 : 3;
  const buttonsRowY = 32 + rowGap * fieldsEnd;
  pvTitle(surface, s.title + (s.dirty ? " *" : ""), 4);
  pvLabel(surface, fx, 20, 100, tr("screen.command-gui.machine.name", "名称"), "#aaaaaa");
  pvLabel(surface, fx + 154, 20, 100, tr("screen.command-gui.machine.category", "分类"), "#aaaaaa");
  pvInput(surface, fx, 32, 146, 20, s.machine?.name || "", {});
  pvInput(surface, fx + 154, 32, 146, 20, s.machine?.category || "", { hint: tr("screen.command-gui.machine.category_hint", "可选") });
  pvLabel(surface, fx, 32 + rowGap - 12, 200, tr("screen.command-gui.machine.description", "描述"), "#aaaaaa");
  pvInput(surface, fx, 32 + rowGap, 300, 20, s.machine?.desc || "", {});
  pvLabel(surface, fx, 32 + rowGap * 2 - 12, 200, tr("screen.command-gui.machine.bots", "Bots（逗号分隔）"), "#aaaaaa");
  pvLabel(surface, fx + 228, 32 + rowGap * 2 - 12, 90, tr("screen.command-gui.machine.switch_interval", "开关间隔(tick)"), "#aaaaaa");
  pvInput(surface, fx, 32 + rowGap * 2, 224, 20, "4550,45501,4550k9", { hint: tr("screen.command-gui.machine.bots_hint", "逗号分隔") });
  pvInput(surface, fx + 228, 32 + rowGap * 2, 72, 20, "200", {});
  if (cfg) {
    pvLabel(surface, fx, 32 + rowGap * 3 - 12, 90, tr("screen.command-gui.machine.permission", "权限等级(可选)"), "#aaaaaa");
    pvLabel(surface, fx + 68, 32 + rowGap * 3 - 12, 180, tr("screen.command-gui.machine.players", "允许的玩家(可选逗号分隔)"), "#aaaaaa");
    pvInput(surface, fx, 32 + rowGap * 3, 60, 20, "0", {});
    pvInput(surface, fx + 68, 32 + rowGap * 3, 232, 20, "", { hint: tr("screen.command-gui.machine.players_hint", "留空则按权限等级") });
  }
  const tw = 69, gap = 8;
  const btn = (label, action, i) => pvBtn(surface, fx + i * (tw + gap), buttonsRowY, tw, 18, label, action);
  btn(tr("screen.command-gui.machine.timeline_on", "开机(5步)"), () => pvPush({ type: "timelineEditor", title: tr("screen.command-gui.machine.mode_boot_title", "开机流程"), parent: s }), 0);
  btn(tr("screen.command-gui.machine.timeline_off", "关机(9步)"), () => pvPush({ type: "timelineEditor", title: tr("screen.command-gui.machine.mode_shutdown_title", "关机流程"), parent: s }), 1);
  btn(tr("screen.command-gui.machine.modes_short_btn", "模式(0个)"), () => pvPush({ type: "modesEditor", title: tr("screen.command-gui.machine.modes_editor_title", "模式列表"), parent: s, machine: s.machine }), 2);
  btn(tr("screen.command-gui.machine.detection", "开关机检测"), () => pvPush({ type: "detection", title: tr("screen.command-gui.machine.detection_title", "方块检测"), parent: s }), 3);
  const barY = H - 22, bw = Math.min(80, 100), count = s.machine ? 3 : 2, sx = fx + (300 - bw * count - 8 * (count - 1)) / 2;
  pvBtn(surface, sx, barY, bw, 18, tr("screen.command-gui.save", "保存"), () => { if (!s.machine) PV.data.machines.push({ name: "新机器", category: "", detected: "off", desc: "" }); pvBack(); });
  let bx = sx + bw + 8;
  if (s.machine) { pvBtn(surface, bx, barY, bw, 18, tr("screen.command-gui.delete", "删除"), () => pvPush({ type: "confirm", title: tr("screen.command-gui.machine.delete_confirm_title", "确认删除机器"), parent: s, message: "删除机器后不可恢复。", onConfirm: () => { PV.data.machines = PV.data.machines.filter((m) => m !== s.machine); PV.stack.pop(); pvBack(); } })); bx += bw + 8; }
  pvBtn(surface, bx, barY, bw, 18, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderModeEditorScreen(s, surface) {
  const W = PV.W, H = PV.H, fx = (W - 300) / 2;
  pvTitle(surface, s.title, 4);
  pvLabel(surface, fx, 36, 200, tr("screen.command-gui.machine.mode_name", "模式名称"), "#aaaaaa");
  pvInput(surface, fx, 48, 224, 20, s.mode?.name || "", {});
  const pw = 94, gap = 8;
  pvBtn(surface, fx, 90, pw, 18, tr("screen.command-gui.machine.mode_boot_title", "开机流程"), () => pvPush({ type: "timelineEditor", title: tr("screen.command-gui.machine.mode_boot_title", "开机流程"), parent: s }));
  pvBtn(surface, fx + 102, 90, pw, 18, tr("screen.command-gui.machine.mode_shutdown_title", "关机流程"), () => pvPush({ type: "timelineEditor", title: tr("screen.command-gui.machine.mode_shutdown_title", "关机流程"), parent: s }));
  pvBtn(surface, fx + 204, 90, pw, 18, tr("screen.command-gui.machine.detection", "检测"), () => pvPush({ type: "detection", title: tr("screen.command-gui.machine.detection_title", "方块检测"), parent: s }));
  const by = H - 22, bw = Math.min(70, 100), sx = fx + (300 - bw * 2 - 8) / 2;
  pvBtn(surface, sx, by, bw, 18, tr("screen.command-gui.save", "保存"), () => pvBack());
  pvBtn(surface, sx + bw + 8, by, bw, 18, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderModesEditorScreen(s, surface) {
  const W = PV.W, H = PV.H, listW = Math.min(400, W - 40), left = (W - listW) / 2, right = left + listW;
  pvTitle(surface, s.title, 6);
  const rows = PV.data.modes, scroll = s.scroll || (s.scroll = { offset: 0 });
  const visible = Math.max(1, Math.floor((H - 26 - 4 - 26) / 20));
  const maxScroll = Math.max(0, rows.length - visible);
  scroll.offset = Math.min(scroll.offset, maxScroll);
  for (let i = 0; i < Math.min(rows.length - scroll.offset, visible); i++) {
    const m = rows[scroll.offset + i], y = 26 + i * 20;
    const labelW = right - left - 110 - 16;
    pvBtn(surface, left, y, labelW, 18, m.name, () => pvPush({ type: "modeEditor", title: tr("screen.command-gui.machine.mode_edit_title", "编辑模式"), parent: s, mode: m }));
    pvDark(surface, left + labelW + 2, y, 48, 18, tr("screen.command-gui.machine.mode_single_label", "单选"), () => { m.single = !m.single; renderPreview(); }, { sel: m.single });
    pvBtn(surface, left + labelW + 51, y, 30, 18, tr("screen.command-gui.action.edit", "编辑"), () => pvPush({ type: "modeEditor", title: tr("screen.command-gui.machine.mode_edit_title", "编辑模式"), parent: s, mode: m }));
    pvBtn(surface, left + labelW + 82, y, 30, 18, tr("screen.command-gui.delete", "删除"), () => { PV.data.modes = PV.data.modes.filter((x) => x !== m); renderPreview(); });
  }
  const scrollX = right - 12, sh = H - 26 - 4 - 26;
  pvAddScrollbar(surface, "modes", scrollX, 26, 12, sh, { get offset() { return scroll.offset; }, set offset(v) { scroll.offset = v; }, get max() { return maxScroll; }, viewport: visible, content: rows.length }, { onChange: (off) => { scroll.offset = off; renderPreview(); } });
  const by = H - 26, addW = Math.min(80, listW / 4), saveW = Math.min(60, listW / 4), total = addW + 4 + saveW * 2, sx = right - total;
  pvBtn(surface, left, by, addW + 4 + saveW, 18, tr("screen.command-gui.machine.multi_mode_config", "多模式配置"), () => {});
  pvBtn(surface, sx, by, addW, 18, tr("screen.command-gui.machine.add_mode", "添加模式"), () => { PV.data.modes.push({ name: "新模式", single: false, detected: "off", steps: [1, 1] }); renderPreview(); });
  pvBtn(surface, sx + addW + 4, by, saveW, 18, tr("screen.command-gui.save", "保存"), () => pvBack());
  pvBtn(surface, sx + addW + 8 + saveW, by, saveW, 18, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderTimelineEditorScreen(s, surface) {
  const W = PV.W, H = PV.H, listW = Math.min(400, W - 40), left = (W - listW) / 2, right = left + listW;
  pvTitle(surface, s.title, 6);
  const steps = s.steps || PV.data.steps, scroll = s.scroll || (s.scroll = { offset: 0 });
  const visible = Math.max(1, Math.floor((H - 26 - 4 - 12 - 26) / 20));
  const maxScroll = Math.max(0, steps.length - visible);
  scroll.offset = Math.min(scroll.offset, maxScroll);
  for (let i = 0; i < Math.min(steps.length - scroll.offset, visible); i++) {
    const st = steps[scroll.offset + i], y = 26 + i * 20;
    const labelW = right - left - 159 - 18;
    pvBtn(surface, left, y, labelW, 18, st.delay ? "延迟条" : (st.desc || st.cmd), () => pvPush({ type: "stepEditor", title: tr("screen.command-gui.machine.step_edit_title", "编辑步骤"), parent: s, step: st }));
    const x0 = left + labelW + 2;
    pvBtn(surface, x0, y, 30, 18, tr("screen.command-gui.action.edit", "编辑"), () => pvPush({ type: "stepEditor", title: tr("screen.command-gui.machine.step_edit_title", "编辑步骤"), parent: s, step: st }));
    pvBtn(surface, x0 + 31, y, 48, 18, tr("screen.command-gui.step_up_short", "上移"), () => { const idx = steps.indexOf(st); if (idx > 0) { [steps[idx], steps[idx - 1]] = [steps[idx - 1], steps[idx]]; renderPreview(); } });
    pvBtn(surface, x0 + 80, y, 48, 18, tr("screen.command-gui.step_down_short", "下移"), () => { const idx = steps.indexOf(st); if (idx < steps.length - 1) { [steps[idx], steps[idx + 1]] = [steps[idx + 1], steps[idx]]; renderPreview(); } });
    pvBtn(surface, x0 + 129, y, 30, 18, tr("screen.command-gui.delete", "删除"), () => { steps.splice(steps.indexOf(st), 1); renderPreview(); });
  }
  const sh = H - 26 - 4 - 12 - 26;
  pvAddScrollbar(surface, "timeline", right - 12, 26, 12, sh, { get offset() { return scroll.offset; }, set offset(v) { scroll.offset = v; }, get max() { return maxScroll; }, viewport: visible, content: steps.length }, { onChange: (off) => { scroll.offset = off; renderPreview(); } });
  const by = H - 26, addW = Math.min(80, listW / 4), saveW = Math.min(60, listW / 4), sx = right - (addW + 4 + saveW * 2);
  pvBtn(surface, sx, by, addW, 18, tr("screen.command-gui.machine.add_step", "添加步骤"), () => { steps.push({ desc: "新步骤", cmd: "/say step", delay: false }); renderPreview(); });
  pvBtn(surface, sx + addW + 4, by, saveW, 18, tr("screen.command-gui.save", "保存"), () => pvBack());
  pvBtn(surface, sx + addW + 8 + saveW, by, saveW, 18, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderStepEditorScreen(s, surface) {
  const W = PV.W, H = PV.H, fx = (W - 360) / 2;
  pvTitle(surface, s.title, 4);
  pvLabel(surface, fx, 14, 200, tr("screen.command-gui.machine.step_description", "步骤描述"), "#aaaaaa");
  pvInput(surface, fx, 26, 360, 20, s.step?.desc || "", {});
  pvLabel(surface, fx, 52, 120, tr("screen.command-gui.machine.step_bot", "假人"), "#aaaaaa");
  pvInput(surface, fx, 64, 110, 20, s.bot || "", {});
  pvBtn(surface, fx + 116, 64, 45, 20, tr("screen.command-gui.machine.step_pick", "选择"), () => pvPush({ type: "botSelect", title: tr("screen.command-gui.machine.bot_select_title", "选择假人"), parent: s }));
  pvLabel(surface, fx + 360 - 45 - 4 - 110, 52, 100, tr("screen.command-gui.machine.step_delay_label", "延迟"), "#aaaaaa");
  pvInput(surface, fx + 360 - 45 - 4 - 110, 64, 110, 20, "20", {});
  pvBtn(surface, fx + 360 - 45, 64, 45, 20, tr("screen.command-gui.machine.step_pick", "选择"), () => pvPush({ type: "numberInput", title: tr("screen.command-gui.command_delay_short", "延迟"), parent: s }));
  pvLabel(surface, fx, 112, 200, tr("screen.command-gui.machine.command_input", "命令行"), "#aaaaaa");
  pvInput(surface, fx, 124, 280, 20, s.step?.cmd || "", {});
  pvBtn(surface, fx + 286, 124, 74, 20, tr("screen.command-gui.add_command_line", "加入列表"), () => {});
  const list = s.list || (s.list = [s.step?.cmd || "/say step"]);
  const scroll = s.listScroll || (s.listScroll = { offset: 0 });
  const maxRows = Math.max(1, Math.floor((H - 22 - 4 - 166) / 12));
  const maxScroll = Math.max(0, list.length - maxRows);
  for (let i = 0; i < Math.min(list.length, maxRows); i++) {
    const y = 166 + i * 12;
    pvLabel(surface, fx + 4, y, 16, "#" + (i + 1), "#aaaaaa");
    pvLabel(surface, fx + 22, y, 155, list[i], "#55ff55");
    const removeX = fx + 360 - 16 - 30;
    pvBtn(surface, removeX - 31 - 49, y, 48, 12, tr("screen.command-gui.step_up_short", "上移"), () => {});
    pvBtn(surface, removeX - 31, y, 48, 12, tr("screen.command-gui.step_down_short", "下移"), () => {});
    pvBtn(surface, removeX - 30, y, 30, 12, tr("screen.command-gui.step_copy_short", "复制"), () => {});
    pvBtn(surface, removeX, y, 30, 12, tr("screen.command-gui.delete", "删除"), () => {});
  }
  pvAddScrollbar(surface, "step", fx + 360 - 12, 166, 12, (H - 22 - 4) - 166, { offset: scroll.offset, max: maxScroll, viewport: maxRows, content: list.length }, { onChange: (off) => { scroll.offset = off; renderPreview(); } });
  const by = H - 22, bw = Math.min(70, 100), sx = fx + (360 - bw * 2 - 8) / 2;
  pvBtn(surface, sx, by, bw, 18, tr("screen.command-gui.save", "保存"), () => pvBack());
  pvBtn(surface, sx + bw + 8, by, bw, 18, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderMachineModesScreen(s, surface) {
  const W = PV.W, H = PV.H, listW = Math.min(340, W - 40), left = (W - listW) / 2, right = left + listW;
  pvTitle(surface, s.title, 6);
  pvBtn(surface, right - 100, 24, 100, 18, tr("screen.command-gui.machine.refresh_detection", "刷新检测"), () => toast("模拟刷新检测"));
  const chipW = Math.floor((right - left - 16 - 12) / 3);
  const rows = PV.data.modes, scroll = s.scroll || (s.scroll = { offset: 0 });
  const visible = Math.min(6, Math.ceil(rows.length / 3));
  const maxScroll = Math.max(0, Math.ceil(rows.length / 3) - visible);
  scroll.offset = Math.min(scroll.offset, maxScroll);
  for (let i = 0; i < rows.length; i++) {
    const m = rows[i];
    const col = i % 3, row = Math.floor(i / 3) - scroll.offset;
    if (row < 0 || row >= visible) continue;
    const x = left + col * (chipW + 6), y = 46 + row * 24;
    pvDark(surface, x, y, chipW, 18, m.name + (m.detected === "on" ? "（已开机）" : "（已关机）"), () => { m.picked = !m.picked; renderPreview(); }, { sel: m.picked });
  }
  const gridH = 138;
  pvAddScrollbar(surface, "modeschips", right - 12, 46, 12, gridH, { get offset() { return scroll.offset; }, set offset(v) { scroll.offset = v; }, get max() { return maxScroll; }, viewport: visible, content: Math.ceil(rows.length / 3) }, { onChange: (off) => { scroll.offset = off; renderPreview(); } });
  const by = H - 24, bw = Math.min(80, listW / 3), sx = left + (listW - bw * 2 - 8) / 2;
  pvBtn(surface, sx, by, bw, 18, tr("screen.command-gui.machine.confirm_modes", "确认模式"), () => pvBack());
  pvBtn(surface, sx + bw + 8, by, bw, 18, tr("screen.command-gui.back", "返回"), () => pvBack());
}

function renderDetectionScreen(s, surface) {
  const W = PV.W, H = PV.H, fx = (W - 360) / 2;
  pvTitle(surface, s.title, 4);
  const dims = [["overworld", tr("screen.command-gui.machine.detection_dim_overworld", "主世界")], ["the_nether", tr("screen.command-gui.machine.detection_dim_nether", "下界")], ["the_end", tr("screen.command-gui.machine.detection_dim_end", "末地")]];
  dims.forEach((d, i) => pvDark(surface, fx + i * 74, 20, 70, 18, d[1], () => { s.dim = d[0]; renderPreview(); }, { sel: (s.dim || "overworld") === d[0] }));
  const y = 48, fw = 82, gap = 2, labelW = 10;
  pvLabel(surface, fx + 2, y + 3, labelW, "X", "#888"); pvInput(surface, fx + 12, y, fw, 18, "0", {});
  pvLabel(surface, fx + 96, y + 3, labelW, "Y", "#888"); pvInput(surface, fx + 108, y, fw, 18, "64", {});
  pvLabel(surface, fx + 192, y + 3, labelW, "Z", "#888"); pvInput(surface, fx + 204, y, fw, 18, "0", {});
  pvBtn(surface, fx + 288, y, 72, 18, tr("screen.command-gui.machine.detection_find", "查找"), () => toast("模拟查找方块"));
  pvBtn(surface, fx, 78, 90, 18, tr("screen.command-gui.machine.detection_pick", "取脚下方块"), () => toast("模拟取脚下方块"));
  pvLabel(surface, fx, 112, 360, tr("screen.command-gui.machine.detection_block", "方块: minecraft:stone"), "#aaaaaa");
  const by = H - 22, bw = Math.min(70, 100), sx = fx + (360 - bw * 2 - 8) / 2;
  pvBtn(surface, sx, by, bw, 18, tr("screen.command-gui.save", "保存"), () => pvBack());
  pvBtn(surface, sx + bw + 8, by, bw, 18, tr("screen.command-gui.back", "返回"), () => pvBack());
}

/* ================= 渲染入口 ================= */
function renderPreview() {
  const surface = pvSurface();
  PV.widgets = [];
  PV.scrolls = [];
  const top = pvTop();
  pvPath();
  if (!top) { renderDesktop(surface); pvInstallMouse(); return; }
  pvRenderScreen(top, surface);
  pvInstallMouse();
}

function initAdvancedPreview() {
  $("previewZoom").addEventListener("change", renderPreview);
  $("btnPreviewBack").addEventListener("click", pvBack);
  window.addEventListener("keydown", (e) => { if (e.key === "Escape" && PV.stack.length) { pvBack(); } });
  pvDefaults();
  renderPreview();
}
window.PV = PV;
window.renderPreviewAdvanced = renderPreview;
window.initAdvancedPreview = initAdvancedPreview;
window.renderPreview = renderPreviewAdvanced;
initAdvancedPreview();
