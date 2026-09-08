package io.github.yylsping.coolapkpurifier;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Live record of actually installed feed hooks, keyed by Method and by the
 * Dex descriptor of each hook's declaring class. Coverage decisions (anchor
 * settling) must observe live hooks, not merely resolved descriptors that
 * may have failed to install.
 */
final class HookedFeedRegistry {
    private final Set<Method> methods = new HashSet<>();
    private final Set<String> classDescriptors = new HashSet<>();

    /** Returns false when the method was already recorded. */
    synchronized boolean add(Method method) {
        if (method == null || !methods.add(method)) {
            return false;
        }
        classDescriptors.add(DescriptorUtils.classDescriptorOf(method.getDeclaringClass()));
        return true;
    }

    synchronized boolean contains(Method method) {
        return method != null && methods.contains(method);
    }

    synchronized int size() {
        return methods.size();
    }

    /** True when at least one live hook is declared by the given class descriptor. */
    synchronized boolean hasHookedInClass(String classDescriptor) {
        return classDescriptor != null && classDescriptors.contains(classDescriptor);
    }
}
