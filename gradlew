#!/bin/sh
set -e

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"

mkdir -p "$APP_HOME/gradle/wrapper"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Mengunduh Gradle Wrapper resmi..."
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 "$WRAPPER_URL" -o "$WRAPPER_JAR"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$WRAPPER_JAR" "$WRAPPER_URL"
    else
        echo "curl atau wget diperlukan."
        exit 1
    fi
fi

exec java -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"