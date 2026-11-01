# Jetpack Glance ActionCallback and AppWidgetProvider
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver {
    <init>();
}
