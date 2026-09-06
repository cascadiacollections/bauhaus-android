package com.cascadiacollections.bauhaus.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Manifest-registered host for [BauhausAppWidget]. */
class BauhausAppWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BauhausAppWidget()
}
