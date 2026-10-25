/*
 * BSD 4-Clause License
 *
 * Copyright (c) 2026, Leo Galambos and the Egothor team
 * Website of the Egothor team: https://www.egothor.org
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. All advertising materials mentioning features or use of this
 * software must display the following acknowledgement:
 *
 * This product includes software developed by Leo Galambos and the Egothor
 * team (https://www.egothor.org).
 *
 * 4. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.j7m.middleware.application.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.annotations.Backpressure;
import org.j7m.middleware.annotations.ConfigValue;
import org.j7m.middleware.annotations.CurrentPrincipal;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.LocalOnly;
import org.j7m.middleware.annotations.Named;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.annotations.OneWay;
import org.j7m.middleware.annotations.PreflightUnknown;
import org.j7m.middleware.annotations.RateLimited;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.RemoteService;
import org.j7m.middleware.annotations.RequiresTrustDomain;
import org.j7m.middleware.annotations.Retry;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationSecurityContext;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.TrustDomain;
import org.junit.jupiter.api.Test;

class AnnotationPreflightTest {
    @SafeVarargs
    private static AnnotationPreflight validator(Class<? extends Annotation>... capabilities) {
        return new AnnotationPreflight(new AnnotationPolicyRegistry(Map.of(), List.of(capabilities)));
    }
    private static AnnotationPreflight.Report inspect(AnnotationPreflight preflight, Class<?> implementation, Class<?>... contracts) {
        return preflight.inspect(AnnotationPreflight.Profile.MANAGED_V1, "contract", 1, implementation, List.of(contracts));
    }
    private static boolean code(AnnotationPreflight.Report report, String code) {
        return report.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals(code));
    }
    static class Plain { }
    @RateLimited("unknown") static class UnknownPolicy { }
    @RateLimited static class DefaultPolicy { }
    @PreflightUnknown static class UnknownAnnotation { }
    @RemoteService @Retention(RetentionPolicy.RUNTIME) @interface Composed { }
    @Composed static class ComposedUse { }
    @RemoteService @Retention(RetentionPolicy.RUNTIME) @interface Meta9 { }
    @Meta9 @Retention(RetentionPolicy.RUNTIME) @interface Meta8 { }
    @Meta8 @Retention(RetentionPolicy.RUNTIME) @interface Meta7 { }
    @Meta7 @Retention(RetentionPolicy.RUNTIME) @interface Meta6 { }
    @Meta6 @Retention(RetentionPolicy.RUNTIME) @interface Meta5 { }
    @Meta5 @Retention(RetentionPolicy.RUNTIME) @interface Meta4 { }
    @Meta4 @Retention(RetentionPolicy.RUNTIME) @interface Meta3 { }
    @Meta3 @Retention(RetentionPolicy.RUNTIME) @interface Meta2 { }
    @Meta2 @Retention(RetentionPolicy.RUNTIME) @interface Meta1 { }
    @Meta1 @Retention(RetentionPolicy.RUNTIME) @interface Meta0 { }
    @Meta0 static class DeepMetadata { }
    @Cycle @Retention(RetentionPolicy.RUNTIME) @interface Cycle { }
    @Cycle static class CyclicMetadata { }

    interface Parent { @RemoteMethod @Deadline(millis = 10) String calculate(String input); }
    interface Child extends Parent { @RemoteMethod int calculate(int input); }
    static class Good implements Child {
        public String calculate(String input) { return input; }
        public int calculate(int input) { return input; }
        public String[] arrays(int[] input) { return new String[0]; }
    }
    static class Widened implements Parent {
        @Deadline(millis = 100) public String calculate(String input) { return input; }
    }
    @Backpressure(maxConcurrent = 1) interface First { void first(); }
    @Backpressure(maxConcurrent = 2) interface Second { void second(); }
    static class Independent implements First, Second {
        public void first() { }
        public void second() { }
    }
    @Backpressure(maxConcurrent = 1) static class WidenedType {
        @Backpressure(maxConcurrent = 2) public void call() { }
    }
    interface Remote { @RemoteMethod @Idempotent void call(); }
    static class Conflicting implements Remote {
        @LocalOnly @NonIdempotent public void call() { }
    }
    @LocalOnly static class LocalType { @RemoteMethod public void call() { } }
    @J7mService(name = "local") @NoRemoteExport static class ExportConflict { }
    static class InvalidPolicyBounds {
        @Deadline(millis = 0) public void deadline() { }
        @Retry(maxAttempts = -1) public void retry() { }
        @Backpressure(maxConcurrent = 0) public void backpressure() { }
        @OneWay public int oneway() { return 1; }
    }
    static class ValidPolicyBounds {
        @Deadline(millis = 1) @Retry(maxAttempts = 0) @Backpressure(maxConcurrent = -1) @OneWay public void call() { }
    }
    @RequiresTrustDomain("first") interface TrustFirst { void call(); }
    @RequiresTrustDomain("second") static class TrustConflict implements TrustFirst { public void call() { } }
    static class QualifierConflict { @Inject @Named("one") @J7mResource("two") Object dependency; }
    static class SameQualifier { @Inject @Named("one") @J7mResource("one") Object dependency; }
    static class BadInjection {
        @Inject static Object shared;
        @Inject BadInjection() { }
        @Inject BadInjection(Object dependency) { }
    }
    @RateLimited("J7M_SENTINEL_SECRET_TOKEN") static class SecretArguments {
        @ConfigValue(value = "password", defaultValue = "SyntheticSensitive") String value;
    }
    static class Fallback { @Inject @Named("absent") String value; }
    static class ContextCapture { @CurrentPrincipal Principal principal; }

    @Test void emptyExplicitSurfaceIsValidAndManagedOptInDoesNotExecuteApplicationCode() {
        var report = inspect(validator(), Plain.class);
        assertTrue(report.valid()); report.requireValid();
        assertEquals(AnnotationPreflight.Profile.MANAGED_V1, report.profile());
        assertTrue(report.methods().isEmpty()); assertTrue(report.diagnostics().isEmpty());
    }

    @Test void unsupportedMarkerAndUnknownFutureAndComposedAnnotationsFailClosed() {
        assertTrue(code(inspect(validator(), UnknownPolicy.class), "UNSUPPORTED_BEHAVIOR"));
        assertTrue(code(inspect(validator(), UnknownAnnotation.class), "UNKNOWN_ANNOTATION"));
        assertTrue(code(inspect(validator(), ComposedUse.class), "UNSUPPORTED_COMPOSED_ANNOTATION"));
        assertThrows(MiddlewareException.class, () -> inspect(validator(), UnknownAnnotation.class).requireValid());
    }

    @Test void metadataDepthAndCyclesFailClosedInsteadOfHidingUnsupportedComposition() {
        var deep = inspect(validator(), DeepMetadata.class);
        assertFalse(deep.valid()); assertTrue(code(deep, "UNSUPPORTED_METADATA_GRAPH"));
        var cyclic = inspect(validator(), CyclicMetadata.class);
        assertFalse(cyclic.valid()); assertTrue(code(cyclic, "UNSUPPORTED_METADATA_GRAPH"));
        var legacy = validator().inspect(AnnotationPreflight.Profile.LEGACY, "legacy", 1, DeepMetadata.class, List.of());
        assertTrue(legacy.valid()); assertTrue(code(legacy, "UNSUPPORTED_METADATA_GRAPH"));
    }

    @Test void policyMustExistEvenWhenHostExplicitlyDeclaresItsAnnotationCapability() {
        assertTrue(code(inspect(validator(RateLimited.class), UnknownPolicy.class), "UNKNOWN_POLICY"));
        var registry = new AnnotationPolicyRegistry(Map.of(AnnotationPolicyRegistry.Kind.RATE_LIMIT, List.of("default")), List.of(RateLimited.class));
        assertTrue(inspect(new AnnotationPreflight(registry), DefaultPolicy.class).valid());
        assertTrue(code(inspect(validator(RateLimited.class), DefaultPolicy.class), "UNKNOWN_POLICY"));
    }

    @Test void fullIdentitiesDistinguishOverloadsInheritanceOwnersArraysAndContractVersions() {
        var preflight = validator(RemoteMethod.class, Deadline.class);
        var first = inspect(preflight, Good.class, Child.class);
        assertTrue(first.valid()); assertEquals(5, first.methods().size());
        assertTrue(first.methods().stream().anyMatch(method -> method.declaringType().equals(Parent.class.getName())
                && method.parameterTypes().equals(List.of("java.lang.String")) && method.returnType().equals("java.lang.String")));
        assertTrue(first.methods().stream().anyMatch(method -> method.parameterTypes().equals(List.of("int"))));
        assertTrue(first.methods().stream().anyMatch(method -> method.parameterTypes().equals(List.of("[I"))
                && method.returnType().equals("[Ljava.lang.String;")));
        var second = preflight.inspect(AnnotationPreflight.Profile.MANAGED_V1, "contract", 2, Good.class, List.of(Child.class));
        assertFalse(first.methods().equals(second.methods()));
        assertEquals(first, inspect(preflight, Good.class, Child.class));
        assertThrows(UnsupportedOperationException.class, () -> first.methods().clear());
    }

    @Test void conflictingMethodDeclarationsAndWideningNeverSilentlyOverrideExistingConstraints() {
        assertTrue(code(inspect(validator(Deadline.class, RemoteMethod.class), Widened.class, Parent.class), "CONFLICTING_POLICY_DECLARATIONS"));
        assertTrue(code(inspect(validator(Backpressure.class), WidenedType.class), "CONFLICTING_POLICY_DECLARATIONS"));
        assertTrue(code(inspect(validator(RemoteMethod.class, LocalOnly.class, Idempotent.class, NonIdempotent.class), Conflicting.class, Remote.class), "CONFLICTING_METHOD_CONTRACT"));
        assertTrue(code(inspect(validator(LocalOnly.class, RemoteMethod.class), LocalType.class), "CONFLICTING_METHOD_CONTRACT"));
        assertTrue(code(inspect(validator(J7mService.class, NoRemoteExport.class), ExportConflict.class), "CONFLICTING_EXPORT_CONTRACT"));
        assertTrue(code(inspect(validator(RequiresTrustDomain.class), TrustConflict.class, TrustFirst.class), "CONFLICTING_TRUST_DOMAINS"));
    }

    @Test void unrelatedInterfaceDefaultsDoNotContaminateAnotherMethod() {
        assertTrue(inspect(validator(Backpressure.class), Independent.class, First.class, Second.class).valid());
    }

    @Test void annotationScalarBoundsAndOneWayReturnContractHaveRealBoundaryChecks() {
        var bad = inspect(validator(Deadline.class, Retry.class, Backpressure.class, OneWay.class), InvalidPolicyBounds.class);
        assertEquals(3, bad.diagnostics().stream().filter(diagnostic -> diagnostic.code().equals("INVALID_POLICY_BOUND")).count());
        assertTrue(code(bad, "ONEWAY_REQUIRES_VOID"));
        assertTrue(inspect(validator(Deadline.class, Retry.class, Backpressure.class, OneWay.class), ValidPolicyBounds.class).valid());
    }

    @Test void qualifierAndConstructorConflictsAreRejectedWithoutChangingTheInjector() {
        var preflight = validator(Inject.class, Named.class, J7mResource.class);
        assertTrue(code(inspect(preflight, QualifierConflict.class), "CONFLICTING_QUALIFIERS"));
        assertTrue(inspect(preflight, SameQualifier.class).valid());
        var bad = inspect(preflight, BadInjection.class);
        assertTrue(code(bad, "MULTIPLE_INJECTION_CONSTRUCTORS")); assertTrue(code(bad, "INVALID_INJECTION_FIELD"));
    }

    @Test void arbitraryConfigurationAndUnknownPolicyArgumentsNeverEnterDiagnosticsOrExceptions() {
        var report = inspect(validator(RateLimited.class, ConfigValue.class), SecretArguments.class);
        assertFalse(report.toString().contains("J7M_SENTINEL_SECRET_TOKEN"));
        assertFalse(report.toString().contains("SyntheticSensitive"));
        assertTrue(report.diagnostics().stream().allMatch(diagnostic -> diagnostic.policyId().isEmpty()));
        assertFalse(assertThrows(MiddlewareException.class, report::requireValid).getMessage().contains("SyntheticSensitive"));
    }

    @Test void legacyNamedFallbackStillWorksAndMigrationWarnsEvenWhenCapabilityIsDeclared() {
        var preflight = validator(Inject.class, Named.class);
        var report = preflight.inspect(AnnotationPreflight.Profile.LEGACY, "legacy", 1, Fallback.class, List.of());
        assertTrue(report.valid()); report.requireValid();
        assertTrue(code(report, "LEGACY_QUALIFIER_FALLBACK_POSSIBLE"));
        var injector = DefaultJ7mInjector.builder().bind(String.class, "unnamed").build();
        assertEquals("unnamed", injector.instantiate(Fallback.class).value);
        assertEquals("qualified", DefaultJ7mInjector.builder().bind(String.class, "absent", "qualified")
                .bind(String.class, "unnamed").build().instantiate(Fallback.class).value);
    }

    @Test void legacyContextSnapshotIsDiagnosedAndRemainsAnInjectionTimeSnapshot() {
        var report = validator(CurrentPrincipal.class).inspect(AnnotationPreflight.Profile.LEGACY, "legacy", 1, ContextCapture.class, List.of());
        assertTrue(report.valid()); assertTrue(code(report, "LEGACY_CONTEXT_CAPTURE"));
        var injector = DefaultJ7mInjector.builder().build();
        Principal alice = new Principal("alice", "Alice", java.util.Set.of(), java.util.Set.of());
        Principal bob = new Principal("bob", "Bob", java.util.Set.of(), java.util.Set.of());
        ContextCapture first;
        try (var scope = J7mContext.open(new InvocationContext(new InvocationSecurityContext(alice, alice.roles(), new TrustDomain("test")), null, "one"))) {
            first = injector.instantiate(ContextCapture.class);
        }
        try (var scope = J7mContext.open(new InvocationContext(new InvocationSecurityContext(bob, bob.roles(), new TrustDomain("test")), null, "two"))) {
            assertSame(alice, first.principal); assertSame(bob, injector.instantiate(ContextCapture.class).principal);
        }
    }

    @Test void malformedContractIdentityAndNonimplementedRootsFailBeforeValidation() {
        var preflight = validator();
        assertThrows(IllegalArgumentException.class, () -> preflight.inspect(null, "id", 1, Plain.class, List.of()));
        assertThrows(IllegalArgumentException.class, () -> preflight.inspect(AnnotationPreflight.Profile.MANAGED_V1, "id", 1, Parent.class, List.of()));
        assertThrows(IllegalArgumentException.class, () -> preflight.inspect(AnnotationPreflight.Profile.MANAGED_V1, "bad id", 1, Plain.class, List.of()));
        assertThrows(IllegalArgumentException.class, () -> preflight.inspect(AnnotationPreflight.Profile.MANAGED_V1, "id", 0, Plain.class, List.of()));
        assertThrows(IllegalArgumentException.class, () -> preflight.inspect(AnnotationPreflight.Profile.MANAGED_V1, "id", 1, Plain.class, List.of(Parent.class)));
    }
}
