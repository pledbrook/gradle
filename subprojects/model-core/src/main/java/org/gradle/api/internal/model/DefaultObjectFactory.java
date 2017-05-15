/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.model;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.gradle.api.Named;
import org.gradle.api.model.ObjectFactory;
import org.gradle.internal.UncheckedException;
import org.gradle.model.internal.asm.AsmClassGenerator;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public class DefaultObjectFactory implements ObjectFactory {
    public static final DefaultObjectFactory INSTANCE = new DefaultObjectFactory();

    private static final Type OBJECT = Type.getType(Object.class);
    private static final Type STRING = Type.getType(String.class);
    private static final String RETURN_STRING = Type.getMethodDescriptor(STRING);
    private static final String RETURN_VOID_FROM_STRING = Type.getMethodDescriptor(Type.VOID_TYPE, STRING);
    private static final String NAME_FIELD = "__name__";
    private static final String[] EMPTY_STRINGS = new String[0];
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String RETURN_VOID = Type.getMethodDescriptor(Type.VOID_TYPE);

    private DefaultObjectFactory() {
    }

    // Currently retains strong references
    private final LoadingCache<Class<?>, LoadingCache<String, Object>> values = CacheBuilder.newBuilder().build(new CacheLoader<Class<?>, LoadingCache<String, Object>>() {
        @Override
        public LoadingCache<String, Object> load(Class<?> type) {
            return CacheBuilder.newBuilder().build(loaderFor(type));
        }
    });

    @Override
    public <T extends Named> T named(final Class<T> type, final String name) {
        try {
            return type.cast(values.getUnchecked(type).getUnchecked(name));
        } catch (UncheckedExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        }
    }

    private ClassGeneratingLoader loaderFor(Class<?> publicClass) {
        //
        // Generate implementation class
        //

        AsmClassGenerator generator = new AsmClassGenerator(publicClass, "$Impl");
        Type implementationType = generator.getGeneratedType();
        ClassWriter visitor = generator.getVisitor();
        Type publicType = Type.getType(publicClass);

        visitor.visit(V1_5, ACC_PUBLIC | ACC_SYNTHETIC, implementationType.getInternalName(), null, OBJECT.getInternalName(), new String[]{publicType.getInternalName()});

        //
        // Add name field
        //

        visitor.visitField(ACC_PRIVATE, NAME_FIELD, STRING.getDescriptor(), null, null);

        //
        // Add constructor
        //

        MethodVisitor methodVisitor = visitor.visitMethod(ACC_PUBLIC, CONSTRUCTOR_NAME, RETURN_VOID_FROM_STRING, null, EMPTY_STRINGS);
        methodVisitor.visitCode();
        // Call this.super()
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, OBJECT.getInternalName(), CONSTRUCTOR_NAME, RETURN_VOID, false);
        // Set this.name = param1
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, implementationType.getInternalName(), NAME_FIELD, STRING.getDescriptor());
        // Done
        methodVisitor.visitInsn(Opcodes.RETURN);
        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();

        //
        // Add `getName()`
        //

        methodVisitor = visitor.visitMethod(ACC_PUBLIC, "getName", RETURN_STRING, null, EMPTY_STRINGS);
        methodVisitor.visitCode();
        // return this.name
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, implementationType.getInternalName(), NAME_FIELD, STRING.getDescriptor());
        methodVisitor.visitInsn(Opcodes.ARETURN);
        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();

        //
        // Add `toString()`
        //

        methodVisitor = visitor.visitMethod(ACC_PUBLIC, "toString", RETURN_STRING, null, EMPTY_STRINGS);
        methodVisitor.visitCode();
        // return this.name
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, implementationType.getInternalName(), NAME_FIELD, STRING.getDescriptor());
        methodVisitor.visitInsn(Opcodes.ARETURN);
        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();

        visitor.visitEnd();
        generator.define();

        //
        // Generate factory class
        //

        generator = new AsmClassGenerator(publicClass, "$Factory");
        visitor = generator.getVisitor();
        visitor.visit(V1_5, ACC_PUBLIC | ACC_SYNTHETIC, generator.getGeneratedType().getInternalName(), null, Type.getType(ClassGeneratingLoader.class).getInternalName(), EMPTY_STRINGS);

        //
        // Add constructor
        //

        methodVisitor = visitor.visitMethod(ACC_PUBLIC, CONSTRUCTOR_NAME, RETURN_VOID, null, EMPTY_STRINGS);
        methodVisitor.visitCode();
        // Call this.super()
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getType(ClassGeneratingLoader.class).getInternalName(), CONSTRUCTOR_NAME, RETURN_VOID, false);
        // Done
        methodVisitor.visitInsn(Opcodes.RETURN);
        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();

        //
        // Add factory method
        //

        methodVisitor = visitor.visitMethod(ACC_PUBLIC, "load", Type.getMethodDescriptor(OBJECT, STRING), null, EMPTY_STRINGS);
        methodVisitor.visitCode();
        // Call return new <implClass>(param1)
        methodVisitor.visitTypeInsn(Opcodes.NEW, implementationType.getInternalName());
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, implementationType.getInternalName(), CONSTRUCTOR_NAME, RETURN_VOID_FROM_STRING, false);
        methodVisitor.visitInsn(Opcodes.ARETURN);
        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();

        visitor.visitEnd();
        Class<Object> factoryClass = generator.define();
        try {
            return (ClassGeneratingLoader) (factoryClass.newInstance());
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    protected abstract static class ClassGeneratingLoader extends CacheLoader<String, Object> {
        @Override
        public abstract Object load(String name);
    }
}
