/*
 * ProGuard -- shrinking, optimization, obfuscation, and preverification
 *             of Java bytecode.
 *
 * Copyright (c) 2002-2020 Guardsquare NV
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package proguard.retrace;

import proguard.classfile.util.ClassUtil;

import java.util.*;
import java.util.regex.*;

/** Remaps HotSpot diagnostic frames and quoted helpful-NPE references. */
final class DiagnosticRemapper
{
    private static final String TYPE = "(?:\\[*[BCDFIJSZ]|\\[*L[^;\\s()]+;)";
    private static final Pattern HOTSPOT = Pattern.compile(
        "^(\\s*(?:j\\s+|J\\s+\\d+\\s+(?:(?:c1|c2|jvmci)\\s+)?))" +
        "([^\\s()]+)\\.([^\\s.()]+)(\\((?:" + TYPE + ")*\\)(?:V|" + TYPE + "))(.*)$");
    private static final Pattern METHOD = Pattern.compile("\"([^\"() ]+)\\.([^\".() ]+)\\(([^\"()]*)\\)\"");
    private static final Pattern FIELD = Pattern.compile("\"([^\"() ]+)\\.([^\".() ]+)\"");

    static boolean isHelpfulNullPointer(String line)
    {
        return line.contains("java.lang.NullPointerException: Cannot invoke \"");
    }

    /** Returns null when this is not a Java frame from an hs_err log. */
    static String hotspot(String line, FrameRemapper mapper)
    {
        Matcher match = HOTSPOT.matcher(line);
        if (!match.matches()) return null;

        String descriptor = match.group(4);
        FrameInfo frame = new FrameInfo(match.group(2), null, 0,
            ClassUtil.externalMethodReturnType(descriptor), null, match.group(3),
            ClassUtil.externalMethodArguments(descriptor));
        Set<String> lines = new LinkedHashSet<String>();
        for (FrameInfo original : mapper.transform(frame))
        {
            // Bytecode offsets and native addresses are not source line numbers.
            // Retain the descriptor's JVM syntax, remapping its class references.
            lines.add(match.group(1) + original.getClassName() + "." +
                      original.getMethodName() + descriptor(descriptor, mapper) + match.group(5));
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static String descriptor(String descriptor, FrameRemapper mapper)
    {
        Matcher match = Pattern.compile("L([^;]+);").matcher(descriptor);
        StringBuffer result = new StringBuffer();
        while (match.find())
        {
            String name = mapper.originalClassName(match.group(1).replace('/', '.'));
            match.appendReplacement(result, Matcher.quoteReplacement("L" + name.replace('.', '/') + ";"));
        }
        match.appendTail(result);
        return result.toString();
    }

    static String helpfulNullPointer(String line, String nextLine, FrameRemapper mapper)
    {
        // Resolve each quoted reference independently, using the original text
        // throughout so mapping targets cannot accidentally be mapped twice.
        Matcher match = METHOD.matcher(line);
        StringBuffer result = new StringBuffer();
        while (match.find())
        {
            String owner = match.group(1);
            String name = match.group(2);
            String arguments = match.group(3);
            FrameInfo frame = new FrameInfo(owner, null, 0, null, null, name, null);
            Set<String> candidates = new LinkedHashSet<String>();
            for (FrameInfo original : mapper.transform(frame))
            {
                if (argumentsMatch(arguments, original.getArguments(), mapper))
                {
                    candidates.add(original.getClassName() + "." + original.getMethodName());
                }
            }
            String reference = candidates.size() == 1 ? candidates.iterator().next() :
                               mapper.originalClassName(owner) + "." + name;
            match.appendReplacement(result, Matcher.quoteReplacement("\"" + reference +
                "(" + remapArguments(arguments, mapper) + ")\""));
        }
        match.appendTail(result);

        // A bare this.field can only be interpreted in the immediately following
        // throwing frame. Mapping files contain no superclass/receiver metadata.
        FrameInfo context = nextLine == null ? null :
            new FramePattern("\\s*at %c\\.%m\\(%s(?::%l)?\\)", false).parse(nextLine);
        match = FIELD.matcher(result.toString());
        result = new StringBuffer();
        while (match.find())
        {
            String owner = match.group(1);
            String name = match.group(2);
            String replacement = match.group();
            if (!owner.equals("this") || context != null)
            {
                String fieldOwner = owner.equals("this") ? context.getClassName() : owner;
                // Do not interpret arbitrary expression chains as class names.
                FrameInfo frame = new FrameInfo(fieldOwner, null, 0, null, name, null, null);
                Set<String> candidates = new LinkedHashSet<String>();
                for (FrameInfo original : mapper.transform(frame))
                {
                    candidates.add(original.getFieldName());
                }
                if (candidates.size() == 1)
                {
                    replacement = "\"" + (owner.equals("this") ? "this" : mapper.originalClassName(owner)) +
                                  "." + candidates.iterator().next() + "\"";
                }
            }
            match.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        match.appendTail(result);
        return result.toString();
    }

    private static boolean argumentsMatch(String actual, String expected, FrameRemapper mapper)
    {
        if (expected == null) return false;
        String[] actualTypes = actual.split(",", -1);
        String[] expectedTypes = expected.split(",", -1);
        if (actualTypes.length != expectedTypes.length) return false;
        for (int index = 0; index < actualTypes.length; index++)
        {
            String type = remapType(actualTypes[index].trim(), mapper);
            String expectedType = expectedTypes[index].trim();
            // HotSpot abbreviates java.lang types in helpful-NPE signatures.
            if (!type.equals(expectedType) &&
                !(expectedType.startsWith("java.lang.") &&
                  type.equals(expectedType.substring("java.lang.".length())))) return false;
        }
        return true;
    }

    private static String remapArguments(String arguments, FrameRemapper mapper)
    {
        String[] types = arguments.split(",", -1);
        for (int index = 0; index < types.length; index++)
        {
            String type = types[index].trim();
            types[index] = types[index].replace(type, remapType(type, mapper));
        }
        return String.join(",", types);
    }

    private static String remapType(String type, FrameRemapper mapper)
    {
        int array = type.indexOf('[');
        return array < 0 ? mapper.originalClassName(type) :
            mapper.originalClassName(type.substring(0, array)) + type.substring(array);
    }
}
