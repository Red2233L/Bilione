package com.android.bilione

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.android.purebilibili.core.store.TokenManager
import com.android.bilione.network.NetworkModule
import com.android.bilione.network.WbiUtils
import com.android.purebilibili.data.model.response.RecommendItem
import com.android.purebilibili.data.model.response.ReplyItem
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

enum class DetailPanel { LEFT, RIGHT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkModule.init(this)

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                var isLoggedIn by remember { mutableStateOf<Boolean?>(null) }
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
                var selectedVideoBvid by remember { mutableStateOf("") }

                // 单手模式状态
                var isOneHandedMode by remember { mutableStateOf(false) }

                // 状态提升
                var homeVideoList by remember { mutableStateOf<List<RecommendItem>>(emptyList()) }
                var isHomeLoading by remember { mutableStateOf(true) }
                var isRefreshing by remember { mutableStateOf(false) }
                val homeListState = rememberLazyListState()

                val scope = rememberCoroutineScope()
                var detailPanel by remember { mutableStateOf(DetailPanel.LEFT) }
                val movableVideoDetail = remember {
                    movableContentOf { bvid: String, onBack: () -> Unit, onToggleOneHanded: () -> Unit ->
                        VideoDetailScreen(
                            bvid = bvid,
                            onBack = onBack,
                            onToggleOneHandedMode = onToggleOneHanded
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    TokenManager.init(context)
                    delay(200)
                    isLoggedIn = !TokenManager.sessDataCache.isNullOrBlank()
                }


                BackHandler(enabled = isOneHandedMode) {
                    isOneHandedMode = false
                }
                // 其次退出详情页
                BackHandler(enabled = !isOneHandedMode && currentScreen == Screen.VideoDetail) {
                    currentScreen = Screen.Home
                }

                // 加载首页数据
                suspend fun loadHomeData(isRefresh: Boolean = false) {
                    try {
                        val baseParams = mapOf(
                            "ps" to "20",
                            "fresh_type" to if (isRefresh) "3" else "3",
                            "fresh_idx" to if (isRefresh) "0" else "0",
                            "y_num" to "0"
                        )
                        val imgKey = "77075d1616ad42aab384d46384304e02"
                        val subKey = "62e15a4272d44051b6c4f164062de936"
                        val finalParams = WbiUtils.sign(baseParams, imgKey, subKey)
                        val resp = NetworkModule.api.getRecommendParams(finalParams)

                        val newList = resp.data?.item ?: emptyList()
                        homeVideoList = newList
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        isHomeLoading = false
                        isRefreshing = false
                    }
                }

                // 首次加载
                LaunchedEffect(Unit) {
                    if (homeVideoList.isNotEmpty()) return@LaunchedEffect
                    loadHomeData()
                }

                // 用单手模式包装器包裹所有内容
                OneHandedModeWrapper(
                    enabled = isOneHandedMode,
                    onDismiss = { isOneHandedMode = false },
                    rightPanelContent = if (isOneHandedMode && detailPanel == DetailPanel.RIGHT) {
                        {
                            movableVideoDetail(
                                selectedVideoBvid,
                                {
                                    // onBack
                                    isOneHandedMode = false
                                    detailPanel = DetailPanel.LEFT
                                    currentScreen = Screen.Home
                                },
                                {
                                    // onToggleOneHanded
                                    isOneHandedMode = !isOneHandedMode
                                }
                            )
                        }
                    } else null,
                    onRightBoxClick = {
                        if (currentScreen == Screen.VideoDetail && detailPanel == DetailPanel.LEFT) {
                            detailPanel = DetailPanel.RIGHT
                            currentScreen = Screen.Home
                        }
                    }
                ) {
                    // 左侧主内容
                    when (isLoggedIn) {
                        true -> {
                            when (currentScreen) {
                                Screen.Home -> {
                                    HomeScreen(
                                        listState = homeListState,
                                        videoList = homeVideoList,
                                        isLoading = isHomeLoading,
                                        isRefreshing = isRefreshing,
                                        onVideoClick = { bvid ->
                                            selectedVideoBvid = bvid
                                            currentScreen = Screen.VideoDetail
                                            detailPanel = DetailPanel.LEFT   // 进入详情页时重置为左侧
                                        },
                                        onToggleOneHandedMode = { isOneHandedMode = !isOneHandedMode },
                                        onRefresh = {
                                            isRefreshing = true
                                            scope.launch { loadHomeData(isRefresh = true) }
                                        }
                                    )
                                }
                                Screen.VideoDetail -> {
                                    if (!isOneHandedMode || detailPanel == DetailPanel.LEFT) {
                                        // 普通模式或详情页在左侧
                                        movableVideoDetail(
                                            selectedVideoBvid,
                                            {
                                                currentScreen = Screen.Home
                                                detailPanel = DetailPanel.LEFT
                                            },
                                            { isOneHandedMode = !isOneHandedMode }
                                        )
                                    } else {
                                        // 详情页已移动到右侧，主区域可显示占位
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("详情页已移至右侧", color = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }
                        false -> QrLoginScreen { isLoggedIn = true }
                        null -> Unit
                    }
                }
            }
        }
    }
}

// ==============================================
@Composable
fun OneHandedModeWrapper(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onDismiss: () -> Unit,
    rightPanelContent: @Composable (() -> Unit)? = null,
    onRightBoxClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // 半透明蒙层（点击关闭单手模式）
        if (enabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable { onDismiss() }
            )
        }

        // 主内容区域（缩放层）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (enabled) {
                        Modifier
                            .background(MaterialTheme.colorScheme.background)
                            .graphicsLayer {
                                scaleX = 0.8f
                                scaleY = 0.8f
                                transformOrigin = TransformOrigin(0f, 1f)  // 左下角为锚点
                            }
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
        }

        // 右侧紫色面板（仅在单手模式显示）
        if (enabled) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxHeight(0.75f)
                    .fillMaxWidth(0.2f)
                    .background(Color(0xFFE1BEE7))
            ) {

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFBBDEFB)).clickable { onRightBoxClick() },  // 淡蓝（Material Light Blue 100）
                    contentAlignment = Alignment.Center
                ) {
                    rightPanelContent?.invoke() ?: Text("点击移动详情页", color = Color.DarkGray)
                }


                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFE1BEE7)),   // 淡紫（Material Purple 100）
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = Color.DarkGray)
                }


                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFFFF9C4)),   // 淡黄（Material Yellow 100）
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = Color.DarkGray)
                }
            }
        }
    }
}
// ==============================================
// 评论页面
// ==============================================
@Composable
fun VideoCommentScreen(
    aid: Long,
    viewModel: VideoCommentViewModel = viewModel()
) {
    val commentState = viewModel.commentState.collectAsStateWithLifecycle().value

    LaunchedEffect(aid) {
        viewModel.init(aid)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "评论 (${commentState.replyCount})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        if (commentState.isRepliesLoading && commentState.replies.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        commentState.repliesError?.let { errorMsg ->
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(text = errorMsg, color = MaterialTheme.colorScheme.error)
            }
            return@Column
        }

        if (commentState.replies.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(text = "暂无评论")
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(commentState.replies.size, key = { index -> commentState.replies[index].rpid }) { index ->
                CommentItem(item = commentState.replies[index])
            }
            if (!commentState.isRepliesEnd && !commentState.isRepliesLoading) {
                item {
                    LaunchedEffect(Unit) { viewModel.loadComments() }
                    Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentItem(item: ReplyItem) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = item.member.uname,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = item.content.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

// 页面枚举
sealed class Screen {
    object Home : Screen()
    object VideoDetail : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    listState: LazyListState,
    videoList: List<RecommendItem>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    onVideoClick: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleOneHandedMode: () -> Unit
) {
    val refreshState = rememberPullToRefreshState()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "推荐",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "🩻",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onToggleOneHandedMode() }
                    .padding(8.dp)
            )
        }

        if (isLoading && videoList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                state = refreshState,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(videoList) { item ->
                        VideoCard(item = item) {
                            onVideoClick(item.bvid ?: "")
                        }
                    }
                }
            }
        }
    }
}

