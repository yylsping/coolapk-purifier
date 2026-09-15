package io.github.yylsping.manifestgen;

import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-feature offline resolvers. Each feature has an independent resolver;
 * evidence is layered (strong = old owner/name/shape still verifies, weak =
 * structural contract candidates) and the verdicts follow manifest §10:
 * unique candidate + strict contract → CHANGED, multiple → AMBIGUOUS,
 * zero → MISSING.
 */
final class Resolve {
    private Resolve() {
    }

    enum Verdict {
        UNCHANGED,
        CHANGED,
        AMBIGUOUS,
        MISSING
    }

    static final class Candidate {
        final String descriptor;
        final List<String> evidence = new ArrayList<>();
        boolean strictPass;
        Specs.FeatureSpec draftSpec;

        Candidate(String descriptor) {
            this.descriptor = descriptor;
        }
    }

    static final class FeatureResult {
        final String feature;
        final String oldDescriptor;
        Verdict verdict = Verdict.MISSING;
        final List<Candidate> candidates = new ArrayList<>();
        String verification = "not run";

        FeatureResult(String feature, String oldDescriptor) {
            this.feature = feature;
            this.oldDescriptor = oldDescriptor;
        }

        Candidate chosenCandidate() {
            return candidates.isEmpty() ? null : candidates.get(0);
        }
    }

    interface Resolver {
        FeatureResult resolve(Specs.FeatureSpec spec, DexIndex index);
    }

    static Resolver forFeature(String feature) {
        switch (feature) {
            case "detailSponsor":
                return new DetailResolver();
            case "replySponsor":
                return new ReplyResolver();
            case "sameTopic":
                return new TopicResolver();
            case "topicDeviceRecommend":
            case "autoComment":
                return new StaticAssemblerResolver();
            default:
                throw new IllegalArgumentException("no resolver for " + feature);
        }
    }

    /** D1: stable owner/method names on the AutoValue Feed model. */
    private static final class DetailResolver implements Resolver {
        @Override
        public FeatureResult resolve(Specs.FeatureSpec raw, DexIndex index) {
            Specs.DetailSpec spec = (Specs.DetailSpec) raw;
            FeatureResult result = new FeatureResult("detailSponsor", spec.descriptor());
            String ownerDesc = Specs.classDescriptorOf(spec.ownerClass);
            String returnDesc = Specs.typeDescriptorOf(spec.returnType);

            ClassDef owner = index.classByName(spec.ownerClass);
            if (owner != null) {
                for (Method method : DexIndex.methodsNamed(owner, spec.methodName)) {
                    if (method.getParameterTypes().size() == 0
                            && method.getReturnType().equals(returnDesc)
                            && !DexIndex.isAbstract(method)) {
                        result.verdict = Verdict.UNCHANGED;
                        result.verification = "pass strict=owner+name+shape";
                        Candidate candidate = new Candidate(DexIndex.methodDescriptor(method));
                        candidate.strictPass = true;
                        candidate.evidence.add("owner class exact: " + ownerDesc);
                        candidate.evidence.add("method name exact: " + spec.methodName);
                        candidate.evidence.add("no-arg, returns " + returnDesc
                                + ", non-abstract");
                        result.candidates.add(candidate);
                        return result;
                    }
                }
            }

            // Weak: the stable method name anywhere, or no-arg Entity getters
            // on the old owner class.
            Set<String> seen = new LinkedHashSet<>();
            for (ClassDef classDef : index.allClasses()) {
                for (Method method : classDef.getMethods()) {
                    boolean nameMatch = method.getName().equals(spec.methodName)
                            && method.getParameterTypes().size() == 0
                            && method.getReturnType().equals(returnDesc);
                    boolean ownerMatch = owner != null
                            && method.getDefiningClass().equals(ownerDesc)
                            && method.getParameterTypes().size() == 0
                            && method.getReturnType().equals(returnDesc)
                            && !DexIndex.isAbstract(method);
                    if ((nameMatch || ownerMatch)
                            && seen.add(DexIndex.methodDescriptor(method))) {
                        Candidate candidate = new Candidate(DexIndex.methodDescriptor(method));
                        candidate.strictPass = true;
                        if (nameMatch) {
                            candidate.evidence.add("stable method name " + spec.methodName
                                    + " with exact ()->Entity shape");
                        }
                        if (ownerMatch) {
                            candidate.evidence.add("no-arg Entity getter on old owner "
                                    + ownerDesc);
                        }
                        Specs.DetailSpec renamed = new Specs.DetailSpec(
                                Specs.classNameOf(method.getDefiningClass()),
                                method.getName(), spec.returnType);
                        candidate.draftSpec = renamed;
                        result.candidates.add(candidate);
                    }
                }
            }
            finalizeVerdict(result);
            return result;
        }
    }

