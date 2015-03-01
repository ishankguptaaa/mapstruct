/**
 *  Copyright 2012-2015 Gunnar Morling (http://www.gunnarmorling.de/)
 *  and/or other contributors as indicated by the @authors tag. See the
 *  copyright.txt file in the distribution for a full listing of all
 *  contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.mapstruct.ap.model;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

import org.mapstruct.ap.model.assignment.AdderWrapper;
import org.mapstruct.ap.model.assignment.ArrayCopyWrapper;
import org.mapstruct.ap.model.assignment.Assignment;
import org.mapstruct.ap.model.assignment.GetterWrapperForCollectionsAndMaps;
import org.mapstruct.ap.model.assignment.NewCollectionOrMapWrapper;
import org.mapstruct.ap.model.assignment.NullCheckWrapper;
import org.mapstruct.ap.model.assignment.SetterWrapper;
import org.mapstruct.ap.model.assignment.SetterWrapperForCollectionsAndMaps;
import org.mapstruct.ap.model.common.ModelElement;
import org.mapstruct.ap.model.common.Parameter;
import org.mapstruct.ap.model.common.Type;
import org.mapstruct.ap.model.source.ForgedMethod;
import org.mapstruct.ap.model.source.SourceMethod;
import org.mapstruct.ap.model.source.SourceReference;
import org.mapstruct.ap.model.source.SourceReference.PropertyEntry;
import org.mapstruct.ap.util.Executables;
import org.mapstruct.ap.util.Message;
import org.mapstruct.ap.util.Strings;

import static org.mapstruct.ap.model.assignment.Assignment.AssignmentType.DIRECT;
import static org.mapstruct.ap.model.assignment.Assignment.AssignmentType.TYPE_CONVERTED;
import static org.mapstruct.ap.model.assignment.Assignment.AssignmentType.TYPE_CONVERTED_MAPPED;

/**
 * Represents the mapping between a source and target property, e.g. from {@code String Source#foo} to
 * {@code int Target#bar}. Name and type of source and target property can differ. If they have different types, the
 * mapping must either refer to a mapping method or a conversion.
 *
 * @author Gunnar Morling
 */
public class PropertyMapping extends ModelElement {

    private final String name;
    private final String sourceBeanName;
    private final String targetAccessorName;
    private final Type targetType;
    private final Assignment assignment;
    private final List<String> dependsOn;

    public static class PropertyMappingBuilder {

        // initial properties
        private MappingBuilderContext ctx;
        private SourceMethod method;
        private ExecutableElement targetAccessor;
        private String targetPropertyName;
        private String dateFormat;
        private List<TypeMirror> qualifiers;
        private TypeMirror resultType;
        private SourceReference sourceReference;
        private Collection<String> existingVariableNames;
        private List<String> dependsOn;

        public PropertyMappingBuilder mappingContext(MappingBuilderContext mappingContext) {
            this.ctx = mappingContext;
            return this;
        }

        public PropertyMappingBuilder souceMethod(SourceMethod sourceMethod) {
            this.method = sourceMethod;
            return this;
        }

        public PropertyMappingBuilder targetAccessor(ExecutableElement targetAccessor) {
            this.targetAccessor = targetAccessor;
            return this;
        }

        public PropertyMappingBuilder targetPropertyName(String targetPropertyName) {
            this.targetPropertyName = targetPropertyName;
            return this;
        }

        public PropertyMappingBuilder sourceReference(SourceReference sourceReference) {
            this.sourceReference = sourceReference;
            return this;
        }

        public PropertyMappingBuilder qualifiers(List<TypeMirror> qualifiers) {
            this.qualifiers = qualifiers;
            return this;
        }

        public PropertyMappingBuilder resultType(TypeMirror resultType) {
            this.resultType = resultType;
            return this;
        }

        public PropertyMappingBuilder dateFormat(String dateFormat) {
            this.dateFormat = dateFormat;
            return this;
        }

