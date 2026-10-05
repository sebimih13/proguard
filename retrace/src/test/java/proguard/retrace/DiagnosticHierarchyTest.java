package proguard.retrace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticHierarchyTest
{
    @TempDir Path directory;
    private Path classes;
    private Path mapping;
    private static final String HEADER = "java.lang.NullPointerException: ";
    private static final String MAPPING =
        "original.Parent -> fixture.Parent:\n" +
        "    original.Model inheritedModel -> f\n" +
        "    original.Node head -> n\n" +
        "    original.Node[] items -> a\n" +
        "    java.lang.Object lock -> l\n" +
        "original.Child -> fixture.Child:\n" +
        "    original.Model ownModel -> o\n" +
        "original.Hiding -> fixture.Hiding:\n" +
        "    original.Model hiddenModel -> f\n" +
        "original.DifferentType -> fixture.DifferentType:\n" +
        "    java.lang.Object hiddenObject -> f\n" +
        "original.Model -> fixture.Model:\n" +
        "    java.lang.Object lookup(java.lang.Object) -> m\n" +
        "    original.Node node() -> n\n" +
        "original.Node -> fixture.Node:\n" +
        "    original.Node next -> n\n" +
        "    int count -> c\n" +
        "original.Root -> fixture.Root:\n" +
        "    original.Model shared -> i\n" +
        "original.Left -> fixture.Left:\n" +
        "original.Right -> fixture.Right:\n" +
        "original.Diamond -> fixture.Diamond:\n" +
        "original.Conflicting -> fixture.Conflicting:\n" +
        "    original.Model other -> i\n" +
        "original.Collision -> fixture.Collision:\n" +
        "original.CustomException -> fixture.Error:\n";

    @BeforeEach void compileClasses() throws IOException
    {
        classes = Files.createDirectory(directory.resolve("classes"));
        mapping = directory.resolve("mapping.txt");
        Files.write(mapping, MAPPING.getBytes(StandardCharsets.UTF_8));
        Path source = directory.resolve("Fixture.java");
        Files.write(source, ("package fixture;\n" +
            "interface Root { Model i = null; }\n" +
            "interface Left extends Root {}\n" +
            "interface Right extends Root {}\n" +
            "interface Diamond extends Left, Right {}\n" +
            "interface Conflicting { Model i = null; }\n" +
            "class Collision implements Diamond, Conflicting {}\n" +
            "class Model { Object m(Object o) { return null; } Node n() { return null; } }\n" +
            "class Node { Node n; int c; }\n" +
            "class Parent { Model f; Node n; Node[] a; Object l; " +
            "Object cast() { return ((Hiding)this).f.m(null); } " +
            "int local(Node user) { return user.c; } " +
            "int localChain(Node user) { return user.n.c; } }\n" +
            "class Child extends Parent implements Diamond { Model o; }\n" +
            "class Hiding extends Parent { Model f; " +
            "Object own() { return f.m(null); } " +
            "Object inherited() { return super.f.m(null); } " +
            "Object both() { return f == null ? super.f.m(null) : f.m(null); } }\n" +
            "class DifferentType extends Parent { Object f; }\n").getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK");
        assertEquals(0, compiler.run(null, null, null, "-g", "-d", classes.toString(), source.toString()));
    }

    private String retrace(String message, String owner, File... inputs) throws IOException
    {
        return retraceAt(message, owner == null ? null : owner + ".run(Fixture.java:10)", inputs);
    }

    private String retraceAt(String message, String frame, File... inputs) throws IOException
    {
        String input = message + (frame == null ? "" : "\n\tat " + frame);
        StringWriter output = new StringWriter();
        new ReTrace(mapping.toFile(), Arrays.asList(inputs)).retrace(
            new LineNumberReader(new StringReader(input)), new PrintWriter(output));
        return output.toString().split("\\R")[0];
    }

    private String retrace(String message, String owner) throws IOException
    {
        return retrace(message, owner, classes.toFile());
    }

    @Test void resolvesOwnAndInheritedFieldWithDeclaringClass() throws IOException
    {
        String action = HEADER + "Cannot invoke \"fixture.Model.m(Object)\" because ";
        assertEquals(HEADER + "Cannot invoke \"original.Model.lookup(Object)\" because \"this.inheritedModel\" is null",
                     retrace(action + "\"this.f\" is null", "fixture.Child"));
        assertTrue(retrace(action + "\"this.o\" is null", "fixture.Child").contains("\"this.ownModel\""));
        DiagnosticClassPool pool = new DiagnosticClassPool(Collections.singletonList(classes.toFile()));
        assertEquals("fixture.Parent", pool.fields("fixture.Child", "f", false).get(0).owner);
    }

    @Test void resolvesSuperinterfacesAndDeduplicatesDiamond() throws IOException
    {
        for (String owner : Arrays.asList("fixture.Root", "fixture.Left", "fixture.Diamond", "fixture.Child"))
        {
            String message = HEADER + "Cannot invoke \"fixture.Model.m(Object)\" because \"" + owner + ".i\" is null";
            assertTrue(retrace(message, "fixture.Child").endsWith(".shared\" is null"));
        }
        DiagnosticClassPool pool = new DiagnosticClassPool(Collections.singletonList(classes.toFile()));
        assertEquals("fixture.Root", pool.fields("fixture.Child", "i", true).get(0).owner);
        assertEquals(1, pool.fields("fixture.Child", "i", true).size());
    }

    @Test void refusesHiddenFieldsAndConflictingInterfaces() throws IOException
    {
        for (String owner : Arrays.asList("fixture.Hiding", "fixture.DifferentType"))
            assertTrue(retrace(HEADER + "Cannot read the array length because \"this.f\" is null", owner)
                       .contains("\"this.f\""));
        assertTrue(retrace(HEADER + "Cannot enter synchronized block because \"fixture.Collision.i\" is null", null)
                   .contains("\"original.Collision.i\""));
    }

    @Test void incompleteHierarchyDoesNotFallBackToMappingGuess() throws IOException
    {
        Files.delete(classes.resolve("fixture/Parent.class"));
        assertTrue(retrace(HEADER + "Cannot invoke \"fixture.Model.m(Object)\" because \"this.o\" is null", "fixture.Child")
                   .contains("\"this.o\""));
    }

    @Test void resolvesTypedChainsArrayElementsAndTargetField() throws IOException
    {
        assertEquals(HEADER + "Cannot read field \"next\" because \"this.head.next\" is null",
            retrace(HEADER + "Cannot read field \"n\" because \"this.n.n\" is null", "fixture.Child"));
        assertEquals(HEADER + "Cannot assign field \"count\" because \"this.items[2]\" is null",
            retrace(HEADER + "Cannot assign field \"c\" because \"this.a[2]\" is null", "fixture.Child"));
        assertEquals(HEADER + "Cannot read field \"next\" because the return value of \"original.Model.node()\" is null",
            retrace(HEADER + "Cannot read field \"n\" because the return value of \"fixture.Model.n()\" is null", null));
        assertTrue(retrace(HEADER + "Cannot read field \"unknown\" because \"this.n.missing.n\" is null", "fixture.Child")
                   .contains("\"this.head.missing.n\""));
    }

    @TestFactory Stream<DynamicTest> handlesEveryAction()
    {
        List<String> actions = new ArrayList<String>(Arrays.asList("read the array length", "throw exception",
            "enter synchronized block", "exit synchronized block"));
        for (String type : Arrays.asList("object", "int", "byte/boolean", "char", "short", "long", "float", "double"))
            for (String operation : Arrays.asList("load from ", "store to ")) actions.add(operation + type + " array");
        return actions.stream().map(action -> DynamicTest.dynamicTest(action, () -> {
            String expected = HEADER + "Cannot " + action + " because \"this.lock\" is null";
            assertEquals(expected, retrace(HEADER + "Cannot " + action + " because \"this.l\" is null", "fixture.Child"));
        }));
    }

    @TestFactory Stream<DynamicTest> preservesUnknownReceiverExpressions()
    {
        return Arrays.asList("myVar", "<local4>", "<parameter1>", "<local1>.name", "<local2>[0]", "null",
                             "names[2]", "args", "flag", "c", "list", "data").stream().map(reason ->
            DynamicTest.dynamicTest(reason, () -> {
                String message = HEADER + "Cannot read field \"n\" because \"" + reason + "\" is null";
                assertEquals(message, retrace(message, "fixture.Child"));
            }));
    }

    @Test void supportsAnyExceptionNameAndPrefixes() throws IOException
    {
        for (String prefix : Arrays.asList("", "Caused by: ", "\tSuppressed: ", "Exception in thread \"main\" "))
            for (String exception : Arrays.asList("java.lang.NullPointerException", "java.lang.MethodNotFoundException", "fixture.Error"))
            {
                String actual = prefix + exception + ": Cannot invoke \"fixture.Model.m(Object)\" because \"this.f\" is null";
                String expected = prefix + (exception.equals("fixture.Error") ? "original.CustomException" : exception) +
                    ": Cannot invoke \"original.Model.lookup(Object)\" because \"this.inheritedModel\" is null";
                assertEquals(expected, retrace(actual, "fixture.Child"));
            }
    }

    @Test void preservesExplicitAndFrameworkMessages() throws IOException
    {
        for (String message : Arrays.asList("java.lang.NullPointerException", HEADER + "name must not be null",
            HEADER + "Parameter specified as non-null is null: method foo, parameter bar",
            HEADER + "name is marked non-null but is null"))
            assertEquals(message, retrace(message, "fixture.Child"));
    }

    @Test void handlesMessageWithoutReasonAndModulePrefixedFrame() throws IOException
    {
        assertEquals(HEADER + "Cannot invoke \"original.Model.lookup(Object)\"",
                     retrace(HEADER + "Cannot invoke \"fixture.Model.m(Object)\"", null));
        assertTrue(retrace(HEADER + "Cannot enter synchronized block because \"this.l\" is null",
                           "app/module@1/fixture.Child").contains("\"this.lock\""));
        assertTrue(retrace(HEADER + "Cannot enter synchronized block because \"this.l\" is null", null)
                   .contains("\"this.l\""));
    }

    @Test void bytecodeDistinguishesHiddenFieldsAndCasts() throws IOException
    {
        String message = HEADER + "Cannot invoke \"fixture.Model.m(Object)\" because \"this.f\" is null";
        assertTrue(retraceAt(message, "fixture.Hiding.own(Unknown Source)", classes.toFile()).contains("\"this.hiddenModel\""));
        assertTrue(retraceAt(message, "fixture.Hiding.inherited(Unknown Source)", classes.toFile()).contains("\"this.inheritedModel\""));
        assertTrue(retraceAt(message, "fixture.Parent.cast(Unknown Source)", classes.toFile()).contains("\"this.hiddenModel\""));
        assertTrue(retraceAt(message, "fixture.Hiding.both(Unknown Source)", classes.toFile()).contains("\"this.f\""));
    }

    @Test void bytecodeIdentifiesActionFieldWithoutLocalDebugType() throws IOException
    {
        String message = HEADER + "Cannot read field \"c\" because \"<parameter1>\" is null";
        assertTrue(retraceAt(message, "fixture.Parent.local(Unknown Source)", classes.toFile()).contains("field \"count\""));
    }

    @Test void bytecodeResolvesFieldsOfLocalsWithoutRenamingTheLocal() throws IOException
    {
        String message = HEADER + "Cannot read field \"c\" because \"<parameter1>.n\" is null";
        assertEquals(HEADER + "Cannot read field \"count\" because \"<parameter1>.next\" is null",
                     retraceAt(message, "fixture.Parent.localChain(Unknown Source)", classes.toFile()));
    }

    @Test void allClassNamesDoesNotRemapOriginalNamesOrLocalVariablesTwice() throws IOException
    {
        Files.write(mapping, (MAPPING + "unrelated.Other -> original.Model:\n").getBytes(StandardCharsets.UTF_8));
        StringWriter output = new StringWriter();
        String message = HEADER + "Cannot invoke \"fixture.Model.m(Object)\" because \"fixture.Model\" is null";
        new ReTrace(ReTrace.REGULAR_EXPRESSION, ReTrace.REGULAR_EXPRESSION2, true, false, mapping.toFile()).retrace(
            new LineNumberReader(new StringReader(message)), new PrintWriter(output));
        assertEquals(HEADER + "Cannot invoke \"original.Model.lookup(Object)\" because \"fixture.Model\" is null\n",
                     output.toString().replace("\r\n", "\n"));
    }

    @Test void lookaheadDoesNotCrossCausesOrSuppressedExceptions() throws IOException
    {
        String first = HEADER + "Cannot enter synchronized block because \"this.l\" is null";
        String second = "Caused by: " + first;
        StringWriter output = new StringWriter();
        new ReTrace(mapping.toFile(), Collections.singletonList(classes.toFile())).retrace(
            new LineNumberReader(new StringReader(first + "\n" + second + "\n\tat fixture.Child.run(Unknown Source)")),
            new PrintWriter(output));
        String[] lines = output.toString().split("\\R");
        assertEquals(first, lines[0]);
        assertEquals(second.replace("this.l", "this.lock"), lines[1]);
        assertEquals(3, lines.length);
    }

    private File jar(String name, String prefix) throws IOException
    {
        Path jar = directory.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> files = Files.walk(classes))
        {
            for (Path path : files.filter(Files::isRegularFile).collect(Collectors.toList()))
            {
                output.putNextEntry(new JarEntry(prefix + classes.relativize(path).toString().replace(File.separatorChar, '/')));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
        return jar.toFile();
    }

    @Test void readsJarsAndIndividualClassFiles() throws IOException
    {
        String message = HEADER + "Cannot enter synchronized block because \"this.l\" is null";
        assertTrue(retrace(message, "fixture.Child", jar("app.jar", "")).contains("\"this.lock\""));
        assertTrue(retrace(message, "fixture.Parent", classes.resolve("fixture/Parent.class").toFile())
                   .contains("\"this.lock\""));
    }

    @Test void rejectsMissingDuplicateCorruptAndVersionAmbiguousInputs() throws IOException
    {
        assertThrows(IOException.class, () -> new DiagnosticClassPool(Collections.singletonList(directory.resolve("missing.jar").toFile())));
        assertThrows(IOException.class, () -> new DiagnosticClassPool(Arrays.asList(classes.toFile(), jar("app.jar", ""))));
        assertThrows(IOException.class, () -> new DiagnosticClassPool(Collections.singletonList(jar("multi.jar", "META-INF/versions/11/"))));
        Path broken = directory.resolve("Broken.class");
        Files.write(broken, new byte[] {0, 1, 2});
        assertThrows(IOException.class, () -> new DiagnosticClassPool(Collections.singletonList(broken.toFile())));
    }
}