    /** D5/D6: static assembler with an exact parameter/return signature. */
    private static final class StaticAssemblerResolver implements Resolver {
        @Override
        public FeatureResult resolve(Specs.FeatureSpec raw, DexIndex index) {
            Specs.StaticSpec spec = (Specs.StaticSpec) raw;
            FeatureResult result = new FeatureResult("staticAssembler:" + spec.methodName,
                    spec.descriptor());
            String oldOwner = Specs.classDescriptorOf(spec.ownerClass);
            String returnDesc = Specs.typeDescriptorOf(spec.returnType);

            for (ClassDef classDef : index.allClasses()) {
                for (Method method : classDef.getDirectMethods()) {
                    if (!DexIndex.isStatic(method) || DexIndex.isAbstract(method)) {
                        continue;
                    }
                    if (!method.getReturnType().equals(returnDesc)) {
                        continue;
                    }
                    List<String> parameters = DexIndex.parameterDescriptors(method);
                    if (parameters.size() != spec.parameterTypes.size()) {
                        continue;
                    }
                    boolean shape = true;
                    for (int i = 0; i < parameters.size(); i++) {
                        String wanted = spec.parameterTypes.get(i);
                        String wantedDesc = wanted.equals(spec.ownerClass)
                                // Self-type parameters move with the owner.
                                ? method.getDefiningClass()
                                : Specs.typeDescriptorOf(wanted);
                        if (!parameters.get(i).equals(wantedDesc)) {
                            shape = false;
                            break;
                        }
                    }
                    if (!shape) {
                        continue;
                    }
                    Candidate candidate = new Candidate(DexIndex.methodDescriptor(method));
                    boolean exactNames = method.getDefiningClass().equals(oldOwner)
                            && method.getName().equals(spec.methodName);
                    candidate.strictPass = true;
                    candidate.evidence.add("static, non-abstract, exact "
                            + parameters.size() + "-parameter signature");
                    candidate.evidence.add("return " + returnDesc);
                    if (exactNames) {
                        candidate.evidence.add("owner+name exact (unchanged)");
                    }
                    List<String> draftParameters = new ArrayList<>();
                    for (String wanted : spec.parameterTypes) {
                        draftParameters.add(wanted.equals(spec.ownerClass)
                                ? Specs.classNameOf(method.getDefiningClass()) : wanted);
                    }
                    candidate.draftSpec = new Specs.StaticSpec(
                            Specs.classNameOf(method.getDefiningClass()),
                            method.getName(), draftParameters, spec.returnType);
                    if (exactNames) {
                        result.verdict = Verdict.UNCHANGED;
                        result.verification = "pass strict=owner+name+signature";
                        result.candidates.clear();
                        result.candidates.add(candidate);
                        return result;
                    }
                    result.candidates.add(candidate);
                }
            }
            finalizeVerdict(result);
            return result;
        }
    }

