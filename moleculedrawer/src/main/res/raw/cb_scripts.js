// ChemicalBook 页面脚本：由 ChemicalBookSheet.kt 注入（放在 raw 里避免 Kotlin 字符串转义）
window.__cbSniff = function () {
  function txt(e) { return ((e && e.textContent) || '').replace(/\s+/g, ' ').trim(); }
  var out = { smiles: '', cas: '', mol: '', prop: '', inchi: '', title: document.title || '' };

  // MOL / SDF 链接：不限路径，认扩展名或 /mol/ 段
  var molRe = /\.(mol|sdf)(\?|#|$)/i;
  var links = document.querySelectorAll('a[href]');
  for (var i = 0; i < links.length; i++) {
    var href = links[i].href || '';
    if (!molRe.test(href) && href.indexOf('/mol/') < 0) continue;
    if (!out.mol || /\d{2,7}-\d{2}-\d/.test(href)) {
      out.mol = href;
      var mm = href.match(/\/(\d{2,7}-\d{2}-\d)\.(?:mol|sdf)/i);
      if (mm) { out.cas = mm[1]; break; }
    }
  }

  // SMILES：标签节点后跟值（桌面 th/td、移动 li>span 都覆盖），冒号可能是全角
  var okSmiles = /^[A-Za-z0-9@+\-\[\]()=#$:\/\.%*]+$/;
  var nodes = document.querySelectorAll('th,span,b,strong,td,li,p,div,font');
  for (var n = 0; n < nodes.length; n++) {
    var t = txt(nodes[n]);
    if (t.length > 400) continue;
    var m = t.match(/^SMILES\s*[:\uFF1A]?\s*([^\s]*)/i);
    if (!m) continue;
    var v = m[1] || '';
    if (!v) {
      var sib = nodes[n].nextElementSibling;
      if (sib) v = txt(sib).replace(/^[:\uFF1A]\s*/, '');
    }
    if (!v && nodes[n].parentElement) {
      var pm = txt(nodes[n].parentElement).match(/SMILES\s*[:\uFF1A]\s*(\S+)/i);
      if (pm) v = pm[1];
    }
    v = (v || '').split(' ')[0];
    if (v.length >= 2 && okSmiles.test(v) && /[A-Za-z]/.test(v)) { out.smiles = v; break; }
  }

  var body = (document.body && document.body.innerText) || '';
  var ik = body.match(/InChIKey\s*[:\uFF1A]?\s*([A-Z]{14}-[A-Z]{10}-[A-Z])/i);
  if (ik) out.inchi = ik[1];

  var p = document.querySelector('a[href*="ChemicalProductProperty"]') ||
          document.querySelector('a[href*="ProductChemicalProperties"]') ||
          document.querySelector('a[href*="productdetail"]');
  if (p) out.prop = p.href;

  if (!out.cas) {
    var m2 = body.match(/\b[0-9]{2,7}-[0-9]{2}-[0-9]\b/);
    if (m2) out.cas = m2[0];
  }
  return JSON.stringify(out);
};

window.__cbSubmitSearch = function (q) {
  var i = document.querySelector('input[name=keyword]') ||
          document.querySelector('input[name=Keywords]') ||
          document.querySelector('input[type=search]');
  if (!i) return 'no-field';
  i.value = q;
  if (i.form) { i.form.submit(); return 'ok'; }
  i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }));
  return 'enter';
};

