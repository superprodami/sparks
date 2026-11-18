import java.io.File;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.dexlib2.writer.io.FileDataStore;

public class PatchFactory {
    public static void main(String[] args) throws Exception {
        int changed = 0;
        for (File file : new File(args[0]).listFiles()) {
            if (!file.getName().endsWith(".dex")) continue;
            DexFile dex = DexFileFactory.loadDexFile(file, Opcodes.forApi(28));
            DexPool pool = new DexPool(dex.getOpcodes());
            boolean found = false;
            for (ClassDef cls : dex.getClasses()) {
                if (!cls.getType().equals("Landroidx/core/app/CoreComponentFactory;")) { pool.internClass(cls); continue; }
                List<Method> methods = new ArrayList<>();
                for (Method method : cls.getVirtualMethods()) {
                    if (method.getName().equals("instantiateApplication")) {
                        MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
                        body.addInstruction(0, new BuilderInstruction21c(Opcode.CONST_STRING, body.getRegisterCount() - 1,
                                new ImmutableStringReference("com.kapinote.ai.MainApp")));
                        methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body));
                        found = true;
                        changed++;
                    } else methods.add(method);
                }
                pool.internClass(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(),
                        cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getStaticFields(),
                        cls.getInstanceFields(), cls.getDirectMethods(), methods));
            }
            if (found) {
                File output = new File(file.getParentFile(), file.getName() + ".factory");
                pool.writeTo(new FileDataStore(output));
                System.out.println("Factory patched in " + file.getName());
            }
        }
        if (changed != 1) throw new IllegalStateException("Expected one application factory; got " + changed);
    }
}