    /** D2: structural contract of the reply-sponsor view holder. */
    private static final class ReplyResolver implements Resolver {
        @Override
        public FeatureResult resolve(Specs.FeatureSpec raw, DexIndex index) {
            Specs.ReplySpec spec = (Specs.ReplySpec) raw;
            FeatureResult result = new FeatureResult("replySponsor", spec.descriptor());
            String entityDesc = Specs.classDescriptorOf(spec.entityClass);
            String helperDesc = Specs.classDescriptorOf(spec.adHelperClass);
            String componentDesc = Specs.classDescriptorOf(spec.bindingComponentClass);
            List<String> ctorShape = new ArrayList<>();
            ctorShape.add("Landroid/view/View;");
            ctorShape.add(helperDesc);
            ctorShape.add(componentDesc);

            for (ClassDef classDef : index.allClasses()) {
                if (!DexIndex.isFinal(classDef)) {
                    continue;
                }
                Method ctor = DexIndex.singleConstructor(classDef, ctorShape);
                if (ctor == null || !DexIndex.isPublic(ctor)) {
                    continue;
                }
                if (DexIndex.fieldsOfType(classDef, entityDesc, false).isEmpty()
                        || DexIndex.fieldsOfType(classDef, helperDesc, false).isEmpty()) {
                    continue;
                }
                ClassDef parent = index.classByDescriptor(classDef.getSuperclass());
                if (parent == null) {
                    continue;
                }
                List<Field> staticInts = DexIndex.staticIntFields(classDef);
                if (staticInts.isEmpty()) {
                    continue;
                }
                // The layout constant is an R.layout id (0x7f......); small
                // constants (e.g. capacity hints) are not layout candidates.
                List<Field> layoutCandidates = new ArrayList<>();
                for (Field field : staticInts) {
                    org.jf.dexlib2.iface.value.EncodedValue value = field.getInitialValue();
                    if (value instanceof org.jf.dexlib2.iface.value.IntEncodedValue
                            && ((org.jf.dexlib2.iface.value.IntEncodedValue) value)
                                    .getValue() >= 0x7F000000) {
                        layoutCandidates.add(field);
                    }
                }
                Field layoutField = layoutCandidates.size() == 1
                        ? layoutCandidates.get(0) : null;
                if (layoutField == null) {
                    for (Field field : staticInts) {
                        if (field.getName().equals(spec.layoutField)) {
                            layoutField = field;
                            break;
                        }
                    }
                }
                for (Method method : classDef.getVirtualMethods()) {
                    if (method.getParameterTypes().size() != 1
                            || !"Ljava/lang/Object;".equals(
                                    method.getParameterTypes().get(0).toString())
                            || !"V".equals(method.getReturnType())
                            || !DexIndex.isPublic(method) || DexIndex.isStatic(method)) {
                        continue;
                    }
                    boolean parentAbstract = false;
                    for (Method parentMethod : DexIndex.methodsNamed(parent, method.getName())) {
                        if (parentMethod.getParameterTypes().size() == 1
                                && "Ljava/lang/Object;".equals(parentMethod
                                        .getParameterTypes().get(0).toString())
                                && "V".equals(parentMethod.getReturnType())
                                && DexIndex.isPublic(parentMethod)
                                && DexIndex.isAbstract(parentMethod)) {
                            parentAbstract = true;
                            break;
                        }
                    }
                    if (!parentAbstract) {
                        continue;
                    }
                    Candidate candidate = new Candidate(DexIndex.methodDescriptor(method));
                    candidate.strictPass = true;
                    candidate.evidence.add("final holder with public (View, "
                            + spec.adHelperClass + ", DataBindingComponent) ctor");
                    candidate.evidence.add("instance Entity + ad-helper fields present");
                    candidate.evidence.add("public void (Object) override of abstract parent");
                    if (DexIndex.referencesString(classDef, spec.entityTemplate)) {
                        candidate.evidence.add("references template string: "
                                + spec.entityTemplate);
                    }
                    boolean exactNames = classDef.getType().equals(
                            Specs.classDescriptorOf(spec.ownerClass))
                            && method.getName().equals(spec.methodName);
                    if (layoutField != null) {
                        String layoutName = layoutField.getName();
                        candidate.draftSpec = spec.renamed(
                                Specs.classNameOf(classDef.getType()),
                                method.getName(), layoutName);
                        if (exactNames && layoutName.equals(spec.layoutField)) {
                            result.verdict = Verdict.UNCHANGED;
                            result.verification = "pass strict=holder contract";
                            result.candidates.clear();
                            result.candidates.add(candidate);
                            return result;
                        }
                    } else {
                        candidate.evidence.add("layout field unresolved among "
                                + staticInts.size() + " static int fields");
                        candidate.strictPass = false;
                    }
                    result.candidates.add(candidate);
                }
            }
            finalizeVerdict(result);
            return result;
        }
    }

