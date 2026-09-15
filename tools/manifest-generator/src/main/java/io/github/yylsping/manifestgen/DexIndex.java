package io.github.yylsping.manifestgen;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.StringReference;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Multi-dex APK index used by every per-feature resolver. */
final class DexIndex {
    private final Map<String, ClassDef> classes = new HashMap<>();

    private DexIndex() {
    }

    static DexIndex load(File input) throws IOException {
        DexIndex index = new DexIndex();
        if (input.isDirectory()) {
            // Runtime-restored dex dumps for packed hosts (the raw APK only
            // carries the shell stub; the business dex is decrypted at
            // runtime and must be dumped separately).
            File[] dumps = input.listFiles(
                    (dir, name) -> name.endsWith(".dex"));
            if (dumps == null || dumps.length == 0) {
                throw new IOException("no .dex files in " + input);
            }
            for (File dump : dumps) {
                index.add(DexFileFactory.loadDexContainer(dump, null));
            }
        } else {
            index.add(DexFileFactory.loadDexContainer(input, null));
        }
        return index;
    }

    private void add(MultiDexContainer<? extends DexBackedDexFile> container)
            throws IOException {
        for (String name : container.getDexEntryNames()) {
            for (ClassDef classDef : container.getEntry(name).getDexFile().getClasses()) {
                classes.putIfAbsent(classDef.getType(), classDef);
            }
        }
    }

    ClassDef classByDescriptor(String descriptor) {
        return classes.get(descriptor);
    }

    ClassDef classByName(String className) {
        return classes.get(Specs.classDescriptorOf(className));
    }

    Iterable<ClassDef> allClasses() {
        return classes.values();
    }

    int classCount() {
        return classes.size();
    }

    static String methodDescriptor(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDefiningClass()).append("->").append(method.getName()).append('(');
        for (CharSequence parameter : method.getParameterTypes()) {
            sb.append(parameter);
        }
        return sb.append(')').append(method.getReturnType()).toString();
    }

    static boolean isStatic(Method method) {
        return AccessFlags.STATIC.isSet(method.getAccessFlags());
    }

    static boolean isAbstract(Method method) {
        return AccessFlags.ABSTRACT.isSet(method.getAccessFlags());
    }

    static boolean isPublic(Method method) {
        return AccessFlags.PUBLIC.isSet(method.getAccessFlags());
    }

    static boolean isFinal(ClassDef classDef) {
        return AccessFlags.FINAL.isSet(classDef.getAccessFlags());
    }

    static List<String> parameterDescriptors(Method method) {
        List<String> parameters = new ArrayList<>();
        for (CharSequence parameter : method.getParameterTypes()) {
            parameters.add(parameter.toString());
        }
        return parameters;
    }

    /** Direct superclass chain, excluding the class itself. */
    List<String> superclassChain(ClassDef classDef) {
        List<String> chain = new ArrayList<>();
        String cursor = classDef.getSuperclass();
        Set<String> seen = new LinkedHashSet<>();
        while (cursor != null && seen.add(cursor)) {
            chain.add(cursor);
            ClassDef parent = classes.get(cursor);
            cursor = parent == null ? null : parent.getSuperclass();
        }
        return chain;
    }

    /** All methods with the given name declared on the class. */
    static List<Method> methodsNamed(ClassDef classDef, String name) {
        List<Method> found = new ArrayList<>();
        for (Method method : classDef.getMethods()) {
            if (method.getName().equals(name)) {
                found.add(method);
            }
        }
        return found;
    }

    /** String constants referenced by any method of the class. */
    static boolean referencesString(ClassDef classDef, String value) {
        for (Method method : classDef.getMethods()) {
            MethodImplementation implementation = method.getImplementation();
            if (implementation == null) {
                continue;
            }
            for (Instruction instruction : implementation.getInstructions()) {
                if (instruction instanceof ReferenceInstruction
                        && ((ReferenceInstruction) instruction).getReference()
                        instanceof StringReference) {
                    if (value.equals(((StringReference) ((ReferenceInstruction) instruction)
                            .getReference()).getString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Fields declared on the class matching a predicate-free type scan. */
    static List<Field> fieldsOfType(ClassDef classDef, String typeDescriptor,
                                    boolean staticOnly) {
        List<Field> found = new ArrayList<>();
        for (Field field : classDef.getFields()) {
            if (staticOnly && !AccessFlags.STATIC.isSet(field.getAccessFlags())) {
                continue;
            }
            if (!staticOnly && AccessFlags.STATIC.isSet(field.getAccessFlags())) {
                continue;
            }
            if (field.getType().equals(typeDescriptor)) {
                found.add(field);
            }
        }
        return found;
    }

    static List<Field> staticIntFields(ClassDef classDef) {
        List<Field> found = new ArrayList<>();
        for (Field field : classDef.getFields()) {
            if (AccessFlags.STATIC.isSet(field.getAccessFlags())
                    && "I".equals(field.getType())) {
                found.add(field);
            }
        }
        return found;
    }

    static Method singleConstructor(ClassDef classDef, List<String> parameterDescriptors) {
        List<Method> matches = new ArrayList<>();
        for (Method method : classDef.getDirectMethods()) {
            if (!"<init>".equals(method.getName())) {
                continue;
            }
            if (parameterDescriptors(method).equals(parameterDescriptors)) {
                matches.add(method);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }
}
