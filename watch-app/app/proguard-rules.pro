# Code shrinking and obfuscation (R8) for release builds.
# Activities, services and receivers named in the manifest are kept automatically.

# Gemini (AppFunctions): the library finds these by name, so they keep their names and members.
-keep class io.github.peterchabs.voiceme.VoiceMeFunctions { *; }
-keep class io.github.peterchabs.voiceme.VoiceMeAppFunctionService { *; }
-keep class io.github.peterchabs.voiceme.$* { *; }
-keep class io.github.peterchabs.voiceme.SendGroupMeMessageParams { *; }
-keep class io.github.peterchabs.voiceme.SentGroupMeMessage { *; }
-keep class io.github.peterchabs.voiceme.ReadGroupMeMessagesParams { *; }
-keep class io.github.peterchabs.voiceme.GroupMeMessage { *; }
-keep class appfunctions_aggregated_deps.** { *; }
-keep class androidx.appfunctions.** { *; }
-dontwarn androidx.appfunctions.**

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
