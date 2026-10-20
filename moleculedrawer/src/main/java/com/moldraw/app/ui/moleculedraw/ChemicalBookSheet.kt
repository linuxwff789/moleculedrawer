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

/** PubChem 反查结果 */
internal data class PcHit(
    val cid: Int,
    val name: String,
    val formula: String,
    val mass: String,
    val cas: String,
    val smiles: String
)

private fun cbHttpGet(url: String): String {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 20000
        setRequestProperty("User-Agent", CB_UA)
    }
    return c.inputStream.bufferedReader().use { it.readText() }
}

/**
 * 用结构（SMILES）反查这是什么化合物：PubChem 精确结构匹配。
 * ChemicalBook 本身只支持名称/CAS 检索，所以结构 → 名称这步只能走 PubChem。
 */
internal fun pubchemLookup(smiles: String): PcHit? = runCatching {
    val enc = URLEncoder.encode(smiles, "UTF-8")
    val cidsJson = JSONObject(cbHttpGet("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/smiles/$enc/cids/JSON"))
    val arr = cidsJson.optJSONObject("IdentifierList")?.optJSONArray("CID") ?: return null
    if (arr.length() == 0) return null
    val cid = arr.getInt(0)
    val propJson = JSONObject(
        cbHttpGet("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/$cid/property/Title,IUPACName,MolecularFormula,MolecularWeight,ConnectivitySMILES/JSON")
    )
    val p = propJson.optJSONObject("PropertyTable")?.optJSONArray("Properties")?.optJSONObject(0)
    val xrefJson = JSONObject(cbHttpGet("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/$cid/xrefs/RegistryID/JSON"))
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

/** 用 InChIKey / CAS 从 PubChem 换 SMILES（页面既没 mol 也没 SMILES 时的兜底） */
internal fun pubchemSmilesOf(query: String, byInchiKey: Boolean): String? = runCatching {
    val enc = URLEncoder.encode(query, "UTF-8")
    val url = if (byInchiKey)
        "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/inchikey/$enc/property/ConnectivitySMILES/JSON"
    else
        "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/name/$enc/property/ConnectivitySMILES/JSON"
    val txt = cbHttpGet(url)
    JSONObject(txt).optJSONObject("PropertyTable")?.optJSONArray("Properties")?.optJSONObject(0)
        ?.optString("ConnectivitySMILES")?.takeIf { it.isNotBlank() }
}.getOrNull()

/** 页面上嗅探到的结构信息 */
private data class Sniffed(
    val smiles: String = "",
    val cas: String = "",
    val molUrl: String = "",
    val propUrl: String = "",
    val inchiKey: String = "",
    val title: String = ""
) {
    val hasStructure: Boolean get() = molUrl.isNotEmpty() || smiles.isNotEmpty() || inchiKey.isNotEmpty() || cas.isNotEmpty()
    /** 卡片上显示的来源说明 */
    val source: String
        get() = when {
            molUrl.isNotEmpty() -> "MOL 文件"
            smiles.isNotEmpty() -> "SMILES"
            inchiKey.isNotEmpty() -> "InChIKey → PubChem"
            cas.isNotEmpty() -> "CAS → PubChem"
            else -> "无"
        }
}

/** 页面脚本放在 res/raw/cb_scripts.js：Kotlin 字符串里塞 JS 太容易踩转义 */
private fun cbScripts(ctx: android.content.Context): String =
    runCatching {
        ctx.resources.openRawResource(com.moldraw.app.R.raw.cb_scripts)
            .bufferedReader().use { it.readText() }
    }.getOrDefault("")

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
    initialUrl: String = "",
    autoImport: Boolean = false,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
    var autoImportQueue by remember { mutableStateOf<Sniffed?>(null) }

    fun doSniff(w: WebView) {
        w.evaluateJavascript(cbScripts(context)) {
        w.evaluateJavascript("window.__cbSniff ? window.__cbSniff() : '{}'") { raw ->
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
                    inchiKey = o.optString("inchi"),
                    title = o.optString("title")
                )
            }.getOrNull()
            // 商品页没有结构但有「理化信息」链接时也要显示，好让用户跳过去
            android.util.Log.d("MOLDRAW_DEBUG", "[CB] sniff cas=${s?.cas} mol=${s?.molUrl} smiles=${s?.smiles} prop=${s?.propUrl}")
            if (s != null && (s.hasStructure || s.propUrl.isNotEmpty() || s.cas.isNotEmpty())) sniffed = s
            if (autoImport && s != null && s.hasStructure) autoImportQueue = s
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

        fun fallbackSmiles(reason: String) {
            if (s.smiles.isNotEmpty()) {
                processSmilesImport(s.smiles, layoutEngine, atoms, bonds, annotations, selectedIds, gNextId, canvasSizePx)
                status = "$reason，已按页面 SMILES 导入（${atoms.size} 原子）"
                sniffed = null
                return
            }
            val key = when {
                s.inchiKey.isNotEmpty() -> "InChIKey"
                s.cas.isNotEmpty() -> "CAS"
                else -> ""
            }
            if (key.isEmpty()) { status = "$reason，页面上也没有 SMILES / InChIKey / CAS"; return }
            status = "$reason，正在用 $key 去 PubChem 换结构…"
            scope.launch {
                val smi = withContext(Dispatchers.IO) {
                    when {
                        s.inchiKey.isNotEmpty() -> pubchemSmilesOf(s.inchiKey, byInchiKey = true)
                        else -> pubchemSmilesOf(s.cas, byInchiKey = false)
                    }
                }
                android.util.Log.d("MOLDRAW_DEBUG", "[CB] $key→PubChem smiles=$smi")
                if (smi.isNullOrBlank()) {
                    status = "$reason，PubChem 也没查到"
                } else {
                    processSmilesImport(smi, layoutEngine, atoms, bonds, annotations, selectedIds, gNextId, canvasSizePx)
                    status = "$reason，已用 PubChem 结构导入（${atoms.size} 原子）"
                    sniffed = null
                }
            }
        }

        if (s.molUrl.isNotEmpty()) {
            status = "正在下载 mol 文件…"
            downloadMol(s.molUrl) { mol ->
                if (!mol.isNullOrBlank() && (mol.contains("V2000") || mol.contains("V3000"))) {
                    processMolImport(mol, layoutEngine, atoms, bonds, annotations, selectedIds, gNextId, canvasSizePx)
                    status = "已导入 ${atoms.size} 个原子（MOL 文件）"
                    sniffed = null
                } else {
                    fallbackSmiles("mol 下载失败")
                }
            }
        } else {
            fallbackSmiles("页面没有 MOL 文件")
        }
    }

    // autoImport 调试/自测用：结构一旦嗅探到就自动导入（放在 importStructure 定义之后，避免前向引用）
    androidx.compose.runtime.LaunchedEffect(autoImportQueue) {
        val q = autoImportQueue
        if (q != null) {
            autoImportQueue = null
            importStructure(q)
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
        web?.evaluateJavascript(cbScripts(context)) {
            web?.evaluateJavascript("window.__cbSubmitSearch ? window.__cbSubmitSearch(" + JSONObject.quote(q) + ") : 'no-field'") { r ->
                if (r != null && r.contains("no-field")) status = "页面上没找到搜索框，请手动输入"
            }
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
                                            view.evaluateJavascript(cbScripts(view.context)) {
                                                view.evaluateJavascript("window.__cbFillMol ? window.__cbFillMol(" + JSONObject.quote(pasteMol) + ") : 'no-script'") { r ->
                                                    android.util.Log.d("MOLDRAW_DEBUG", "[CB] fillMol r=$r")
                                                }
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
                                                view.evaluateJavascript("window.__cbSubmitSearch ? window.__cbSubmitSearch(" + JSONObject.quote(q) + ") : 'no-field'") { r ->
                                                    if (r != null && r.contains("no-field")) status = "页面上没找到搜索框，请手动输入"
                                                }
                                            }
                                        }
                                        view?.let { doSniff(it) }
                                    }
                                }
                                loadUrl(
                                    when {
                                        initialUrl.isNotBlank() -> initialUrl
                                        pasteMol.isNotBlank() -> CB_STRUCT_SEARCH
                                        else -> CB_HOME
                                    }
                                )
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
                                            web?.evaluateJavascript("window.__cbSubmitSearch ? window.__cbSubmitSearch(" + JSONObject.quote(h.cas) + ") : 'no-field'") { r ->
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
