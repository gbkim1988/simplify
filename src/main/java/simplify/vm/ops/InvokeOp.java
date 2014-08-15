package simplify.vm.ops;

import gnu.trove.list.TIntList;

import java.util.List;
import java.util.logging.Logger;

import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction35c;
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.util.ReferenceUtil;

import simplify.Main;
import simplify.MethodReflector;
import simplify.SmaliClassUtils;
import simplify.Utils;
import simplify.emulate.MethodEmulator;
import simplify.vm.ContextGraph;
import simplify.vm.MethodContext;
import simplify.vm.SideEffect;
import simplify.vm.VirtualMachine;
import simplify.vm.types.UnknownValue;

public class InvokeOp extends Op {

    private static final Logger log = Logger.getLogger(Main.class.getSimpleName());

    private static void addCalleeParameters(MethodContext calleeContext, MethodContext callerContext, int[] registers,
                    String[] parameterTypes, boolean isStatic) {
        int offset = 0;

        if (!isStatic) {
            // First register is instance reference.
            int register = registers[0];
            Object instance = callerContext.readRegister(register);
            calleeContext.assignParameter(-1, instance);
            offset = 1;
        }

        for (int i = offset; i < registers.length; i++) {
            int register = registers[i];
            int pos = i - offset;

            // Can't trust SmaliClassUtils.getValueType(value) because it may be of an unknown type.
            String type = parameterTypes[pos];

            // Pass original value reference, and not a clone. If method is emulated or reflected, it'll be updated in
            // place. Otherwise, cloning is handled by MethodExecutor.
            Object value = callerContext.readRegister(register);
            if (value instanceof UnknownValue) {
                ((UnknownValue) value).setType(type);
            }
            calleeContext.assignParameter(pos, value);

            if (type.equals("J")) {
                // This register index and the next refer to this variable.
                i++;
            }
        }
    }

    private static boolean allArgumentsKnown(MethodContext mctx) {
        List<Object> registerValues = mctx.getRegisterToValue().getValues();
        for (Object value : registerValues) {
            if (value instanceof UnknownValue) {
                return false;
            }
        }

        return true;
    }

    private static void assumeMaximumUnknown(VirtualMachine vm, MethodContext callerContext, boolean isStatic,
                    int[] registers, String[] parameterTypes, String returnType) {
        for (int i = 0; i < registers.length; i++) {
            int register = registers[i];

            // It's ugly. Need better way to handle static / non-static.
            String type = null;
            if (!isStatic) {
                if (i == 0) { // Instance type
                    type = callerContext.peekRegisterType(register);
                } else {
                    type = parameterTypes[i - 1];
                }
            } else {
                type = parameterTypes[i];
            }

            if (SmaliClassUtils.isImmutableClass(type)) {
                if (type.equals("J")) {
                    i++;
                }

                log.fine(type + " is immutable");
                continue;
            }

            log.fine(type + " is mutable and passed into strange method, marking unknown");
            callerContext.pokeRegister(register, new UnknownValue(type));
        }

        if (!returnType.equals("V")) {
            callerContext.assignResultRegister(new UnknownValue(returnType));
        }
    }

    private static MethodContext buildCalleeContext(MethodContext callerContext, boolean isStatic, int[] registers,
                    String[] parameterTypes) {
        int parameterCount = registers.length;
        int registerCount = parameterCount;
        int callDepth = callerContext.getCallDepth() + 1;

        if (!isStatic && (registerCount > 0)) {
            parameterCount--;
        }

        MethodContext calleeContext = new MethodContext(registerCount, parameterCount, callDepth);
        addCalleeParameters(calleeContext, callerContext, registers, parameterTypes, isStatic);

        return calleeContext;
    }

    private static void updateInstanceAndMutableArguments(MethodContext callerContext, int[] registers,
                    boolean isStatic, MethodContext calleeContext, ContextGraph graph) {

    }

    private static void updateInstanceAndMutableArguments_broken(VirtualMachine vm, MethodContext callerContext,
                    ContextGraph graph, boolean isStatic) {
        MethodContext calleeContext = graph.getNodePile(0).get(0).getContext();

        // int calleeParamStart = calleeContext.getParameterStart();
        // TIntList addresses = graph.getConnectedTerminatingAddresses();
        //
        // if (!isStatic) {
        // int register = callerContext.getParameterStart() - 1;
        // RegisterStore callerInstance = callerContext.peekRegister(register);
        // Object value = graph.getRegisterConsensus(addresses, calleeParamStart - 1).getValue();
        //
        // log.fine("updating instance value: " + callerInstance + " to " + value);
        // callerInstance.setValue(value);
        // }
        //
        // int callerParamStart = callerContext.getParameterStart();
        // int paramCount = callerContext.getRegisterCount() - callerParamStart;
        // for (int i = 0; i < paramCount; i++) {
        // RegisterStore registerStore = callerContext.peekRegister(callerParamStart + i);
        // if (!SmaliClassUtils.isImmutableClass(registerStore.getType())) {
        // Object value = graph.getRegisterConsensus(addresses, calleeParamStart + i).getValue();
        // registerStore.setValue(value);
        // log.fine(registerStore.getType() + " is mutable, updating with callee value = " + registerStore);
        // }
        // }
    }

