package com.aura.auth;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Structural rules, enforced by the build rather than by review.
 *
 * <p>These are what keep the consolidated-service decision honest. Merging inventory into catalog
 * and cart/payment into order is only a good trade while the internal boundaries hold; without a
 * check, the first "just this once" cross-domain import goes unnoticed and the modules quietly
 * fuse into something that can never be split again.
 */
@AnalyzeClasses(
    packages = "com.aura.auth",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    @ArchTest
    static final ArchRule authDoesNotReachIntoOtherDomains = noClasses()
        .that().resideInAPackage("com.aura.auth..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.aura.catalog..",
            "com.aura.order..",
            "com.aura.search..",
            "com.aura.media..",
            "com.aura.notification.."
        )
        .because("services share data through events, not by importing each other's types");

    @ArchTest
    static final ArchRule controllersDoNotTouchRepositories = noClasses()
        .that().haveSimpleNameEndingWith("Controller")
        .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
        .because("skipping the service layer puts transaction boundaries in the wrong place");

    @ArchTest
    static final ArchRule layersAreRespected = layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("Controller").definedBy("..user..")
        .layer("Security").definedBy("..security..")
        .whereLayer("Security").mayOnlyBeAccessedByLayers("Controller", "Security")
        .as("token issuance is reached through the service layer, not from arbitrary code");
}
