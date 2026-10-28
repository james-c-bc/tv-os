package io.github.james_c_bc.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.TextView

/**
 * A row of HDMI input and Settings tiles, then every installed TV app as a
 * tile, five per row, in the order apps.txt gives. Apps show their 16:9 banner, or their
 * icon and name if they have no banner.
 */
class HomeActivity : Activity() {
    private lateinit var grid: LinearLayout
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
        val padH = resources.getDimensionPixelSize(R.dimen.screen_padding_horizontal)
        val padV = resources.getDimensionPixelSize(R.dimen.screen_padding_vertical)
        // The focused tile grows, so nothing may clip it.
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
            clipChildren = false
            clipToPadding = false
        }
        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(grid, MATCH_PARENT, WRAP_CONTENT)
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
            shortcut(input, getString(R.string.hdmi, input.port), R.drawable.input) { switchTo(input) }
        } + shortcut(SettingsTile, getString(R.string.settings), R.drawable.settings) { openSettings() }

        grid.removeAllViews()
        shortcuts.chunked(COLUMNS).forEach { addRow(it) }
        for (row in gridRows(byApp.keys.toList(), packageName, appOrder)) {
            addRow(row.map { app ->
                val info = byApp.getValue(app)
                val banner = info.activityInfo.loadBanner(packageManager)
                tile(app, banner, app.label, if (banner == null) info.loadIcon(packageManager) else null) { launch(app) }
            })
        }

        // Keep focus on the same tile across a rebuild, or start at the top.
        val target = focused?.let { grid.findViewWithTag<View>(it) } ?: firstTile()
        target?.requestFocus()
    }

    private fun tvInputs(): List<Input> =
        tvInputManager?.tvInputList.orEmpty().map {
            // A CEC device behind an HDMI port is an input too, with the
            // port's input as its parent.
            val hdmi = it.type == TvInputInfo.TYPE_HDMI && it.isPassthroughInput && it.parentId == null
            Input(it.id, it.loadLabel(this).toString(), hdmi)
        }

    // Adds a row of tiles to the grid. A short row is padded with spacers so
    // its tiles are the same size as everyone else's.
    private fun addRow(tiles: List<View>) {
        val gap = resources.getDimensionPixelSize(R.dimen.tile_gap)
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
        grid.addView(rowView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (grid.childCount > 0) topMargin = gap
        })
    }

    private fun firstTile(): View? =
        (grid.getChildAt(0) as? LinearLayout)?.getChildAt(0)

    private fun shortcut(tag: Any, label: String, icon: Int, onSelect: () -> Unit) =
        tile(tag, null, label, getDrawable(icon), onSelect)

    // A tile shows [banner] if there is one, or else [icon] above [label].
    private fun tile(tag: Any, banner: Drawable?, label: String, icon: Drawable?, onSelect: () -> Unit): View =
        TileView(this).apply {
            this.tag = tag
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.tile_background)
            // Inset the content so the focus ring shows around a banner.
            val ring = dp(3)
            setPadding(ring, ring, ring, ring)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            contentDescription = label

            if (banner != null) {
                addView(ImageView(context).apply {
                    setImageDrawable(banner)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, MATCH_PARENT, MATCH_PARENT)
            } else {
                addView(iconAndName(label, icon), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            }

            setOnClickListener { onSelect() }
            setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1f
                view.animate().scaleX(scale).scaleY(scale)
                    .translationZ(if (hasFocus) dp(8).toFloat() else 0f)
                    .setDuration(120).start()
            }
        }

    private fun iconAndName(label: String, icon: Drawable?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = resources.getDimensionPixelSize(R.dimen.tile_icon)
        addView(ImageView(context).apply {
            setImageDrawable(icon)
        }, LinearLayout.LayoutParams(size, size))
        addView(TextView(context).apply {
            text = label
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tile_text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), 0)
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
}

private const val TV_SETTINGS = "com.android.tv.settings"

/** The owner's app order, from home/config/apps.txt. */
private const val APP_ORDER = "apps.txt"

/** The Settings tile's tag, so focus stays on it across a rebuild. */
private data object SettingsTile

/** A tile that keeps a 16:9 shape at whatever width its row gives it. */
private class TileView(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY),
        )
    }
}
