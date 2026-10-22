#!/usr/bin/env bash
# Compile and run plain-JVM Kotlin (no Android) with the Kotlin compiler that
# Gradle already cached, e.g. to unit-test the app's networking code on the Mac:
#   scripts/kt.sh build <out-dir> <src files or dirs...>
#   scripts/kt.sh run <out-dir> <MainClassKt> [args...]
set -euo pipefail
C="$HOME/.gradle/caches/modules-2/files-2.1"
jar() { find "$C/$1" -name "$2" -not -name "*sources*" | head -1; }
V=2.4.20
STDLIB=$(jar org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-$V.jar")
CP_COMPILER="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$V.jar"):$STDLIB:$(jar org.jetbrains.kotlin/kotlin-script-runtime "kotlin-script-runtime-$V.jar"):$(jar org.jetbrains.kotlin/kotlin-reflect "kotlin-reflect-2.2.10.jar"):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable "kotlin-daemon-embeddable-$V.jar"):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm "*.jar"):$(jar org.jetbrains/annotations "annotations-13.0.jar")"
LIBS="$STDLIB:$(jar com.squareup.okhttp3/okhttp "okhttp-4.12.0.jar"):$(jar com.squareup.okio/okio-jvm "*.jar"):$(jar junit/junit "junit-4.13.2.jar"):$(jar org.hamcrest/hamcrest-core "hamcrest-core-1.3.jar")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
JAVA="$JAVA_HOME/bin/java"
cmd=$1; out=$2; shift 2
case "$cmd" in
  build)
    mkdir -p "$out"
    "$JAVA" -Xmx2g -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -jvm-target 17 -nowarn -cp "$LIBS" -d "$out" "$@"
    ;;
  run)
    main=$1; shift
    "$JAVA" -cp "$out:$LIBS" "$main" "$@"
    ;;
  *) echo "usage: kt.sh build|run <out> ..."; exit 2 ;;
esac
