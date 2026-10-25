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

package org.j7m.middleware.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import org.j7m.middleware.annotations.SchemaType;
import org.j7m.middleware.annotations.WireField;

/**
 * JSR269 processor generating finite, directly callable trusted adapters.
 * Compilation errors use stable J7M codes and never include annotation payload values.
 * Processor instances are compiler-owned and are not thread safe.
 */
public final class J7mProcessor extends AbstractProcessor {
    /**
     * Complete project license retained in each generated Java compilation unit.
     */
    static final String GENERATED_LICENSE = "/*\n * BSD 4-Clause License\n *\n * Copyright (c) 2026, Leo Galambos and the Egothor team\n * Website of the Egothor team: https://www.egothor.org\n * All rights reserved.\n *\n * Redistribution and use in source and binary forms, with or without\n * modification, are permitted provided that the following conditions are\n * met:\n *\n * 1. Redistributions of source code must retain the above copyright\n * notice, this list of conditions and the following disclaimer.\n *\n * 2. Redistributions in binary form must reproduce the above copyright\n * notice, this list of conditions and the following disclaimer in the\n * documentation and/or other materials provided with the distribution.\n *\n * 3. All advertising materials mentioning features or use of this\n * software must display the following acknowledgement:\n *\n * This product includes software developed by Leo Galambos and the Egothor\n * team (https://www.egothor.org).\n *\n * 4. Neither the name of the copyright holder nor the names of its\n * contributors may be used to endorse or promote products derived from\n * this software without specific prior written permission.\n *\n * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS\n * IS\" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED\n * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A\n * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT\n * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,\n * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED\n * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR\n * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF\n * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING\n * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS\n * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.\n */\n\n";

    private final Map<String, List<TypeElement>> schemasById = new LinkedHashMap<>();
    private final Set<String> schemaProviders = new LinkedHashSet<>();
    private final Set<String> componentProviders = new LinkedHashSet<>();
    private final Set<String> generated = new HashSet<>();

