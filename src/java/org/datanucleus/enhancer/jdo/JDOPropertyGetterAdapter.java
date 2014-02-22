/**********************************************************************
Copyright (c) 2007 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.enhancer.jdo;

import org.datanucleus.asm.AnnotationVisitor;
import org.datanucleus.asm.Attribute;
import org.datanucleus.asm.ClassVisitor;
import org.datanucleus.asm.Label;
import org.datanucleus.asm.MethodVisitor;
import org.datanucleus.asm.Opcodes;
import org.datanucleus.asm.Type;
import org.datanucleus.enhancer.ClassEnhancer;
import org.datanucleus.enhancer.ClassMethod;
import org.datanucleus.enhancer.DataNucleusEnhancer;
import org.datanucleus.enhancer.EnhanceUtils;
import org.datanucleus.enhancer.EnhancementNamer;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.PersistenceFlags;
import org.datanucleus.util.JavaUtils;
import org.datanucleus.util.Localiser;

/**
 * Adapter for property getter methods in JDO-enabled classes.
 * This adapter processes the getXXX method and
 * <ul>
 * <li>Creates aaaGetXXX with the same code as was present in getXXX</li>
 * <li>Changes getXXX to have the code below</li>
 * </ul>
 * When detachable this will be (CHECK_READ variant)
 * <pre>
 * YYY aaaGetZZZ()
 * {
 *     if (aaaFlags > 0 && aaaStateManager != null && !aaaStateManager.isLoaded(this, 0))
 *         return (Integer) aaaStateManager.getObjectField(this, 0, aaaGetXXX());
 *     if (aaaIsDetached() && !((BitSet) aaaDetachedState[2]).get(0))
 *         throw new DetachedFieldAccessException
 *             ("You have just attempted to access property \"id\" yet this field was not detached ...");
 *     return aaaGetXXX();
 * }
 * </pre>
 * and when not detachable
 * <pre>
 * YYY aaaGetZZZ()
 * {
 *     if (aaaFlags > 0 && aaaStateManager != null && !aaaStateManager.isLoaded(this, 0))
 *         return (Integer) aaaStateManager.getObjectField(this, 0, aaaGetXXX());
 *     return aaaGetXXX();
 * }
 * </pre>
 * There are other variants for MEDIATE_READ and NORMAL_READ
 */
public class JDOPropertyGetterAdapter extends MethodVisitor
{
    /** Localisation of messages. */
    protected static final Localiser LOCALISER = Localiser.getInstance("org.datanucleus.Localisation",
        ClassEnhancer.class.getClassLoader());

    /** The enhancer for this class. */
    protected ClassEnhancer enhancer;

    /** Name for the method being adapted. */
    protected String methodName;

    /** Descriptor for the method being adapted. */
    protected String methodDescriptor;

    /** MetaData for the property. */
    protected AbstractMemberMetaData mmd;

    /** Visitor for the aaaGetXXX method. */
    protected MethodVisitor visitor = null;

    /**
     * Constructor for the method adapter.
     * @param mv MethodVisitor
     * @param enhancer ClassEnhancer for the class with the method
     * @param methodName Name of the method
     * @param methodDesc Method descriptor
     * @param mmd MetaData for the property
     * @param cv ClassVisitor
     */
    public JDOPropertyGetterAdapter(MethodVisitor mv, ClassEnhancer enhancer, String methodName, String methodDesc,
            AbstractMemberMetaData mmd, ClassVisitor cv)
    {
        super(ClassEnhancer.ASM_API_VERSION, mv);
        this.enhancer = enhancer;
        this.methodName = methodName;
        this.methodDescriptor = methodDesc;
        this.mmd = mmd;

        // Generate aaaGetXXX method to include code that this getXXX currently has
        int access = (mmd.isPublic() ? Opcodes.ACC_PUBLIC : 0) | 
            (mmd.isProtected() ? Opcodes.ACC_PROTECTED : 0) | 
            (mmd.isPrivate() ? Opcodes.ACC_PRIVATE : 0) |
            (mmd.isAbstract() ? Opcodes.ACC_ABSTRACT : 0);
        this.visitor = cv.visitMethod(access, enhancer.getNamer().getGetMethodPrefixMethodName() + mmd.getName(), methodDesc, 
            null, null);
    }

