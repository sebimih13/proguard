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

import proguard.classfile.*;
import proguard.classfile.attribute.*;
import proguard.classfile.constant.FieldrefConstant;
import proguard.classfile.instruction.*;
import proguard.classfile.io.ProgramClassReader;
import proguard.classfile.util.ClassUtil;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** Class-file metadata for diagnostic field lookup. Never loads or executes classes. */
final class DiagnosticClassPool
{
    private final Map<String, ClassInfo> classes = new HashMap<String, ClassInfo>();

    DiagnosticClassPool(List<File> inputs) throws IOException
    {
        for (File input : inputs)
        {
            if (input.isDirectory())
            {
                try (Stream<Path> paths = Files.walk(input.toPath()))
                {
                    Iterator<Path> iterator = paths.filter(p -> p.toString().endsWith(".class")).iterator();
                    while (iterator.hasNext())
                    {
                        try (InputStream stream = Files.newInputStream(iterator.next())) { read(stream); }
                    }
                }
            }
            else if (input.getName().endsWith(".class"))
            {
                try (InputStream stream = new FileInputStream(input)) { read(stream); }
            }
            else
            {
                try (ZipFile zip = new ZipFile(input))
                {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements())
                    {
                        ZipEntry entry = entries.nextElement();
                        if (!entry.getName().endsWith(".class")) continue;
                        // There is no target runtime version in a stack trace.
                        if (entry.getName().startsWith("META-INF/versions/"))
                            throw new IOException("Multi-release input requires extracted runtime classes: " + input);
                        try (InputStream stream = zip.getInputStream(entry)) { read(stream); }
                    }
                }
            }
        }
    }

    private void read(InputStream stream) throws IOException
    {
        ProgramClass clazz = new ProgramClass();
        try
        {
            clazz.accept(new ProgramClassReader(new DataInputStream(new BufferedInputStream(stream))));
            index(clazz);
        }
        catch (RuntimeException ex)
        {
            throw new IOException("Cannot read diagnostic class file", ex);
        }
    }

    private void index(ProgramClass clazz) throws IOException
    {
        String name = ClassUtil.externalClassName(clazz.getName());
        if (name.equals("module-info")) return;
        if (classes.containsKey(name)) throw new IOException("Duplicate diagnostic class: " + name);
        ClassInfo info = new ClassInfo();
        for (int index = 0; index < clazz.getInterfaceCount(); index++)
            info.parents.add(ClassUtil.externalClassName(clazz.getInterfaceName(index)));
        if (clazz.getSuperName() != null)
        {
            info.superName = ClassUtil.externalClassName(clazz.getSuperName());
            info.parents.add(ClassUtil.externalClassName(clazz.getSuperName()));
        }
        for (int index = 0; index < clazz.u2fieldsCount; index++)
        {
            ProgramField field = clazz.fields[index];
            info.fields.add(new FieldInfo(name, field.getName(clazz),
                ClassUtil.externalType(field.getDescriptor(clazz)),
                (field.getAccessFlags() & AccessConstants.STATIC) != 0));
        }
        for (int index = 0; index < clazz.u2methodsCount; index++)
        {
            ProgramMethod method = clazz.methods[index];
            MethodInfo methodInfo = new MethodInfo(method.getName(clazz));
            for (int attributeIndex = 0; attributeIndex < method.u2attributesCount; attributeIndex++)
            {
                if (!(method.attributes[attributeIndex] instanceof CodeAttribute)) continue;
                CodeAttribute code = (CodeAttribute)method.attributes[attributeIndex];
                LineNumberTableAttribute lines = (LineNumberTableAttribute)code.getAttribute(clazz, Attribute.LINE_NUMBER_TABLE);
                for (int offset = 0; offset < code.u4codeLength;)
                {
                    Instruction instruction = InstructionFactory.create(code.code, offset);
                    if (lines != null) methodInfo.lines.add(lines.getLineNumber(offset));
                    if (instruction instanceof ConstantInstruction)
                    {
                        int constantIndex = ((ConstantInstruction)instruction).constantIndex;
                        if (clazz.constantPool[constantIndex] instanceof FieldrefConstant)
                        {
                            FieldrefConstant reference = (FieldrefConstant)clazz.constantPool[constantIndex];
                            methodInfo.references.add(new FieldInfo(
                                ClassUtil.externalClassName(reference.getClassName(clazz)), reference.getName(clazz),
                                ClassUtil.externalType(reference.getType(clazz)),
                                instruction.opcode == Instruction.OP_GETSTATIC || instruction.opcode == Instruction.OP_PUTSTATIC));
                        }
                    }
                    offset += instruction.length(offset);
                }
            }
            info.methods.add(methodInfo);
        }
        classes.put(name, info);
    }

    boolean contains(String name) { return classes.containsKey(name); }

    /**
     * Returns candidates from the throwing method, or null without a matching
     * method context. Examine the whole method: a null producer can precede the
     * throwing source line. Use source lines only to select method overloads.
     */
    List<FieldInfo> referencedFields(FrameInfo context, String name, boolean staticOnly)
    {
        if (context == null) return null;
        ClassInfo clazz = classes.get(context.getClassName());
        if (clazz == null) return null;
        Map<String, FieldInfo> result = new LinkedHashMap<String, FieldInfo>();
        boolean foundMethod = false;
        boolean foundName = false;
        for (MethodInfo method : clazz.methods)
        {
            if (!method.name.equals(context.getMethodName())) continue;
            foundName = true;
            if (context.getLineNumber() != 0 && !method.lines.isEmpty() && !method.lines.contains(context.getLineNumber())) continue;
            foundMethod = true;
            for (FieldInfo reference : method.references)
            {
                if (!reference.name.equals(name) || staticOnly && !reference.isStatic) continue;
                List<FieldInfo> declarations = new ArrayList<FieldInfo>();
                if (!resolve(reference.owner, reference.name, reference.type, new HashSet<String>(), declarations) ||
                    declarations.isEmpty()) return Collections.emptyList();
                for (FieldInfo declaration : declarations)
                {
                    if (declaration.isStatic != reference.isStatic) return Collections.emptyList();
                    result.put(declaration.owner + ":" + declaration.name + ":" + declaration.type, declaration);
                }
            }
        }
        return foundMethod || foundName ? new ArrayList<FieldInfo>(result.values()) : null;
    }

    /** JVM field-reference lookup: name AND descriptor, interfaces before superclass. */
    private boolean resolve(String owner, String name, String type, Set<String> visited, List<FieldInfo> result)
    {
        if (!visited.add(owner)) return true;
        ClassInfo clazz = classes.get(owner);
        if (clazz == null) return owner.equals("java.lang.Object");
        for (FieldInfo field : clazz.fields)
        {
            if (field.name.equals(name) && field.type.equals(type))
            {
                result.add(field);
                return true;
            }
        }
        boolean complete = true;
        int size = result.size();
        for (String parent : clazz.parents)
            if (!parent.equals(clazz.superName)) complete &= resolve(parent, name, type, visited, result);
        if (!complete || result.size() != size || clazz.superName == null) return complete;
        return resolve(clazz.superName, name, type, visited, result);
    }

    /**
     * Without a field descriptor in the message, hiding cannot be resolved using
     * Java source lookup rules. Collect every possible declaration instead of
     * guessing the first match. Missing hierarchy nodes make lookup inconclusive.
     */
    List<FieldInfo> fields(String owner, String name, boolean staticOnly)
    {
        List<FieldInfo> result = new ArrayList<FieldInfo>();
        if (!collect(owner, name, staticOnly, new HashSet<String>(), result)) return Collections.emptyList();
        return result;
    }

    private boolean collect(String owner, String name, boolean staticOnly,
                            Set<String> visited, List<FieldInfo> result)
    {
        if (!visited.add(owner)) return true;
        ClassInfo info = classes.get(owner);
        if (info == null) return owner.equals("java.lang.Object");
        for (FieldInfo field : info.fields)
            if (field.name.equals(name) && (!staticOnly || field.isStatic)) result.add(field);
        boolean complete = true;
        for (String parent : info.parents)
            complete &= collect(parent, name, staticOnly, visited, result);
        return complete;
    }

    static final class FieldInfo
    {
        final String owner;
        final String name;
        final String type;
        final boolean isStatic;

        FieldInfo(String owner, String name, String type, boolean isStatic)
        {
            this.owner = owner;
            this.name = name;
            this.type = type;
            this.isStatic = isStatic;
        }
    }

    private static final class ClassInfo
    {
        String superName;
        final List<String> parents = new ArrayList<String>();
        final List<FieldInfo> fields = new ArrayList<FieldInfo>();
        final List<MethodInfo> methods = new ArrayList<MethodInfo>();
    }

    private static final class MethodInfo
    {
        final String name;
        final Set<Integer> lines = new HashSet<Integer>();
        final List<FieldInfo> references = new ArrayList<FieldInfo>();

        MethodInfo(String name) { this.name = name; }
    }
}
