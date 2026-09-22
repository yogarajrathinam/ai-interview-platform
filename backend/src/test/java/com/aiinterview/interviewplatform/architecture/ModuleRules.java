package com.aiinterview.interviewplatform.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

/**
 * Expresses "a module is private except for its {@code api} package" as one
 * condition, instead of the N&times;N enumeration of module pairs that would
 * otherwise be needed.
 *
 * <p>A plain ArchUnit predicate cannot express this: the rule depends on
 * <em>both</em> the source and the target of a dependency, so it has to be an
 * {@link ArchCondition}.
 */
final class ModuleRules {

    private static final String ROOT = "com.aiinterview.interviewplatform";

    /** Cross-cutting mechanics; every module may depend on these. */
    private static final String SHARED = "shared";

    private static final Set<String> MODULES = Set.of(
            SHARED, "identity", "candidate", "interview", "question",
            "evaluation", "reporting", "ai", "admin");

    private ModuleRules() {
    }

    static ArchCondition<JavaClass> notReachIntoAnotherModulesInternals() {
        return new ArchCondition<>("not reach into another module's non-api package") {

            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String sourceModule = moduleOf(source.getName());
                if (sourceModule == null) {
                    return;
                }
                for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String targetModule = moduleOf(target.getName());

                    boolean external = targetModule == null;
                    boolean sameModule = sourceModule.equals(targetModule);
                    boolean sharedMechanics = SHARED.equals(targetModule);
                    if (external || sameModule || sharedMechanics) {
                        continue;
                    }
                    boolean publishedSurface =
                            target.getPackageName().contains("." + targetModule + ".api");
                    if (publishedSurface) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(dependency,
                            "%s reaches into %s — cross-module access must go through %s.api"
                                    .formatted(source.getName(), target.getName(), targetModule)));
                }
            }
        };
    }

    /** The first package segment below the root, or {@code null} if not ours. */
    static String moduleOf(String className) {
        String prefix = ROOT + ".";
        if (!className.startsWith(prefix)) {
            return null;
        }
        String rest = className.substring(prefix.length());
        int dot = rest.indexOf('.');
        if (dot < 0) {
            return null;
        }
        String candidate = rest.substring(0, dot);
        return MODULES.contains(candidate) ? candidate : null;
    }
}
