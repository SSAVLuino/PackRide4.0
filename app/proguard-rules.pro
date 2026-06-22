# GraphHopper pulls in JVM/desktop classes not available on Android
-dontwarn com.sun.activation.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.activation.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.stream.**
-dontwarn org.codehaus.stax2.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.xmlgraphics.**
-keep class org.apache.xmlgraphics.** { *; }

# Keep GraphHopper classes (R8 strips them causing graph load failures)
-keep class com.graphhopper.** { *; }
-keep class com.graphhopper.storage.** { *; }
-keep class com.graphhopper.routing.** { *; }
-keep class com.graphhopper.util.** { *; }
-keep class com.graphhopper.reader.** { *; }
-keep class com.graphhopper.config.** { *; }

# Keep Jackson (used by GraphHopper for config serialization)
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}

# Keep SLF4J
-keep class org.slf4j.** { *; }

# Keep NanoHTTPD (tile server)
-keep class fi.iki.elonen.** { *; }
