# Default ProGuard rules. The app does not ship with minification enabled,
# but these keep CameraX and the service entry points safe if you turn it on.
-keep class androidx.camera.** { *; }
-keep class com.damir.homecam.CameraService { *; }
