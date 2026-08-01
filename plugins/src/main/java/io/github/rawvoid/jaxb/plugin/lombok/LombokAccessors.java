/*
 * Copyright 2026 Rawvoid(https://github.com/rawvoid)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.rawvoid.jaxb.plugin.lombok;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Thin reflective access to Lombok's {@code HandlerUtil.toGetterName}/{@code toSetterName}.
 * <p>
 * Those APIs live in Lombok's SCL shadow jar (same bootstrap as {@link LombokSingulars}).
 * They require an {@code AST} for configuration lookup; this class supplies a one-shot dummy
 * AST so all naming rules stay inside Lombok (defaults only — no project {@code lombok.config}
 * is loaded for the dummy URI).
 * </p>
 * <p>
 * If bootstrap fails, falls back to a minimal {@code get}/{@code is}/{@code set} rule with
 * capitalization matching Lombok {@code CapitalizationStrategy.BASIC}.
 * </p>
 *
 * @author Rawvoid
 */
public final class LombokAccessors {

    private static final Logger log = LoggerFactory.getLogger(LombokAccessors.class);
    private static volatile boolean failureLogged;

    private static final Method TO_GETTER_NAME;
    private static final Method TO_SETTER_NAME;
    private static final Object DUMMY_AST;

    static {
        Method toGetter = null;
        Method toSetter = null;
        Object dummyAst = null;
        try {
            var shadow = LombokShadow.classLoader();
            var handlerUtil = shadow.loadClass("lombok.core.handlers.HandlerUtil");
            var astClass = shadow.loadClass("lombok.core.AST");
            var annotationValuesClass = shadow.loadClass("lombok.core.AnnotationValues");
            toGetter = handlerUtil.getMethod(
                "toGetterName", astClass, annotationValuesClass, CharSequence.class, boolean.class);
            toSetter = handlerUtil.getMethod(
                "toSetterName", astClass, annotationValuesClass, CharSequence.class, boolean.class);
            dummyAst = new DefiningLoader(shadow)
                .define("lombok.core.XjcDummyAST", DummyAstClassFile.bytes())
                .getDeclaredConstructor()
                .newInstance();
        } catch (Exception | LinkageError e) {
            // Catch Exception (not only ReflectiveOperationException) so DummyAstClassFile
            // generation failures fall back instead of failing class initialization.
            logOnce(
                "Cannot bind lombok.core.handlers.HandlerUtil via ShadowClassLoader; "
                    + "accessor names use minimal fallback",
                e
            );
        }
        TO_GETTER_NAME = toGetter;
        TO_SETTER_NAME = toSetter;
        DUMMY_AST = dummyAst;
    }

    private LombokAccessors() {
    }

    /**
     * Whether {@link #toGetterName}/{@link #toSetterName} will use HandlerUtil (not the local fallback).
     * False when Lombok shadow bootstrap or dummy AST setup failed.
     */
    public static boolean isHandlerUtilAvailable() {
        return TO_GETTER_NAME != null && TO_SETTER_NAME != null && DUMMY_AST != null;
    }

    /**
     * Lombok default getter name for {@code fieldName}.
     *
     * @param isBoolean {@code true} only for primitive {@code boolean} (not {@link Boolean})
     */
    public static String toGetterName(String fieldName, boolean isBoolean) {
        return invoke(TO_GETTER_NAME, fieldName, isBoolean, true);
    }

    /**
     * Lombok default setter name for {@code fieldName}.
     *
     * @param isBoolean {@code true} only for primitive {@code boolean} (not {@link Boolean})
     */
    public static String toSetterName(String fieldName, boolean isBoolean) {
        return invoke(TO_SETTER_NAME, fieldName, isBoolean, false);
    }

    private static String invoke(Method method, String fieldName, boolean isBoolean, boolean getter) {
        if (fieldName == null || fieldName.isEmpty()) {
            return null;
        }
        if (method != null && DUMMY_AST != null) {
            try {
                return (String) method.invoke(null, DUMMY_AST, null, fieldName, isBoolean);
            } catch (InvocationTargetException e) {
                logOnce("Lombok HandlerUtil accessor naming failed", e.getCause() != null ? e.getCause() : e);
            } catch (ReflectiveOperationException e) {
                logOnce("Lombok HandlerUtil accessor naming invoke failed", e);
            }
        }
        return fallbackName(fieldName, isBoolean, getter);
    }

