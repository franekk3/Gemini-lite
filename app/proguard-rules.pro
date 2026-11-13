# JavaScript bridge methods are called from injected web code by name.
-keepclassmembers class com.frank.geminilite.GeminiWebView$JsBridge {
    public *;
}
