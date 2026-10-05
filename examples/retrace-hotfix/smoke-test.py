#!/usr/bin/env python3
"""Obfuscate a fixture and retrace real JVM exceptions; requires Java 17+ and javac."""
import pathlib
import re
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
EXAMPLES = pathlib.Path(__file__).resolve().parent


def run(*args, check=True):
    return subprocess.run([str(arg) for arg in args], text=True, capture_output=True, check=check)


with tempfile.TemporaryDirectory(prefix="retrace-inheritance-") as temporary:
    work = pathlib.Path(temporary)
    classes = work / "classes"
    classes.mkdir()
    run("javac", "--release", "8", "-g", "-d", classes, EXAMPLES / "InheritanceCrash.java")
    injar = work / "input.jar"
    outjar = work / "output.jar"
    mapping = work / "mapping.txt"
    with zipfile.ZipFile(injar, "w") as archive:
        for source in classes.rglob("*.class"):
            archive.write(source, source.relative_to(classes).as_posix())
    properties = run("java", "-XshowSettings:properties", "-version").stderr
    java_home = pathlib.Path(re.search(r"^\s*java.home = (.+)$", properties, re.MULTILINE)[1])
    config = work / "config.pro"
    config.write_text(
        f"-injars '{injar}'\n-outjars '{outjar}'\n"
        f"-libraryjars '{java_home / 'jmods/java.base.jmod'}'(!**.jar;!module-info.class)\n"
        "-dontoptimize\n-dontshrink\n"
        "-keep public class demo.InheritanceCrash { public static void main(java.lang.String[]); }\n"
        "-keepattributes SourceFile,LineNumberTable\n"
        f"-printmapping '{mapping}'\n"
    )
    run("java", "-jar", ROOT / "lib/proguard.jar", f"@{config}")
    expectations = {
        "own": ['getTreeElementsForRoot(Object)', '"this.ownModel"'],
        "inherited": ['getTreeElementsForRoot(Object)', '"this.inheritedModel"'],
        "shared": ['getTreeElementsForRoot(Object)', '.sharedModel"'],
        "array": ['Cannot read the array length', '"this.items"'],
        "read": ['Cannot read field "count"', '"this.stats"'],
        "write": ['Cannot assign field "count"', '"this.stats"'],
        "monitor": ['Cannot enter synchronized block', '"this.lock"'],
        "hidden": ['getTreeElementsForRoot(Object)', '"this.inheritedModel"'],
        "base": ['getTreeElementsForRoot(Object)', '"this.inheritedModel"'],
    }
    for case, expected in expectations.items():
        crash = run("java", "-XX:+ShowCodeDetailsInExceptionMessages", "-cp", outjar,
                    "demo.InheritanceCrash", case, check=False)
        assert crash.returncode != 0, f"Fixture did not fail: {case}"
        trace = work / "trace.txt"
        trace.write_text(crash.stderr)
        result = run("java", "-jar", ROOT / "lib/retrace.jar", "-injars", outjar, mapping, trace)
        for text in expected:
            assert text in result.stdout, f"{case}: missing {text}\n{crash.stderr}\n{result.stdout}"
        print(f"PASS {case}: {result.stdout.splitlines()[0]}")

# Validate actual optimizer inlining, including the complete reconstructed chain.
with tempfile.TemporaryDirectory(prefix="retrace-optimized-") as temporary:
    work = pathlib.Path(temporary)
    classes = work / "classes"
    classes.mkdir()
    run("javac", "--release", "8", "-g", "-d", classes, EXAMPLES / "OptimizedCrash.java")
    injar = work / "input.jar"
    with zipfile.ZipFile(injar, "w") as archive:
        for source in classes.rglob("*.class"):
            archive.write(source, source.relative_to(classes).as_posix())
    for label, optimizations in [("inlining", "method/inlining/*"),
                                 ("optimized", "!method/marking/private,!method/removal/parameter")]:
        outjar = work / (label + ".jar")
        mapping = work / (label + ".mapping.txt")
        config = work / (label + ".pro")
        config.write_text(
            f"-injars '{injar}'\n-outjars '{outjar}'\n"
            f"-libraryjars '{java_home / 'jmods/java.base.jmod'}'(!**.jar;!module-info.class)\n"
            f"-optimizationpasses 3\n-optimizations {optimizations}\n"
            "-keep,allowoptimization public class demo.OptimizedCrash { public static void main(java.lang.String[]); }\n"
            "-keepclassmembers,allowobfuscation class demo.OptimizedCrash$State { <fields>; }\n"
            "-keepclassmembers,allowobfuscation class demo.OptimizedCrash { <fields>; }\n"
            "-keep,allowobfuscation interface demo.OptimizedCrash$Target { <methods>; }\n"
            "-keepattributes SourceFile,LineNumberTable,LocalVariableTable\n"
            f"-printmapping '{mapping}'\n"
        )
        run("java", "-jar", ROOT / "lib/proguard.jar", f"@{config}")
        # Confirm the helper methods were removed, rather than just testing an
        # optimized build that happened to retain its original call stack.
        bytecode = run("javap", "-p", "-classpath", outjar, "demo.OptimizedCrash").stdout
        assert "leaf(" not in bytecode and "bridge(" not in bytecode and "entry(" not in bytecode, bytecode
        crash = run("java", "-XX:+ShowCodeDetailsInExceptionMessages", "-cp", outjar,
                    "demo.OptimizedCrash", check=False)
        assert crash.returncode != 0 and "NullPointerException" in crash.stderr, crash.stderr
        trace = work / "trace.txt"
        trace.write_text(crash.stderr)
        result = run("java", "-jar", ROOT / "lib/retrace.jar", "-injars", outjar, mapping, trace)
        assert "demo.OptimizedCrash$Target.mappedCall()" in result.stdout, result.stdout
        assert '.field" is null' in result.stdout, result.stdout
        frame_lines = [line for line in result.stdout.splitlines() if line.lstrip().startswith("at ")]
        for method, frame in zip(["leaf", "bridge", "entry", "main"], frame_lines):
            assert f"demo.OptimizedCrash.{method}(" in frame, result.stdout
        assert len(frame_lines) == 4, result.stdout
        print(f"PASS {label}: restored leaf -> bridge -> entry -> main, and the null field")
