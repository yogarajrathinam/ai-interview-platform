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

    /**
     * Refined in M2. The original form forbade {@code domain -> api} outright,
     * which conflicted with a boundary that matters more: a module's published
     * value types must live in its {@code api} package, or consumers of the
     * contract are forced to import {@code <module>.domain} and break
     * {@link #modules_talk_only_through_published_api}.
     *
     * <p>So {@code Verdict} and {@code EvaluationStatus} live in
     * {@code evaluation.api} and the scorer depends on them. What the rule was
     * really protecting — the domain staying free of the transport layer — is
     * now stated directly, and is stricter for it: controllers and web types
     * are named explicitly rather than inferred from a package name.
     */
    @ArchTest
    static final ArchRule domain_does_not_depend_on_the_transport_layer =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                    .orShould().dependOnClassesThat()
                        .resideInAnyPackage("org.springframework.web..",
                                            "jakarta.servlet..")
                    .because("the domain must not know it is reachable over HTTP")
                    .allowEmptyShould(true);

    /**
     * The evaluation engine is handed an answer, a question version and a
     * rubric — it does not know what an interview is. That is what lets the
     * same engine serve the admin rubric dry-run, where no interview, no turn
     * and no stored answer exist.
     */
    @ArchTest
    static final ArchRule evaluation_does_not_depend_on_the_interview_module =
            noClasses()
                    .that().resideInAPackage(ROOT + ".evaluation..")
                    .should().dependOnClassesThat().resideInAPackage(ROOT + ".interview..")
                    .because("grading must stay usable without an interview")
                    .allowEmptyShould(true);

    /**
     * Tripwire for the milestone that adds a real provider. Vendor SDK types
     * must not escape an infrastructure package: the moment one appears in a
     * service or a domain type, swapping providers stops being a config change.
     */
    @ArchTest
    static final ArchRule provider_sdks_stay_in_infrastructure =
            noClasses()
                    .that().resideOutsideOfPackage("..infrastructure..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.openai..", "com.anthropic..", "com.google.genai..",
                            "dev.langchain4j..", "com.theokanning..")
                    .because("the AI vendor must be reachable only behind EvaluationProvider")
                    .allowEmptyShould(true);

    /**
     * Tighter than the rule above, and the one that does the real work now that
     * a vendor SDK is actually on the classpath.
     *
     * <p>"Somewhere in infrastructure" is too generous: it would let a vendor
     * type leak into the job worker or a repository, and replacing the provider
     * would stop being a local change. Confining the SDK to the single adapter
     * package is what keeps {@code EvaluationProvider} a genuine port rather
     * than a decorative interface — swapping vendors means writing one new
     * package and changing one property.
     */
    @ArchTest
    static final ArchRule vendor_sdk_is_confined_to_its_adapter_package =
            noClasses()
                    .that().resideOutsideOfPackage("..evaluation.infrastructure.anthropic..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.anthropic..")
                    .because("the whole point of the port is that only the adapter "
                            + "knows which vendor is in use");

    /**
     * The vendor's wire shape must not escape the adapter.
     *
     * <p>{@code GradingResponse} is package-private for this reason, and this
     * rule makes that a build failure rather than a convention: the moment
     * another package can name it, the provider's output shape becomes part of
     * our internal contract and the next vendor is a refactor rather than a
     * new class.
     */
    @ArchTest
    static final ArchRule evaluation_api_does_not_know_about_any_vendor =
            noClasses()
                    .that().resideInAPackage("..evaluation.api..")
                    .or().resideInAPackage("..evaluation.domain..")
                    .or().resideInAPackage("..evaluation.application..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..evaluation.infrastructure.anthropic..")
                    .because("scoring and validation must be identical whichever "
                            + "provider produced the verdicts, so nothing that "
                            + "scores may name the adapter that produced them");

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
     *
     * <p>Matched by the {@code @Entity} annotation rather than by a name ending
     * in "Entity". The original form was written before any controller existed
     * and, the moment one did, flagged Spring's own {@code ResponseEntity} — a
     * rule that fires on a naming coincidence teaches people to work around it
     * rather than to obey it.
     */
    @ArchTest
    static final ArchRule entities_are_not_exposed_by_controllers =
            noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat()
                        .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .because("controllers must return DTOs, never JPA entities")
                    .allowEmptyShould(true);

    /**
     * Added in M5A, when the first controllers arrived.
     *
     * <p>A controller may reach a module only through its published {@code api}.
     * The moment it can name an application service or a domain type directly,
     * "thin controller" becomes a convention rather than a property — logic
     * migrates upward one convenience at a time, and the HTTP layer quietly
     * becomes the place business rules live.
     */
    @ArchTest
    static final ArchRule controllers_call_only_published_contracts =
            noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat()
                        .resideInAnyPackage("..application..", "..domain..")
                    .because("a controller orchestrates through <module>.api and nothing else")
                    .allowEmptyShould(true);

    /**
     * The score is computed in one place and shown in another.
     *
     * <p>A controller that could reach the scorer could also round it, weight it
     * or renormalise it "just for display", and the number a candidate sees
     * would stop being the number the system stored. The HTTP layer transports
     * a score; it never participates in producing one.
     */
    @ArchTest
    static final ArchRule controllers_do_not_compute_scores =
            noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Scorer")
                    .orShould().dependOnClassesThat().haveSimpleNameEndingWith("ScoringPolicy")
                    .because("scoring belongs to the evaluation domain, not to transport")
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
