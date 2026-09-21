package com.aura.auth;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

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

    /**
     * A primitive component on a request record makes its JSON property mandatory in a way nobody
     * declared and nothing documents. Jackson cannot construct the record when the property is
     * absent — there is no value to pass — so the whole request fails with an opaque 400 "Failed to
     * read request" that names no field, and bean validation never gets to run. Box the component
     * and default it in a compact constructor, or box it and mark it {@code @NotNull} if it really
     * is required; either way the client gets an error that says which field and why.
     */
    @ArchTest
    static final ArchRule requestDtosDoNotUsePrimitiveComponents = noFields()
        .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Request")
        .and().areDeclaredInClassesThat().resideInAPackage("..dto..")
        .and().areNotStatic()
        .should().haveRawType(DescribedPredicate.describe("a primitive type", JavaClass::isPrimitive))
        .because("a primitive record component silently makes its JSON property required, "
            + "and failing to supply it yields an unreadable 400 instead of a field-level error");

    @ArchTest
    static final ArchRule layersAreRespected = layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("Controller").definedBy("..user..")
        .layer("Security").definedBy("..security..")
        .whereLayer("Security").mayOnlyBeAccessedByLayers("Controller", "Security")
        .as("token issuance is reached through the service layer, not from arbitrary code");
}
