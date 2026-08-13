package com.aura.order;

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
 * <p>In place before the code is, because order-service absorbed cart, payment and discount and is
 * therefore the module most likely to blur. The consolidation in the plan's §1.1 is only a good
 * trade while the internal boundaries hold, and the first "just this once" cross-domain import is
 * the one nobody notices.
 */
@AnalyzeClasses(
    packages = "com.aura.order",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    @ArchTest
    static final ArchRule orderDoesNotReachIntoOtherDomains = noClasses()
        .that().resideInAPackage("com.aura.order..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.aura.auth..",
            "com.aura.catalog..",
            "com.aura.search..",
            "com.aura.media..",
            "com.aura.notification.."
        )
        .because("services share data through events and HTTP, not by importing each other's types");

    @ArchTest
    static final ArchRule controllersDoNotTouchRepositories = noClasses()
        .that().haveSimpleNameEndingWith("Controller")
        .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
        .because("skipping the service layer puts transaction boundaries in the wrong place")
        // There are no controllers yet. ArchUnit treats a rule matching nothing as a failure -
        // reasonably, since a rule that silently stops applying is worse than no rule - but these
        // are deliberately in place before the code they police, so the module cannot drift while
        // it is being written.
        .allowEmptyShould(true);

    /**
     * A primitive component on a request record makes its JSON property mandatory in a way nobody
     * declared: Jackson cannot construct the record when it is absent, so the whole request fails
     * with an opaque 400 naming no field, and bean validation never runs. Box it and default it in
     * a compact constructor, or box it and mark it {@code @NotNull} if it really is required.
     */
    @ArchTest
    static final ArchRule requestDtosDoNotUsePrimitiveComponents = noFields()
        .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Request")
        .and().areDeclaredInClassesThat().resideInAPackage("..dto..")
        .and().areNotStatic()
        .should().haveRawType(DescribedPredicate.describe("a primitive type", JavaClass::isPrimitive))
        .because("a primitive record component silently makes its JSON property required, "
            + "and omitting it yields an unreadable 400 instead of a field-level error")
        .allowEmptyShould(true);

    /**
     * Money is BIGINT Rial everywhere — an integer minor unit, chosen so there is never a rounding
     * question to get wrong. A {@code double} in a price or total is how half a Rial goes missing
     * per line and the books stop balancing; {@code BigDecimal} is not wrong but invites the
     * fractional thinking the choice was made to avoid.
     */
    @ArchTest
    static final ArchRule moneyIsNeverFloatingPoint = noFields()
        .that().areDeclaredInClassesThat().resideInAPackage("com.aura.order..")
        .and().haveNameMatching(".*([Pp]rice|[Tt]otal|[Aa]mount|[Ff]ee|[Ss]ubtotal).*")
        .should().haveRawType(DescribedPredicate.describe("a floating-point or decimal type",
            type -> type.isAssignableTo(Double.class) || type.isAssignableTo(Float.class)
                || type.getName().equals("double") || type.getName().equals("float")
                || type.isAssignableTo(java.math.BigDecimal.class)))
        .because("money is BIGINT Rial throughout - an integer minor unit with no rounding questions")
        .allowEmptyShould(true);
}
