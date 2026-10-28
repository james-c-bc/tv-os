package io.github.james_c_bc.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.os.Bundle
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
 * Every installed TV app as a tile, five per row, alphabetical. Apps show
 * their 16:9 banner, or their icon and name if they have no banner.
 */
class HomeActivity : Activity() {
    private lateinit var grid: LinearLayout

    // Rebuilds the grid when an app is installed, updated or removed.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        showApps()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    // Back does nothing: Home is the bottom of the stack.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private fun showApps() {
        val focused = grid.findFocus()?.tag
        val infos = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
        )
        val byApp = infos.associateBy {
            App(it.loadLabel(packageManager).toString().trim(), it.activityInfo.packageName, it.activityInfo.name)
        }
        val gap = resources.getDimensionPixelSize(R.dimen.tile_gap)

        grid.removeAllViews()
        for ((index, row) in gridRows(byApp.keys.toList(), packageName).withIndex()) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            // A short last row is padded with spacers so its tiles are the
            // same size as everyone else's.
            for (column in 0 until COLUMNS) {
                val app = row.getOrNull(column)
                val view = if (app != null) tile(app, byApp.getValue(app)) else Space(this)
                rowView.addView(view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (column > 0) marginStart = gap
                })
            }
            grid.addView(rowView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (index > 0) topMargin = gap
            })
        }

        // Keep focus on the same app across a rebuild, or start at the top.
        val target = focused?.let { grid.findViewWithTag<View>(it) } ?: firstTile()
        target?.requestFocus()
    }

    private fun firstTile(): View? =
        (grid.getChildAt(0) as? LinearLayout)?.getChildAt(0)

    private fun tile(app: App, info: ResolveInfo): View {
        val banner = info.activityInfo.loadBanner(packageManager)
        return TileView(this).apply {
            tag = app
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.tile_background)
            // Inset the content so the focus ring shows around a banner.
            val ring = dp(3)
            setPadding(ring, ring, ring, ring)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            contentDescription = app.label

            if (banner != null) {
                addView(ImageView(context).apply {
                    setImageDrawable(banner)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, MATCH_PARENT, MATCH_PARENT)
            } else {
                addView(iconAndName(app, info), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            }

            setOnClickListener { launch(app) }
            setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1f
                view.animate().scaleX(scale).scaleY(scale)
                    .translationZ(if (hasFocus) dp(8).toFloat() else 0f)
                    .setDuration(120).start()
            }
        }
    }

    private fun iconAndName(app: App, info: ResolveInfo) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = resources.getDimensionPixelSize(R.dimen.tile_icon)
        addView(ImageView(context).apply {
            setImageDrawable(info.loadIcon(packageManager))
        }, LinearLayout.LayoutParams(size, size))
        addView(TextView(context).apply {
            text = app.label
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tile_text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
            showApps()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

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
