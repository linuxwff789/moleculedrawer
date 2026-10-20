package com.moldraw.app.ui.moleculedraw

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ChemicalBook 查结构：内嵌网页 → 从理化信息页嗅探 SMILES / MOL File → 导入画布。
 *
 * 为什么是"嗅探网页"而不是调用接口：ChemicalBook 没有公开 API，中文站搜索是表单 POST，
 * 详情页对非浏览器请求返回 503。这里用 WebView 走用户自己的浏览会话，只读取页面里已经
 * 公开展示的 SMILES 和 /CAS/mol/{CAS}.mol 下载地址，不做站内抓取队列。
 */
private const val CB_HOME = "https://m.chemicalbook.com/"
private const val CB_STRUCT_SEARCH = "https://m.chemicalbook.com/StructuredSearch.htm"
private const val CB_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

/** 页面上嗅探到的结构信息 */
private data class Sniffed(
    val smiles: String = "",
    val cas: String = "",
    val molUrl: String = "",
    val propUrl: String = "",
    val title: String = ""
) {
    val hasStructure: Boolean get() = molUrl.isNotEmpty() || smiles.isNotEmpty()
}

/** PubChem 反查结果 */
internal data class PcHit(
    val cid: Int,
    val name: String,
    val formula: String,
    val mass: String,
    val cas: String,
    val smiles: String
)

/**
 * 用结构（SMILES）反查这是什么化合物：PubChem 精确结构匹配。
 * ChemicalBook 本身只支持名称/CAS 检索，所以结构 → 名称这一步只能走 PubChem，
 * 拿到 CAS 之后再回到站点里搜中文名/供应商。
 */