        public PropertyMappingBuilder existingVariableNames(Collection<String> existingVariableNames) {
            this.existingVariableNames = existingVariableNames;
            return this;
        }

        public PropertyMappingBuilder dependsOn(List<String> dependsOn) {
            this.dependsOn = dependsOn;
            return this;
        }

        private enum TargetAccessorType {

            GETTER,
            SETTER,
            ADDER
        }

        public PropertyMapping build() {

            // handle target
            TargetAccessorType targetAccessorType = getTargetAcccessorType();
            Type targetType = getTargetType( targetAccessorType );

            // handle source
            String sourceElement = getSourceElement();
            Type sourceType = getSourceType();
            String sourceRefStr;
            if ( targetAccessorType == TargetAccessorType.ADDER && sourceType.isCollectionType() ) {
                // handle adder, if source is collection then use iterator element type as source type.
                // sourceRef becomes a local variable in the itereation.
                sourceType = sourceType.getTypeParameters().get( 0 );
                sourceRefStr = Executables.getElementNameForAdder( targetAccessor );
            }
            else {
                sourceRefStr = getSourceRef();
            }

            Assignment assignment = ctx.getMappingResolver().getTargetAssignment(
                method,
                sourceElement,
                sourceType,
                targetType,
                targetPropertyName,
                dateFormat,
                qualifiers,
                resultType,
                sourceRefStr
            );

            // No mapping found. Try to forge a mapping
            if ( assignment == null ) {
                assignment = forgeMapOrIterableMapping( sourceType, targetType, sourceRefStr, method.getExecutable() );
            }

            if ( assignment != null ) {
                if ( targetType.isCollectionOrMapType() ) {
                    assignment = assignCollection( targetType, targetAccessorType, assignment );
                }
                else if ( targetType.isArrayType() && sourceType.isArrayType() && assignment.getType() == DIRECT ) {
                    Type arrayType = ctx.getTypeFactory().getType( Arrays.class );
                    assignment = new ArrayCopyWrapper(
                        assignment,
                        targetPropertyName,
                        arrayType,
                        targetType,
                        existingVariableNames
                    );
                    assignment = new NullCheckWrapper( assignment );
                }
                else {
                    assignment = assignObject( sourceType, targetType, targetAccessorType, assignment );
                }
            }
            else {
                ctx.getMessager().printMessage( method.getExecutable(),
                    Message.PROPERTYMAPPING_MAPPING_NOT_FOUND,
                    sourceElement,
                    targetType,
                    targetPropertyName,
                    targetType,
                    getSourceType() /* original source type */
                );
            }

            return new PropertyMapping(
                targetPropertyName,
                sourceReference.getParameter().getName(),
                targetAccessor.getSimpleName().toString(),
                targetType,
                assignment,
                dependsOn
            );
        }

        private Assignment assignObject(Type sourceType, Type targetType, TargetAccessorType targetAccessorType,
                                        Assignment rhs) {

            Assignment result = rhs;

            if ( targetAccessorType == TargetAccessorType.SETTER ) {
                result = new SetterWrapper( result, method.getThrownTypes() );
                if ( !sourceType.isPrimitive()
                    && !sourceReference.getPropertyEntries().isEmpty() /* parameter null taken care of by beanmapper */
                    && ( result.getType() == TYPE_CONVERTED
                    || result.getType() == TYPE_CONVERTED_MAPPED
                    || result.getType() == DIRECT && targetType.isPrimitive() ) ) {
                    // for primitive types null check is not possible at all, but a conversion needs
                    // a null check.
                    result = new NullCheckWrapper( result );
                }
            }
            else {
                // TargetAccessorType must be ADDER
                if ( getSourceType().isCollectionType() ) {
                    result = new AdderWrapper(
                        result,
                        method.getThrownTypes(),
                        getSourceRef(),
                        sourceType
                    );
                }
                else {
                    // Possibly adding null to a target collection. So should be surrounded by an null check.
                    result = new SetterWrapper( result, method.getThrownTypes() );
                    result = new NullCheckWrapper( result );
                }
            }
            return result;

        }

