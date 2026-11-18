import java.io.File;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.pool.DexPool;

public final class PatchDex {
    static final String NODE = "Lcom/st/iad/common/accessibility/r3;";
    static final String ADAPTER = "Lcn/local/kapijd/NativeAdapter;";
    static final String REQ = "Lcom/st/iad/common/accessibility/bean/AccessibleAccountReq;";
    static int patched;

    static Method rewrite(Method method) {
        if (method.getDefiningClass().equals("Lcom/st/iad/common/accessibility/AccountDataUploader;") && method.getName().equals("N")) {
            MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
            Label original = body.newLabelForIndex(0);
            body.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, body.getRegisterCount() - 3, 2,
                    new ImmutableMethodReference(ADAPTER, "submit", Arrays.asList("Ljava/lang/Object;", "Ljava/lang/Object;"), "Z")));
            body.addInstruction(1, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
            body.addInstruction(2, new BuilderInstruction21t(Opcode.IF_EQZ, 0, original));
            body.addInstruction(3, new BuilderInstruction10x(Opcode.RETURN_VOID));
            patched++;
            return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
        }
        if (method.getDefiningClass().equals("Lcom/st/iad/common/accessibility/PaymentAccessibilityService;") && method.getName().equals("applyAccessibilityConfig")) {
            MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
            for (int i = body.getInstructions().size() - 1; i >= 0; i--) {
                if (body.getInstructions().get(i).getOpcode() == Opcode.RETURN_OBJECT)
                    body.addInstruction(i, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, body.getRegisterCount() - 3, 1,
                            new ImmutableMethodReference(ADAPTER, "configureService", Collections.singletonList("Landroid/accessibilityservice/AccessibilityService;"), "V")));
            }
            patched++;
            return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
        }
        if (!method.getDefiningClass().equals(NODE)) return method;
        if (method.getName().equals("Y")) {
            MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
            int first = body.getRegisterCount() - 1 - method.getParameters().size();
            body.addInstruction(0, new BuilderInstruction22x(Opcode.MOVE_OBJECT_FROM16, 0, first));
            body.addInstruction(1, new BuilderInstruction22x(Opcode.MOVE_OBJECT_FROM16, 1, first + 1));
            body.addInstruction(2, new BuilderInstruction22x(Opcode.MOVE_OBJECT_FROM16, 2, first + 4));
            body.addInstruction(3, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 3, 0, 1, 2, 0, 0,
                    new ImmutableMethodReference(ADAPTER, "visit", Arrays.asList("Ljava/lang/Object;", "Ljava/lang/String;",
                            "Landroid/view/accessibility/AccessibilityNodeInfo;"), "V")));
            patched++;
            return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
        }
        if (!(method.getName().equals("V") || method.getName().equals("F"))) return method;
        MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
        if (method.getName().equals("V")) {
            if (!method.getParameterTypes().toString().equals("[Ljava/util/ArrayList;]"))
                throw new IllegalStateException("Unexpected V prototype " + method.getParameterTypes());
            body.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                    body.getRegisterCount() - 1, 1, new ImmutableMethodReference(ADAPTER,
                    "extend", Collections.singletonList("Ljava/util/List;"), "V")));
        } else {
            for (int i = body.getInstructions().size() - 1; i >= 0; i--) {
                if (body.getInstructions().get(i).getOpcode() == Opcode.RETURN_OBJECT) {
                    int reg = ((OneRegisterInstruction) body.getInstructions().get(i)).getRegisterA();
                    body.addInstruction(i, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, reg, 1,
                            new ImmutableMethodReference(ADAPTER, "enrich", Collections.singletonList("Ljava/lang/Object;"), "Ljava/lang/Object;")));
                    body.addInstruction(i + 1, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, reg));
                    body.addInstruction(i + 2, new BuilderInstruction21c(Opcode.CHECK_CAST, reg, new ImmutableTypeReference(REQ)));
                }
            }
        }
        patched++;
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
    }

    public static void main(String[] args) throws Exception {
        DexFile original = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forApi(28));
        DexFile helper = DexFileFactory.loadDexFile(new File(args[1]), Opcodes.forApi(28));
        DexPool pool = new DexPool(original.getOpcodes());
        for (ClassDef cls : original.getClasses()) {
                if (!cls.getType().equals(NODE) && !cls.getType().equals("Lcom/st/iad/common/accessibility/PaymentAccessibilityService;") && !cls.getType().equals("Lcom/st/iad/common/accessibility/AccountDataUploader;")) { pool.internClass(cls); continue; }
            List<Method> direct = new ArrayList<>(), virtual = new ArrayList<>();
            for (Method method : cls.getDirectMethods()) direct.add(rewrite(method));
            for (Method method : cls.getVirtualMethods()) virtual.add(rewrite(method));
            pool.internClass(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(),
                    cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getStaticFields(),
                    cls.getInstanceFields(), direct, virtual));
        }
        for (ClassDef cls : helper.getClasses()) pool.internClass(cls);
        if (patched != 5) throw new IllegalStateException("Expected five modified methods; got " + patched);
        pool.writeTo(new org.jf.dexlib2.writer.io.FileDataStore(new File(args[2])));
        System.out.println("Patched methods: " + patched + "; output bytes: " + new File(args[2]).length());
    }
}
