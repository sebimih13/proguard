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
    private static final Pattern HELPFUL = Pattern.compile(
        "^(.*?)([^\\s:]+): (Cannot (?:invoke \"[^\"]+\"|(?:read|assign) field \"[^\"]+\"|" +
        "read the array length|(?:load from|store to) (?:object|int|byte/boolean|char|short|long|float|double) array|" +
        "throw exception|(?:enter|exit) synchronized block))" +
        "(?: because (the return value of )?\"([^\"]*)\" is null)?$");
    private static final Pattern ACTION_FIELD = Pattern.compile("Cannot (?:read|assign) field \"([^\"]+)\"");
    private static final Pattern STACK_FRAME = Pattern.compile("^\\s*at\\s+([^\\s(]+)\\(([^()]*)\\)(?:\\s+.*)?$");

    static boolean isHelpfulException(String line)
    {
        return HELPFUL.matcher(line).matches();
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

    /** Parses the message structure, independently of the exception class name. */
    static String helpfulException(String line, String nextLine, FrameRemapper mapper,
                                   DiagnosticClassPool classes)
    {
        Matcher message = HELPFUL.matcher(line);
        if (!message.matches()) return line;
        FrameInfo context = contextFrame(nextLine);
        String reason = message.group(5);
        Expression receiver = reason == null ? new Expression("", null) :
            message.group(4) != null ? method(reason, mapper) : expression(reason, context, mapper, classes);

        String action = message.group(3);
        Matcher field = ACTION_FIELD.matcher(action);
        if (field.matches())
        {
            Expression resolved = field(receiver.type, field.group(1), false, context, mapper, classes);
            if (resolved != null)
                action = action.substring(0, field.start(1)) + resolved.text + action.substring(field.end(1));
        }
        else if (action.startsWith("Cannot invoke \""))
        {
            action = "Cannot invoke \"" + method(action.substring(15, action.length() - 1), mapper).text + "\"";
        }
        return message.group(1) + mapper.originalClassName(message.group(2)) + ": " + action +
               (reason == null ? "" : " because " + (message.group(4) == null ? "" : message.group(4)) +
                "\"" + receiver.text + "\" is null");
    }

    private static FrameInfo contextFrame(String line)
    {
        if (line == null) return null;
        Matcher frame = STACK_FRAME.matcher(line);
        if (!frame.matches()) return null;
        String reference = frame.group(1);
        // Java 9+ frames can have a module and/or class-loader prefix.
        reference = reference.substring(reference.lastIndexOf('/') + 1);
        int dot = reference.lastIndexOf('.');
        if (dot < 0) return null;
        String source = frame.group(2);
        int lineNumber = 0;
        int colon = source.lastIndexOf(':');
        if (colon >= 0)
        {
            try { lineNumber = Integer.parseInt(source.substring(colon + 1)); }
            catch (NumberFormatException ignored) { /* Unknown or nonstandard source location. */ }
        }
        return new FrameInfo(reference.substring(0, dot), source, lineNumber, null, null,
                             reference.substring(dot + 1), null);
    }

    private static Expression method(String reference, FrameRemapper mapper)
    {
        Matcher match = METHOD.matcher("\"" + reference + "\"");
        if (!match.matches()) return new Expression(reference, null);
        String owner = match.group(1);
        String name = match.group(2);
        String arguments = match.group(3);
        FrameInfo frame = new FrameInfo(owner, null, 0, null, null, name, null);
        Set<String> candidates = new LinkedHashSet<String>();
        Set<String> types = new HashSet<String>();
        for (FrameInfo original : mapper.transform(frame))
        {
            if (argumentsMatch(arguments, original.getArguments(), mapper))
            {
                candidates.add(original.getClassName() + "." + original.getMethodName());
                types.add(mapper.obfuscatedType(original.getType()));
            }
        }
        String target = candidates.size() == 1 ? candidates.iterator().next() :
                        mapper.originalClassName(owner) + "." + name;
        return new Expression(target + "(" + remapArguments(arguments, mapper) + ")",
                              types.size() == 1 ? types.iterator().next() : null);
    }

    /** Remaps each field in a typed chain; preserves locals and unknown suffixes. */
    private static Expression expression(String expression, FrameInfo context,
                                         FrameRemapper mapper, DiagnosticClassPool classes)
    {
        int index;
        String type;
        StringBuilder result;
        boolean staticOnly;
        if (expression.equals("this") || expression.startsWith("this.") || expression.startsWith("this["))
        {
            index = 4;
            type = context == null ? null : context.getClassName();
            result = new StringBuilder("this");
            staticOnly = false;
        }
        else
        {
            // Longest known class prefix avoids treating a package component,
            // local variable placeholder, or arbitrary dotted text as an owner.
            index = expression.length();
            while ((index = expression.lastIndexOf('.', index - 1)) >= 0)
            {
                String prefix = expression.substring(0, index);
                if (mapper.hasClassMapping(prefix) || classes != null && classes.contains(prefix)) break;
            }
            if (index < 0)
            {
                // Keep the local/parameter name, but bytecode may still identify
                // uniquely named fields accessed through it.
                index = 0;
                while (index < expression.length() && expression.charAt(index) != '.' && expression.charAt(index) != '[') index++;
                type = null;
                result = new StringBuilder(expression.substring(0, index));
                staticOnly = false;
            }
            else
            {
                type = expression.substring(0, index);
                result = new StringBuilder(mapper.originalClassName(type));
                staticOnly = true;
            }
        }
        while (index < expression.length())
        {
            char token = expression.charAt(index);
            if (token == '[')
            {
                int end = expression.indexOf(']', index);
                if (end < 0) return new Expression(expression, null);
                result.append(expression, index, end + 1);
                type = type != null && type.endsWith("[]") ? type.substring(0, type.length() - 2) : null;
                index = end + 1;
            }
            else if (token == '.')
            {
                int end = index + 1;
                while (end < expression.length() && expression.charAt(end) != '.' && expression.charAt(end) != '[') end++;
                String name = expression.substring(index + 1, end);
                Expression resolved = field(type, name, staticOnly, context, mapper, classes);
                result.append('.').append(resolved == null ? name : resolved.text);
                type = resolved == null ? null : resolved.type;
                staticOnly = false;
                index = end;
            }
            else return new Expression(expression, null);
        }
        return new Expression(result.toString(), type);
    }

    private static Expression field(String owner, String name, boolean staticOnly,
                                    FrameInfo context, FrameRemapper mapper, DiagnosticClassPool classes)
    {
        if (classes != null)
        {
            List<DiagnosticClassPool.FieldInfo> declarations = classes.referencedFields(context, name, staticOnly);
            if (declarations == null)
                declarations = owner == null ? Collections.<DiagnosticClassPool.FieldInfo>emptyList() :
                               classes.fields(owner, name, staticOnly);
            // Even equally named fields in different declaring classes are
            // ambiguous: the message lacks the bytecode field descriptor.
            if (declarations.size() != 1) return null;
            DiagnosticClassPool.FieldInfo declaration = declarations.get(0);
            List<FrameInfo> mappings = mapper.fieldMappings(declaration.owner, name, declaration.type);
            if (mappings.isEmpty()) return new Expression(name, declaration.type);
            Expression mapped = uniqueField(mappings, mapper);
            return mapped == null ? null : new Expression(mapped.text, declaration.type);
        }
        // Mapping-only compatibility: this is an inference from the immediate
        // frame, not proof of the field's declaring class.
        return owner == null ? null : uniqueField(mapper.fieldMappings(owner, name, null), mapper);
    }

    private static Expression uniqueField(List<FrameInfo> fields, FrameRemapper mapper)
    {
        Expression result = null;
        String owner = null;
        for (FrameInfo field : fields)
        {
            String type = mapper.obfuscatedType(field.getType());
            if (result != null && (!result.text.equals(field.getFieldName()) ||
                !Objects.equals(result.type, type) || !owner.equals(field.getClassName()))) return null;
            result = new Expression(field.getFieldName(), type);
            owner = field.getClassName();
        }
        return result;
    }

    private static final class Expression
    {
        final String text;
        final String type;

        Expression(String text, String type)
        {
            this.text = text;
            this.type = type;
        }
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
