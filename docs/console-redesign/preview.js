/*
 * 设计预览脚本（plan/console-redesign）。
 * 只做三件事：画出共用的后台外壳、按 ?state= 切换示例状态、窄屏抽屉开合。
 * 不发任何网络请求，不读写 sessionStorage，不接入真实接口。
 *
 * 页面用法：
 *   window.PREVIEW = { page, title, crumb, shell, states: [{ id, label, admin, key }] }
 *   内容里用 data-show="状态a 状态b" 标出只在这些状态下出现的块。
 *   ?bare=1 隐藏顶部深色预览条（截图用）；?nav=1 窄屏下默认展开抽屉。
 */
(function () {
  var cfg = window.PREVIEW || {};
  var q = new URLSearchParams(location.search);
  var states = cfg.states || [];
  var state = states.find(function (s) { return s.id === q.get("state"); }) || states[0] || { id: "default" };
  if (q.get("bare") === "1") document.body.classList.add("bare");
  document.body.dataset.state = state.id;

  var ICON = {
    tenants: '<path d="M3 13.5V6l5-3 5 3v7.5H3z"/><path d="M6.5 13.5v-3h3v3"/>',
    templates: '<rect x="2.5" y="2.5" width="11" height="11" rx="1.5"/><path d="M2.5 6.5h11M6.5 6.5v7"/>',
    assets: '<rect x="2" y="3.5" width="12" height="9" rx="1.5"/><path d="M6.8 6.2v3.6L10 8z"/>',
    jobs: '<path d="M3 4h10M3 8h10M3 12h6"/>',
    webhooks: '<circle cx="4" cy="11.5" r="1.8"/><circle cx="12" cy="11.5" r="1.8"/><circle cx="8" cy="4" r="1.8"/><path d="M7.2 5.6L4.8 9.9M8.8 5.6l2.4 4.3M5.8 11.5h4.4"/>',
    usage: '<path d="M3 13V8M8 13V3M13 13V6"/>',
    play: '<circle cx="8" cy="8" r="5.5"/><path d="M7 5.8v4.4L10.2 8z"/>',
    ext: '<path d="M6.5 3.5h-3v9h9v-3M9 3h4v4M13 3L7.5 8.5"/>',
    lock: '<rect x="3.5" y="7" width="9" height="6" rx="1.2"/><path d="M5.5 7V5.2a2.5 2.5 0 015 0V7"/>',
    user: '<circle cx="8" cy="5.5" r="2.5"/><path d="M3 13.5c.6-2.4 2.6-3.6 5-3.6s4.4 1.2 5 3.6"/>',
    key: '<circle cx="5.2" cy="10.8" r="2.4"/><path d="M7 9l5.5-5.5M10.5 5.5l1.6 1.6M9 7l1.2 1.2"/>',
    info: '<circle cx="8" cy="8" r="5.8"/><path d="M8 7.2v3.6M8 5.2v.2"/>',
    warn: '<path d="M8 2.5l6 10.5H2z"/><path d="M8 6.8v2.8M8 11.4v.2"/>',
    check: '<circle cx="8" cy="8" r="5.8"/><path d="M5.4 8.2l1.8 1.8 3.4-3.6"/>',
    upload: '<path d="M8 10.5v-7M5 6l3-3 3 3M3 10.5v2.5h10v-2.5"/>',
    refresh: '<path d="M13 8a5 5 0 11-1.6-3.7M13 3v2.6h-2.6"/>',
    plus: '<path d="M8 3.5v9M3.5 8h9"/>',
    inbox: '<path d="M2.5 9.5l2-6h7l2 6v3.5h-11z"/><path d="M2.5 9.5h3.2l.8 1.5h3l.8-1.5h3.2"/>',
    menu: '<path d="M2.5 4.5h11M2.5 8h11M2.5 11.5h11"/>',
    close: '<path d="M4 4l8 8M12 4l-8 8"/>'
  };
  function icon(name) { return '<svg class="i" viewBox="0 0 16 16" aria-hidden="true">' + ICON[name] + "</svg>"; }
  window.pvIcon = icon;

  // 侧栏导航：分组与顺序按 README 已定的结构。三个核心页有设计稿，其余为“后续沿用”。
  var NAV = [
    { head: "管理端", by: "用管理登录", items: [
      { id: "tenants", label: "租户与 Key", href: "tenants.html" },
      { id: "templates", label: "模板" }
    ] },
    { head: "租户端", by: "用租户 Key", tenant: true, items: [
      { id: "assets", label: "资产", href: "assets.html" },
      { id: "jobs", label: "任务" },
      { id: "webhooks", label: "Webhook" },
      { id: "usage", label: "用量与配额" }
    ] }
  ];

  function keep(href) {
    return href + (document.body.classList.contains("bare") ? "?bare=1" : "");
  }

  function whoAdmin() {
    if (state.admin === false) {
      return '<span class="who none">' + icon("user") + '<span class="k">未登录管理端</span><a href="' + keep("login.html") + '">去登录</a></span>';
    }
    return '<span class="who admin" title="管理登录：只用于「管理端」页面">' + icon("user") +
      '<span class="k">管理员</span><span class="v">admin</span><a href="' + keep("login.html") + '">退出</a></span>';
  }
  function whoKey() {
    if (state.key === "none") {
      return '<span class="who none" title="租户端页面需要先选用一把租户 Key">' + icon("key") +
        '<span class="k">未选用租户 Key</span><a href="' + keep("tenants.html") + '">去选用</a></span>';
    }
    if (state.key === "invalid") {
      return '<span class="who bad" title="这把 Key 已被服务端拒绝">' + icon("key") +
        '<span class="k">租户 Key 已失效</span><span class="v">示例租户 A · vk_3f9a…</span><a href="' + keep("tenants.html") + '">换 Key</a></span>';
    }
    return '<span class="who tenant" title="租户 Key：只用于「租户端」页面。这里只显示备注和前缀，不显示明文">' + icon("key") +
      '<span class="k">租户 Key</span><span class="v">示例租户 A · vk_3f9a…</span><a href="' + keep("tenants.html") + '">换 Key</a></span>';
  }

  function buildShell() {
    var content = document.getElementById("content");
    var nav = NAV.map(function (g) {
      var locked = g.tenant && state.key !== "ok";
      return '<div class="nav-group"><div class="nav-head"><span>' + g.head + '</span>' +
        '<span class="badge plain tiny" style="' + (g.tenant
          ? "background:var(--tenant-soft);color:var(--tenant);border-color:transparent"
          : "background:var(--primary-soft);color:var(--primary);border-color:transparent") + '">' + g.by + "</span></div>" +
        g.items.map(function (it) {
          var on = it.id === cfg.page;
          return '<a class="item' + (on ? " on" : "") + '" href="' + (it.href ? keep(it.href) : "#") + '"' +
            (on ? ' aria-current="page"' : "") + (it.href ? "" : ' title="本轮未出设计稿，后续沿用同一套规范" data-todo') + ">" +
            icon(it.id) + "<span>" + it.label + "</span>" +
            (locked ? '<span class="lock" title="需要先选用租户 Key">' + icon("lock") + "</span>" : "") + "</a>";
        }).join("") + "</div>";
    }).join("");

    var shell = document.createElement("div");
    shell.className = "shell";
    shell.innerHTML =
      '<aside class="side" id="side" aria-label="后台导航">' +
        '<div class="brand"><span class="brand-mark"><svg viewBox="0 0 16 16"><path d="M5 3.2v9.6L13 8z"/></svg></span>视频云控制台' +
          '<button class="btn icon sm text close-nav" type="button" aria-label="关闭导航" data-nav-close>' + icon("close") + "</button></div>" +
        '<nav class="nav">' + nav + "</nav>" +
        '<div class="side-foot"><a href="#" data-todo title="指向旧播放页 /，本轮不改该页面">' + icon("play") +
          '<span>短剧场（旧播放页）</span><span class="ext">' + icon("ext") + "</span></a></div>" +
      "</aside>" +
      '<button class="scrim" type="button" aria-label="关闭导航" data-nav-close></button>' +
      '<div class="main">' +
        '<header class="top">' +
          '<button class="btn icon menu-btn" type="button" aria-label="打开导航" aria-controls="side" data-nav-open>' + icon("menu") + "</button>" +
          '<div class="top-title">' + ((state.crumb || cfg.crumb) ? '<div class="crumb">' + (state.crumb || cfg.crumb) + "</div>" : "") + "<h1>" + (state.title || cfg.title || "") + "</h1></div>" +
          '<div class="ctx" aria-label="当前身份">' + whoAdmin() + whoKey() + "</div>" +
        "</header>" +
        '<main class="content"><div class="content-inner" id="slot"></div></main>' +
      "</div>";
    document.body.appendChild(shell);
    shell.querySelector("#slot").appendChild(content);
    content.hidden = false;

    function setNav(open) { document.body.classList.toggle("nav-open", open); }
    document.addEventListener("click", function (e) {
      var t = e.target.closest("[data-nav-open],[data-nav-close],[data-todo]");
      if (!t) return;
      if (t.hasAttribute("data-todo")) { e.preventDefault(); return; }
      setNav(t.hasAttribute("data-nav-open"));
    });
    document.addEventListener("keydown", function (e) { if (e.key === "Escape") setNav(false); });
    if (q.get("nav") === "1") setNav(true);
  }

  function buildBar() {
    var bar = document.createElement("div");
    bar.className = "pv";
    bar.setAttribute("role", "note");
    var html = '<span class="pv-tag">设计预览</span><span>此条不属于产品界面 · 页面内<b>全部为示例数据</b>，未连接任何接口</span>' +
      '<span class="pv-sep"></span><a href="index.html">总览</a><a href="login.html">登录页</a><a href="tenants.html">租户与 Key</a><a href="assets.html">资产列表</a>';
    if (states.length > 1) {
      html += '<span class="pv-sep"></span><span>示例状态：</span>' + states.map(function (s) {
        return '<a class="' + (s.id === state.id ? "on" : "") + '" href="?state=' + s.id + '">' + s.label + "</a>";
      }).join("");
    }
    bar.innerHTML = html;
    document.body.insertBefore(bar, document.body.firstChild);
  }

  // 只显示当前状态对应的块
  document.querySelectorAll("[data-show]").forEach(function (el) {
    el.hidden = el.dataset.show.split(/\s+/).indexOf(state.id) < 0;
  });
  // 把 <i data-i="name"> 换成图标
  document.querySelectorAll("i[data-i]").forEach(function (el) { el.outerHTML = icon(el.dataset.i); });

  buildBar();
  if (cfg.shell !== false) buildShell();
})();
