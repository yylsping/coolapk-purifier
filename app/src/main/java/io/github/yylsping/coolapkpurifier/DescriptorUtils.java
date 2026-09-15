package io.github.yylsping.coolapkpurifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class DescriptorUtils {
    private DescriptorUtils() {
    }

    /** Dex class descriptor ("Lcom/example/Foo;") for a dotted class name or Class. */
    static String classDescriptorOf(String dottedName) {
        return "L" + dottedName.replace('.', '/') + ";";
    }

    static String classDescriptorOf(Class<?> type) {
        return classDescriptorOf(type.getName());
    }

    /** Dex type descriptor for a dotted class name or a primitive type name. */
    static String typeDescriptorOf(String dottedOrPrimitive) {
        switch (dottedOrPrimitive) {
            case "void":
                return "V";
            case "boolean":
                return "Z";
            case "byte":
                return "B";
            case "short":
                return "S";
            case "char":
                return "C";
            case "int":
                return "I";
            case "long":
                return "J";
            case "float":
                return "F";
            case "double":
                return "D";
            default:
                return classDescriptorOf(dottedOrPrimitive);
        }
    }

    static Class<?> classForName(String nameOrDescriptor, ClassLoader loader)
            throws ClassNotFoundException {
        if (nameOrDescriptor == null || nameOrDescriptor.isEmpty()) {
            throw new ClassNotFoundException("empty descriptor");
        }
        String name = nameOrDescriptor;
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        return Class.forName(name.replace('/', '.'), false, loader);
    }

    static Method methodForDescriptor(String descriptor, ClassLoader loader) {
        if (descriptor == null || descriptor.isEmpty()) {
            return null;
        }
        try {
            int arrow = descriptor.indexOf("->");
            if (arrow <= 0) {
                return null;
            }
            String classPart = descriptor.substring(0, arrow);
            String rest = descriptor.substring(arrow + 2);
            int open = rest.indexOf('(');
            if (open <= 0) {
                return null;
            }
            String methodName = rest.substring(0, open);
            int close = rest.indexOf(')', open);
            if (close < 0) {
                return null;
            }
            String params = rest.substring(open + 1, close);
            String returnType = rest.substring(close + 1);

            Class<?> owner = classForName(classPart, loader);
            List<Class<?>> parameterTypes = new ArrayList<>();
            int index = 0;
            while (index < params.length()) {
                int end = skipType(params, index);
                if (end <= index) {
                    return null;
                }
                parameterTypes.add(parseType(params.substring(index, end), loader));
                index = end;
            }
            Class<?>[] parameterArray = parameterTypes.toArray(new Class<?>[0]);
            try {
                Method method = owner.getDeclaredMethod(methodName, parameterArray);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                Method method = owner.getMethod(methodName, parameterArray);
                method.setAccessible(true);
                return method;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int skipType(String descriptor, int start) {
        char first = descriptor.charAt(start);
        if (first == 'L') {
            int end = descriptor.indexOf(';', start);
            return end < 0 ? descriptor.length() : end + 1;
        }
        if (first == '[') {
            int cursor = start;
            while (cursor < descriptor.length() && descriptor.charAt(cursor) == '[') {
                cursor++;
            }
            return skipType(descriptor, cursor);
        }
        return start + 1;
    }

    private static Class<?> parseType(String typeDescriptor, ClassLoader loader)
            throws ClassNotFoundException {
        switch (typeDescriptor) {
            case "V":
                return void.class;
            case "Z":
                return boolean.class;
            case "B":
                return byte.class;
            case "S":
                return short.class;
            case "C":
                return char.class;
            case "I":
                return int.class;
            case "J":
                return long.class;
            case "F":
                return float.class;
            case "D":
                return double.class;
            default:
                if (typeDescriptor.startsWith("[")) {
                    String element = typeDescriptor.substring(1);
                    if (element.startsWith("L") && element.endsWith(";")) {
                        return Class.forName(typeDescriptor.replace('/', '.'), false, loader);
                    }
                    return Class.forName(typeDescriptor.replace('/', '.'), false, loader);
                }
                return classForName(typeDescriptor, loader);
        }
    }
}
