# ReTrace hotfix examples

These are **synthetic mappings**, based on the reported stack traces. The names
`MessageLoop`, `CollectionImporter`, `ObjectionImporter`, and `viewModel` are
illustrative; they do not establish the original names in your application.

From the ProGuard repository root:

```sh
./gradlew -PlocalProguardCore :retrace:test :distZip
java -jar lib/retrace.jar examples/retrace-hotfix/mapping.txt examples/retrace-hotfix/stacktrace.txt
```

Compare the output with `expected.txt`. For your application, supply the mapping
file produced by the exact obfuscated build that crashed:

```sh
java -jar lib/retrace.jar /path/to/mapping.txt /path/to/hs_err_pid123.log
java -jar lib/retrace.jar /path/to/mapping.txt /path/to/exception.txt
```

The build uses a Gradle composite build of `../proguard-core`, enabled by
`-PlocalProguardCore`. This compiles the local source directly, without needing a
Maven publication. Both repositories have hotfix versions configured:
ProGuard `7.7-hotfix`, ProGuardCORE `9.1.10-hotfix`.

Outputs:

- `lib/proguard.jar`: executable ProGuard with local core included.
- `lib/retrace.jar`: executable ReTrace with the fixes.
- `build/distributions/proguard-7.7-hotfix.zip`: distribution with command-line tools and GUI.
- `../proguard-core/base/build/libs/proguard-core-9.1.10-hotfix.jar`: local core.

## How the fixes work

`DiagnosticRemapper` recognizes interpreted `j` and compiled `J` Java frames,
including `c1`, `c2`, and `jvmci` markers. JVM argument and return descriptors
select method overloads. Object types in descriptors are also remapped while
retaining JVM syntax. Bytecode offsets (`+24`), compilation IDs, code sizes, and
native addresses are preserved; they are not treated as source line numbers.
If several mappings match, distinct candidate frames are emitted.

For helpful NPEs beginning with `Cannot invoke`, quoted methods are remapped
independently, including the return-value expression. Matching supports abbreviated
`java.lang` argument types such as `Object`, as well as primitive and array types.
When a method reference is ambiguous, its class is remapped but its method name
is retained. The diagnostic's signature layout is retained in verbose mode too.

A quoted `this.f` is resolved against the class in the immediately following
stack frame, only when its mapping yields a unique field name. Without that
frame, a field mapping, or a unique result, the expression is left unchanged.
Qualified static-field references are supported too.

This field resolution is an inference from the throwing frame. A mapping file
does not encode class hierarchy or bytecode receiver information, so inherited
or hidden fields and optimizer inlining can require inspection of the original
and obfuscated class files. Arbitrary receiver chains (`this.a.b`), local
variables, and dynamically generated lambda names are not reconstructed.
The actual name of the reported `this.f` cannot be established from the supplied
stack trace alone: inspect the `RegistersView` field mapping ending in ` -> f`.

The existing custom line-range matching in `FrameRemapper` is preserved. Custom
`-regex` parsing bypasses the new diagnostic parser.

## Validation

Nine JUnit tests cover interpreted/compiled frames, descriptor overload matching,
arrays and return types, malformed/native/lambda lines, helpful NPE methods,
unique/ambiguous/missing fields, ambiguous methods, custom regexes, and ordinary
frames. The example has also been run through the packaged ReTrace JAR.

An additional Java 17 smoke test compiled and obfuscated a small application with
a null `viewModel` field, then retraced the JVM-generated exception. It restored
both `Model.getTreeElementsForRoot(Object)` and `this.viewModel`, as well as the
throwing method's name and source line. The full ProGuard/ProGuardCORE test suites
were not run.
