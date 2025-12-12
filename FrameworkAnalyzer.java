import soot.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.options.Options;
import soot.jimple.*;
import soot.jimple.InvokeExpr;
import soot.jimple.NullConstant;

import java.io.File;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;


public class FrameworkAnalyzer {

    private static CallGraph cg;

    // Writer to dump call stacks / core paths
    private static PrintWriter pathWriter;

    public static void main(String[] args) {
        // --- CONFIGURATION ---
        // 1. Point this to your android.jar (SDK Stub)
        String androidJarPath = "android.jar";

        // 2. Point this to your services.apk
        String servicesJarPath = "framework/services.apk";

        // 3. (OPTIONAL BUT RECOMMENDED) Point this to framework.jar if you extracted it
        // This helps resolve types like "Context" or "IBinder" correctly.
        String frameworkJarPath = "framework/framework.jar";
        // ---------------------

        // Setup Soot
        setupSoot(androidJarPath, servicesJarPath, frameworkJarPath);

        // Identify Entry Points
        List<SootMethod> entryPoints = identifyAPIs();

        if (entryPoints.isEmpty()) {
            System.err.println("CRITICAL: Still no entry points found. The analysis cannot proceed.");
            return;
        }

        // Run SPARK
        System.out.println("Building Call Graph for " + entryPoints.size() + " entry points...");
        Scene.v().setEntryPoints(entryPoints);
        PackManager.v().getPack("cg").apply();
        cg = Scene.v().getCallGraph();

        // Initialize writer for dumping call stacks
        try {
            pathWriter = new PrintWriter(new BufferedWriter(new FileWriter("core_paths.txt")));
            System.out.println("Call stacks will be dumped to core_paths.txt");
        } catch (IOException e) {
            System.err.println("Failed to open core_paths.txt for writing: " + e.getMessage());
            // We can still proceed, but only console output will be available
            pathWriter = null;
        }

        // Traverse
        System.out.println("Starting Path Extraction...");
        for (SootMethod api : entryPoints) {
            extractCorePaths(api, new LinkedList<>(), new HashSet<>());
        }

        // Close writer
        if (pathWriter != null) {
            pathWriter.close();
            System.out.println("Finished writing call stacks to core_paths.txt");
        }

        dumpCallGraphPlain("callgraph.txt");
    }

