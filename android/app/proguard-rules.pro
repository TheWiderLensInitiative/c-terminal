# The terminal page calls these from JavaScript by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