    /**
     * Method called at the end of visiting the getXXX method.
     * This is used to add the aaaGetXXX method with the same code as is present originally in the getXXX method.
     */
    public void visitEnd()
    {
        visitor.visitEnd();
        if (DataNucleusEnhancer.LOGGER.isDebugEnabled())
        {
            String msg = ClassMethod.getMethodAdditionMessage(enhancer.getNamer().getGetMethodPrefixMethodName() + mmd.getName(), 
                mmd.getType(), null, null);
            DataNucleusEnhancer.LOGGER.debug(LOCALISER.msg("Enhancer.AddMethod", msg));
        }

        if (!mmd.isAbstract())
        {
            // Property is not abstract so generate the getXXX method to use the aaaGetXXX we just added
            generateGetXXXMethod(mv, mmd, enhancer.getASMClassName(), enhancer.getClassDescriptor(), false, 
                JavaUtils.useStackMapFrames(), enhancer.getNamer());
        }
    }

    /**
     * Convenience method to use the MethodVisitor to generate the code for the method getXXX() for the
     * property with the specified MetaData.
     * @param mv MethodVisitor
     * @param mmd MetaData for the property
     * @param asmClassName ASM class name for the owning class
     * @param asmClassDesc ASM descriptor for the owning class
     * @param detachListener true if the generate code must support DetachListener
     */
    public static void generateGetXXXMethod(MethodVisitor mv, AbstractMemberMetaData mmd,
            String asmClassName, String asmClassDesc, boolean detachListener, boolean includeFrames,
            EnhancementNamer namer)
    {
        String[] argNames = new String[] {"this"};
        String fieldTypeDesc = Type.getDescriptor(mmd.getType());

        mv.visitCode();

        AbstractClassMetaData cmd = mmd.getAbstractClassMetaData();
        if ((mmd.getPersistenceFlags() & PersistenceFlags.MEDIATE_READ) == PersistenceFlags.MEDIATE_READ)
        {
            // MEDIATE_READ - see method JdoGetViaMediate
            Label startLabel = new Label();
            mv.visitLabel(startLabel);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, asmClassName,
                namer.getStateManagerFieldName(), "L" + namer.getStateManagerAsmClassName() + ";");
            Label l1 = new Label();
            mv.visitJumpInsn(Opcodes.IFNULL, l1);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, asmClassName,
                namer.getStateManagerFieldName(), "L" + namer.getStateManagerAsmClassName() + ";");
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            EnhanceUtils.addBIPUSHToMethod(mv, mmd.getFieldId());
            if (cmd.getPersistenceCapableSuperclass() != null)
            {
                mv.visitFieldInsn(Opcodes.GETSTATIC, asmClassName, namer.getInheritedFieldCountFieldName(), "I");
                mv.visitInsn(Opcodes.IADD);
            }
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, namer.getStateManagerAsmClassName(),
                "isLoaded", "(L" + namer.getPersistableAsmClassName() + ";I)Z");
            mv.visitJumpInsn(Opcodes.IFNE, l1);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, asmClassName,
                namer.getStateManagerFieldName(), "L" + namer.getStateManagerAsmClassName() + ";");
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            EnhanceUtils.addBIPUSHToMethod(mv, mmd.getFieldId());
            if (cmd.getPersistenceCapableSuperclass() != null)
            {
                mv.visitFieldInsn(Opcodes.GETSTATIC, asmClassName, namer.getInheritedFieldCountFieldName(), "I");
                mv.visitInsn(Opcodes.IADD);
            }

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, asmClassName, 
                namer.getGetMethodPrefixMethodName() + mmd.getName(), "()" + fieldTypeDesc);
            String methodName = "get" + EnhanceUtils.getTypeNameForJDOMethod(mmd.getType()) + "Field";
            String argTypeDesc = fieldTypeDesc;
            if (methodName.equals("getObjectField"))
            {
                argTypeDesc = EnhanceUtils.CD_Object;
            }
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, namer.getStateManagerAsmClassName(),
                methodName, "(L" + namer.getPersistableAsmClassName() + ";I" + argTypeDesc + ")" + argTypeDesc);
            if (methodName.equals("getObjectField"))
            {
                // Cast any object fields to the correct type
                mv.visitTypeInsn(Opcodes.CHECKCAST, mmd.getTypeName().replace('.', '/'));
            }
            EnhanceUtils.addReturnForType(mv, mmd.getType());

            mv.visitLabel(l1);
            if (includeFrames)
            {
                mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            }

            if (cmd.isDetachable())
            {
                // "if (objPC.aaaIsDetached() != false && ((BitSet) objPC.aaaDetachedState[2]).get(5) != true)"
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, asmClassName, namer.getIsDetachedMethodName(), "()Z");

                Label l4 = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, l4);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, asmClassName,
                    namer.getDetachedStateFieldName(), "[Ljava/lang/Object;");
                mv.visitInsn(Opcodes.ICONST_2);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/BitSet");
                EnhanceUtils.addBIPUSHToMethod(mv, mmd.getFieldId());
                if (cmd.getPersistenceCapableSuperclass() != null)
                {
                    mv.visitFieldInsn(Opcodes.GETSTATIC, asmClassName, namer.getInheritedFieldCountFieldName(), "I");
                    mv.visitInsn(Opcodes.IADD);
                }
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/BitSet", "get", "(I)Z");
                mv.visitJumpInsn(Opcodes.IFNE, l4);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, asmClassName,
                    namer.getDetachedStateFieldName(), "[Ljava/lang/Object;");
                mv.visitInsn(Opcodes.ICONST_3);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/BitSet");
                EnhanceUtils.addBIPUSHToMethod(mv, mmd.getFieldId());
                if (cmd.getPersistenceCapableSuperclass() != null)
                {
                    mv.visitFieldInsn(Opcodes.GETSTATIC, asmClassName, namer.getInheritedFieldCountFieldName(), "I");
                    mv.visitInsn(Opcodes.IADD);
                }
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/BitSet", "get", "(I)Z");
                mv.visitJumpInsn(Opcodes.IFNE, l4);

                if (detachListener)
                {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, namer.getDetachListenerAsmClassName(), "getInstance", 
                        "()L" + namer.getDetachListenerAsmClassName() + ";");
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitLdcInsn(mmd.getName());
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, namer.getDetachListenerAsmClassName(), 
                        "undetachedFieldAccess", "(Ljava/lang/Object;Ljava/lang/String;)V");
                }
                else
                {
                    // "throw new JDODetachedFieldAccessException(...)"
                    mv.visitTypeInsn(Opcodes.NEW, namer.getDetachedFieldAccessExceptionAsmClassName());
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitLdcInsn(LOCALISER.msg("Enhancer.DetachedPropertyAccess", mmd.getName()));
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, namer.getDetachedFieldAccessExceptionAsmClassName(), 
                        "<init>", "(Ljava/lang/String;)V");
                    mv.visitInsn(Opcodes.ATHROW);
                }

                mv.visitLabel(l4);
                if (includeFrames)
                {
                    mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                }
            }

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, asmClassName, 
                namer.getGetMethodPrefixMethodName() + mmd.getName(), "()" + fieldTypeDesc);
            EnhanceUtils.addReturnForType(mv, mmd.getType());

            Label endLabel = new Label();
            mv.visitLabel(endLabel);
            mv.visitLocalVariable(argNames[0], asmClassDesc, null, startLabel, endLabel, 0);
            mv.visitMaxs(4, 1);
        }
        else if ((mmd.getPersistenceFlags() & PersistenceFlags.CHECK_READ) == PersistenceFlags.CHECK_READ)
        {
            // CHECK_READ - see method JdoGetViaCheck
            Label startLabel = new Label();
            mv.visitLabel(startLabel);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, asmClassName, namer.getFlagsFieldName(), "B");
            Label l1 = new Label();
            mv.visitJumpInsn(Opcodes.IFLE, l1);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, asmClassName,
                namer.getStateManagerFieldName(), "L" + namer.getStateManagerAsmClassName() + ";");
            mv.visitJumpInsn(Opcodes.IFNULL, l1);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, asmClassName,
                namer.getStateManagerFieldName(), "L" + namer.getStateManagerAsmClassName() + ";");
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            EnhanceUtils.addBIPUSHToMethod(mv, mmd.getFieldId());
            if (cmd.getPersistenceCapableSuperclass() != null)
            {
                mv.visitFieldInsn(Opcodes.GETSTATIC, asmClassName,
                    namer.getInheritedFieldCountFieldName(), "I");
                mv.visitInsn(Opcodes.IADD);
            }
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, namer.getStateManagerAsmClassName(),
                "isLoaded", "(L" + namer.getPersistableAsmClassName() + ";I)Z");
            mv.visitJumpInsn(Opcodes.IFNE, l1);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, asmClassName,
                namer.getStateManagerFieldName(), "L" + namer.getStateManagerAsmClassName() + ";");
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            EnhanceUtils.addBIPUSHToMethod(mv, mmd.getFieldId());
            if (cmd.getPersistenceCapableSuperclass() != null)
            {
                mv.visitFieldInsn(Opcodes.GETSTATIC, asmClassName, namer.getInheritedFieldCountFieldName(), "I");
                mv.visitInsn(Opcodes.IADD);
            }
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, asmClassName, namer.getGetMethodPrefixMethodName() + mmd.getName(), "()" + fieldTypeDesc);
            String methodName = "get" + EnhanceUtils.getTypeNameForJDOMethod(mmd.getType()) + "Field";
            String argTypeDesc = fieldTypeDesc;
            if (methodName.equals("getObjectField"))
            {
                argTypeDesc = EnhanceUtils.CD_Object;
            }
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, namer.getStateManagerAsmClassName(),
                methodName, "(L" + namer.getPersistableAsmClassName() + ";I" + argTypeDesc + ")" + argTypeDesc);
            if (methodName.equals("getObjectField"))
            {
                // Cast any object fields to the correct type
                mv.visitTypeInsn(Opcodes.CHECKCAST, mmd.getTypeName().replace('.', '/'));
            }
            EnhanceUtils.addReturnForType(mv, mmd.getType());
            mv.visitLabel(l1);
            if (includeFrames)
            {
                mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            }

            if (cmd.isDetachable())
            {
                // "if (objPC.aaaIsDetached() != false && ((BitSet) objPC.aaaDetachedState[2]).get(5) != true)"
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, asmClassName, namer.getIsDetachedMethodName(), "()Z");
                Label l4 = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, l4);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, asmClassName,
                    namer.getDetachedStateFieldName(), "[Ljava/lang/Object;");
                mv.visitInsn(Opcodes.ICONST_2);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/BitSet");
                EnhanceUtils.addBIPUSHToMethod(mv, mmd.getFieldId());
                if (cmd.getPersistenceCapableSuperclass() != null)
                {
                    mv.visitFieldInsn(Opcodes.GETSTATIC, asmClassName,
                        namer.getInheritedFieldCountFieldName(), "I");
                    mv.visitInsn(Opcodes.IADD);
                }
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/BitSet", "get", "(I)Z");
                mv.visitJumpInsn(Opcodes.IFNE, l4);

                if (detachListener)
                {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, namer.getDetachListenerAsmClassName(), "getInstance",
                        "()L" + namer.getDetachListenerAsmClassName() + ";");
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitLdcInsn(mmd.getName());
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, namer.getDetachListenerAsmClassName(),
                        "undetachedFieldAccess", "(Ljava/lang/Object;Ljava/lang/String;)V");
                }
                else
                {
                    // "throw new JDODetachedFieldAccessException(...)"
                    mv.visitTypeInsn(Opcodes.NEW, namer.getDetachedFieldAccessExceptionAsmClassName());
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitLdcInsn(LOCALISER.msg("Enhancer.DetachedPropertyAccess", mmd.getName()));
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, namer.getDetachedFieldAccessExceptionAsmClassName(), 
                        "<init>", "(Ljava/lang/String;)V");
                    mv.visitInsn(Opcodes.ATHROW);
                }

                mv.visitLabel(l4);
                if (includeFrames)
                {
                    mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                }
            }

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, asmClassName, 
                namer.getGetMethodPrefixMethodName() + mmd.getName(), "()" + fieldTypeDesc);
            EnhanceUtils.addReturnForType(mv, mmd.getType());

            Label endLabel = new Label();
            mv.visitLabel(endLabel);
            mv.visitLocalVariable(argNames[0], asmClassDesc, null, startLabel, endLabel, 0);
            mv.visitMaxs(4, 1);
        }
        else
        {
            // NORMAL - see method JdoGetNormal
            Label startLabel = new Label();
            mv.visitLabel(startLabel);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, asmClassName, 
                namer.getGetMethodPrefixMethodName() + mmd.getName(), "()" + fieldTypeDesc);
            EnhanceUtils.addReturnForType(mv, mmd.getType());

            Label endLabel = new Label();
            mv.visitLabel(endLabel);
            mv.visitLocalVariable(argNames[0], asmClassDesc, null, startLabel, endLabel, 0);
            mv.visitMaxs(1, 1);
        }

        mv.visitEnd();
    }

    public AnnotationVisitor visitAnnotation(String arg0, boolean arg1)
    {
        // Keep any annotations on the getXXX method
        return mv.visitAnnotation(arg0, arg1);
    }

    public AnnotationVisitor visitAnnotationDefault()
    {
        return visitor.visitAnnotationDefault();
    }

    public void visitAttribute(Attribute arg0)
    {
        visitor.visitAttribute(arg0);
    }

    public void visitCode()
    {
        visitor.visitCode();
    }

    public void visitFieldInsn(int arg0, String arg1, String arg2, String arg3)
    {
        visitor.visitFieldInsn(arg0, arg1, arg2, arg3);
    }

    public void visitFrame(int arg0, int arg1, Object[] arg2, int arg3, Object[] arg4)
    {
        visitor.visitFrame(arg0, arg1, arg2, arg3, arg4);
    }

    public void visitIincInsn(int arg0, int arg1)
    {
        visitor.visitIincInsn(arg0, arg1);
    }

    public void visitInsn(int arg0)
    {
        visitor.visitInsn(arg0);
    }

    public void visitIntInsn(int arg0, int arg1)
    {
        visitor.visitIntInsn(arg0, arg1);
    }

    public void visitJumpInsn(int arg0, Label arg1)
    {
        visitor.visitJumpInsn(arg0, arg1);
    }

    public void visitLabel(Label arg0)
    {
        visitor.visitLabel(arg0);
    }

    public void visitLdcInsn(Object arg0)
    {
        visitor.visitLdcInsn(arg0);
    }

    public void visitLineNumber(int arg0, Label arg1)
    {
        visitor.visitLineNumber(arg0, arg1);
    }

    public void visitLocalVariable(String arg0, String arg1, String arg2, Label arg3, Label arg4, int arg5)
    {
        visitor.visitLocalVariable(arg0, arg1, arg2, arg3, arg4, arg5);
    }

    public void visitLookupSwitchInsn(Label arg0, int[] arg1, Label[] arg2)
    {
        visitor.visitLookupSwitchInsn(arg0, arg1, arg2);
    }

    public void visitMaxs(int arg0, int arg1)
    {
        visitor.visitMaxs(arg0, arg1);
    }

    public void visitMethodInsn(int arg0, String arg1, String arg2, String arg3)
    {
        visitor.visitMethodInsn(arg0, arg1, arg2, arg3);
    }

    public void visitMultiANewArrayInsn(String arg0, int arg1)
    {
        visitor.visitMultiANewArrayInsn(arg0, arg1);
    }

    public AnnotationVisitor visitParameterAnnotation(int arg0, String arg1, boolean arg2)
    {
        return visitor.visitParameterAnnotation(arg0, arg1, arg2);
    }

    public void visitTableSwitchInsn(int arg0, int arg1, Label arg2, Label... arg3)
    {
        visitor.visitTableSwitchInsn(arg0, arg1, arg2, arg3);
    }

    public void visitTryCatchBlock(Label arg0, Label arg1, Label arg2, String arg3)
    {
        visitor.visitTryCatchBlock(arg0, arg1, arg2, arg3);
    }

    public void visitTypeInsn(int arg0, String arg1)
    {
        visitor.visitTypeInsn(arg0, arg1);
    }

    public void visitVarInsn(int arg0, int arg1)
    {
        visitor.visitVarInsn(arg0, arg1);
    }
}
