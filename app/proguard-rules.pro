# R8 keep rules for the release build.
#
# The release variant is the only one with isMinifyEnabled = true, and it has
# never been built. Every rule below names what it protects and why. Where a
# library already ships the same rule as a consumer rule, it is repeated here
# anyway: this file is the list a reader checks, and a rule that silently
# depends on a library version is a rule that can vanish on an upgrade.
#
# The failure mode all of these share is quiet. A stripped or renamed class
# that is reached by name does not fail the build. It fails on the device,
# usually as a feature that does nothing rather than as a crash.

# ---------------------------------------------------------------- manifest
# Every class the manifest names. The platform instantiates them by the string
# in AndroidManifest.xml. aapt2 emits keep rules for these too; they are
# repeated so the list is complete in one place.

# @HiltAndroidApp application. Named by android:name on <application>.
-keep class dev.molasses.MolassesApp { *; }

# The accessibility service. Bound by the system from its manifest name. A
# renamed class is a silent bind failure: the switch in Android Settings turns
# on and nothing ever connects. Also matched by name in
# SettingsRepository.isAccessibilityServiceEnabled, which compares the enabled-services
# string against MolassesAccessibilityService::class.java.name.
-keep class dev.molasses.monitor.MolassesAccessibilityService { *; }

# SettingsActivity. Named in the manifest and also by android:settingsActivity
# in res/xml/accessibility_service_config.xml, which R8 does not scan.
-keep class dev.molasses.ui.settings.SettingsActivity { *; }

# ReminderReceiver. Named in the manifest, instantiated by the system for
# BOOT_COMPLETED and for each reminder's alarm. Renamed, reminders would
# never fire and never be re-armed after a reboot, with nothing on screen.
-keep class dev.molasses.monitor.ReminderReceiver { *; }

# LauncherActivity. Named in the manifest, and matched by string in
# ForegroundEventRouter.LAUNCHER_CLASS_NAME against the class name the
# platform puts on a WINDOW_STATE_CHANGED event. If it were renamed the router
# would never see the user arrive home, and a target session would never close
# on a return to the launcher.
-keep class dev.molasses.ui.launcher.LauncherActivity { *; }

# ------------------------------------------------------- protobuf-javalite
# The lite runtime reads message fields by reflection on their Java names
# (MessageSchema resolves each field by the generated field name). A renamed
# field is a parse that silently reads defaults: every lock, lease and target
# gone on the first launch of a release build. protobuf-javalite ships this
# rule as a consumer rule; it is stated here for the reason at the top.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# The generated messages and enums in this app, by name: CycleState, AppState,
# ConsoleQueued, ConsoleBudget, LockEntry, LeaseEntry, ReminderEntry,
# UntrackSunsetEntry, and the two proto enums
# (LockReasonProto, CycleResetPolicyProto), all in package dev.molasses from
# cycle_state.proto. The enums resolve by number through Internal.EnumLite.
-keep class dev.molasses.CycleState { *; }
-keep class dev.molasses.AppState { *; }
-keep class dev.molasses.ConsoleQueued { *; }
-keep class dev.molasses.ConsoleBudget { *; }
-keep class dev.molasses.LockEntry { *; }
-keep class dev.molasses.LeaseEntry { *; }
-keep class dev.molasses.ReminderEntry { *; }
-keep class dev.molasses.UntrackSunsetEntry { *; }
-keep enum dev.molasses.LockReasonProto { *; }
-keep enum dev.molasses.CycleResetPolicyProto { *; }

# ------------------------------------------------------------- DataStore
# The serializer is referenced directly from CycleStateStore, not by name, so
# this is belt and braces. It is cheap, and it is the object every byte of
# persisted state passes through.
-keep class dev.molasses.data.datastore.CycleStateSerializer { *; }

# ------------------------------------------------------------------ Room
# Room.databaseBuilder finds the generated implementation by name, as
# MolassesDatabase plus "_Impl", through reflection. room-runtime ships the
# first rule as a consumer rule.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class dev.molasses.data.db.MolassesDatabase_Impl { *; }

# ------------------------------------------------------------------ Hilt
# Hilt and Dagger ship their own consumer rules, including the ones for
# generated components. The one lookup that is by name is the ViewModel map
# hiltViewModel() reads, keyed by the ViewModel's class. Keeping the name of
# every @HiltViewModel class costs nothing and removes the question.
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel

# ------------------------------------------------------------------ enums
# Every enum in the app keeps its constants and their names.
#
# By ordinal: FontScale (font_scale_ordinal) and GatePolicy.GateMode
# (gate_mode_ordinal) are stored as ordinals. R8 can remove an enum constant
# nothing references, which renumbers every constant after it, so a stored
# ordinal would silently point at a different value.
#
# By name: EventType is written to Room as its name by Converters and read
# back with valueOf, falling back to SCROLL on a miss, so a renamed constant
# is a ledger that reads every row as a scroll. The sensor path
# (GateProgress.Path) is stored on each ledger row by name. Many ledger
# meta strings interpolate an enum, which is its name, and the exported ledger
# is only readable if those names survive.
#
# Scoped to dev.molasses rather than listed, so a new persisted enum is
# covered the day it is written. The cost is that R8 does not unbox these
# enums into ints, which is not measurable in an app this size.
-keepclassmembers enum dev.molasses.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