        private Assignment assignCollection(Type targetType, TargetAccessorType targetAccessorType, Assignment rhs) {

            Assignment result = rhs;

            // wrap the setter in the collection / map initializers
            if ( targetAccessorType == TargetAccessorType.SETTER ) {

                // wrap the assignment in a new Map or Collection implementation if this is not done in a
                // mapping method. Note, typeconversons do not apply to collections or maps
                Assignment newCollectionOrMap = null;
                if ( result.getType() == DIRECT ) {
                    newCollectionOrMap = new NewCollectionOrMapWrapper( result, targetType.getImportTypes() );
                    newCollectionOrMap = new SetterWrapper( newCollectionOrMap, method.getThrownTypes() );
                }

                // wrap the assignment in the setter method
                result = new SetterWrapper( result, method.getThrownTypes() );

                // target accessor is setter, so wrap the setter in setter map/ collection handling
                result = new SetterWrapperForCollectionsAndMaps(
                    result,
                    targetAccessor.getSimpleName().toString(),
                    newCollectionOrMap
                );
            }
            else {
                // target accessor is getter, so wrap the setter in getter map/ collection handling
                result = new GetterWrapperForCollectionsAndMaps(
                    result,
                    method.getThrownTypes(),
                    targetType,
                    existingVariableNames
                );
            }

            // For collections and maps include a null check, when the assignment type is DIRECT.
            // for mapping methods (builtin / custom), the mapping method is responsible for the
            // null check. Typeconversions do not apply to collections and maps.
            if ( result.getType() == DIRECT ) {
                result = new NullCheckWrapper( result );
            }

            return result;
        }

        private Type getSourceType() {

            Parameter sourceParam = sourceReference.getParameter();
            List<PropertyEntry> propertyEntries = sourceReference.getPropertyEntries();
            if ( propertyEntries.isEmpty() ) {
                return sourceParam.getType();
            }
            else if ( propertyEntries.size() == 1 ) {
                PropertyEntry propertyEntry = propertyEntries.get( 0 );
                return propertyEntry.getType();
            }
            else {
                PropertyEntry lastPropertyEntry = propertyEntries.get( propertyEntries.size() - 1 );
                return lastPropertyEntry.getType();
            }
        }

        private String getSourceRef() {
            Parameter sourceParam = sourceReference.getParameter();
            List<PropertyEntry> propertyEntries = sourceReference.getPropertyEntries();

            // parameter reference
            if ( propertyEntries.isEmpty() ) {
                return sourceParam.getName();
            }
            // simple property
            else if ( propertyEntries.size() == 1 ) {
                PropertyEntry propertyEntry = propertyEntries.get( 0 );
                return sourceParam.getName() + "." + propertyEntry.getAccessor().getSimpleName() + "()";
            }
            // nested property given as dot path
            else {
                PropertyEntry lastPropertyEntry = propertyEntries.get( propertyEntries.size() - 1 );

                // forge a method from the parameter type to the last entry type.
                String forgedName = Strings.joinAndCamelize( sourceReference.getElementNames() );
                forgedName = Strings.getSaveVariableName( forgedName, ctx.getNamesOfMappingsToGenerate() );
                ForgedMethod methodRef = new ForgedMethod(
                    forgedName,
                    sourceReference.getParameter().getType(),
                    lastPropertyEntry.getType(),
                    method.getExecutable()
                );
                NestedPropertyMappingMethod.Builder builder = new NestedPropertyMappingMethod.Builder();
                NestedPropertyMappingMethod nestedPropertyMapping = builder
                    .method( methodRef )
                    .propertyEntries( sourceReference.getPropertyEntries() )
                    .build();

                // add if not yet existing
                if ( !ctx.getMappingsToGenerate().contains( nestedPropertyMapping ) ) {
                    ctx.getMappingsToGenerate().add( nestedPropertyMapping );
                }
                else {
                    forgedName = ctx.getExistingMappingMethod( nestedPropertyMapping ).getName();
                }

                return forgedName + "( " + sourceParam.getName() + " )";
            }
        }

