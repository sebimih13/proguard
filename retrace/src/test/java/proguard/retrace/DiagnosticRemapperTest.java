package proguard.retrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticRemapperTest
{
    @TempDir Path directory;

    private static final String MAPPING =
        "example.Model -> a:\n" +
        "    java.lang.Object lookup(java.lang.Object) -> a\n" +
        "    int indexed(int) -> a\n" +
        "    void longs(long) -> b\n" +
        "    void arrays(example.Value[],int[][]) -> b\n" +
        "    example.Value value() -> c\n" +
        "example.View -> v:\n" +
        "    example.Model model -> f\n" +
        "    int first -> x\n" +
        "    long second -> x\n" +
        "    10:10:void render():42:42 -> r\n" +
        "example.Ambiguous -> amb:\n" +
        "    java.lang.Object first(java.lang.Object) -> a\n" +
        "    java.lang.String second(java.lang.Object) -> a\n" +
        "example.Value -> z:\n";

    private String retrace(String input) throws IOException
    {
        return retrace(input, ReTrace.REGULAR_EXPRESSION, ReTrace.REGULAR_EXPRESSION2);
    }

    private String retrace(String input, String regex, String regex2) throws IOException
    {
        Path mapping = directory.resolve("mapping.txt");
        Files.write(mapping, MAPPING.getBytes(StandardCharsets.UTF_8));
        StringWriter output = new StringWriter();
        new ReTrace(regex, regex2, false, false, mapping.toFile()).retrace(
            new LineNumberReader(new StringReader(input)), new PrintWriter(output));
        return output.toString().replace("\r\n", "\n");
    }

    @Test void interpretedDescriptorsSelectOverloads() throws IOException
    {
        assertEquals("j  example.Model.longs(J)V+24\n" +
                     "j  example.Model.arrays([Lexample/Value;[[I)V+5\n" +
                     "j  example.Model.value()Lexample/Value;+1\n",
                     retrace("j  a.b(J)V+24\nj  a.b([Lz;[[I)V+5\nj  a.c()Lz;+1"));
    }

    @Test void compiledFramesPreserveMetadata() throws IOException
    {
        for (String compiler : new String[] {"", "c1 ", "c2 ", "jvmci "})
        {
            String prefix = "J 13199 " + compiler;
            String suffix = "(J)V (8 bytes) @ 0x0123 [0x0100+0x23]";
            assertEquals(prefix + "example.Model.longs" + suffix + "\n",
                         retrace(prefix + "a.b" + suffix));
        }
    }

    @Test void unknownNativeLambdaAndMalformedLinesSurvive() throws IOException
    {
        for (String line : new String[] {"V  [libjvm.so+0x123]", "j  a.b(Q)V+1",
             "J 12 c1 org.cef.CefApp$$Lambda$723+0x0000080.run()V (8 bytes) @ 0x12",
             "Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)"})
            assertEquals(line + "\n", retrace(line));
    }

    @Test void helpfulNpeUsesImmediateFrameForThisField() throws IOException
    {
        assertEquals("java.lang.NullPointerException: Cannot invoke \"example.Model.lookup(Object)\" because \"this.model\" is null\n" +
                     "\tat example.View.render(View.java:42)\n",
            retrace("java.lang.NullPointerException: Cannot invoke \"a.a(Object)\" because \"this.f\" is null\n" +
                    "\tat v.r(V.java:10)"));
    }

    @Test void unknownAndAmbiguousFieldsRemainUnchanged() throws IOException
    {
        for (String field : new String[] {"x", "missing"})
        {
            String message = "java.lang.NullPointerException: Cannot invoke \"a.a(Object)\" because \"this." + field + "\" is null";
            assertTrue(retrace(message + "\n\tat v.r(V.java:10)").contains("\"this." + field + "\""));
        }
        String message = "java.lang.NullPointerException: Cannot invoke \"a.a(Object)\" because \"this.f\" is null";
        assertTrue(retrace(message).contains("\"this.f\""));
        assertTrue(retrace(message + "\nCaused by: other.Error\n\tat v.r(V.java:10)").contains("\"this.f\""));
    }

    @Test void returnValueAndQualifiedFieldReferences() throws IOException
    {
        assertEquals("java.lang.NullPointerException: Cannot invoke \"example.Model.lookup(Object)\" because the return value of \"example.Model.value()\" is null\n",
            retrace("java.lang.NullPointerException: Cannot invoke \"a.a(Object)\" because the return value of \"a.c()\" is null"));
        assertTrue(retrace("java.lang.NullPointerException: Cannot invoke \"a.a(int)\" because \"v.f\" is null")
            .contains("\"example.Model.indexed(int)\" because \"example.View.model\""));
    }

    @Test void ambiguousMethodsAreNotGuessed() throws IOException
    {
        assertEquals("java.lang.NullPointerException: Cannot invoke \"example.Ambiguous.a(Object)\" because \"this.f\" is null\n",
            retrace("java.lang.NullPointerException: Cannot invoke \"amb.a(Object)\" because \"this.f\" is null"));
    }

    @Test void customRegexStillControlsParsing() throws IOException
    {
        assertEquals("j  a.b(J)V+24\n", retrace("j  a.b(J)V+24", "at %c.%m", "at %c.%m"));
    }

    @Test void regularFramesAndExceptionHeadersStillWork() throws IOException
    {
        assertEquals("example.Value\n\tat example.View.render(View.java:42)\n",
                     retrace("z\n\tat v.r(V.java:10)"));
    }
}