internal fun pubchemLookup(smiles: String): PcHit? = runCatching {
    fun http(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", CB_UA)
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    val enc = URLEncoder.encode(smiles, "UTF-8")
    val cidsJson = JSONObject(http("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/smiles/$enc/cids/JSON"))
    val arr = cidsJson.optJSONObject("IdentifierList")?.optJSONArray("CID") ?: return null
    if (arr.length() == 0) return null
    val cid = arr.getInt(0)
    val propJson = JSONObject(
        http("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/$cid/property/Title,IUPACName,MolecularFormula,MolecularWeight,ConnectivitySMILES/JSON")
    )
    val p = propJson.optJSONObject("PropertyTable")?.optJSONArray("Properties")?.optJSONObject(0)
    val xrefJson = JSONObject(http("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/$cid/xrefs/RegistryID/JSON"))
    val regs = xrefJson.optJSONObject("InformationList")?.optJSONArray("Information")?.optJSONObject(0)
        ?.optJSONArray("RegistryID")
    var cas = ""
    if (regs != null) {
        for (i in 0 until regs.length()) {
            val v = regs.optString(i)
            if (Regex("^\\d{2,7}-\\d{2}-\\d$").matches(v)) { cas = v; break }
        }
    }
    PcHit(
        cid = cid,
        name = p?.optString("Title").orEmpty().ifBlank { p?.optString("IUPACName").orEmpty() },
        formula = p?.optString("MolecularFormula").orEmpty(),
        mass = p?.optString("MolecularWeight").orEmpty(),
        cas = cas,
        smiles = smiles
    )
}.getOrNull()

/** 在页面里找 SMILES 标签（桌面版 th/td、移动版 li>span）与 MOL 下载链接 */
private val SNIFF_JS = """
(function () {
  function txt(e) { return ((e && e.textContent) || '').replace(/\s+/g, ' ').trim(); }
  var out = { smiles: '', cas: '', mol: '', prop: '', title: document.title || '' };
  var a = document.querySelector('a[href*="/CAS/mol/"]');
  if (a) {
    out.mol = a.href;
    var mm = a.href.match(/\/([0-9]{2,7}-[0-9]{2}-[0-9])\.mol/i);
    if (mm) out.cas = mm[1];
  }
  var ok = /^[A-Za-z0-9@+\-\[\]()=#$:\/\\\.%*]+$/;
  var nodes = document.querySelectorAll('th,span,b,strong,td,li,p');
  for (var i = 0; i < nodes.length; i++) {
    var t = txt(nodes[i]);
    if (!/^SMILES\b/i.test(t) || t.length > 400) continue;
    var v = t.replace(/^SMILES\s*[:\uFF1A]?\s*/i, '');
    if (!v && nodes[i].nextElementSibling) v = txt(nodes[i].nextElementSibling).replace(/^[:\uFF1A]\s*/, '');
    if (v && v.length >= 2 && ok.test(v) && /[A-Za-z]/.test(v)) { out.smiles = v; break; }
  }
  var p = document.querySelector('a[href*="ChemicalProductProperty"]') ||
          document.querySelector('a[href*="ProductChemicalProperties"]');
  if (p) out.prop = p.href;
  if (!out.cas) {
    var m2 = ((document.body && document.body.innerText) || '').match(/\b[0-9]{2,7}-[0-9]{2}-[0-9]\b/);
    if (m2) out.cas = m2[0];
  }
  return JSON.stringify(out);
})()
"""

/** 把 molfile 灌进化学结构式搜索页（Ketcher 编辑器 + 站点的隐藏 textarea） */
private fun fillMolJs(mol: String): String {
    val q = JSONObject.quote(mol)
    return """
    (function (mol) {
      var ta = document.getElementById('mol');
      if (ta) ta.value = mol;

      function frame() { return document.getElementById('ketcherFrame'); }

      // 移动版把 Ketcher 放在标签页容器里，容器/iframe 没布局时画布是空的：
      // 先把它显出来、给足尺寸，再触发 resize，Ketcher 才会重绘 SVG。
      function focusMode() {
        // 折叠站点头部/面包屑/标题行，把竖向空间全留给编辑器
        try {
          if (!document.getElementById('cb-focus-style')) {
            var st = document.createElement('style');
            st.id = 'cb-focus-style';
            st.textContent = '.Maps,.structure,#foot,header,.head,.nav,.header{display:none!important}';
            (document.head || document.documentElement).appendChild(st);
          }
          // 站点把编辑器写死 700px 宽，屏窄时会横向溢出：用 CSS zoom 整体缩到刚好铺满，
          // iframe 内部仍是 700x500（Ketcher 的坐标映射不受影响，缩放后仍能拖动）
          try {
            if (!document.body.dataset.cbZoom && window.innerWidth < 720) {
              var z = Math.max(0.6, Math.min(1, window.innerWidth / 712));
              document.body.style.zoom = z;
              document.body.dataset.cbZoom = String(z);
            }
          } catch (e) {}
        } catch (e) {}
      }

      function revealAndResize() {
        var f = frame();
        try {
          focusMode();
          var tab = document.getElementById('b_Ketcher');
          if (tab) { tab.style.display = 'block'; }
          var wrap = tab && tab.parentElement;
          if (wrap) { wrap.style.display = 'block'; }
          if (f) {
            // 不要改 iframe 宽高：Ketcher 在加载时按 700x500 建画布，
            // 事后改容器尺寸会让它重绘到错误视口（点一下就空白、拖动也不跟手）。
            // 只保证它可见，尺寸交给站点自己。
            f.style.visibility = 'visible';
            f.style.display = 'block';
          }
        } catch (e) { console.log('[CB] reveal error ' + e); }
      }

      function countAtoms(m) {
        try { var c = String(m).split('\n')[3].trim().split(/\s+/); return 'atoms=' + c[0] + ' bonds=' + c[1]; }
        catch (e) { return 'unparsed'; }
      }

      // 画布内容是否真的落在可见区域：把每个 path 的屏幕坐标算出来，取并集和视口比较
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
          var overlapW = Math.max(0, Math.min(maxx, vr.right) - Math.max(minx, vr.left));
          var overlapH = Math.max(0, Math.min(maxy, vr.bottom) - Math.max(miny, vr.top));
          return 'view=' + Math.round(vr.width) + 'x' + Math.round(vr.height) + ' paths=' + n +
                 ' bondPx=' + (cnt ? Math.round(sumSpan / cnt) : 0) +
                 ' screenBox=' + [minx, miny, maxx, maxy].map(Math.round).join(',') +
                 ' overlap=' + Math.round(overlapW) + 'x' + Math.round(overlapH);
        } catch (e) { return 'canvasInfo error ' + e; }
      }

      function readback(k) {
        try {
          var out = k.getMolfile();
          var inner = frame() && frame().contentWindow;
          var svgNodes = 0;
          try { svgNodes = inner.document.querySelectorAll('svg *').length; } catch (e) {}
          if (out && out.then) out.then(function (m) { console.log('[CB] readback ' + countAtoms(m) + ' svg=' + svgNodes + ' ' + canvasInfo()); });
          else console.log('[CB] readback ' + countAtoms(out) + ' svg=' + svgNodes + ' ' + canvasInfo());
        } catch (e) { console.log('[CB] readback error ' + e); }
      }

      var tries = 0;
      var timer = setInterval(function () {
        tries++;
        revealAndResize();
        var k = null;
        try { var f = frame(); k = f && f.contentWindow && f.contentWindow.ketcher; } catch (e) {}
        if (!k) {
          if (tries > 50) { clearInterval(timer); window.__cbFill = 'noframe'; console.log('[CB] ketcher not found'); }
          return;
        }
        clearInterval(timer);
        console.log('[CB] ketcher api set=' + (typeof k.setMolecule) + ' get=' + (typeof k.getMolfile) +
                    ' layout=' + (typeof k.layout) + ' zoomFit=' + (typeof k.zoomToFit));
        try {
          window.__cbFill = 'pending';
          var r = k.setMolecule(mol);
          var after = function () {
            setTimeout(function () {
              try { if (k.layout) k.layout(); } catch (e) {}
              try { if (k.zoomToFit) k.zoomToFit(); } catch (e) {}
              try { if (k.editor && k.editor.zoomToFit) k.editor.zoomToFit(); } catch (e) {}
              revealAndResize();
              readback(k);
            }, 700);
          };
          if (r && r.then) r.then(function () { window.__cbFill = 'ok'; console.log('[CB] setMolecule ok'); after(); })
                             .catch(function (e) { window.__cbFill = 'reject'; console.log('[CB] setMolecule rejected: ' + e); });
          else { after(); }
        } catch (e) { console.log('[CB] setMolecule threw: ' + e); }
      }, 400);
      return 'queued';
    })($q)
    """.trimIndent()
}

/** 把关键词填进站点自己的搜索框再提交（站点搜索只接受表单提交） */
private fun submitSearchJs(query: String): String {
    val q = JSONObject.quote(query)
    return """
    (function (q) {
      var i = document.querySelector('input[name=keyword]') ||
              document.querySelector('input[name=Keywords]') ||
              document.querySelector('input[type=search]');
      if (!i) return 'no-field';
      i.value = q;
      if (i.form) { i.form.submit(); return 'ok'; }
      i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }));
      return 'enter';
    })($q)
    """.trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChemicalBookSheet(
    atoms: MutableList<MoleculeAtom>,
    bonds: MutableList<MoleculeBond>,
    annotations: MutableList<MoleculeAnnotation>,
    selectedIds: MutableSet<Int>,
    layoutEngine: MoleculeLayoutEngine?,
    canvasSizePx: Size,
    initialQuery: String = "",
    pasteMol: String = "",
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var sniffed by remember { mutableStateOf<Sniffed?>(null) }
    var hit by remember { mutableStateOf<PcHit?>(null) }
    var reverseBusy by remember { mutableStateOf(false) }
    var autoFollow by remember { mutableStateOf(false) }
    var pendingQuery by remember { mutableStateOf(initialQuery.trim()) }
    var loadRetries by remember { mutableStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }

    fun doSniff(w: WebView) {
        w.evaluateJavascript(SNIFF_JS) { raw ->
            // evaluateJavascript 把 JS 字符串结果再包一层 JSON 引号，先解出来
            val json = runCatching {
                val t = raw?.trim().orEmpty()
                if (t.startsWith("\"")) org.json.JSONTokener(t).nextValue() as String else t
            }.getOrDefault("")
            val s = runCatching {
                val o = JSONObject(if (json.isNotBlank()) json else "{}")
                Sniffed(
                    smiles = o.optString("smiles"),
                    cas = o.optString("cas"),
                    molUrl = o.optString("mol"),
                    propUrl = o.optString("prop"),
                    title = o.optString("title")
                )
            }.getOrNull()
            // 商品页没有结构但有「理化信息」链接时也要显示，好让用户跳过去
            if (s != null && (s.hasStructure || s.propUrl.isNotEmpty() || s.cas.isNotEmpty())) sniffed = s
            // 从 CAS 页进来时，自动再跳一步到理化详情页
            if (s != null && autoFollow && !s.hasStructure && s.propUrl.isNotEmpty()) {
                autoFollow = false
                status = "正在打开理化详情页…"
                w.loadUrl(s.propUrl)
            } else if (s != null && s.hasStructure) {
                autoFollow = false
            }
        }
    }

    fun downloadMol(url: String, onDone: (String?) -> Unit) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val c = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 20000
                        setRequestProperty("User-Agent", CB_UA)
                        setRequestProperty("Referer", CB_HOME)
                    }
                    c.inputStream.bufferedReader().use { it.readText() }
                }.getOrNull()
            }
            onDone(text)
        }
    }

    fun importStructure(s: Sniffed) {
        if (!s.hasStructure) { status = "这一页没有结构信息，先打开「理化信息」页"; return }
        if (s.molUrl.isNotEmpty()) {
            status = "正在下载 mol 文件…"
            downloadMol(s.molUrl) { mol ->
                if (!mol.isNullOrBlank() && (mol.contains("V2000") || mol.contains("V3000"))) {
                    processMolImport(mol, layoutEngine, atoms, bonds, annotations, selectedIds, gNextId, canvasSizePx)
                    status = "已导入 ${atoms.size} 个原子（mol 文件）"
                    sniffed = null
                } else if (s.smiles.isNotEmpty()) {
                    processSmilesImport(s.smiles, layoutEngine, atoms, bonds, annotations, selectedIds, gNextId, canvasSizePx)
                    status = "mol 下载失败，已按 SMILES 导入"
                    sniffed = null
                } else {
                    status = "mol 下载失败，且没有 SMILES 可用"
                }
            }
        } else if (s.smiles.isNotEmpty()) {
            processSmilesImport(s.smiles, layoutEngine, atoms, bonds, annotations, selectedIds, gNextId, canvasSizePx)
            status = "已导入 ${atoms.size} 个原子（SMILES）"
            sniffed = null
        }
    }

    fun lookupSmilesText(smilesIn: String, autoOpen: Boolean = false) {
        reverseBusy = true
        status = "正在用 SMILES 反查…"
        hit = null
        scope.launch {
            val smiles = smilesIn.trim()
            val r = withContext(Dispatchers.IO) { pubchemLookup(smiles) }
            android.util.Log.d("MOLDRAW_DEBUG", "[CB] reverse smiles='$smiles' hit=$r")
            reverseBusy = false
            if (r == null) {
                status = "PubChem 里没有匹配的结构（SMILES: $smiles）"
            } else {
                hit = r
                status = ""
                // 从 SMILES 搜进来的，直接一路跳到理化详情页
                if (autoOpen && r.cas.isNotEmpty()) {
                    autoFollow = true
                    status = "已识别：${r.name}，正在打开理化详情页…"
                    web?.loadUrl("https://m.chemicalbook.com/CAS_" + r.cas + ".htm")
                }
            }
        }
    }

    fun doReverseLookup() {
        if (atoms.isEmpty()) { status = "画布是空的，先画个结构"; return }
        reverseBusy = true
        status = "正在生成当前结构的 SMILES…"
        hit = null
        scope.launch {
            val smiles = withContext(Dispatchers.Default) {
                runCatching {
                    IndigoNativeLayoutEngineAdapter().generateSmiles(atoms.toList(), bonds.toList(), annotations.toList())
                }.getOrNull().orEmpty()
            }
            if (smiles.isBlank()) {
                reverseBusy = false
                status = "生成 SMILES 失败，检查结构有没有异常价态"
            } else {
                lookupSmilesText(smiles)
            }
        }
    }

    /** 输入框里像 SMILES（含 = # [ ] ( ) @ + 或 元素+数字）就直接反查，不当关键词搜 */
    fun looksLikeSmiles(q: String): Boolean {
        val t = q.trim()
        if (t.length < 2 || t.any { it.code > 0x2E80 }) return false
        if (t.contains(' ')) return false
        if (Regex("^\\d{2,7}-\\d{2}-\\d$").matches(t)) return false
        val structural = t.any { it in "=#[]()@+\\/" }
        val elemNum = Regex("[A-Za-z][0-9]").containsMatchIn(t)
        return structural || elemNum
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        sniffed = null
        hit = null
        if (looksLikeSmiles(q)) {
            lookupSmilesText(q, autoOpen = true)
            return
        }
        status = "搜索中…"
        web?.evaluateJavascript(submitSearchJs(q)) { r ->
            if (r != null && r.contains("no-field")) status = "页面上没找到搜索框，请手动输入"
        }
    }

    android.util.Log.d("MOLDRAW_DEBUG", "[CB] sheet compose pasteMol=${pasteMol.length} q=$initialQuery")
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding()) {
                // 顶栏：关闭 / 搜索框 / 站点后退
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("中文名 / 英文名 / CAS，如 苯甲酸", fontSize = 13.sp) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runSearch() })
                    )
                    IconButton(onClick = { runSearch() }) { Icon(Icons.Default.Search, contentDescription = "搜索") }
                    IconButton(onClick = { web?.let { if (it.canGoBack()) it.goBack() } }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "后退")
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = CB_UA
                                settings.loadsImagesAutomatically = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                                        val msg = m?.message().orEmpty()
                                        android.util.Log.d("MOLDRAW_DEBUG", "[CB] console: $msg")
                                        // 把注入结果反映到底部提示条，别一直显示"正在填入"
                                        when {
                                            msg.contains("mol filled") ->
                                                status = "结构已填入编辑器，选好搜索类型后点「立即搜索」"
                                            msg.contains("ketcher not found") ->
                                                status = "编辑器没加载出来，点右上角返回箭头重新进一次"
                                            msg.contains("setMolecule rejected") ->
                                                status = "编辑器拒绝了这份 molfile：$msg"
                                        }
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        loading = true
                                    }

                                    override fun onReceivedError(
                                        view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
                                    ) {
                                        android.util.Log.w("MOLDRAW_DEBUG", "[CB] error ${request?.url} ${error?.description}")
                                        // 代理/VPN 的 DNS 偶发解析失败，主文档失败时自动重试几次
                                        if (request?.isForMainFrame == true && loadRetries < 3) {
                                            loadRetries++
                                            val delayMs = 1200L * loadRetries
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                status = "网络失败，正在重试…"
                                                view?.reload()
                                            }, delayMs)
                                        }
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean = false

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loading = false
                                        loadRetries = 0
                                        android.util.Log.d("MOLDRAW_DEBUG", "[CB] page done $url pasteMol=${pasteMol.length}")
                                        if (pasteMol.isNotBlank() && view != null) {
                                            status = "正在把 molfile 填进结构式搜索…"
                                            view.evaluateJavascript(fillMolJs(pasteMol)) { r ->
                                                android.util.Log.d("MOLDRAW_DEBUG", "[CB] fillMol r=$r")
                                            }
                                            view.postDelayed({
                                                view.evaluateJavascript("window.__cbFill || 'none'") { r ->
                                                    android.util.Log.d("MOLDRAW_DEBUG", "[CB] fill state=$r")
                                                    status = when {
                                                        r.contains("ok") -> "结构已填入编辑器，选好类型后点「立即搜索」"
                                                        r.contains("reject") -> "编辑器拒绝了这份 molfile（价态/芳香键问题）"
                                                        r.contains("noframe") -> "编辑器没加载出来，返回再进一次试试"
                                                        else -> "已提交，等编辑器响应…"
                                                    }
                                                }
                                            }, 5000)
                                            sniffed = null
                                        }
                                        if (pendingQuery.isNotBlank() && view != null) {
                                            val q = pendingQuery
                                            pendingQuery = ""
                                            query = q
                                            if (looksLikeSmiles(q)) lookupSmilesText(q, autoOpen = true)
                                            else {
                                                status = "搜索中…"
                                                view.evaluateJavascript(submitSearchJs(q)) { r ->
                                                    if (r != null && r.contains("no-field")) status = "页面上没找到搜索框，请手动输入"
                                                }
                                            }
                                        }
                                        view?.let { doSniff(it) }
                                    }
                                }
                                loadUrl(if (pasteMol.isNotBlank()) CB_STRUCT_SEARCH else CB_HOME)
                                web = this
                            }
                        }
                    )
                }

                // 嗅探结果条
                val s = sniffed
                if (s != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                buildString {
                                    if (s.title.isNotEmpty()) append(s.title).append("\n")
                                    if (s.cas.isNotEmpty()) append("CAS  ").append(s.cas).append("    ")
                                    append(if (s.molUrl.isNotEmpty()) "MOL 文件" else "SMILES")
                                }.trim(),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3
                            )
                            if (s.smiles.isNotEmpty()) {
                                Text(
                                    s.smiles,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (!s.hasStructure && s.propUrl.isNotEmpty()) {
                                    TextButton(onClick = { web?.loadUrl(s.propUrl) }) { Text("打开理化信息页") }
                                }
                                if (s.hasStructure) {
                                    Button(onClick = { importStructure(s) }) {
                                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("导入画布")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                        val h = hit
                        if (h != null) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    "${h.name.ifBlank { "未命名" }}   CID ${h.cid}",
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2
                                )
                                Text(
                                    buildString {
                                        if (h.cas.isNotEmpty()) append("CAS ").append(h.cas).append("    ")
                                        if (h.formula.isNotEmpty()) append(h.formula).append("    ")
                                        if (h.mass.isNotEmpty()) append(h.mass)
                                    }.trim(),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    if (h.cas.isNotEmpty()) {
                                        TextButton(onClick = {
                                            autoFollow = true
                                            status = "正在打开理化详情页…"
                                            web?.loadUrl("https://m.chemicalbook.com/CAS_" + h.cas + ".htm")
                                        }) { Text("打开理化详情页") }
                                        TextButton(onClick = {
                                            query = h.cas
                                            status = "正在站内搜 CAS ${h.cas}…"
                                            web?.evaluateJavascript(submitSearchJs(h.cas)) { r ->
                                                if (r != null && r.contains("no-field")) status = "页面上没找到搜索框，请手动输入"
                                            }
                                        }) { Text("在 ChemicalBook 搜 CAS") }
                                    }
                                    TextButton(onClick = { hit = null }) { Text("关闭") }
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                when {
                                    status.isNotEmpty() -> status
                                    pasteMol.isNotBlank() -> "molfile 已填入结构式编辑器，点页面上的「立即搜索」即可（剪贴板里也放了一份）"
                                    else -> "搜到化合物后打开「理化信息」页即可导入；也可以反查当前画的结构"
                                },
                                fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f)
                            )
                            if (status.contains("正在") || status.contains("中…") || reverseBusy) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            TextButton(onClick = { doReverseLookup() }, enabled = !reverseBusy) {
                                Text("反查当前结构", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
