package com.glass.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

data class AppItem(
    val label: String,
    val pkg: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    val category: Int,
)

class Actions(
    val launch: (AppItem, android.graphics.Rect?) -> Unit,
    val info: (AppItem) -> Unit,
    val uninstall: (AppItem) -> Unit,
)

class MainActivity : ComponentActivity() {
    private val apps = mutableStateListOf<AppItem>()
    private val dock = mutableStateListOf<AppItem>()
    private val homeSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        addOnNewIntentListener { homeSignal.intValue++ }
        val actions = Actions(::launch, ::openInfo, ::uninstall)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LauncherScreen(apps, dock, homeSignal.intValue, actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val (list, d) = withContext(Dispatchers.Default) { queryApps() }
            if (list.map { it.component } != apps.map { it.component }) {
                apps.clear(); apps.addAll(list)
                dock.clear(); dock.addAll(d)
            }
        }
    }

    private fun queryApps(): Pair<List<AppItem>, List<AppItem>> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { ri ->
                AppItem(
                    ri.loadLabel(pm).toString(),
                    ri.activityInfo.packageName,
                    ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                    ri.loadIcon(pm).toIcon(),
                    ri.activityInfo.applicationInfo.category,
                )
            }
            .sortedBy { it.label.lowercase() }

        val probes = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        )
        var d = probes
            .mapNotNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .mapNotNull { p -> list.firstOrNull { it.pkg == p } }
            .distinct()
        if (d.isEmpty()) d = list.take(4)
        return list to d
    }

    private fun launch(app: AppItem, r: android.graphics.Rect?) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            val opts = r?.let {
                ActivityOptions.makeScaleUpAnimation(window.decorView, it.left, it.top, it.width(), it.height())
            }
            startActivity(intent, opts?.toBundle())
        } catch (_: Exception) {
        }
    }

    private fun openInfo(app: AppItem) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        } catch (_: Exception) {
        }
    }

    private fun uninstall(app: AppItem) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
        } catch (_: Exception) {
        }
    }
}

/* ---------- Yardımcılar ---------- */

fun Drawable.toIcon(size: Int = 192): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val d = this
    if (d is AdaptiveIconDrawable) {
        val pad = size / 4
        d.background?.let { it.setBounds(-pad, -pad, size + pad, size + pad); it.draw(c) }
        d.foreground?.let { it.setBounds(-pad, -pad, size + pad, size + pad); it.draw(c) }
    } else {
        d.setBounds(0, 0, size, size)
        d.draw(c)
    }
    return bmp.asImageBitmap()
}

val Squircle: Shape = GenericShape { size, _ ->
    val n = 5.0
    val a = size.width / 2.0
    val b = size.height / 2.0
    val steps = 96
    for (i in 0..steps) {
        val t = 2.0 * Math.PI * i / steps
        val cs = cos(t)
        val sn = sin(t)
        val x = (a + a * sign(cs) * abs(cs).pow(2.0 / n)).toFloat()
        val y = (b + b * sign(sn) * abs(sn).pow(2.0 / n)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

fun Modifier.glass(shape: Shape, alpha: Float = 0.22f): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = alpha + 0.10f), Color.White.copy(alpha = (alpha - 0.08f).coerceAtLeast(0.04f)))
        )
    )
    .border(
        1.dp,
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.40f))),
        shape,
    )

fun categoryName(c: Int): String = when (c) {
    ApplicationInfo.CATEGORY_SOCIAL -> "Sosyal"
    ApplicationInfo.CATEGORY_GAME -> "Oyunlar"
    ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> "Eğlence ve Medya"
    ApplicationInfo.CATEGORY_NEWS -> "Haberler"
    ApplicationInfo.CATEGORY_MAPS -> "Haritalar"
    ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Üretkenlik"
    else -> "Diğer"
}

val CategoryOrder = listOf("Sosyal", "Oyunlar", "Eğlence ve Medya", "Haberler", "Haritalar", "Üretkenlik", "Diğer")

/* ---------- Ana ekran ---------- */

