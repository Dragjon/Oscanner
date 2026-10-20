package dev.olaf.oscanner

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** Opens the camera straight away: used by the app shortcut, the Quick Settings tile and the widget. */
const val ACTION_SCAN = "dev.olaf.oscanner.action.SCAN"

fun scanIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_SCAN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

private fun scanPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(context, 0, scanIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** "Scan" in Quick Settings. */
class ScanTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(scanPendingIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(scanIntent(this))
        }
    }
}

/** A one-button home-screen widget: Scan. */
class ScanWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_scan).apply {
            setOnClickPendingIntent(R.id.widget_scan, scanPendingIntent(context))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