// ==============================================
@Composable
fun VideoCard(item: RecommendItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val imageRequest = remember(item.pic) {
        ImageRequest.Builder(context)
            .data(item.pic)
            .addHeader("Referer", "https://www.bilibili.com")
            .build()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(12.dp))
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = item.title ?: "",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "${item.owner?.name} • ${formatViewCount(item.stat?.view)} 播放",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}


@Composable
fun VideoDetailScreen(
    bvid: String,
    onBack: () -> Unit,
    onToggleOneHandedMode: () -> Unit
) {
    var aid by remember { mutableLongStateOf(0L) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(bvid) {
        scope.launch {
            try {
                val viewResp = NetworkModule.api.getVideoInfo(bvid)
                aid = viewResp.data?.aid ?: 0L
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // 返回栏（包含返回按钮 + 一步按钮）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "← 返回",
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .weight(1f)
            )
            Text(
                text = "单手",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onToggleOneHandedMode() }
                    .padding(8.dp)
            )
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            VideoCommentScreen(aid = aid)
        }
    }
}

// ==============================================
// 工具函数
// ==============================================
fun formatViewCount(count: Long?): String {
    val num = count ?: 0
    return when {
        num >= 10000 -> "%.1f万".format(num / 10000f)
        else -> num.toString()
    }
}

// ==============================================
// 二维码登录
// ==============================================
@Composable
fun QrLoginScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loginStatus by remember { mutableStateOf("获取二维码中...") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val qrResp = NetworkModule.passportApi.generateQrCode()
                val qrKey = qrResp.data?.qrcode_key ?: return@launch
                val qrUrl = qrResp.data.url ?: return@launch

                val size = 280
                val writer = QRCodeWriter()
                val matrix = writer.encode(qrUrl, BarcodeFormat.QR_CODE, size, size)
                val bmp = createBitmap(size, size)
                for (x in 0 until size) for (y in 0 until size) {
                    bmp[x, y] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
                qrBitmap = bmp

                repeat(60) {
                    val pollResp = NetworkModule.passportApi.pollQrCode(qrKey)
                    val data = pollResp.body()?.data

                    when (data?.code) {
                        0 -> {
                            loginStatus = "登录成功"
                            val sessData = pollResp.headers()
                                .firstOrNull { it.first.equals("Set-Cookie", true) && it.second.startsWith("SESSDATA=") }
                                ?.second?.split(";")?.firstOrNull()?.substringAfter("SESSDATA=")

                            if (!sessData.isNullOrEmpty()) {
                                launch {
                                    TokenManager.saveCookies(context, sessData)
                                    onLoginSuccess()
                                }
                            }
                            return@launch
                        }
                        86101 -> loginStatus = "等待扫码"
                        86090 -> loginStatus = "已扫码，请确认"
                        86038 -> loginStatus = "二维码已过期"
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                loginStatus = "异常：${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("扫码登录", fontSize = 20.sp)
        Spacer(Modifier.height(20.dp))

        qrBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(280.dp)
            )
        } ?: CircularProgressIndicator(modifier = Modifier.size(60.dp))

        Spacer(Modifier.height(20.dp))
        Text(loginStatus, fontSize = 16.sp)
    }
}