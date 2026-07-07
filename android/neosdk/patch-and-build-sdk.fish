#!/usr/bin/env fish
# Patch the NeoLAB SDK for Android 14+, build it to an AAR, and drop it into neosdk/libs/ so the
# :neosdk module (and the real NeoSdkAdapter) compile. Device/shell: the OCR host (fish shell).
#
# Usage:  android/neosdk/patch-and-build-sdk.fish [path-to-Android-SDK2.0]   (default ~/Android-SDK2.0)
#
# ⚠ This 2017 SDK ships a Gradle 5.1.1 wrapper (AGP ~3.x) that runs on JDK 8–11 ONLY — NOT 17+.
#   Point JAVA_HOME at a Java 11 just for this build, e.g.:
#       set -x JAVA_HOME /usr/lib/jvm/java-11-openjdk      # sudo pacman -S jdk11-openjdk
#   and make sure compileSdk 34's platform is installed:  sdkmanager "platforms;android-34"
#   (If the old AGP rejects build-tools 36, also: sdkmanager "build-tools;34.0.0".)
#   Easiest alternative: open NASDK2.0_Studio in Android Studio, let it sync, Build → Make Module
#   'app', then copy app/build/outputs/aar/*-release.aar into neosdk/libs/ yourself.

set repo $argv[1]
test -z "$repo"; and set repo ~/Android-SDK2.0
set here (dirname (status --current-filename))   # android/neosdk
set studio "$repo/NASDK2.0_Studio"               # the library module ( :app ) lives here

if not test -d "$studio"
    echo "!! SDK clone not found at $studio — clone https://github.com/NeoSmartpen/Android-SDK2.0 first."
    exit 1
end

echo ">> patching the SDK library for Android 14+…"
# 1) library too old to reference the receiver flag
sed -i 's/compileSdkVersion 23/compileSdkVersion 34/' "$studio/app/build.gradle"
# 1b) AGP 3.4 (this SDK's plugin) predates the `namespace` DSL — a prior Android Studio sync may have
#     injected it, which makes evaluation fail. Strip it; the package comes from AndroidManifest.
sed -i '/namespace/d' "$studio/app/build.gradle"
# 2) dead jcenter() blocks dependency resolution
for f in (find "$studio" -name 'build.gradle')
    grep -q 'jcenter()' "$f"; and sed -i 's/jcenter()/mavenCentral()/g' "$f"
end
# 3) the USB receiver crash on Android 14+
sed -i 's#mContext.registerReceiver(usbReceiver, filter);#if (android.os.Build.VERSION.SDK_INT >= 33) mContext.registerReceiver(usbReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED); else mContext.registerReceiver(usbReceiver, filter);#' "$studio/app/src/main/java/kr/neolab/sdk/pen/usb/UsbAdt.java"
# 4) modern-OneUI BLE: the 2017 SDK connects with autoConnect=false, which fast-fails (GATT status
#    133) on current Samsung for these pens. autoConnect=true (background connect) is the documented
#    fix — it tolerates the connection timing that direct-connect can't. Verified failure: connectGatt
#    -> PEN_CONNECTION_TRY -> ~1-2s -> PEN_CONNECTION_FAILURE, never reaching the connected callback.
set btle "$studio/app/src/main/java/kr/neolab/sdk/pen/bluetooth/BTLEAdt.java"
test -f "$btle"; and sed -i 's/connectGatt(\([^,]*\),[ \t]*false,/connectGatt(\1, true,/g' "$btle"

echo ">> building the SDK AAR ( :app library in NASDK2.0_Studio )…"
pushd "$studio"
chmod +x gradlew
./gradlew :app:assembleRelease; or begin
    echo "!! SDK build failed."
    echo "   - 'Unsupported class file major version' → the Gradle 5.1.1 wrapper needs JDK 8–11:"
    echo "       set -x JAVA_HOME /usr/lib/jvm/java-11-openjdk   (sudo pacman -S jdk11-openjdk)  then re-run."
    echo "   - missing platform/build-tools → sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\""
    popd; exit 1
end
popd

echo ">> copying AAR into neosdk/libs/…"
mkdir -p "$here/libs"
cp "$studio"/app/build/outputs/aar/*-release.aar "$here/libs/"

echo ">> done. :neosdk is now enabled (settings.gradle.kts gates on neosdk/libs). Next:"
echo "   cd android; ./gradlew :app:installDebug -PpenPassword=XXXX   # XXXX = your LAMY password"