    /** D3: same-topic event flow on an EntityListFragment subclass. */
    private static final class TopicResolver implements Resolver {
        @Override
        public FeatureResult resolve(Specs.FeatureSpec raw, DexIndex index) {
            Specs.TopicSpec spec = (Specs.TopicSpec) raw;
            FeatureResult result = new FeatureResult("sameTopic", spec.descriptor());
            String parentDesc = Specs.classDescriptorOf(spec.ownerParentClass);

            for (ClassDef classDef : index.allClasses()) {
                if (!index.superclassChain(classDef).contains(parentDesc)) {
                    continue;
                }
                for (Method handler : DexIndex.methodsNamed(classDef, spec.eventHandler)) {
                    if (handler.getParameterTypes().size() != 1
                            || !"V".equals(handler.getReturnType())
                            || DexIndex.isStatic(handler)) {
                        continue;
                    }
                    String eventDesc = handler.getParameterTypes().get(0).toString();
                    ClassDef eventClass = index.classByDescriptor(eventDesc);
                    if (eventClass == null) {
                        continue;
                    }
                    List<Method> anchorGetters = zeroArgReturning(eventClass,
                            "Ljava/lang/String;");
                    List<Method> cardGetters = zeroArgReturning(eventClass,
                            "Ljava/util/List;");
                    Method anchorGetter = pickGetter(anchorGetters, spec.anchorGetter);
                    Method cardGetter = pickGetter(cardGetters, spec.cardGetter);
                    Method eventCtor = DexIndex.singleConstructor(eventClass, List.of(
                            "Ljava/lang/String;", "Ljava/util/List;"));
                    if (anchorGetter == null || cardGetter == null
                            || eventCtor == null) {
                        continue;
                    }
                    List<Method> semantics = new ArrayList<>();
                    for (Method method : classDef.getDirectMethods()) {
                        if (DexIndex.isStatic(method) && !DexIndex.isAbstract(method)
                                && method.getParameterTypes().size() == 1
                                && "Ljava/lang/Object;".equals(
                                        method.getParameterTypes().get(0).toString())
                                && "Z".equals(method.getReturnType())) {
                            semantics.add(method);
                        }
                    }
                    if (semantics.size() != 1) {
                        continue;
                    }
                    Candidate candidate = new Candidate(
                            DexIndex.methodDescriptor(handler));
                    candidate.strictPass = true;
                    candidate.evidence.add("extends " + spec.ownerParentClass);
                    candidate.evidence.add("handler " + spec.eventHandler + "("
                            + eventDesc + ")V with (String, List) event contract");
                    candidate.evidence.add("unique static (Object)Z semantic method");
                    if (DexIndex.referencesString(classDef, spec.entityTemplate)) {
                        candidate.evidence.add("references template string: "
                                + spec.entityTemplate);
                    }
                    String anchorName = anchorGetter.getName();
                    String cardName = cardGetter.getName();
                    candidate.draftSpec = spec.renamed(
                            Specs.classNameOf(classDef.getType()),
                            semantics.get(0).getName(),
                            Specs.classNameOf(eventDesc),
                            handler.getName(), anchorName, cardName);
                    boolean exactNames = classDef.getType().equals(
                            Specs.classDescriptorOf(spec.ownerClass))
                            && eventDesc.equals(Specs.classDescriptorOf(spec.eventClass))
                            && semantics.get(0).getName().equals(spec.semanticMethod)
                            && anchorName.equals(spec.anchorGetter)
                            && cardName.equals(spec.cardGetter);
                    if (exactNames) {
                        result.verdict = Verdict.UNCHANGED;
                        result.verification = "pass strict=event contract";
                        result.candidates.clear();
                        result.candidates.add(candidate);
                        return result;
                    }
                    result.candidates.add(candidate);
                }
            }
            finalizeVerdict(result);
            return result;
        }

        /**
         * Picks the getter among shape candidates: the old spec name when it
         * still exists (unchanged evidence), otherwise only a unique
         * candidate. Object overrides such as toString are excluded upstream.
         */
        private static Method pickGetter(List<Method> candidates, String oldName) {
            for (Method candidate : candidates) {
                if (candidate.getName().equals(oldName)) {
                    return candidate;
                }
            }
            return candidates.size() == 1 ? candidates.get(0) : null;
        }

        private static List<Method> zeroArgReturning(ClassDef classDef, String returnType) {
            List<Method> found = new ArrayList<>();
            for (Method method : classDef.getMethods()) {
                if ("toString".equals(method.getName())) {
                    continue;
                }
                if (method.getParameterTypes().size() == 0
                        && method.getReturnType().equals(returnType)) {
                    found.add(method);
                }
            }
            return found;
        }
    }

    /** §10: unique + strict → CHANGED; multiple → AMBIGUOUS; zero → MISSING. */
    private static void finalizeVerdict(FeatureResult result) {
        if (result.candidates.isEmpty()) {
            result.verdict = Verdict.MISSING;
            result.verification = "no candidate";
            return;
        }
        long strict = result.candidates.stream().filter(c -> c.strictPass).count();
        if (result.candidates.size() == 1 && strict == 1) {
            result.verdict = Verdict.CHANGED;
            result.verification = "pass strict=contract on unique candidate";
        } else {
            result.verdict = Verdict.AMBIGUOUS;
            result.verification = strict + "/" + result.candidates.size()
                    + " candidates pass strict contract";
        }
    }
}