    /**
     * Minimal fallback when Lombok is unavailable (default bean naming + BASIC capitalization).
     */
    static String fallbackName(String fieldName, boolean isBoolean, boolean getter) {
        if (isBoolean && fieldName.startsWith("is") && fieldName.length() > 2
            && !Character.isLowerCase(fieldName.charAt(2))) {
            return getter ? fieldName : "set" + fieldName.substring(2);
        }
        var prefix = getter ? (isBoolean ? "is" : "get") : "set";
        return prefix + basicCapitalize(fieldName);
    }

    /**
     * Same shape as Lombok {@code CapitalizationStrategy.BASIC}.
     */
    static String basicCapitalize(String in) {
        if (in.isEmpty()) {
            return in;
        }
        var first = in.charAt(0);
        if (!Character.isLowerCase(first)) {
            return in;
        }
        var useUpperCase = in.length() > 2
            && (Character.isTitleCase(in.charAt(1)) || Character.isUpperCase(in.charAt(1)));
        return (useUpperCase ? Character.toUpperCase(first) : Character.toTitleCase(first)) + in.substring(1);
    }

    private static void logOnce(String message, Throwable t) {
        if (!failureLogged) {
            synchronized (LombokAccessors.class) {
                if (!failureLogged) {
                    failureLogged = true;
                    log.warn(message, t);
                }
            }
        }
    }

    /**
     * Child of the shadow loader so {@code defineClass} can resolve {@code lombok.core.AST}.
     */
    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /**
     * Minimal {@code public class lombok.core.XjcDummyAST extends lombok.core.AST} classfile.
     * Used only so {@code HandlerUtil.toGetterName/toSetterName} can be invoked with defaults.
     */
    private static final class DummyAstClassFile {
        private DummyAstClassFile() {
        }

        /**
         * @throws Exception on classfile assembly failure (caller falls back; do not wrap as Error)
         */
        static byte[] bytes() throws Exception {
            return write();
        }