    static InvokeOp create(Instruction instruction, int address, VirtualMachine vm) {
        int childAddress = address + instruction.getCodeUnits();
        String opName = instruction.getOpcode().name;

        int[] registers = null;
        MethodReference methodReference = null;
        if (opName.contains("/range")) {
            Instruction3rc instr = (Instruction3rc) instruction;
            int registerCount = instr.getRegisterCount();
            int start = instr.getStartRegister();
            int end = start + registerCount;

            registers = new int[registerCount];
            for (int i = start; i < end; i++) {
                registers[i - start] = i;
            }

            methodReference = (MethodReference) instr.getReference();
        } else {
            Instruction35c instr = (Instruction35c) instruction;
            int registerCount = instr.getRegisterCount();

            registers = new int[registerCount];
            switch (registerCount) {
            case 5:
                registers[4] = instr.getRegisterG();
            case 4:
                registers[3] = instr.getRegisterF();
            case 3:
                registers[2] = instr.getRegisterE();
            case 2:
                registers[1] = instr.getRegisterD();
            case 1:
                registers[0] = instr.getRegisterC();
                break;
            }

            methodReference = (MethodReference) instr.getReference();
        }

        return new InvokeOp(address, opName, childAddress, methodReference, registers, vm);
    }

    private final boolean isStatic;
    private final MethodReference methodReference;
    private final String methodDescriptor;
    private final String returnType;
    private final int[] registers;
    private final VirtualMachine vm;
    private SideEffect.Type sideEffectType;

    private InvokeOp(int address, String opName, int childAddress, MethodReference methodReference, int[] registers,
                    VirtualMachine vm) {
        super(address, opName, childAddress);

        this.methodReference = methodReference;
        this.methodDescriptor = ReferenceUtil.getMethodDescriptor(methodReference);
        this.returnType = methodReference.getReturnType();
        this.registers = registers;
        this.vm = vm;
        isStatic = opName.contains("-static");
    }

    @Override
    public int[] execute(MethodContext callerContext) {
        sideEffectType = SideEffect.Type.STRONG;

        boolean returnsVoid = returnType.equals("V");
        String[] parameterTypes = Utils.getParameterTypes(methodDescriptor);
        if (vm.isMethodDefined(methodDescriptor)) {
            // Local method, so the VM can execute it.
            MethodContext calleeContext = vm.getInstructionGraph(methodDescriptor).getRootContext();
            calleeContext.setCallDepth(callerContext.getCallDepth() + 1);
            addCalleeParameters(calleeContext, callerContext, registers, parameterTypes, isStatic);

            ContextGraph graph = vm.execute(methodDescriptor, calleeContext);
            if (graph == null) {
                // Problem executing the method. Maybe node visits or call depth exceeded?
                log.info("Problem executing " + methodDescriptor + ", propigating ambiguity.");
                assumeMaximumUnknown(vm, callerContext, isStatic, registers, parameterTypes, returnType);

                return getPossibleChildren();
            } else {
                sideEffectType = graph.getStrongestSideEffectType();
            }

            updateInstanceAndMutableArguments_broken(vm, callerContext, graph, isStatic);

            if (!returnsVoid) {
                TIntList terminating = graph.getConnectedTerminatingAddresses();
                // TODO: use getTerminatingRegisterConsensus
                Object consensus = graph.getRegisterConsensus(terminating, MethodContext.ReturnRegister);
                callerContext.assignResultRegister(consensus);
            }
        } else {
            MethodContext calleeContext = buildCalleeContext(callerContext, isStatic, registers, parameterTypes);
            boolean allArgumentsKnown = allArgumentsKnown(calleeContext);
            if (allArgumentsKnown && MethodEmulator.canEmulate(methodDescriptor)) {
                MethodEmulator.emulate(calleeContext, methodDescriptor);
                // If a method is emulated, it's assumed to have no side-effects.
                sideEffectType = SideEffect.Type.NONE;
            } else if (allArgumentsKnown && MethodReflector.canReflect(methodDescriptor)) {
                MethodReflector reflector = new MethodReflector(methodReference, isStatic);
                reflector.reflect(calleeContext); // player play
                sideEffectType = SideEffect.Type.NONE;
            } else {
                log.fine("Unknown argument(s) or can't find/emulate/reflect " + methodDescriptor
                                + ". Propigating ambiguity.");
                assumeMaximumUnknown(vm, callerContext, isStatic, registers, parameterTypes, returnType);

                return getPossibleChildren();
            }

            if (!isStatic) {
                // Handle updating the instance reference
                Object originalInstance = callerContext.peekRegister(registers[0]);
                Object newInstance = calleeContext.peekParameter(-1);
                if (originalInstance != newInstance) {
                    // Instance went from UninitializedInstance class to something else.
                    callerContext.assignRegisterAndUpdateIdentities(registers[0], newInstance);
                } else {
                    // The instance reference could have changed, so mark it as assigned here.
                    callerContext.assignRegister(registers[0], newInstance);
                }
            }

            if (!returnsVoid) {
                Object returnRegister = calleeContext.readReturnRegister();
                callerContext.assignResultRegister(returnRegister);
            }
        }

        return getPossibleChildren();
    }

    public String getReturnType() {
        return returnType;
    }

    @Override
    public SideEffect.Type sideEffectType() {
        return sideEffectType;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getOpName());

        sb.append(" {");
        if (getOpName().contains("/range")) {
            sb.append("r").append(registers[0]).append(" .. r").append(registers[registers.length - 1]);
        } else {
            if (registers.length > 0) {
                for (int register : registers) {
                    sb.append("r").append(register).append(", ");
                }
                sb.setLength(sb.length() - 2);
            }
        }
        sb.append("}, ").append(ReferenceUtil.getMethodDescriptor(methodReference));

        return sb.toString();
    }

}
