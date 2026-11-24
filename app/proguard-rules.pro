-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.finmagic.data.backup.** { *; }
-keep class com.finmagic.data.local.entity.** { *; }
-keep class com.finmagic.data.local.BackupPreferencesPayload { *; }
-keep class com.finmagic.data.local.RecurringTemplate { *; }
-keep class com.finmagic.data.local.AccountTypeConfig { *; }
-keep class com.finmagic.data.sms.AccountTemplate { *; }
-keep class com.finmagic.data.web.** { *; }
-keep class com.finmagic.data.update.** { *; }
