package io.github.yylsping.coolapkpurifier;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Shared strict verification for exact static-assembler targets (D5/D6). */
final class ExactMethodVerifier {
    private ExactMethodVerifier() {
    }

    static boolean isExactTarget(StaticAssemblerTargetSpec spec, Method method) {
        if (spec == null || method == null) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        List<String> parameterNames = new ArrayList<>(parameters.length);
        for (Class<?> parameter : parameters) {
            parameterNames.add(parameter.getName());
        }
        return spec.ownerClass.equals(method.getDeclaringClass().getName())
                && spec.methodName.equals(method.getName())
                && spec.parameterTypes.equals(parameterNames)
                && spec.returnType.equals(method.getReturnType().getName())
                && Modifier.isStatic(method.getModifiers())
                && !Modifier.isAbstract(method.getModifiers());
    }
}
