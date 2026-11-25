package com.kazemail.app.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

private val viewportMetaRegex = Regex(
    "(?is)<meta\\b[^>]*\\bname\\s*=\\s*(?:\"viewport\"|'viewport'|viewport\\b)[^>]*>",
)

private val colorSchemeMetaRegex = Regex(
    "(?is)<meta\\b[^>]*\\bname\\s*=\\s*(?:\"(?:supported-)?color-scheme(?:s)?\"|'(?:supported-)?color-scheme(?:s)?'|(?:supported-)?color-scheme(?:s)?\\b)[^>]*>",
)

/**
 * Sandboxed HTML email viewer:
 * - JavaScript strictly disabled
 * - File/Content/DOM storage disabled
 * - External network/tracking-pixel loads blocked unless explicitly allowed per message
 * - Renders on crisp light paper (#FFFFFF / #111111) with WebView algorithmic darkening disabled
 *   so black text never turns low-contrast gray; optional smart dark invert toggle included
 * - Supports pinch-to-zoom, +/- zoom controls, and edge-to-edge fullscreen zoom dialog
 */
@Composable
fun SandboxedHtmlViewer(
    html: String,
    allowExternalImages: Boolean,
    @Suppress("UNUSED_PARAMETER") isDarkSurface: Boolean,
    onBlockedExternalResource: () -> Unit,
    onExternalLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAllowExternalImages: (() -> Unit)? = null,
    fitScreenWidth: Boolean = false,
) {
    var showFullscreenDialog by remember { mutableStateOf(false) }
    var useDarkInvert by remember(html) { mutableStateOf(false) }

    SandboxedHtmlPanel(
        html = html,
        allowExternalImages = allowExternalImages,
        useDarkInvert = useDarkInvert,
        isFullscreen = false,
        fitScreenWidth = fitScreenWidth,
        onToggleDarkInvert = { useDarkInvert = !useDarkInvert },
        onBlockedExternalResource = onBlockedExternalResource,
        onExternalLinkClick = onExternalLinkClick,
        onOpenFullscreen = { showFullscreenDialog = true },
        onAllowExternalImages = onAllowExternalImages,
        modifier = modifier,
    )

    if (showFullscreenDialog) {
        Dialog(
            onDismissRequest = { showFullscreenDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            IconButton(onClick = { showFullscreenDialog = false }) {
                                Icon(Icons.Filled.Close, contentDescription = "閉じる")
                            }
                            Text(
                                text = "HTML拡大表示（ピンチ・スクロール可）",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!allowExternalImages && onAllowExternalImages != null) {
                                TextButton(onClick = onAllowExternalImages) {
                                    Text("画像を表示")
                                }
                            }
                            TextButton(onClick = { useDarkInvert = !useDarkInvert }) {
                                Text(if (useDarkInvert) "白背景" else "暗反転")
                            }
                        }
                    }
                    HorizontalDivider()
                    SandboxedHtmlPanel(
                        html = html,
                        allowExternalImages = allowExternalImages,
                        useDarkInvert = useDarkInvert,
                        isFullscreen = true,
                        fitScreenWidth = false,
                        onToggleDarkInvert = { useDarkInvert = !useDarkInvert },
                        onBlockedExternalResource = onBlockedExternalResource,
                        onExternalLinkClick = onExternalLinkClick,
                        onOpenFullscreen = null,
                        onAllowExternalImages = onAllowExternalImages,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SandboxedHtmlPanel(
    html: String,
    allowExternalImages: Boolean,
    useDarkInvert: Boolean,
    isFullscreen: Boolean,
    fitScreenWidth: Boolean,
    onToggleDarkInvert: () -> Unit,
    onBlockedExternalResource: () -> Unit,
    onExternalLinkClick: (String) -> Unit,
    onOpenFullscreen: (() -> Unit)?,
    onAllowExternalImages: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val applyFitWidth = fitScreenWidth && !isFullscreen
    val wrappedHtml = remember(html, useDarkInvert, applyFitWidth) {
        prepareWrappedHtml(html, useDarkInvert = useDarkInvert, fitScreenWidth = applyFitWidth)
    }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    var baseScale by remember(wrappedHtml, allowExternalImages) { mutableStateOf<Float?>(null) }
    var currentScale by remember(wrappedHtml, allowExternalImages) { mutableFloatStateOf(1f) }

    val zoomPercent = remember(baseScale, currentScale) {
        val base = baseScale
        if (base == null || base <= 0f) {
            100
        } else {
            val rawPct = ((currentScale / base) * 100f).roundToInt()
            if (abs(rawPct - 100) <= 3) 100 else rawPct.coerceIn(25, 500)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!isFullscreen) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (allowExternalImages) "JS無効・画像可" else "JS無効",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (!allowExternalImages && onAllowExternalImages != null) {
                        TextButton(
                            onClick = onAllowExternalImages,
                            modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 30.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text("画像読込", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TextButton(
                        onClick = onToggleDarkInvert,
                        modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 30.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = if (useDarkInvert) "白背景" else "暗反転",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                } else {
                    Text(
                        text = "倍率",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp),
                    )
                }

                if (!applyFitWidth) {
                    TextButton(
                        onClick = { webViewHolder[0]?.zoomOut() },
                        modifier = Modifier.defaultMinSize(minWidth = 30.dp, minHeight = 30.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("－", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = {
                            val wv = webViewHolder[0]
                            val base = baseScale
                            if (wv != null && base != null && currentScale > 0f && abs(currentScale - base) > 0.01f) {
                                val ratio = (base / currentScale).coerceIn(0.1f, 10f)
                                wv.zoomBy(ratio)
                            }
                        },
                        enabled = zoomPercent != 100,
                        modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 30.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("$zoomPercent%", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { webViewHolder[0]?.zoomIn() },
                        modifier = Modifier.defaultMinSize(minWidth = 30.dp, minHeight = 30.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("＋", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (onOpenFullscreen != null) {
                    TextButton(
                        onClick = onOpenFullscreen,
                        modifier = Modifier.defaultMinSize(minWidth = 42.dp, minHeight = 30.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("全画面", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        SandboxedWebViewBody(
            wrappedHtml = wrappedHtml,
            allowExternalImages = allowExternalImages,
            useDarkInvert = useDarkInvert,
            allowVerticalParentScroll = !isFullscreen,
            fitScreenWidth = applyFitWidth,
            webViewHolder = webViewHolder,
            onScaleChanged = { oldScale, newScale ->
                if (baseScale == null) {
                    baseScale = oldScale.takeIf { it > 0f } ?: newScale
                }
                currentScale = newScale
            },
            onBlockedExternalResource = onBlockedExternalResource,
            onExternalLinkClick = onExternalLinkClick,
            modifier = if (isFullscreen) {
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            } else {
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SandboxedWebViewBody(
    wrappedHtml: String,
    allowExternalImages: Boolean,
    useDarkInvert: Boolean,
    allowVerticalParentScroll: Boolean,
    fitScreenWidth: Boolean,
    webViewHolder: Array<WebView?>,
    onScaleChanged: (oldScale: Float, newScale: Float) -> Unit,
    onBlockedExternalResource: () -> Unit,
    onExternalLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestAllowImages by rememberUpdatedState(allowExternalImages)
    val latestDarkInvert by rememberUpdatedState(useDarkInvert)
    val latestOnScaleChanged by rememberUpdatedState(onScaleChanged)
    val latestOnBlocked by rememberUpdatedState(onBlockedExternalResource)
    val latestOnLinkClick by rememberUpdatedState(onExternalLinkClick)

    DisposableEffect(Unit) {
        onDispose {
            webViewHolder[0]?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webViewHolder[0] = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ZoomableMailWebView(
                context = context,
                allowVerticalParentScroll = allowVerticalParentScroll,
            ).apply {
                webViewHolder[0] = this
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (allowVerticalParentScroll) {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    } else {
                        ViewGroup.LayoutParams.MATCH_PARENT
                    },
                )
                setBackgroundColor(if (latestDarkInvert) Color.rgb(20, 20, 20) else Color.WHITE)
                isVerticalScrollBarEnabled = !allowVerticalParentScroll
                isHorizontalScrollBarEnabled = !fitScreenWidth
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    @Suppress("DEPRECATION")
                    databaseEnabled = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    useWideViewPort = !fitScreenWidth
                    loadWithOverviewMode = !fitScreenWidth
                    setSupportZoom(!fitScreenWidth)
                    builtInZoomControls = !fitScreenWidth
                    displayZoomControls = false
                    loadsImagesAutomatically = latestAllowImages
                    blockNetworkLoads = !latestAllowImages
                    // Disable Chromium auto-darkening so black text never turns gray or vanishes
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        isAlgorithmicDarkeningAllowed = false
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        @Suppress("DEPRECATION")
                        forceDark = WebSettings.FORCE_DARK_OFF
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val scheme = request?.url?.scheme?.lowercase() ?: return null
                        if (!latestAllowImages && scheme != "data" && scheme != "about") {
                            view?.post { latestOnBlocked() }
                            return WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                ByteArrayInputStream(ByteArray(0)),
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return true
                        latestOnLinkClick(url)
                        return true
                    }

                    override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                        super.onScaleChanged(view, oldScale, newScale)
                        view?.post { latestOnScaleChanged(oldScale, newScale) }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (allowVerticalParentScroll && view != null) {
                            view.post { view.requestLayout() }
                            view.postVisualStateCallback(
                                0L,
                                object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        view.requestLayout()
                                    }
                                },
                            )
                            view.postDelayed({ if (view.isAttachedToWindow) view.requestLayout() }, 80L)
                        }
                    }
                }
                tag = wrappedHtml.hashCode()
                loadDataWithBaseURL("about:blank", wrappedHtml, "text/html", "UTF-8", null)
            }
        },
        update = { view ->
            webViewHolder[0] = view
            view.setBackgroundColor(if (latestDarkInvert) Color.rgb(20, 20, 20) else Color.WHITE)
            val prevBlock = view.settings.blockNetworkLoads
            view.settings.loadsImagesAutomatically = latestAllowImages
            view.settings.blockNetworkLoads = !latestAllowImages
            if (prevBlock != !latestAllowImages || view.tag != wrappedHtml.hashCode()) {
                view.tag = wrappedHtml.hashCode()
                view.resetContentHeightTracking()
                view.scrollTo(0, 0)
                view.loadDataWithBaseURL("about:blank", wrappedHtml, "text/html", "UTF-8", null)
            }
        },
    )
}

private class ZoomableMailWebView(
    context: Context,
    private val allowVerticalParentScroll: Boolean,
) : WebView(context) {
    private var downX = 0f
    private var downY = 0f
    private var lastRequestedContentHeightPx = -1

    fun resetContentHeightTracking() {
        lastRequestedContentHeightPx = -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (allowVerticalParentScroll && contentHeight > 0) {
            @Suppress("DEPRECATION")
            val currentWebScale = scale
            val expectedPx = (contentHeight * currentWebScale).roundToInt()
            if (expectedPx > 0 && abs(expectedPx - height) > 8 && expectedPx != lastRequestedContentHeightPx) {
                lastRequestedContentHeightPx = expectedPx
                post { if (isAttachedToWindow) requestLayout() }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                if (!allowVerticalParentScroll) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!allowVerticalParentScroll || settings.supportZoom()) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                val canPanHoriz = canScrollHorizontally(-1) || canScrollHorizontally(1)
                if (!allowVerticalParentScroll ||
                    (settings.supportZoom() && event.pointerCount >= 2) ||
                    (canPanHoriz && dx > dy * 1.15f)
                ) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }
}

internal fun prepareWrappedHtml(
    rawHtml: String,
    useDarkInvert: Boolean = false,
    fitScreenWidth: Boolean = false,
): String {
    val sanitizedHtml = rawHtml
        .replace(viewportMetaRegex, "")
        .replace(colorSchemeMetaRegex, "")
    val darkInvertCss = if (useDarkInvert) {
        """
            html {
                filter: invert(0.92) hue-rotate(180deg);
                background-color: #FFFFFF;
            }
            img, picture, video, svg {
                filter: invert(1) hue-rotate(180deg);
            }
        """.trimIndent()
    } else {
        ""
    }
    val fitWidthCss = if (fitScreenWidth) {
        """
            *, *::before, *::after { box-sizing: border-box; max-width: 100%; }
            html, body { width: 100%; max-width: 100%; overflow-x: hidden; }
            table, tbody, thead, tfoot, tr, td, th, div, p, blockquote, pre {
                max-width: 100% !important;
            }
            img, svg, video { max-width: 100% !important; height: auto !important; }
        """.trimIndent()
    } else {
        ""
    }
    val viewportMeta = if (fitScreenWidth) {
        "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
    } else {
        "width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes"
    }
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="$viewportMeta" />
            <meta name="color-scheme" content="light only" />
            <style>
                :root { color-scheme: light only; }
                html, body {
                    margin: 0;
                    padding: 0;
                    color: #111111;
                    background-color: #FFFFFF;
                }
                body {
                    margin: 2px 3px;
                    font-family: sans-serif;
                    font-size: 15px;
                    line-height: 1.55;
                    word-wrap: break-word;
                    overflow-wrap: anywhere;
                }
                a { color: #0B556B; }
                img { max-width: 100%; height: auto; }
                table { max-width: 100%; }
                pre, code { white-space: pre-wrap; word-break: break-word; }
                $fitWidthCss
                $darkInvertCss
            </style>
        </head>
        <body>$sanitizedHtml</body>
        </html>
    """.trimIndent()
}
