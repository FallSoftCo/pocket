# Platform components are retained by the Android Gradle plugin's manifest rules.
-keepattributes Signature,InnerClasses,EnclosingMethod
# WorkManager persists this class name across application updates.
-keep class co.fallsoft.pocket.PresentedReadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
