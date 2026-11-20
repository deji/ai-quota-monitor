# Jetpack Glance ActionCallback and AppWidgetProvider
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver {
    public <init>();
}

# WorkManager instantiates workers by class name through reflection.
-keep class * extends androidx.work.CoroutineWorker {
    public <init>();
    public <init>(...);
}
