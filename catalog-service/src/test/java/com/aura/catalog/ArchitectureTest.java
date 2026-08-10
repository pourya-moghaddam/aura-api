package com.aura.catalog;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Structural rules, enforced by the build rather than by review.
 *
 * <p>Catalog is the module that absorbed inventory and reviews, so it is the one most likely to
 * blur — and it carries the largest family of request DTOs, which is why the primitive-component
 * rule matters more here than anywhere else.
 */
@AnalyzeClasses(
    packages = "com.aura.catalog",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    @ArchTest
    static final ArchRule catalogDoesNotReachIntoOtherDomains = noClasses()
        .that().resideInAPackage("com.aura.catalog..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.aura.auth..",
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
     *
     * <p>This cost an afternoon: category and colour creates worked from a client that happened to
     * send {@code sortOrder} and failed from one that did not.
     */
    @ArchTest
    static final ArchRule requestDtosDoNotUsePrimitiveComponents = noFields()
        .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Request")
        .and().areDeclaredInClassesThat().resideInAPackage("..dto..")
        .and().areNotStatic()
        .should().haveRawType(DescribedPredicate.describe("a primitive type", JavaClass::isPrimitive))
        .because("a primitive record component silently makes its JSON property required, "
            + "and failing to supply it yields an unreadable 400 instead of a field-level error");
}