@Composable
fun LauncherScreen(apps: List<AppItem>, dock: List<AppItem>, homeSignal: Int, act: Actions) {
    val homeApps = apps.filter { it !in dock }
    val homePages = listOf(homeApps.take(16)) + homeApps.drop(16).chunked(20)
    val pageCount = homePages.size + 1
    val pager = rememberPagerState { pageCount }

    var search by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<Pair<String, List<AppItem>>?>(null) }

    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) {
            search = false
            folder = null
            pager.animateScrollToPage(0)
        }
    }
    BackHandler(enabled = search || folder != null) {
        if (folder != null) folder = null else search = false
    }

    val blurRadius by animateDpAsState(if (search || folder != null) 18.dp else 0.dp, label = "blur")
    val launchAndClose: (AppItem, android.graphics.Rect?) -> Unit = { a, r -> folder = null; act.launch(a, r) }
    val folderActions = Actions(launchAndClose, act.info, act.uninstall)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 140f) search = true },
                    onDragCancel = { total = 0f },
                ) { _, dy -> total += dy }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
                .systemBarsPadding()
        ) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { p ->
                if (p < homePages.size) {
                    HomePage(homePages[p], widget = p == 0, act = act)
                } else {
                    AppLibrary(apps, onFolder = { n, l -> folder = n to l }, onSearch = { search = true })
                }
            }
            PageDots(pageCount, pager.currentPage)
            Dock(dock, act)
        }

        AnimatedVisibility(visible = folder != null, enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut() + scaleOut(targetScale = 0.85f)) {
            folder?.let { (name, list) -> FolderOverlay(name, list, folderActions) { folder = null } }
        }
        AnimatedVisibility(visible = search, enter = fadeIn(), exit = fadeOut()) {
            SearchOverlay(apps, act) { search = false }
        }
    }
}

@Composable
fun HomePage(items: List<AppItem>, widget: Boolean, act: Actions) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (widget) {
            ClockWidget()
            Spacer(Modifier.height(12.dp))
        }
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                row.forEach { app ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AppIcon(app, true, act) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun ClockWidget() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val date = remember(now) { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .glass(RoundedCornerShape(28.dp))
            .padding(horizontal = 22.dp, vertical = 14.dp)
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(date, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(time, color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(app: AppItem, showLabel: Boolean, act: Actions) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.5f), label = "press")
    var menu by remember { mutableStateOf(false) }
    var rect by remember { mutableStateOf<android.graphics.Rect?>(null) }

    Column(
        Modifier.combinedClickable(
            interactionSource = src,
            indication = null,
            onLongClick = { menu = true },
            onClick = { act.launch(app, rect) },
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier
                .size(60.dp)
                .onGloballyPositioned {
                    val b = it.boundsInWindow()
                    rect = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                }
                .scale(scale)
                .clip(Squircle),
        )
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(
                app.label,
                modifier = Modifier.width(74.dp),
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 6f)),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Uygulama bilgisi") }, onClick = { menu = false; act.info(app) })
            DropdownMenuItem(text = { Text("Kaldır") }, onClick = { menu = false; act.uninstall(app) })
        }
    }
}

@Composable
fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == current) 0.95f else 0.38f))
            )
        }
    }
}

@Composable
fun Dock(dock: List<AppItem>, act: Actions) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .glass(RoundedCornerShape(34.dp), 0.24f)
            .padding(vertical = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            dock.forEach { AppIcon(it, false, act) }
        }
    }
}

/* ---------- Uygulama Kitaplığı ---------- */

@Composable
fun AppLibrary(apps: List<AppItem>, onFolder: (String, List<AppItem>) -> Unit, onSearch: () -> Unit) {
    val groups = apps.groupBy { categoryName(it.category) }
        .toList()
        .sortedBy { CategoryOrder.indexOf(it.first) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .glass(RoundedCornerShape(24.dp), 0.22f)
                .clickable { onSearch() }
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("Uygulama Kitaplığı", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
        }
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(groups) { g -> FolderTile(g.first, g.second) { onFolder(g.first, g.second) } }
        }
    }
}

@Composable
fun FolderTile(name: String, list: List<AppItem>, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .glass(RoundedCornerShape(30.dp), 0.20f)
                .clickable { onClick() }
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.take(4).chunked(2).forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        r.forEach {
                            Image(it.icon, null, Modifier.weight(1f).aspectRatio(1f).clip(Squircle))
                        }
                        if (r.size < 2) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun FolderOverlay(name: String, list: List<AppItem>, act: Actions, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.30f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() }
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .glass(RoundedCornerShape(36.dp), 0.28f)
                .padding(20.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            list.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    row.forEach { app ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AppIcon(app, true, act) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/* ---------- Arama ---------- */

@Composable
fun SearchOverlay(apps: List<AppItem>, act: Actions, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = if (q.isBlank()) emptyList() else apps.filter { it.label.contains(q, ignoreCase = true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .glass(RoundedCornerShape(26.dp), 0.25f)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Ara", color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp)
                BasicTextField(
                    value = q,
                    onValueChange = { q = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(Color.White),
                )
            }
            Text("İptal", color = Color.White, modifier = Modifier.clickable { onClose() })
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            items(results) { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { act.launch(a, null); onClose() }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(a.icon, null, Modifier.size(44.dp).clip(Squircle))
                    Spacer(Modifier.width(14.dp))
                    Text(a.label, color = Color.White, fontSize = 17.sp)
                }
            }
        }
    }
}
