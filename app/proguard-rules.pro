# WebView calls these methods by name. Keep if minification is enabled later.
-keepclassmembers class com.ocshimeji.app.MainActivity$NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
