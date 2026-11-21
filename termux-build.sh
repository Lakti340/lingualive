#!/data/data/com.termux/files/usr/bin/bash
# Compile LinguaLive directement sur le téléphone, dans Termux.
# Usage :  bash termux-build.sh
set -e

echo "== 1/5 Paquets Termux (Java 17, aapt2…) =="
pkg update -y && pkg upgrade -y
pkg install -y openjdk-21 wget unzip aapt2

export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

echo "== 2/5 Android SDK (outils en ligne de commande) =="
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  wget -O "$TMPDIR/cmdtools.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$TMPDIR/cmdtools.zip" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm "$TMPDIR/cmdtools.zip"
fi

echo "== 3/5 Plateforme Android 35 =="
yes | sdkmanager --licenses > /dev/null
sdkmanager "platforms;android-35" "build-tools;35.0.0"

echo "== 4/5 Compilation (15 à 40 min la 1re fois) =="
cd "$(dirname "$0")/android"
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew
# aapt2 de Google ne marche pas sur un téléphone ARM : on utilise celui de Termux.
./gradlew assembleDebug --no-daemon \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
  -Dorg.gradle.jvmargs="-Xmx2g"

echo "== 5/5 Copie de l'APK =="
OUT="app/build/outputs/apk/debug/app-debug.apk"
if [ -d "$HOME/storage/downloads" ]; then
  cp "$OUT" "$HOME/storage/downloads/LinguaLive.apk"
  echo "OK ! L'APK est dans ton dossier Téléchargements : LinguaLive.apk"
else
  echo "OK ! APK : $(pwd)/$OUT  (lance termux-setup-storage pour le copier dans Téléchargements)"
fi
