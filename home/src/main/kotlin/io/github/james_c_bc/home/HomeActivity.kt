package io.github.james_c_bc.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView

/**
 * A header naming the focused tile, with the time and date, then a row of
 * HDMI input and Settings tiles, then every installed TV app as a tile, five
 * per row, in the order apps.txt gives. Apps show their 16:9 banner, or their
 * icon and name if they have no banner.
 */
class HomeActivity : Activity() {
    private lateinit var scroller: ScrollView
    private lateinit var grid: LinearLayout
    private lateinit var focusedTitle: TextView
    private lateinit var focusedHint: TextView
    private val appRows = mutableListOf<View>()
    private lateinit var appOrder: AppOrder
    private var tvInputManager: TvInputManager? = null

    // Rebuilds the grid when an app is installed, updated or removed.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showTiles()
    }

    // Rebuilds the grid when a TV input comes or goes. At boot, the HDMI
    // inputs can turn up after Home has started.
    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = showTiles()
        override fun onInputRemoved(inputId: String) = showTiles()
        override fun onInputUpdated(inputId: String) = showTiles()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // apps.txt is built into the app, so it only changes with bin/home.
        appOrder = AppOrder.parse(assets.open(APP_ORDER).bufferedReader().use { it.readText() })
        val padH = dimen(R.dimen.screen_padding_horizontal)
        val padV = dimen(R.dimen.screen_padding_vertical)
        // The focused tile grows and has a ring outside it, so nothing in the
        // grid may clip it. Leave room for it above the top row.
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, dp(8), padH, padV)
            clipChildren = false
            clipToPadding = false
        }
        scroller = HomeScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(grid, MATCH_PARENT, WRAP_CONTENT)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(padH, padV), MATCH_PARENT, WRAP_CONTENT)
            addView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                topMargin = dimen(R.dimen.header_gap)
            })
        })
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        tvInputManager = getSystemService(TvInputManager::class.java)
        tvInputManager?.registerCallback(inputCallback, Handler(mainLooper))
        showTiles()
    }

    override fun onDestroy() {
        tvInputManager?.unregisterCallback(inputCallback)
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    // Back does nothing: Home is the bottom of the stack.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private fun showTiles() {
        val focused = grid.findFocus()?.tag
        val infos = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
        )
        val byApp = infos.associateBy {
            App(it.loadLabel(packageManager).toString().trim(), it.activityInfo.packageName, it.activityInfo.name)
        }
        val shortcuts = hdmiInputs(tvInputs()).map { input ->
            shortcut(input, getString(R.string.hdmi, input.port), getString(R.string.switch_input), R.drawable.input) {
                switchTo(input)
            }
        } + shortcut(SettingsTile, getString(R.string.settings), getString(R.string.open_settings), R.drawable.settings) {
            openSettings()
        }

        grid.removeAllViews()
        appRows.clear()
        addLabel(R.string.inputs)
        shortcuts.chunked(COLUMNS).forEach { addRow(it) }
        addLabel(R.string.apps)
        for (row in gridRows(byApp.keys.toList(), packageName, appOrder)) {
            appRows += addRow(row.map { app ->
                val info = byApp.getValue(app)
                val banner = info.activityInfo.loadBanner(packageManager)
                val face = if (banner != null) {
                    ImageView(this).apply {
                        setImageDrawable(banner)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                } else {
                    iconAndName(app.label, info.loadIcon(packageManager))
                }
                tile(app, app.label, getString(R.string.open_app, app.label), BANNER_RATIO, face, fill = banner != null) {
                    launch(app)
                }
            })
        }

        // Keep focus on the same tile across a rebuild, or start at the top.
        val target = focused?.let { grid.findViewWithTag<View>(it) } ?: shortcuts.first()
        target.requestFocus()
    }

    // The focused tile's name and what OK does, then the time and date.
    private fun header(padH: Int, padV: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        setPadding(padH, padV, padH, 0)
        focusedTitle = text(R.dimen.header_title, R.color.text, Typeface.BOLD)
        focusedHint = text(R.dimen.header_text, R.color.text_secondary)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(focusedTitle)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val badge = dimen(R.dimen.ok_badge)
                addView(text(R.dimen.ok_badge_text, R.color.text_secondary, Typeface.BOLD).apply {
                    text = getString(R.string.ok)
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.ok_badge)
                }, LinearLayout.LayoutParams(badge, badge))
                addView(focusedHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(5) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(clock(R.string.time_12, R.string.time_24, R.dimen.header_title, R.color.text))
            addView(clock(R.string.date, R.string.date, R.dimen.header_text, R.color.text_secondary),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        })
    }

    // Follows the TV's 12 or 24 hour setting.
    private fun clock(format12: Int, format24: Int, size: Int, color: Int) = TextClock(this).apply {
        format12Hour = getString(format12)
        format24Hour = getString(format24)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
        setTextColor(getColor(color))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun text(size: Int, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
        setTextColor(getColor(color))
        typeface = Typeface.create("sans-serif", style)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    // A section heading above a group of rows.
    private fun addLabel(label: Int) {
        grid.addView(text(R.dimen.section_label, R.color.text_secondary).apply {
            text = getString(label).uppercase()
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (grid.childCount > 0) topMargin = dimen(R.dimen.section_gap)
        })
    }

    // Scrolls so the focused tile's row is in view. Inputs and the first two
    // app rows show from the very top; further down, the row above the
    // focused one sits at the top of the screen.
    private fun scrollTo(row: View) {
        scroller.post {
            val top = topRow(appRows.indexOf(row))?.let { appRows[it].top - grid.paddingTop } ?: 0
            scroller.smoothScrollTo(0, top)
        }
    }

    private fun tvInputs(): List<Input> =
        tvInputManager?.tvInputList.orEmpty().map {
            // A CEC device behind an HDMI port is an input too, with the
            // port's input as its parent.
            val hdmi = it.type == TvInputInfo.TYPE_HDMI && it.isPassthroughInput && it.parentId == null
            Input(it.id, it.loadLabel(this).toString(), hdmi)
        }

    // Adds a row of tiles to the grid and returns it. A short row is padded
    // with spacers so its tiles are the same size as everyone else's.
    private fun addRow(tiles: List<View>): View {
        val gap = dimen(R.dimen.tile_gap)
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        for (column in 0 until COLUMNS) {
            rowView.addView(tiles.getOrNull(column) ?: Space(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (column > 0) marginStart = gap
            })
        }
        // Right from the end of a row stays put, rather than jumping
        // diagonally into the next row.
        tiles.lastOrNull()?.apply {
            id = View.generateViewId()
            nextFocusRightId = id
        }
        // Rows follow a heading closely and each other further apart.
        val afterLabel = grid.getChildAt(grid.childCount - 1) is TextView
        grid.addView(rowView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dimen(if (afterLabel) R.dimen.label_gap else R.dimen.row_gap)
        })
        return rowView
    }

    // An input or Settings tile: a short tile with its icon beside its name.
    private fun shortcut(tag: Any, label: String, hint: String, icon: Int, onSelect: () -> Unit): View {
        val face = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
            val size = dimen(R.dimen.shortcut_icon)
            addView(ImageView(context).apply { setImageDrawable(getDrawable(icon)) }, LinearLayout.LayoutParams(size, size))
            addView(text(R.dimen.shortcut_text, R.color.text).apply {
                text = label
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
        }
        return tile(tag, label, hint, SHORTCUT_RATIO, face, fill = false, onSelect)
    }

    // A tile [heightRatio] times as tall as it is wide, showing [face]: filling
    // it if [fill], or else across its middle. Focusing it names it, with
    // [hint], in the header.
    private fun tile(
        tag: Any,
        label: String,
        hint: String,
        heightRatio: Float,
        face: View,
        fill: Boolean,
        onSelect: () -> Unit,
    ): View =
        TileView(this, heightRatio).apply {
            this.tag = tag
            isFocusable = true
            isClickable = true
            contentDescription = label
            this.face.addView(face, FrameLayout.LayoutParams(MATCH_PARENT, if (fill) MATCH_PARENT else WRAP_CONTENT, Gravity.CENTER))

            setOnClickListener { onSelect() }
            setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) FOCUS_SCALE else 1f
                view.animate().scaleX(scale).scaleY(scale)
                    .translationZ(if (hasFocus) dp(12).toFloat() else 0f)
                    .setDuration(150).start()
                if (hasFocus) {
                    focusedTitle.text = label
                    focusedHint.text = hint
                    scrollTo(view.parent as View)
                }
            }
        }

    private fun iconAndName(label: String, icon: Drawable?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = dimen(R.dimen.tile_icon)
        addView(ImageView(context).apply {
            setImageDrawable(icon)
        }, LinearLayout.LayoutParams(size, size))
        addView(text(R.dimen.tile_text, R.color.text).apply {
            text = label
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // The TV app (com.tcl.tv) handles a pass-through input's channel URI
    // and switches straight to it.
    private fun switchTo(input: HdmiInput) {
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.inputId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No TV app to show it. Nothing to do.
        }
    }

    // Android TV Settings, not TCL's picture and sound menu.
    private fun openSettings() {
        val intent = Intent(Settings.ACTION_SETTINGS)
            .setPackage(TV_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // TV Settings is disabled. Nothing to do.
        }
    }

    private fun launch(app: App) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName(app.packageName, app.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Removed since the grid was drawn.
            showTiles()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun dimen(id: Int) = resources.getDimensionPixelSize(id)
}

private const val TV_SETTINGS = "com.android.tv.settings"

/** The owner's app order, from home/config/apps.txt. */
private const val APP_ORDER = "apps.txt"

/** The Settings tile's tag, so focus stays on it across a rebuild. */
private data object SettingsTile

/** App tiles are 16:9, like their banners. */
private const val BANNER_RATIO = 9f / 16f

/** Input and Settings tiles are shorter: 60dp tall at the TV's 160dp column. */
private const val SHORTCUT_RATIO = 3f / 8f

/** How much the focused tile grows. */
private const val FOCUS_SCALE = 1.07f

/**
 * A tile that keeps its shape at whatever width its row gives it. Its [face]
 * holds the background and content, clipped to rounded corners. When focused,
 * it draws a ring a little outside itself, so its row must not clip it.
 */
private class TileView(context: Context, private val heightRatio: Float) : FrameLayout(context) {
    val face = FrameLayout(context).apply {
        setBackgroundResource(R.drawable.tile_background)
        isDuplicateParentStateEnabled = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }

    private val radius = resources.getDimension(R.dimen.tile_radius)
    private val ringGap = resources.getDimension(R.dimen.focus_ring_gap)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.focus_ring)
        color = context.getColor(R.color.focus_ring)
    }
    private val ringBounds = RectF()

    init {
        addView(face, MATCH_PARENT, MATCH_PARENT)
        setWillNotDraw(false)
        // Gives the focused tile its shadow.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(Rect(0, 0, view.width, view.height), radius)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((width * heightRatio).toInt(), MeasureSpec.EXACTLY),
        )
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isFocused) return
        val out = ringGap + ring.strokeWidth / 2
        ringBounds.set(-out, -out, width + out, height + out)
        canvas.drawRoundRect(ringBounds, radius + out, radius + out, ring)
    }
}

/**
 * Scrolls only where Home tells it to, not just enough to show the focused
 * tile, so the row above stays in view.
 */
private class HomeScrollView(context: Context) : ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0
}
