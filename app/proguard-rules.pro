# biweekly looks up its property scribes reflectively.
-keep class biweekly.** { *; }
# Optional dependencies of biweekly (jCal/xCal support) that we don't ship.
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn javax.xml.**
