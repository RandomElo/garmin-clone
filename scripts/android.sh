#!/usr/bin/env bash
# Build the Android app and install it on a phone plugged in over USB.
#   npm run android          install the debug build and open the app
#   npm run android:install  install only
#   npm run android:setup    install what is missing (Android SDK, Gradle wrapper), nothing else
#   npm run android:devices  list the phones adb sees
#   npm run android:logs     follow the app logs
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT/android"
PACKAGE="fr.foulee.app"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
CACHE="$HOME/.cache/foulee"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
GRADLE_VERSION="8.9"
ADB="$SDK/platform-tools/adb"

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nErreur : %b\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' est introuvable, installe-le d'abord ($2)."; }

check_java() {
  need java "sudo apt install openjdk-17-jdk"
  local major
  major="$(java -version 2>&1 | sed -n '1s/.*"\([0-9]*\)[."].*/\1/p')"
  [ "${major:-0}" -ge 17 ] || die "Java 17 ou plus est requis (trouvé : ${major:-inconnu})."
}

setup_sdk() {
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    need curl "sudo apt install curl"; need unzip "sudo apt install unzip"
    say "Téléchargement des outils en ligne de commande Android (dl.google.com)"
    mkdir -p "$CACHE" "$SDK/cmdline-tools"
    curl -fL -o "$CACHE/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
    rm -rf "$CACHE/cmdline-extract" && mkdir -p "$CACHE/cmdline-extract"
    unzip -q "$CACHE/cmdline-tools.zip" -d "$CACHE/cmdline-extract"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$CACHE/cmdline-extract/cmdline-tools" "$SDK/cmdline-tools/latest"
  fi
  if [ ! -x "$ADB" ] || [ ! -d "$SDK/platforms/android-35" ] || [ ! -d "$SDK/build-tools/35.0.0" ]; then
    say "Installation des paquets du SDK (platform-tools, android-35, build-tools 35.0.0)"
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
  fi
  printf 'sdk.dir=%s\n' "$SDK" > "$APP_DIR/local.properties"
}

setup_wrapper() {
  [ -f "$APP_DIR/gradlew" ] && return
  need curl "sudo apt install curl"; need unzip "sudo apt install unzip"
  say "Génération du wrapper Gradle $GRADLE_VERSION (services.gradle.org)"
  mkdir -p "$CACHE"
  if [ ! -x "$CACHE/gradle-$GRADLE_VERSION/bin/gradle" ]; then
    curl -fL -o "$CACHE/gradle.zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    unzip -q -o "$CACHE/gradle.zip" -d "$CACHE"
  fi
  (cd "$APP_DIR" && "$CACHE/gradle-$GRADLE_VERSION/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --no-daemon)
}

setup() { check_java; setup_sdk; setup_wrapper; }

# Exactly one phone, authorized for USB debugging
pick_device() {
  [ -x "$ADB" ] || die "adb manque, lance d'abord : npm run android:setup"
  "$ADB" start-server >/dev/null 2>&1 || true
  local list ready
  list="$("$ADB" devices | tail -n +2 | sed '/^$/d')"
  ready="$(printf '%s\n' "$list" | awk '$2=="device"{print $1}')"
  if [ -z "$ready" ]; then
    [ -n "$list" ] || die "Aucun téléphone détecté. Branche-le en USB et active le débogage USB (Options pour les développeurs)."
    printf '%s\n' "$list" >&2
    die "Téléphone vu mais pas utilisable. 'unauthorized' : accepte la demande sur l'écran du téléphone. 'no permissions' : règle udev manquante (https://developer.android.com/studio/run/device#setting-up)."
  fi
  [ "$(printf '%s\n' "$ready" | wc -l)" -eq 1 ] || die "Plusieurs appareils branchés, n'en garde qu'un :\n$ready"
  printf '%s' "$ready"
}

install() {
  setup
  local device
  device="$(pick_device)"
  say "Compilation et installation sur $device"
  (cd "$APP_DIR" && ANDROID_SERIAL="$device" ./gradlew --console=plain installDebug)
}

run() {
  install
  local device
  device="$(pick_device)"
  say "Ouverture de l'appli"
  "$ADB" -s "$device" shell am start -n "$PACKAGE/.MainActivity" >/dev/null
}

case "${1:-run}" in
  setup) setup ;;
  install) install ;;
  run) run ;;
  devices) [ -x "$ADB" ] || die "adb manque, lance d'abord : npm run android:setup"; "$ADB" devices ;;
  logs) "$ADB" logcat --pid="$("$ADB" shell pidof "$PACKAGE")" ;;
  *) die "Commande inconnue : $1 (setup, install, run, devices, logs)" ;;
esac
