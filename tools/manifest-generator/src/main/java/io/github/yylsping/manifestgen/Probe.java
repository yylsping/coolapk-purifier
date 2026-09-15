package io.github.yylsping.manifestgen;

import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;

import java.io.File;

/** Dumps the raw dex-level shape of a class for adaptation debugging. */
final class Probe {
    private Probe() {
    }

    static void run(java.util.Map<String, String> options) throws Exception {
        String input = options.get("--apk");
        String type = options.get("--class");
        if (input == null || type == null) {
            System.out.println("usage: probe --apk <apk|dex|dir> --class <Lcom/example/Foo;>");
            return;
        }
        String descriptor = type.startsWith("L") ? type : Specs.classDescriptorOf(type);
        DexIndex index = DexIndex.load(new File(input));
        ClassDef classDef = index.classByDescriptor(descriptor);
        if (classDef == null) {
            System.out.println("class not found: " + descriptor
                    + " (indexed " + index.classCount() + " classes)");
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append("class ").append(classDef.getType())
                .append(" access=0x").append(Integer.toHexString(classDef.getAccessFlags()))
                .append('\n');
        out.append("superclass: ").append(classDef.getSuperclass()).append('\n');
        out.append("interfaces:");
        for (String iface : classDef.getInterfaces()) {
            out.append(' ').append(iface);
        }
        out.append('\n');
        out.append("fields:\n");
        for (Field field : classDef.getFields()) {
            out.append("  ").append(field.getName()).append(':').append(field.getType())
                    .append(" access=0x").append(Integer.toHexString(field.getAccessFlags()))
                    .append('\n');
        }
        out.append("methods:\n");
        for (Method method : classDef.getMethods()) {
            out.append("  ").append(DexIndex.methodDescriptor(method))
                    .append(" access=0x").append(Integer.toHexString(method.getAccessFlags()))
                    .append('\n');
        }
        System.out.print(out);
    }
}
