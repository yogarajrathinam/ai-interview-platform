package com.aiinterview.interviewplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module boundaries as executable rules.
 *
 * <p>These exist from the first commit precisely so they never have to be
 * retrofitted against existing violations. They are deliberately few and
 * realistic: each one prevents a specific coupling that would make splitting a
 * module into a service expensive later.
 *
 * <p>Several rules currently match nothing — there are no controllers or
 * repositories yet. That is intentional. They are tripwires for the
 * milestones that add them, not decoration.
 */
@AnalyzeClasses(
        packages = ArchitectureTest.ROOT,
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String ROOT = "com.aiinterview.interviewplatform";

    /** Cross-cutting mechanics every module may use. */
    private static final String SHARED = "shared";

    // ------------------------------------------------- module independence

    /**
     * The rule that matters most: a module may reach into another module only
     * through its published {@code api} package. Everything else —
     * {@code application}, {@code domain}, {@code infrastructure} — is private
     * to the owning module.
     *
     * <p>This is what keeps a future extraction to a separate service a
     * mechanical change rather than an archaeology project, and it is why the
     * attempt tables reference catalogue rows by raw {@code UUID} instead of a
     * JPA association.
     */
    @ArchTest
    static final ArchRule modules_talk_only_through_published_api =
            classes()
                    .should(ModuleRules.notReachIntoAnotherModulesInternals())
                    .because("cross-module access goes through <module>.api only")
                    .allowEmptyShould(true);

    // ------------------------------------------------------------ layering

    @ArchTest
    static final ArchRule domain_does_not_depend_on_infrastructure =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                    .because("the domain must not know how it is persisted or transported")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domain_does_not_depend_on_api =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..api..")
                    .because("dependencies point inward, never out to the transport layer")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule api_does_not_reach_into_infrastructure =
            noClasses()
                    .that().resideInAPackage("..api..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                    .because("controllers orchestrate through application services, "
                            + "not through adapters")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_repositories =
            noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                    .because("a controller that queries directly has no place to put "
                            + "authorisation or transaction boundaries")
                    .allowEmptyShould(true);

    /**
     * Entities are persistence types. Exposing one over HTTP leaks the schema
     * into the public contract and drags lazy loading into serialization.
     */
    @ArchTest
    static final ArchRule entities_are_not_exposed_by_controllers =
            noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Entity")
                    .because("controllers must return DTOs, never JPA entities")
                    .allowEmptyShould(true);

    // -------------------------------------------------------- domain purity

    /**
     * The domain may use JPA mapping annotations — at this milestone entities
     * are the domain — but it must not depend on the Spring container.
     */
    @ArchTest
    static final ArchRule domain_does_not_depend_on_spring =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .because("domain types must be constructible and testable without a "
                            + "Spring context")
                    .allowEmptyShould(true);

    // ------------------------------------------------- injected primitives

    /**
     * Deadlines, the follow-up phase and the maintenance sweeper are all
     * time-driven and are only testable if time is injected.
     */
    @ArchTest
    static final ArchRule no_direct_clock_access =
            noClasses()
                    .that().resideOutsideOfPackage(ROOT + ".shared.time..")
                    .should().callMethod(java.time.Instant.class, "now")
                    .orShould().callMethod(java.time.LocalDateTime.class, "now")
                    .orShould().callMethod(java.time.OffsetDateTime.class, "now")
                    .orShould().callMethod(java.time.LocalDate.class, "now")
                    .because("inject java.time.Clock instead; see shared.time.ClockConfig")
                    .allowEmptyShould(true);

    /** UUID generation lives in exactly one place so the strategy stays swappable. */
    @ArchTest
    static final ArchRule no_scattered_uuid_generation =
            noClasses()
                    .that().resideOutsideOfPackage(ROOT + ".shared.id..")
                    .should().callMethod(java.util.UUID.class, "randomUUID")
                    .because("depend on shared.id.IdGenerator, which issues UUIDv7")
                    .allowEmptyShould(true);

    // --------------------------------------------------------- transactions

    @ArchTest
    static final ArchRule transactions_are_declared_in_application_services =
            noClasses()
                    .that().resideInAnyPackage("..api..", "..domain..")
                    .should().beAnnotatedWith(
                            org.springframework.transaction.annotation.Transactional.class)
                    .because("the transaction boundary is the use case, which lives in "
                            + "the application layer")
                    .allowEmptyShould(true);

    // --------------------------------------------------------------- hygiene

    @ArchTest
    static final ArchRule no_field_injection =
            noClasses()
                    .should().beAnnotatedWith(
                            org.springframework.beans.factory.annotation.Autowired.class)
                    .because("constructor injection keeps dependencies visible and "
                            + "objects constructible in a plain unit test")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule entities_live_in_domain_packages =
            classes()
                    .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should().resideInAPackage("..domain..")
                    .because("persistence types belong to a module's domain, not its edges")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule shared_does_not_depend_on_business_modules =
            noClasses()
                    .that().resideInAPackage(ROOT + "." + SHARED + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            ROOT + ".identity..", ROOT + ".candidate..", ROOT + ".interview..",
                            ROOT + ".question..", ROOT + ".evaluation..", ROOT + ".reporting..",
                            ROOT + ".ai..", ROOT + ".admin..")
                    .because("shared holds mechanics, never business rules; a dependency "
                            + "in this direction means something belongs elsewhere")
                    .allowEmptyShould(true);
}
