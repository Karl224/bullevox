#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to an Android SDK with platform 35 and build tools 35.0.0}"
: "${MAIL_JAR:?Set MAIL_JAR to the absolute path to android-mail-1.6.7.jar}"
: "${ACTIVATION_JAR:?Set ACTIVATION_JAR to the absolute path to android-activation-1.6.7.jar}"
platform="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
tools="$ANDROID_SDK_ROOT/build-tools/35.0.0"
mkdir -p build/classes build/dex build/probe
python3 - "$platform" <<'PY'
from sys import argv
from zipfile import ZipFile, ZIP_DEFLATED

with ZipFile(argv[1]) as platform, ZipFile("build/framework.jar", "w", ZIP_DEFLATED) as framework:
    for name in platform.namelist():
        if name.startswith(("android/", "dalvik/")) and name.endswith(".class"):
            framework.writestr(name, platform.read(name))
PY
classpath="build/framework.jar:$MAIL_JAR:$ACTIVATION_JAR"
mapfile -t sources < <(find src -name '*.java')
javac --release 8 -cp "$classpath" -d build/classes "${sources[@]}"
manifest=AndroidManifest.xml
if [[ "${STRICT_SETUP_TEST:-0}" == 1 ]]; then
    manifest=build/SetupTestManifest.xml
    sed -e 's/package="ca.local.vvm"/package="ca.local.vvm.setup"/' \
        -e 's/ca.local.vvm.STATUS_CALLBACK/ca.local.vvm.setup.STATUS_CALLBACK/g' \
        -e 's/android:label="BulleVox Lab"/android:label="BulleVox for Fizz"/' \
        AndroidManifest.xml > "$manifest"
fi
"$tools/aapt2" compile --dir res -o build/resources.zip
"$tools/aapt2" link -o build/unsigned.apk --manifest "$manifest" -I "$platform" build/resources.zip
mapfile -t classes < <(find build/classes -name '*.class')
"$tools/d8" --release --min-api 31 --lib "$platform" --output build/dex "${classes[@]}" "$MAIL_JAR" "$ACTIVATION_JAR"
python3 - "$MAIL_JAR" "$ACTIVATION_JAR" <<'PY'
from sys import argv
from zipfile import ZipFile, ZIP_DEFLATED

with ZipFile("build/unsigned.apk", "a", ZIP_DEFLATED) as apk:
    apk.write("build/dex/classes.dex", "classes.dex")
    for label, path in zip(("mail", "activation"), argv[1:]):
        with ZipFile(path) as library:
            for name in library.namelist():
                if not name.startswith("META-INF/") or name.endswith(("/", "/MANIFEST.MF")):
                    continue
                target = name
                if name.endswith(("/LICENSE.md", "/NOTICE.md")):
                    target = "META-INF/" + label + "-" + name.split("/")[-1]
                apk.writestr(target, library.read(name))
PY
"$tools/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [[ ! -f build/test-key.p12 ]]; then
    keytool -genkeypair -keystore build/test-key.p12 -storepass changeit -alias local-lab \
        -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Local Voicemail Lab test build'
fi
"$tools/apksigner" sign --ks build/test-key.p12 --ks-pass pass:changeit \
    --out build/local-voicemail-lab.apk build/aligned.apk
"$tools/apksigner" verify build/local-voicemail-lab.apk

javac --release 8 -cp "build/classes:$MAIL_JAR:$ACTIVATION_JAR" -d build/probe test/Probe.java
python3 test/mock.py "build/probe:build/classes:$MAIL_JAR:$ACTIVATION_JAR"