        private String getSourceElement() {

            Parameter sourceParam = sourceReference.getParameter();
            List<PropertyEntry> propertyEntries = sourceReference.getPropertyEntries();
            if ( propertyEntries.isEmpty() ) {
                return String.format( "parameter \"%s %s\"", sourceParam.getType(), sourceParam.getName() );
            }
            else if ( propertyEntries.size() == 1 ) {
                PropertyEntry propertyEntry = propertyEntries.get( 0 );
                return String.format( "property \"%s %s\"", propertyEntry.getType(), propertyEntry.getName() );
            }
            else {
                PropertyEntry lastPropertyEntry = propertyEntries.get( propertyEntries.size() - 1 );
                return String.format(
                    "property \"%s %s\"",
                    lastPropertyEntry.getType(),
                    Strings.join( sourceReference.getElementNames(), "." )
                );
            }
        }

        private TargetAccessorType getTargetAcccessorType() {
            if ( Executables.isSetterMethod( targetAccessor ) ) {
                return TargetAccessorType.SETTER;
            }
            else if ( Executables.isAdderMethod( targetAccessor ) ) {
                return TargetAccessorType.ADDER;
            }
            else {
                return TargetAccessorType.GETTER;
            }
        }

        private Type getTargetType(TargetAccessorType targetAccessorType) {
            switch ( targetAccessorType ) {
                case ADDER:
                case SETTER:
                    return ctx.getTypeFactory().getSingleParameter( targetAccessor ).getType();
                case GETTER:
                default:
                    return ctx.getTypeFactory().getReturnType( targetAccessor );
            }
        }

        private Assignment forgeMapOrIterableMapping(Type sourceType, Type targetType, String sourceReference,
                                                     ExecutableElement element) {

            Assignment assignment = null;

            String name = getName( sourceType, targetType );
            name = Strings.getSaveVariableName( name, ctx.getNamesOfMappingsToGenerate() );

            if ( (sourceType.isCollectionType() || sourceType.isArrayType())
                && (targetType.isCollectionType() || targetType.isArrayType()) ) {

                ForgedMethod methodRef = new ForgedMethod( name, sourceType, targetType, element );
                IterableMappingMethod.Builder builder = new IterableMappingMethod.Builder();

                IterableMappingMethod iterableMappingMethod = builder
                    .mappingContext( ctx )
                    .method( methodRef )
                    .build();

                if ( !ctx.getMappingsToGenerate().contains( iterableMappingMethod ) ) {
                    ctx.getMappingsToGenerate().add( iterableMappingMethod );
                }
                else {
                    String existingName = ctx.getExistingMappingMethod( iterableMappingMethod ).getName();
                    methodRef = new ForgedMethod( existingName, methodRef );
                }

                assignment = AssignmentFactory.createMethodReference( methodRef, null, targetType );
                assignment.setAssignment( AssignmentFactory.createDirect( sourceReference ) );

            }
            else if ( sourceType.isMapType() && targetType.isMapType() ) {

                ForgedMethod methodRef = new ForgedMethod( name, sourceType, targetType, element );

                MapMappingMethod.Builder builder = new MapMappingMethod.Builder();
                MapMappingMethod mapMappingMethod = builder
                    .mappingContext( ctx )
                    .method( methodRef )
                    .build();

                if ( !ctx.getMappingsToGenerate().contains( mapMappingMethod ) ) {
                    ctx.getMappingsToGenerate().add( mapMappingMethod );
                }
                else {
                    String existingName = ctx.getExistingMappingMethod( mapMappingMethod ).getName();
                    methodRef = new ForgedMethod( existingName, methodRef );
                }
                assignment = AssignmentFactory.createMethodReference( methodRef, null, targetType );
                assignment.setAssignment( AssignmentFactory.createDirect( sourceReference ) );
            }
            return assignment;
        }