    private static void dumpCallGraphPlain(String fileName) {
        if (cg == null) {
            System.err.println("CallGraph not initialized!");
            return;
        }

        // Map from caller -> set of callees
        Map<String, Set<String>> graph = new LinkedHashMap<>();

        // Iterate over all edges in the call graph
        Iterator<Edge> it = cg.iterator();
        while (it.hasNext()) {
            Edge e = it.next();

            String src = e.getSrc().method().getSignature();
            String tgt = e.getTgt().method().getSignature();

            // (Optional) only keep application → application edges
            if (!e.getSrc().method().getDeclaringClass().isApplicationClass()) continue;
            if (!e.getTgt().method().getDeclaringClass().isApplicationClass()) continue;

            graph.computeIfAbsent(src, k -> new LinkedHashSet<>()).add(tgt);
        }

        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(fileName)))) {
            for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
                String caller = entry.getKey();
                out.println("CALLER: " + caller);
                for (String callee : entry.getValue()) {
                    out.println("    -> " + callee);
                }
                out.println();
            }
        } catch (IOException e) {
            System.err.println("Error writing call graph to " + fileName + ": " + e.getMessage());
        }

        System.out.println("Plain call graph written to " + fileName);
    }

    public static void setupSoot(String androidJarPath, String servicesPath, String frameworkPath) {
        G.reset();
        Options.v().set_src_prec(Options.src_prec_apk);

        // Add services.apk AND framework.jar (if it exists) to process_dir
        List<String> processDirs = new ArrayList<>();
        processDirs.add(servicesPath);

        File fw = new File(frameworkPath);
        if (fw.exists()) {
            System.out.println("Including framework.jar in analysis for better type resolution.");
            processDirs.add(frameworkPath);
        } else {
            System.out.println("Warning: framework.jar not found. Type resolution might be incomplete.");
        }

        Options.v().set_process_dir(processDirs);
        Options.v().set_force_android_jar(androidJarPath);
        Options.v().set_whole_program(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().setPhaseOption("cg.spark", "on");
        Options.v().setPhaseOption("cg.spark", "string-constants:true");

        Scene.v().loadNecessaryClasses();
    }

    public static List<SootMethod> identifyAPIs() {
        System.out.println("Identifying API Entry Points...");
        Set<SootMethod> entryPoints = new HashSet<>();
        Set<SootClass> serviceClasses = new HashSet<>();

        int totalMethodsScanned = 0;
        int candidatesFound = 0;

        // --- STRATEGY 1: Strict Registration Logic ---
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            if (sc.isInterface() || sc.isPhantom()) continue;

            for (SootMethod sm : sc.getMethods()) {
                if (!sm.hasActiveBody()) continue;
                totalMethodsScanned++;

                try {
                    Body body = sm.getActiveBody();
                    for (Unit unit : body.getUnits()) {
                        Stmt stmt = (Stmt) unit;
                        if (!stmt.containsInvokeExpr()) continue;

                        InvokeExpr invoke = stmt.getInvokeExpr();
                        String methodName = invoke.getMethod().getName();

                        // Debug print to see if we are even seeing the right method names
                        if (methodName.equals("publishBinderService") || methodName.equals("addService")) {
                            candidatesFound++;
                            // Check arguments
                            if (invoke.getArgCount() >= 2) {
                                Value binderArg = invoke.getArg(1);
                                if (binderArg.getType() instanceof RefType) {
                                    SootClass binderClass = ((RefType) binderArg.getType()).getSootClass();
                                    serviceClasses.add(binderClass);
                                    System.out.println(" [MATCH] Found Service Registration: " + binderClass.getName());
                                } else {
                                    // This often fails if framework.jar is missing because Soot can't resolve the type
                                    System.out.println(" [DEBUG] Found " + methodName + " but arg1 was not RefType. Type: " + binderArg.getType());
                                }
                            }
                        }

                        // Receiver Logic
                        if (methodName.equals("registerReceiver") || methodName.equals("registerReceiverAsUser")) {
                            if (invoke.getArgCount() > 0) {
                                analyzeReceiverRegistration(invoke, entryPoints);
                            }
                        }
                    }
                } catch (Exception e) { /* ignore */ }
            }
        }

        // Collect methods from Strategy 1
        for (SootClass serviceClass : serviceClasses) {
            entryPoints.addAll(extractAIDLMethods(serviceClass));
        }

        // --- STRATEGY 2: Fallback Heuristic (If Strategy 1 failed) ---
        if (entryPoints.isEmpty()) {
            System.out.println("\n!!! Strict registration logic found 0 entries. Switching to Fallback Heuristic !!!");
            System.out.println("Scanning for all classes in 'com.android.server' that extend Binder...");

            for (SootClass sc : Scene.v().getApplicationClasses()) {
                // Heuristic: It's in the server package AND it looks like a Binder service
                if (sc.getName().startsWith("com.android.server") && !sc.isInterface() && !sc.isPhantom()) {
                    if (isBinder(sc)) {
                        List<SootMethod> methods = extractAIDLMethods(sc);
                        if (!methods.isEmpty()) {
                            System.out.println(" [FALLBACK] Found likely service: " + sc.getName());
                            entryPoints.addAll(methods);
                        }   
                    }
                }
            }
        }

        System.out.println("Total Entry Points Found: " + entryPoints.size());
        return new ArrayList<>(entryPoints);
    }

    // Check if class extends android.os.Binder
    private static boolean isBinder(SootClass sc) {
        if (sc.getName().equals("android.os.Binder")) return true;
        if (sc.hasSuperclass()) return isBinder(sc.getSuperclass());
        return false;
    }

    private static List<SootMethod> extractAIDLMethods(SootClass serviceClass) {
        List<SootMethod> aidlMethods = new ArrayList<>();
        List<SootClass> interfaces = getAllInterfaces(serviceClass);
        for (SootClass iface : interfaces) {
            if (isAIDLInterface(iface)) {
                for (SootMethod interfaceMethod : iface.getMethods()) {
                    SootMethod implementation = serviceClass.getMethodUnsafe(interfaceMethod.getSubSignature());
                    if (implementation != null) aidlMethods.add(implementation);
                }
            }
        }
        return aidlMethods;
    }

    private static boolean isAIDLInterface(SootClass sc) {
        if (sc.getName().equals("android.os.IInterface")) return true;
        for (SootClass parent : sc.getInterfaces()) {
            if (isAIDLInterface(parent)) return true;
        }
        return false;
    }

    private static List<SootClass> getAllInterfaces(SootClass sc) {
        List<SootClass> list = new ArrayList<>(sc.getInterfaces());
        if (sc.hasSuperclass()) list.addAll(getAllInterfaces(sc.getSuperclass()));
        return list;
    }

    private static void analyzeReceiverRegistration(InvokeExpr invoke, Set<SootMethod> entryPoints) {
        Value receiverArg = null;
        Value permissionArg = null;

        if (invoke.getArgCount() == 2) {
            receiverArg = invoke.getArg(0);
            permissionArg = null;
        } else if (invoke.getArgCount() >= 4) {
            receiverArg = invoke.getArg(0);
            permissionArg = invoke.getArg(2);
        }

        boolean isUnprotected = (permissionArg == null || permissionArg instanceof NullConstant);

        if (isUnprotected && receiverArg != null && receiverArg.getType() instanceof RefType) {
            SootClass receiverClass = ((RefType) receiverArg.getType()).getSootClass();
            SootMethod onReceive = receiverClass.getMethodUnsafe("void onReceive(android.content.Context,android.content.Intent)");
            if (onReceive != null) {
                System.out.println(" [MATCH] Found Unprotected Receiver: " + receiverClass.getName());
                entryPoints.add(onReceive);
            }
        }
    }

    public static void extractCorePaths(SootMethod method,
                                        LinkedList<String> currentPathTrace,
                                        Set<SootMethod> recursionStack) {
        if (recursionStack.contains(method)) return;
        recursionStack.add(method);

        if (!method.hasActiveBody()) {
            recursionStack.remove(method);
            return;
        }

        Body body = method.getActiveBody();
        for (Unit unit : body.getUnits()) {
            currentPathTrace.add(unit.toString());

            if (((Stmt) unit).containsInvokeExpr()) {
                Iterator<Edge> edges = cg.edgesOutOf(unit);
                while (edges.hasNext()) {
                    Edge edge = edges.next();
                    SootMethod target = edge.getTgt().method();
                    if (target.getDeclaringClass().isApplicationClass()) {
                        extractCorePaths(target, currentPathTrace, recursionStack);
                    }
                }
            }

            if (unit instanceof ReturnStmt || unit instanceof ReturnVoidStmt) {
                printPath(method, currentPathTrace);
            }
        }

        recursionStack.remove(method);
        if (!currentPathTrace.isEmpty()) currentPathTrace.removeLast();
    }

    private static void printPath(SootMethod root, List<String> trace) {
        // Console summary
        System.out.println("--- Core Path Found for " + root.getName() + " ---");
        if (trace.size() > 5) {
            System.out.println(trace.get(0));
            System.out.println("... (" + (trace.size() - 2) + " steps) ...");
            System.out.println(trace.get(trace.size() - 1));
        } else {
            System.out.println(trace);
        }

        // Full dump to file
        if (pathWriter != null) {
            pathWriter.println("=== Core Path for " + root.getSignature() + " ===");
            for (String stmt : trace) {
                pathWriter.println("  " + stmt);
            }
            pathWriter.println();
            pathWriter.flush();
        }
    }
}
