#!/bin/bash
# Compile Kotlin with the compiler bundled in the local Gradle distribution (no network needed).
L=${GRADLE_LIB:-/opt/gradle-8.14.3/lib}
CCP=$(ls $L/kotlin-compiler-embeddable-*.jar $L/kotlin-stdlib-2*.jar $L/kotlin-reflect-*.jar $L/kotlin-script-runtime-*.jar $L/trove4j*.jar $L/annotations-24*.jar $L/kotlin-daemon-embeddable*.jar $L/kotlinx-coroutines-core-jvm*.jar | tr '\n' ':')
exec java -Xmx2g -cp "$CCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "$@"