window.__cbFillMol = function (mol) {
  var ta = document.getElementById('mol');
  if (ta) ta.value = mol;

  function frame() { return document.getElementById('ketcherFrame'); }

  function focusMode() {
    try {
      if (!document.getElementById('cb-focus-style')) {
        var st = document.createElement('style');
        st.id = 'cb-focus-style';
        st.textContent = '.Maps,.structure,#foot,header,.head,.nav,.header{display:none!important}';
        (document.head || document.documentElement).appendChild(st);
      }
      if (!document.body.dataset.cbZoom && window.innerWidth < 720) {
        var z = Math.max(0.6, Math.min(1, window.innerWidth / 712));
        document.body.style.zoom = z;
        document.body.dataset.cbZoom = String(z);
      }
    } catch (e) {}
  }

  function reveal() {
    focusMode();
    try {
      var f = frame();
      if (f) { f.style.visibility = 'visible'; f.style.display = 'block'; }
    } catch (e) {}
  }

  function countAtoms(m) {
    try { var c = String(m).split('\n')[3].trim().split(/\s+/); return 'atoms=' + c[0] + ' bonds=' + c[1]; }
    catch (e) { return 'unparsed'; }
  }

  function canvasInfo() {
    try {
      var inner = frame().contentWindow;
      var svg = inner.document.querySelector('svg');
      if (!svg) return 'no-svg';
      var vr = svg.getBoundingClientRect();
      var paths = svg.querySelectorAll('path,line,polyline,polygon');
      var n = 0, minx = 1e9, miny = 1e9, maxx = -1e9, maxy = -1e9, sumSpan = 0, cnt = 0;
      for (var i = 0; i < paths.length; i++) {
        var el = paths[i], bb, m;
        try { bb = el.getBBox(); m = el.getScreenCTM(); } catch (e) { continue; }
        if (!m || (!(bb.width > 0.3) && !(bb.height > 0.3))) continue;
        var xs = [], ys = [];
        var pts = [[bb.x, bb.y], [bb.x + bb.width, bb.y], [bb.x, bb.y + bb.height], [bb.x + bb.width, bb.y + bb.height]];
        for (var j = 0; j < 4; j++) {
          xs.push(m.a * pts[j][0] + m.c * pts[j][1] + m.e);
          ys.push(m.b * pts[j][0] + m.d * pts[j][1] + m.f);
        }
        var x0 = Math.min.apply(null, xs), y0 = Math.min.apply(null, ys);
        var x1 = Math.max.apply(null, xs), y1 = Math.max.apply(null, ys);
        n++;
        minx = Math.min(minx, x0); miny = Math.min(miny, y0);
        maxx = Math.max(maxx, x1); maxy = Math.max(maxy, y1);
        var span = Math.max(x1 - x0, y1 - y0);
        if (span > 3) { sumSpan += span; cnt++; }
      }
      var ow = Math.max(0, Math.min(maxx, vr.right) - Math.max(minx, vr.left));
      var oh = Math.max(0, Math.min(maxy, vr.bottom) - Math.max(miny, vr.top));
      return 'view=' + Math.round(vr.width) + 'x' + Math.round(vr.height) + ' paths=' + n +
             ' bondPx=' + (cnt ? Math.round(sumSpan / cnt) : 0) +
             ' screenBox=' + [minx, miny, maxx, maxy].map(Math.round).join(',') +
             ' overlap=' + Math.round(ow) + 'x' + Math.round(oh);
    } catch (e) { return 'canvasInfo error ' + e; }
  }

  function readback(k) {
    try {
      var out = k.getMolfile();
      if (out && out.then) out.then(function (m) { console.log('[CB] readback ' + countAtoms(m) + ' ' + canvasInfo()); });
      else console.log('[CB] readback ' + countAtoms(out) + ' ' + canvasInfo());
    } catch (e) { console.log('[CB] readback error ' + e); }
  }

  var tries = 0;
  var timer = setInterval(function () {
    tries++;
    reveal();
    var k = null;
    try { var f = frame(); k = f && f.contentWindow && f.contentWindow.ketcher; } catch (e) {}
    if (!k) {
      if (tries > 50) { clearInterval(timer); window.__cbFill = 'noframe'; console.log('[CB] ketcher not found'); }
      return;
    }
    clearInterval(timer);
    console.log('[CB] ketcher api set=' + (typeof k.setMolecule) + ' get=' + (typeof k.getMolfile));
    try {
      window.__cbFill = 'pending';
      var r = k.setMolecule(mol);
      var after = function () { setTimeout(function () { reveal(); readback(k); }, 700); };
      if (r && r.then) r.then(function () { window.__cbFill = 'ok'; console.log('[CB] setMolecule ok'); after(); })
                         .catch(function (e) { window.__cbFill = 'reject'; console.log('[CB] setMolecule rejected: ' + e); });
      else { window.__cbFill = 'ok'; after(); }
    } catch (e) { window.__cbFill = 'err'; console.log('[CB] setMolecule threw: ' + e); }
  }, 400);
  return 'queued';
};
