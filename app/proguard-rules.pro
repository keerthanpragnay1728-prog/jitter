# The AccessibilityService is instantiated by the system from the name in
# AndroidManifest.xml. R8 has no visible reference to it and would strip or
# rename it, which the platform reports only as a silent bind failure.
-keep class dev.molasses.monitor.MolassesAccessibilityService { *; }

# SettingsActivity is referenced by name from accessibility_service_config.xml
# (android:settingsActivity), which R8 does not scan.
-keep class dev.molasses.ui.settings.SettingsActivity { *; }

# Proto DataStore uses reflection on generated lite message classes.
-keep class dev.molasses.CycleState { *; }
-keep class dev.molasses.AppState { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