    /**
     * Creates an uninitialized compiler-owned processor.
     */
    public J7mProcessor() { }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("org.j7m.middleware.annotations.*");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment round) {
        Set<Element> components = new LinkedHashSet<>();
        components.addAll(round.getElementsAnnotatedWith(org.j7m.middleware.annotations.J7mComponent.class));
        components.addAll(round.getElementsAnnotatedWith(org.j7m.middleware.annotations.J7mApplication.class));
        components.addAll(round.getElementsAnnotatedWith(org.j7m.middleware.annotations.PartitionHandler.class));
        components.addAll(round.getElementsAnnotatedWith(org.j7m.middleware.annotations.SagaDefinition.class));
        for (var callback : round.getElementsAnnotatedWith(org.j7m.middleware.annotations.EventSubscription.class)) {
            components.add(callback.getEnclosingElement());
        }
        for (var callbackType : java.util.List.of(org.j7m.middleware.annotations.DurableQueueConsumer.class,
                org.j7m.middleware.annotations.DeadLetterHandler.class, org.j7m.middleware.annotations.SagaStep.class,
                org.j7m.middleware.annotations.Compensation.class, org.j7m.middleware.annotations.ReconciliationHandler.class, org.j7m.middleware.annotations.DurableJob.class,
                org.j7m.middleware.annotations.Schedule.class, org.j7m.middleware.annotations.Checkpoint.class,
                org.j7m.middleware.annotations.Restore.class, org.j7m.middleware.annotations.Progress.class)) {
            for (var callback : round.getElementsAnnotatedWith(callbackType)) components.add(callback.getEnclosingElement());
        }
        components.addAll(round.getElementsAnnotatedWith(org.j7m.middleware.annotations.J7mService.class));
        for (Element element : components) {
            if (element instanceof TypeElement type && generated.add("component:" + type.getQualifiedName())) {
                try { componentProviders.add(new ComponentGenerator(processingEnv).generate(type)); }
                catch (IllegalArgumentException exception) { error(type, exception.getMessage()); }
                catch (IOException exception) { error(type, "J7M_GENERATION_IO"); }
            }
        }
        for (Element element : round.getElementsAnnotatedWith(SchemaType.class)) {
            if (element instanceof TypeElement type && generated.add(type.getQualifiedName().toString())) {
                try { generateSchema(type); }
                catch (IllegalArgumentException exception) { error(type, exception.getMessage()); }
                catch (IOException exception) { error(type, "J7M_GENERATION_IO"); }
            }
        }
        for (Element element : round.getElementsAnnotatedWith(WireField.class)) {
            if (element.getEnclosingElement().getAnnotation(SchemaType.class) == null) {
                error(element, "J7M_ORPHAN_WIRE_FIELD");
            }
        }
        if (round.processingOver() && !componentProviders.isEmpty()) {
            try (Writer writer = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/services/org.j7m.middleware.application.api.ManagedComponentIndex").openWriter()) {
                for (String provider : componentProviders) { writer.write(provider + "\n"); }
            } catch (IOException exception) { error(null, "J7M_GENERATION_IO"); }
        }
        if (round.processingOver() && !schemaProviders.isEmpty()) {
            try (Writer writer = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/services/org.j7m.middleware.codec.api.SchemaIndex").openWriter()) {
                for (String provider : schemaProviders) { writer.write(provider + "\n"); }
            } catch (IOException exception) { error(null, "J7M_GENERATION_IO"); }
        }
        return true;
    }

    private void generateSchema(final TypeElement type) throws IOException {
        SchemaType schema = type.getAnnotation(SchemaType.class);
        require(type.getKind() == ElementKind.RECORD && type.getModifiers().contains(Modifier.PUBLIC)
                && type.getTypeParameters().isEmpty() && !type.getNestingKind().isNested(),
                "J7M_SCHEMA_REQUIRES_PUBLIC_TOP_LEVEL_RECORD");
        require(schema.id().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}") && schema.version() > 0,
                "J7M_INVALID_SCHEMA_ID_OR_VERSION");
        require(type.getRecordComponents().size() <= 128, "J7M_SCHEMA_FIELD_LIMIT");
        List<String> fields = new ArrayList<>();
        List<String> encoders = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (RecordComponentElement component : type.getRecordComponents()) {
            WireField field = component.getAnnotation(WireField.class);
            require(field != null, "J7M_MISSING_WIRE_FIELD");
            require(field.id() > 0 && ids.add(field.id()), "J7M_DUPLICATE_OR_INVALID_FIELD_ID");
            require(!component.asType().getKind().isPrimitive() || field.required(), "J7M_OPTIONAL_PRIMITIVE");
            require(!field.required() || !field.hasDefault(), "J7M_REQUIRED_DEFAULT");
            require(field.hasDefault() || field.defaultValue().isEmpty(), "J7M_UNDECLARED_DEFAULT");
            String boxed = boxed(component.asType());
            require(supported(component.asType()), "J7M_UNSUPPORTED_SCHEMA_FIELD_TYPE");
            String defaultValue = field.hasDefault() ? defaultExpression(boxed, field.defaultValue()) : "null";
            String key = quote(Integer.toString(field.id()));
            fields.add("new org.j7m.middleware.codec.api.SchemaField(" + key + "," + boxed + ".class,"
                    + field.required() + "," + defaultValue + ")");
            encoders.add("fields.put(" + key + ",value." + component.getSimpleName() + "());");
            arguments.add("(" + boxed + ")fields.get(" + key + ")");
        }
        checkEvolution(type);
        String pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String simple = type.getSimpleName() + "__J7mSchema";
        String qualified = pkg.isEmpty() ? simple : pkg + "." + simple;
        String source = GENERATED_LICENSE + (pkg.isEmpty() ? "" : "package " + pkg + ";\n")
                + "/**\n * Provides the immutable schema allowlist for the admitted application record.\n * DTO instances are constructed only when decoding a validated value.\n */\n"
                + "public final class " + simple + " implements org.j7m.middleware.codec.api.SchemaIndex {\n"
                + "/**\n * Creates the schema provider without decoding or constructing application values.\n */ public " + simple + "(){}\n"
                + "/**\n * Returns the fixed schema descriptors and their direct application codecs.\n * @return immutable schema descriptor list\n */ public java.util.List<org.j7m.middleware.codec.api.SchemaDescriptor<?>> schemas(){\n"
                + "return java.util.List.of(new org.j7m.middleware.codec.api.SchemaDescriptor<>("
                + quote(schema.id()) + "," + schema.version() + "," + type.getQualifiedName() + ".class,java.util.List.of("
                + String.join(",", fields) + "),value->{java.util.Map<String,Object> fields=new java.util.LinkedHashMap<>();"
                + String.join("", encoders) + "return java.util.Collections.unmodifiableMap(fields);},fields->new "
                + type.getQualifiedName() + "(" + String.join(",", arguments) + ")));}\n}\n";
        try (Writer writer = processingEnv.getFiler().createSourceFile(qualified, type).openWriter()) {
            writer.write(source);
        }
        schemaProviders.add(qualified);
    }

    private void checkEvolution(final TypeElement type) {
        SchemaType schema = type.getAnnotation(SchemaType.class);
        List<TypeElement> previous = schemasById.computeIfAbsent(schema.id(), ignored -> new ArrayList<>());
        for (TypeElement old : previous) {
            int oldVersion = old.getAnnotation(SchemaType.class).version();
            require(oldVersion != schema.version(), "J7M_DUPLICATE_SCHEMA_VERSION");
            require(Math.abs((long) oldVersion - schema.version()) == 1, "J7M_NON_ADJACENT_SCHEMA_VERSIONS");
            checkFields(type, old);
            checkFields(old, type);
        }
        previous.add(type);
    }

    private void checkFields(final TypeElement first, final TypeElement second) {
        for (RecordComponentElement component : first.getRecordComponents()) {
            WireField wire = component.getAnnotation(WireField.class);
            RecordComponentElement other = second.getRecordComponents().stream()
                    .filter(candidate -> candidate.getAnnotation(WireField.class) != null
                            && candidate.getAnnotation(WireField.class).id() == wire.id()).findFirst().orElse(null);
            if (other == null) { require(!wire.required(), "J7M_INCOMPATIBLE_SCHEMA_EVOLUTION"); }
            else {
                require(boxed(component.asType()).equals(boxed(other.asType()))
                        && wire.required() == other.getAnnotation(WireField.class).required(), "J7M_INCOMPATIBLE_SCHEMA_EVOLUTION");
            }
        }
    }

    private boolean supported(final TypeMirror type) {
        String boxed = boxed(type);
        if (Set.of("java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
                "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character",
                "byte[]").contains(boxed)) { return true; }
        Element element = processingEnv.getTypeUtils().asElement(type);
        return element instanceof TypeElement declared && declared.getAnnotation(SchemaType.class) != null
                && declared.getTypeParameters().isEmpty();
    }

    private String boxed(final TypeMirror type) {
        return type.getKind().isPrimitive()
                ? processingEnv.getTypeUtils().boxedClass((javax.lang.model.type.PrimitiveType) type).getQualifiedName().toString()
                : type.toString();
    }

    private static String defaultExpression(final String type, final String value) {
        try {
            return switch (type) {
                case "java.lang.String" -> quote(value);
                case "java.lang.Boolean" -> {
                    require("true".equals(value) || "false".equals(value), "J7M_INVALID_DEFAULT");
                    yield value;
                }
                case "java.lang.Character" -> {
                    require(value.length() == 1, "J7M_INVALID_DEFAULT");
                    yield "Character.valueOf((char)" + (int) value.charAt(0) + ")";
                }
                case "java.lang.Byte" -> "Byte.valueOf(" + quote(Byte.toString(Byte.parseByte(value))) + ")";
                case "java.lang.Short" -> "Short.valueOf(" + quote(Short.toString(Short.parseShort(value))) + ")";
                case "java.lang.Integer" -> "Integer.valueOf(" + quote(Integer.toString(Integer.parseInt(value))) + ")";
                case "java.lang.Long" -> "Long.valueOf(" + quote(Long.toString(Long.parseLong(value))) + ")";
                case "java.lang.Float" -> {
                    require(Float.isFinite(Float.parseFloat(value)), "J7M_INVALID_DEFAULT");
                    yield "Float.valueOf(" + quote(value) + ")";
                }
                case "java.lang.Double" -> {
                    require(Double.isFinite(Double.parseDouble(value)), "J7M_INVALID_DEFAULT");
                    yield "Double.valueOf(" + quote(value) + ")";
                }
                default -> throw new IllegalArgumentException("J7M_UNSUPPORTED_DEFAULT");
            };
        } catch (NumberFormatException exception) { throw new IllegalArgumentException("J7M_INVALID_DEFAULT", exception); }
    }

    static String quote(final String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            if (character == '\\' || character == '"') { result.append('\\').append(character); }
            else if (character < 32 || character == 127) { result.append(String.format("\\%03o", (int) character)); }
            else if (character > 126) { result.append(String.format("\\u%04x", (int) character)); }
            else { result.append(character); }
        }
        return result.append('"').toString();
    }

    private static void require(final boolean condition, final String code) {
        if (!condition) { throw new IllegalArgumentException(code); }
    }

    private void error(final Element element, final String code) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, code, element);
    }
}