        private String getName(Type sourceType, Type targetType) {
            String fromName = getName( sourceType );
            String toName = getName( targetType );
            return Strings.decapitalize( fromName + "To" + toName );
        }

        private String getName(Type type) {
            StringBuilder builder = new StringBuilder();
            for ( Type typeParam : type.getTypeParameters() ) {
                builder.append( typeParam.getName().replace( "[]", "Array" ) );
            }
            builder.append( type.getName().replace( "[]", "Array" ) );
            return builder.toString();
        }
    }

    public static class ConstantMappingBuilder {

        private MappingBuilderContext ctx;
        private SourceMethod method;
        private String constantExpression;
        private ExecutableElement targetAccessor;
        private String targetPropertyName;
        private String dateFormat;
        private List<TypeMirror> qualifiers;
        private TypeMirror resultType;
        private Collection<String> existingVariableNames;
        private List<String> dependsOn;

        public ConstantMappingBuilder mappingContext(MappingBuilderContext mappingContext) {
            this.ctx = mappingContext;
            return this;
        }

        public ConstantMappingBuilder sourceMethod(SourceMethod sourceMethod) {
            this.method = sourceMethod;
            return this;
        }

        public ConstantMappingBuilder constantExpression(String constantExpression) {
            this.constantExpression = constantExpression;
            return this;
        }

        public ConstantMappingBuilder targetAccessor(ExecutableElement targetAccessor) {
            this.targetAccessor = targetAccessor;
            return this;
        }

        public ConstantMappingBuilder targetPropertyName(String targetPropertyName) {
            this.targetPropertyName = targetPropertyName;
            return this;
        }

        public ConstantMappingBuilder dateFormat(String dateFormat) {
            this.dateFormat = dateFormat;
            return this;
        }

        public ConstantMappingBuilder qualifiers(List<TypeMirror> qualifiers) {
            this.qualifiers = qualifiers;
            return this;
        }

        public ConstantMappingBuilder resultType(TypeMirror resultType) {
            this.resultType = resultType;
            return this;
        }

        public ConstantMappingBuilder existingVariableNames(Collection<String> existingVariableNames) {
            this.existingVariableNames = existingVariableNames;
            return this;
        }

        public ConstantMappingBuilder dependsOn(List<String> dependsOn) {
            this.dependsOn = dependsOn;
            return this;
        }

        public PropertyMapping build() {

            // source
            String mappedElement = "constant '" + constantExpression + "'";
            Type sourceType = ctx.getTypeFactory().getType( String.class );

            // target
            Type targetType;
            if ( Executables.isSetterMethod( targetAccessor ) ) {
                targetType = ctx.getTypeFactory().getSingleParameter( targetAccessor ).getType();
            }
            else {
                targetType = ctx.getTypeFactory().getReturnType( targetAccessor );
            }

            Assignment assignment = ctx.getMappingResolver().getTargetAssignment(
                method,
                mappedElement,
                sourceType,
                targetType,
                targetPropertyName,
                dateFormat,
                qualifiers,
                resultType,
                constantExpression
            );

            if ( assignment != null ) {

                if ( Executables.isSetterMethod( targetAccessor ) ) {
                    // target accessor is setter, so decorate assignment as setter
                    assignment = new SetterWrapper( assignment, method.getThrownTypes() );
                }
                else {
                    // wrap when dealing with getter only on target
                    assignment = new GetterWrapperForCollectionsAndMaps(
                        assignment,
                        method.getThrownTypes(),
                        targetType,
                        existingVariableNames
                    );
                }
            }
            else {
                ctx.getMessager().printMessage( method.getExecutable(),
                    Message.CONSTANTMAPPING_MAPPING_NOT_FOUND,
                    sourceType,
                    constantExpression,
                    targetType,
                    targetPropertyName
                );
            }

            return new PropertyMapping(
                targetPropertyName,
                targetAccessor.getSimpleName().toString(),
                targetType,
                assignment,
                dependsOn
            );
        }
    }

