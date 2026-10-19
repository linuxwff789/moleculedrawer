package com.moldraw.app.ui.moleculedraw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val REPO_URL = "https://github.com/linuxwff789/moleculedrawer"
private const val LATEST_RELEASE_PAGE = "$REPO_URL/releases/latest"
private const val TAG_PREFIX = "$REPO_URL/releases/tag/"
private const val CHANGELOG_URL = "$REPO_URL/blob/master/CHANGELOG.md"
private const val ISSUES_URL = "$REPO_URL/issues"

/** GitHub 上最新的 release（只用网页重定向取版本号，避开 API 限流） */
private data class ReleaseInfo(
    val tag: String,
    val downloadUrl: String,
    val pageUrl: String
)

/**
 * 请求 releases/latest，跟随 302 后从最终 URL 里取 tag，再按固定的资产命名拼出 APK 地址。
 * 不走 api.github.com：未认证配额 60 次/小时，共享出口 IP 经常直接 403。
 */
private fun fetchLatestRelease(): ReleaseInfo {
    val conn = (URL(LATEST_RELEASE_PAGE).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000
        readTimeout = 6000
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("User-Agent", "MoleculeDraw-Android")
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val finalUrl = conn.url.toString()
        val tag = finalUrl.substringAfter("/releases/tag/", "").trim('/').substringBefore('?')
        if (tag.isBlank()) throw IllegalStateException("没有找到 release 版本号")
        val apk = "$REPO_URL/releases/download/$tag/moleculedrawer-$tag-release-arm64-v8a.apk"
        return ReleaseInfo(tag, apk, "$TAG_PREFIX$tag")
    } finally {
        conn.disconnect()
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        android.widget.Toast.makeText(context, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun copyText(context: Context, text: String, tip: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("moldraw", text))
    android.widget.Toast.makeText(context, tip, android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * 关于页：版本、最新版下载地址、项目链接。
 * 最新版信息实时从 GitHub API 取；取不到（无网 / 被墙）就退回固定链接，功能不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: "?"
    val versionCode = packageInfo?.let {
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)
    } ?: 0L

    var loading by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<ReleaseInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { runCatching { fetchLatestRelease() } }
        result.onSuccess { info = it }.onFailure { error = it.message ?: "网络错误" }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFE8F0FE)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Science, null, tint = Color(0xFF1A73E8), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("分子编辑器", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text("版本 $versionName ($versionCode)", fontSize = 12.sp, color = Color(0xFF78909C))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "分子结构式绘制器：点按/拖拽画结构，支持 SMILES 与 Molfile 导入、官能团缩写、反应箭头、" +
                    "多文档，导出 PNG / JPEG / SVG / PDF，并可用 Indigo 计算分子式与分子量。",
                fontSize = 13.sp, color = Color(0xFF546E7A), lineHeight = 19.sp
            )

            Spacer(Modifier.height(18.dp))
            Text("最新版本", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Surface(
                color = Color(0xFFF7F9FC),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = Color(0xFF1A73E8)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("正在检查 GitHub 上的最新版…", fontSize = 13.sp, color = Color(0xFF607D8B))
                        }
                        info != null -> {
                            val r = info!!
                            val isLatest = r.tag.trimStart('v') == versionName
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.tag, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(8.dp))
                                if (isLatest) {
                                    Text(
                                        "已是最新",
                                        fontSize = 11.sp,
                                        color = Color(0xFF2E7D32),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFE8F5E9))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                } else {
                                    Text(
                                        "有新版本",
                                        fontSize = 11.sp,
                                        color = Color(0xFFC62828),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFFFEBEE))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                r.downloadUrl,
                                fontSize = 11.sp,
                                color = Color(0xFF1A73E8),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { openUrl(context, r.downloadUrl) }
                            )
                        }
                        else -> {
                            Text("无法获取最新版信息（${error ?: "网络不可用"}）", fontSize = 13.sp, color = Color(0xFFC62828))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                LATEST_RELEASE_PAGE,
                                fontSize = 11.sp,
                                color = Color(0xFF1A73E8),
                                modifier = Modifier.clickable { openUrl(context, LATEST_RELEASE_PAGE) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { openUrl(context, info?.downloadUrl ?: LATEST_RELEASE_PAGE) },
                    modifier = Modifier.weight(1f).height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (info != null && info!!.tag.trimStart('v') != versionName) "下载最新版" else "打开发布页", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = {
                        copyText(context, info?.downloadUrl ?: LATEST_RELEASE_PAGE, "下载链接已复制")
                    },
                    modifier = Modifier.weight(1f).height(42.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制链接", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("链接", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            LinkRow("项目主页", REPO_URL) { openUrl(context, REPO_URL) }
            LinkRow("更新日志", CHANGELOG_URL) { openUrl(context, CHANGELOG_URL) }
            LinkRow("问题反馈", ISSUES_URL) { openUrl(context, ISSUES_URL) }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFFEEEEEE))
            Spacer(Modifier.height(12.dp))
            Text(
                "结构布局与分子式/分子量计算使用 EPAM Indigo（Apache-2.0）。" +
                    "所有文档都保存在本机，不上传任何服务器。",
                fontSize = 11.sp, color = Color(0xFF90A4AE), lineHeight = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            Text("© 2026 linuxwff789", fontSize = 11.sp, color = Color(0xFFB0BEC5))
        }
    }
}

/** 一行可点击的外链 */
@Composable
private fun LinkRow(label: String, url: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Link, null, tint = Color(0xFF90A4AE), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = Color(0xFF37474F))
            Text(url, fontSize = 10.sp, color = Color(0xFF90A4AE), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFCFD8DC), modifier = Modifier.size(18.dp))
    }
}