        private static byte[] write() throws Exception {
            var cp = new ConstantPool();
            int thisUtf = cp.utf8("lombok/core/XjcDummyAST");
            int thisClass = cp.cls(thisUtf);
            int superUtf = cp.utf8("lombok/core/AST");
            int superClass = cp.cls(superUtf);
            int initUtf = cp.utf8("<init>");
            int initDesc = cp.utf8("()V");
            int superInitDesc = cp.utf8(
                "(Ljava/lang/String;Ljava/lang/String;Llombok/core/ImportList;Ljava/util/Collection;)V");
            int superInitNt = cp.nameAndType(initUtf, superInitDesc);
            int superInitRef = cp.methodref(superClass, superInitNt);
            int codeUtf = cp.utf8("Code");
            int fileUtf = cp.utf8("(xjc).java");
            int fileStr = cp.string(fileUtf);
            int emptyListUtf = cp.utf8("emptyList");
            int emptyListDesc = cp.utf8("()Ljava/util/List;");
            int collectionsUtf = cp.utf8("java/util/Collections");
            int collectionsClass = cp.cls(collectionsUtf);
            int emptyListNt = cp.nameAndType(emptyListUtf, emptyListDesc);
            int emptyListRef = cp.methodref(collectionsClass, emptyListNt);
            int getAbsUtf = cp.utf8("getAbsoluteFileLocation");
            int getAbsDesc = cp.utf8("()Ljava/net/URI;");
            int uriUtf = cp.utf8("java/net/URI");
            int uriClass = cp.cls(uriUtf);
            int createUtf = cp.utf8("create");
            int createDesc = cp.utf8("(Ljava/lang/String;)Ljava/net/URI;");
            int createNt = cp.nameAndType(createUtf, createDesc);
            int createRef = cp.methodref(uriClass, createNt);
            int dummyUriUtf = cp.utf8("file:///xjc-dummy");
            int dummyUriStr = cp.string(dummyUriUtf);
            int buildTreeUtf = cp.utf8("buildTree");
            int buildTreeDesc = cp.utf8(
                "(Ljava/lang/Object;Llombok/core/AST$Kind;)Llombok/core/LombokNode;");

            var out = new ByteArrayOutputStream();
            var dos = new DataOutputStream(out);
            dos.writeInt(0xCAFEBABE);
            dos.writeShort(0);
            dos.writeShort(52);
            cp.writeTo(dos);
            dos.writeShort(0x0021); // public super
            dos.writeShort(thisClass);
            dos.writeShort(superClass);
            dos.writeShort(0); // interfaces
            dos.writeShort(0); // fields
            dos.writeShort(3); // methods

            writeMethod(dos, 0x0001, initUtf, initDesc, codeUtf, code -> {
                code.writeByte(0x2A); // aload_0
                code.writeByte(0x12);
                code.writeByte(fileStr);
                code.writeByte(0x01); // aconst_null package
                code.writeByte(0x01); // aconst_null ImportList
                code.writeByte(0xB8);
                code.writeShort(emptyListRef);
                code.writeByte(0xB7);
                code.writeShort(superInitRef);
                code.writeByte(0xB1); // return
            }, 5, 1);

            writeMethod(dos, 0x0001, getAbsUtf, getAbsDesc, codeUtf, code -> {
                code.writeByte(0x12);
                code.writeByte(dummyUriStr);
                code.writeByte(0xB8);
                code.writeShort(createRef);
                code.writeByte(0xB0); // areturn
            }, 1, 1);

            writeMethod(dos, 0x0004, buildTreeUtf, buildTreeDesc, codeUtf, code -> {
                code.writeByte(0x01);
                code.writeByte(0xB0);
            }, 1, 3);

            dos.writeShort(0); // class attributes
            return out.toByteArray();
        }

        @FunctionalInterface
        private interface CodeWriter {
            void write(DataOutputStream code) throws Exception;
        }

        private static void writeMethod(
            DataOutputStream dos,
            int access,
            int nameIdx,
            int descIdx,
            int codeAttrIdx,
            CodeWriter body,
            int maxStack,
            int maxLocals
        ) throws Exception {
            dos.writeShort(access);
            dos.writeShort(nameIdx);
            dos.writeShort(descIdx);
            dos.writeShort(1);
            var codeBytes = new ByteArrayOutputStream();
            body.write(new DataOutputStream(codeBytes));
            var bytecode = codeBytes.toByteArray();
            dos.writeShort(codeAttrIdx);
            dos.writeInt(12 + bytecode.length);
            dos.writeShort(maxStack);
            dos.writeShort(maxLocals);
            dos.writeInt(bytecode.length);
            dos.write(bytecode);
            dos.writeShort(0);
            dos.writeShort(0);
        }

        private static final class ConstantPool {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            private final DataOutputStream out = new DataOutputStream(buf);
            private int count = 1;

            int utf8(String s) throws Exception {
                out.writeByte(1);
                var b = s.getBytes(StandardCharsets.UTF_8);
                out.writeShort(b.length);
                out.write(b);
                return count++;
            }

            int cls(int nameIdx) throws Exception {
                out.writeByte(7);
                out.writeShort(nameIdx);
                return count++;
            }

            int string(int utf8Idx) throws Exception {
                out.writeByte(8);
                out.writeShort(utf8Idx);
                return count++;
            }

            int nameAndType(int nameIdx, int descIdx) throws Exception {
                out.writeByte(12);
                out.writeShort(nameIdx);
                out.writeShort(descIdx);
                return count++;
            }

            int methodref(int clsIdx, int ntIdx) throws Exception {
                out.writeByte(10);
                out.writeShort(clsIdx);
                out.writeShort(ntIdx);
                return count++;
            }

            void writeTo(DataOutputStream dos) throws Exception {
                dos.writeShort(count);
                dos.write(buf.toByteArray());
            }
        }
    }
}