    public static class JavaExpressionMappingBuilder {

        private MappingBuilderContext ctx;
        private SourceMethod method;
        private String javaExpression;
        private ExecutableElement targetAccessor;
        private Collection<String> existingVariableNames;
        private String targetPropertyName;
        private List<String> dependsOn;

        public JavaExpressionMappingBuilder mappingContext(MappingBuilderContext mappingContext) {
            this.ctx = mappingContext;
            return this;
        }

        public JavaExpressionMappingBuilder souceMethod(SourceMethod sourceMethod) {
            this.method = sourceMethod;
            return this;
        }

        public JavaExpressionMappingBuilder javaExpression(String javaExpression) {
            this.javaExpression = javaExpression;
            return this;
        }

        public JavaExpressionMappingBuilder targetAccessor(ExecutableElement targetAccessor) {
            this.targetAccessor = targetAccessor;
            return this;
        }

        public JavaExpressionMappingBuilder existingVariableNames(Collection<String> existingVariableNames) {
            this.existingVariableNames = existingVariableNames;
            return this;
        }

        public JavaExpressionMappingBuilder targetPropertyName(String targetPropertyName) {
            this.targetPropertyName = targetPropertyName;
            return this;
        }

        public JavaExpressionMappingBuilder dependsOn(List<String> dependsOn) {
            this.dependsOn = dependsOn;
            return this;
        }

        public PropertyMapping build() {

            Assignment assignment = AssignmentFactory.createDirect( javaExpression );

            Type targetType;
            if ( Executables.isSetterMethod( targetAccessor ) ) {
                // setter, so wrap in setter
                assignment = new SetterWrapper( assignment, method.getThrownTypes() );
                targetType = ctx.getTypeFactory().getSingleParameter( targetAccessor ).getType();
            }
            else {
                targetType = ctx.getTypeFactory().getReturnType( targetAccessor );
                // target accessor is getter, so wrap the setter in getter map/ collection handling
                assignment = new GetterWrapperForCollectionsAndMaps(
                    assignment,
                    method.getThrownTypes(),
                    targetType,
                    existingVariableNames
                );
            }

            return new PropertyMapping(
                targetPropertyName,
                targetAccessor.getSimpleName().toString(),
                targetType,
                assignment,
                dependsOn
            );
        }

    }

    // Constructor for creating mappings of constant expressions.
    private PropertyMapping(String name, String targetAccessorName, Type targetType, Assignment propertyAssignment,
                            List<String> dependsOn) {
        this( name, null, targetAccessorName, targetType, propertyAssignment, dependsOn );
    }

    private PropertyMapping(String name, String sourceBeanName, String targetAccessorName, Type targetType,
                            Assignment assignment, List<String> dependsOn) {
        this.name = name;
        this.sourceBeanName = sourceBeanName;
        this.targetAccessorName = targetAccessorName;
        this.targetType = targetType;
        this.assignment = assignment;
        this.dependsOn = dependsOn != null ? dependsOn : Collections.<String>emptyList();
    }

    /**
     * Returns the name of this mapping (property name on the target side)
     */
    public String getName() {
        return name;
    }

    public String getSourceBeanName() {
        return sourceBeanName;
    }

    public String getTargetAccessorName() {
        return targetAccessorName;
    }

    public Type getTargetType() {
        return targetType;
    }

    public Assignment getAssignment() {
        return assignment;
    }

    @Override
    public Set<Type> getImportTypes() {
        return assignment.getImportTypes();
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    @Override
    public String toString() {
        return "PropertyMapping {"
            + "\n    name='" + name + "\',"
            + "\n    targetType=" + targetType + ","
            + "\n    propertyAssignment=" + assignment + ","
            + "\n    dependsOn=" + dependsOn
            + "\n}";
    }
}
