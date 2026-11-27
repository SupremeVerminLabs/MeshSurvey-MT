#!/bin/bash
# Compiles the shared core + its tests on the JVM and runs them, using only the Kotlin compiler,
# kotlinx libraries and JUnit that ship inside a Gradle 8.14 distribution (works offline).
# SqlStore tests run against real SQLite through tools/sqlite_bridge.py (python3 required).
# Real builds should use: ./gradlew :core:desktopTest
set -euo pipefail
cd "$(dirname "$0")/.."
L=${GRADLE_LIB:-/opt/gradle-8.14.3/lib}
OUT=${OUT:-build/verify}
rm -rf "$OUT"; mkdir -p "$OUT"
LIBS="$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/kotlinx-serialization-core-jvm-1.6.2.jar:$L/kotlinx-serialization-json-jvm-1.6.2.jar"
JUNIT="$L/junit-4.13.2.jar:$(ls $L/hamcrest*.jar 2>/dev/null | tr '\n' ':')"
echo "== compiling core (commonMain + jvmShared)"
tools/kc.sh -cp "$LIBS" -d "$OUT/main" $(find core/src/commonMain core/src/jvmShared -name '*.kt') 2>&1 | grep -v '^Picked up' || true
echo "== compiling tests (commonTest + local shims)"
TESTS="$(find core/src/commonTest core/src/desktopTest -name '*.kt' ! -path '*testsupport/TestDriver.kt' ! -path '*testsupport/FileDriver.kt')"
tools/kc.sh -Xallow-kotlin-package -cp "$OUT/main:$LIBS:$JUNIT" -d "$OUT/test" $TESTS tools/testshim/*.kt 2>&1 | grep -v '^Picked up' || true
CLASSES=$(cd "$OUT/test" && find meshsurvey -name '*Test.class' ! -name '*\$*' | sed 's/\.class$//; s#/#.#g' | tr '\n' ' ')
echo "== running: $CLASSES"
java -Dbridge.script=tools/sqlite_bridge.py -cp "$OUT/test:$OUT/main:$LIBS:$JUNIT" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v '^Picked up'
