# ReTrace hotfix examples

Build ProGuard `7.7-hotfix` with the sibling ProGuardCORE `9.1.10-hotfix` sources:

```sh
./gradlew -PlocalProguardCore :retrace:test :distZip
```

The Gradle composite build compiles `../proguard-core` directly; no Maven
publication is needed. Outputs are `lib/proguard.jar`, `lib/retrace.jar`,
`build/distributions/proguard-7.7-hotfix.zip`, and
`../proguard-core/base/build/libs/proguard-core-9.1.10-hotfix.jar`.

## Usage

For hs-error files or mapping-only exception messages:

```sh
java -jar lib/retrace.jar /path/to/mapping.txt /path/to/stacktrace.txt
```

For field lookup through classes, superclasses, interfaces, and superinterfaces,
supply the **exact obfuscated artifacts from the crashed build**:

```sh
java -jar lib/retrace.jar -injars '/path/to/app.jar:/path/to/dependency.jar' /path/to/mapping.txt /path/to/stacktrace.txt
```

`-injars` accepts JAR/ZIP/JMOD files, class directories, and individual `.class`
files. Use `:` to separate paths on Unix and `;` on Windows, or repeat `-injars`.
Include dependencies that declare relevant inherited fields. These inputs are
read as class-file data; their classes are never loaded or executed.

Duplicate classes are rejected, because the trace does not identify the defining
class loader. Multi-release JARs require extracting the class versions used by
the crashed runtime into a directory first. Nested dependency JARs must be
supplied separately. This option does not implement ProGuard input filters or
wildcards. The GUI retains its existing mapping-only interface.

## Supported diagnostics

The parser recognizes the **message structure**, independently of the exception
name. Your `java.lang.MethodNotFoundException` example is processed like an NPE
when it uses the same structure. It does not need to be a JVM-defined exception.
`Caused by`, `Suppressed`, and `Exception in thread` prefixes are retained.

| Message part | Behavior |
| --- | --- |
| `Cannot invoke "Owner.method(types)"` | Remaps the owner, a uniquely matched method, and argument classes. |
| `Cannot read field "f"` / `Cannot assign field "f"` | Remaps the field when its declaration can be identified from bytecode or receiver type. |
| Array length, every listed primitive/object array load/store, throw, monitor enter/exit | Recognizes the action and retraces references in its reason. |
| `because "this.f" is null` | Resolves the field using the immediately following throwing frame. |
| Qualified static fields | Searches declaring classes/interfaces, including superinterfaces and diamond hierarchies. |
| Field chains and array elements, such as `this.head.next` or `this.items[2]` | Remaps identifiable fields, preserving indexes and unknown suffixes. |
| `because the return value of "Owner.method(types)" is null` | Remaps the method; its mapped return type can identify an action field. |
| `local.field`, `<local1>.field`, `<parameter1>.field` | Bytecode can identify a unique field while preserving the local/parameter label. |
| Plain local names, `<localN>`, `<parameterN>`, `null`, array indexes | Preserved; original local names or runtime values are not reconstructed. |
| No-message NPEs, `requireNonNull`, Kotlin Intrinsics, Lombok, other free-form prose | Existing ordinary stack-frame processing applies. There is no universal parser for arbitrary exception text. |

Method matching supports abbreviated `java.lang` arguments such as `Object`,
primitives, and arrays. Ambiguous methods retain their obfuscated method name.
Structured diagnostics retain their original layout even with `-verbose`;
`-allclassnames` does not run a second substitution over already retraced
references or treat local labels as class names. Custom `-regex` parsing bypasses
the diagnostic parser.

## How inherited fields are resolved

With `-injars`, ReTrace looks at field references in the throwing method. Each
reference supplies an owner, field name, and descriptor. Resolution checks the
owner's declarations, its interfaces/superinterfaces, then its superclass,
following the [JVM field-resolution rules](https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-5.html#jvms-5.4.3.2).
This distinguishes a subclass field, an explicit superclass access, and a cast
when there is a unique declaration among the relevant references.

Source lines select possible method overloads. Field references are collected
from the **whole method**, because the null-producing expression can occur before
the throwing line. This is a conservative search, not complete operand-stack
provenance analysis. If several declarations with the same obfuscated name are
referenced by a candidate method, the field stays unchanged. A unique candidate
is not selected by guessing which statement on a line threw the exception.

Without a usable method context, lookup searches the receiver's complete known
hierarchy and requires a unique declaration. A missing hierarchy node, conflicting
interface fields, or unresolved hiding prevents a field substitution. Interface
fields are static; they are not inherited instance state.

Without `-injars`, the original mapping-only inference is retained: a unique
field mapping on the class in the following frame can rename `this.f`. **That
mode cannot establish an inherited field's owner or rule out field hiding.**
Mapping files contain no hierarchy, so use `-injars` for inherited fields.

This does not establish the runtime type of a null value. Fabricated messages,
wrong-build inputs, missing classes, and incomplete optimizer mappings can still
prevent resolution. Use the mapping and class files from the exact crashed build.

## Examples and validation

Run the original synthetic example:

```sh
java -jar lib/retrace.jar examples/retrace-hotfix/mapping.txt examples/retrace-hotfix/stacktrace.txt
```

Compare with `expected.txt`. Its names `MessageLoop`, `CollectionImporter`,
`ObjectionImporter`, and `viewModel` are illustrative, not recovered names from
your application's mapping.

Run an end-to-end smoke test with Python 3, Java 17+, and `javac` available:

```sh
python3 examples/retrace-hotfix/smoke-test.py
```

It compiles `InheritanceCrash.java`, obfuscates it using the local ProGuard JAR,
generates real JVM exceptions, and retraces them using the resulting mapping and
obfuscated JAR. It checks current-class fields, superclass fields, interface
constants, array length, field reads/writes, synchronization, and hidden fields.
Temporary files are automatically removed.

JUnit tests also cover all listed array operations, throw/monitor actions,
custom exception names, prefixes and nested causes, chains and local fields,
unknown/ambiguous/missing declarations, malformed inputs, and hs-error formats.
The full ProGuard/ProGuardCORE test suites are not part of this focused test run.

The hs-error parser handles interpreted `j` and compiled `J` frames with `c1`,
`c2`, or `jvmci` markers. JVM descriptors select overloads and are remapped while
retaining their syntax. Bytecode offsets, compilation IDs, sizes, and native
addresses are preserved. Existing custom line-range matching in `FrameRemapper`
is unchanged.
