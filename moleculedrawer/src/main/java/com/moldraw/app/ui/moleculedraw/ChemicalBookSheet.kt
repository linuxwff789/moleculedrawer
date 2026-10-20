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
  var p = document.querySelector('a[href*="ChemicalProductProperty"]');
  if (p) out.prop = p.href;
  if (!out.cas) {
    var m2 = ((document.body && document.body.innerText) || '').match(/\b[0-9]{2,7}-[0-9]{2}-[0-9]\b/);
    if (m2) out.cas = m2[0];
  }
  return JSON.stringify(out);
})()
"""

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
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var sniffed by remember { mutableStateOf<Sniffed?>(null) }
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
                        keyboardActions = KeyboardActions(onSearch = {
                            if (query.isNotBlank()) {
                                status = "搜索中…"
                                sniffed = null
                                web?.evaluateJavascript(submitSearchJs(query.trim())) { r ->
                                    if (r != null && r.contains("no-field")) {
                                        status = "页面上没找到搜索框，请手动输入"
                                    }
                                }
                            }
                        })
                    )
                    IconButton(onClick = {
                        if (query.isNotBlank()) {
                            status = "搜索中…"
                            sniffed = null
                            web?.evaluateJavascript(submitSearchJs(query.trim())) { r ->
                                if (r != null && r.contains("no-field")) status = "页面上没找到搜索框，请手动输入"
                            }
                        }
                    }) { Icon(Icons.Default.Search, contentDescription = "搜索") }
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
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        loading = true
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean = false

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loading = false
                                        view?.let { doSniff(it) }
                                    }
                                }
                                loadUrl(CB_HOME)
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
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (status.isNotEmpty()) Text(status, fontSize = 12.sp, maxLines = 2)
                        else Text("在站内搜到化合物后，打开「理化信息」页即可导入", fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        if (status.contains("正在") || status.contains("中…")) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}
