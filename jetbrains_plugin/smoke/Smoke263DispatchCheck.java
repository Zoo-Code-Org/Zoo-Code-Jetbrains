import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Headless proof for the 2026.3 terminal compatibility, run by the
 * smoke263Dispatch Gradle task against an unpacked ideaIC 263.5701.42.
 * It checks the facts that the Plugin Verifier cannot see:
 * <ul>
 *   <li>the platform entry point createTtyConnector(ShellStartupOptions) exists
 *       on LocalTerminalDirectRunner and is overridable there (public, not
 *       static, not private, not final),</li>
 *   <li>the custom runner is a direct LocalTerminalDirectRunner subclass whose
 *       createTtyConnector with the identical descriptor can override it. The
 *       JVM dispatch rule that matters here is: same name and descriptor, the
 *       subclass method is public or protected and not static, the platform
 *       method is not final, static or private. A final subclass method is a
 *       valid override; it only cannot itself be overridden, which nothing
 *       needs to do,</li>
 *   <li>no packaged class references LocalTerminalDirectRunner#createProcess,
 *       whose 2023.3 descriptor does not resolve on 2026.3,</li>
 *   <li>RawOutputTtyConnector decodes and forwards output when linked against
 *       this IDE build's jediterm and pty4j binaries.</li>
 * </ul>
 *
 * The 2026.3 platform classes are Java 25 bytecode, so they are parsed as
 * bytes and never loaded. The execution part uses only the IDE libraries
 * that ship as Java 8 or Java 11 bytecode: jediterm, pty4j and util-8, which
 * carries the platform Logger, AppExecutorUtil and the Kotlin runtime.
 */
public class Smoke263DispatchCheck {

    private static final String RUNNER_CLASS = "org/jetbrains/plugins/terminal/LocalTerminalDirectRunner";
    private static final String DISPATCH_DESCRIPTOR =
        "(Lorg/jetbrains/plugins/terminal/ShellStartupOptions;)Lcom/jediterm/terminal/TtyConnector;";
    private static final String BANNED_REF_PREFIX =
        "org/jetbrains/plugins/terminal/LocalTerminalDirectRunner#createProcess#";
    private static final String MULTIBYTE = "naive — 你好, τξζ, 🚀 smoke";

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_SUPER = 0x0020;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_INTERFACE = 0x0200;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--scan-jar".equals(args[0])) {
            checkNoBannedRefs(Paths.get(args[1]));
            System.out.println("[smoke-263] scan OK: no packaged reference to " + BANNED_REF_PREFIX + "*");
            return;
        }
        Path pluginJar = Paths.get(args[0]);
        Path terminalJar = Paths.get(args[1]);
        for (Path path : new Path[]{pluginJar, terminalJar}) {
            if (!Files.isRegularFile(path)) {
                throw new AssertionError("Required jar not found: " + path);
            }
        }

        checkPlatformEntryPoint(terminalJar);
        checkDispatchBinds(pluginJar);
        checkNoBannedRefs(pluginJar);
        checkConnectorForwards();

        System.out.println("[smoke-263] OK: dispatch binds and output forwarding works against the 2026.3 IDE jars");
    }

    private static void checkPlatformEntryPoint(Path terminalJar) throws Exception {
        byte[] runnerBytes = readClass(terminalJar, RUNNER_CLASS + ".class");
        ClassFile runner = ClassFile.parse(runnerBytes);
        if ((runner.access & ACC_INTERFACE) != 0) {
            throw new AssertionError(RUNNER_CLASS + " became an interface");
        }

        String[] entryPoint = runner.findMethod("createTtyConnector", DISPATCH_DESCRIPTOR);
        if (entryPoint == null) {
            throw new AssertionError(
                RUNNER_CLASS + " does not declare createTtyConnector" + DISPATCH_DESCRIPTOR
                    + "; the 2026.3 entry point moved elsewhere");
        }
        checkOverridable(RUNNER_CLASS, entryPoint);

        // The compatibility premise: the 2023.3 signature returns PtyProcess and the
        // 2026.3 signature returns java.lang.Process. Both facts must hold, otherwise
        // this smoke check no longer describes the platforms it claims to compare.
        if (runner.findMethod("createProcess",
                "(Lorg/jetbrains/plugins/terminal/ShellStartupOptions;)Ljava/lang/Process;") == null) {
            throw new AssertionError(
                RUNNER_CLASS + " no longer declares createProcess(ShellStartupOptions) returning "
                    + "java.lang.Process; the premise of the compatibility patch changed");
        }
        if (runner.findMethod("createProcess",
                "(Lorg/jetbrains/plugins/terminal/ShellStartupOptions;)Lcom/pty4j/PtyProcess;") != null) {
            throw new AssertionError(
                "createProcess(ShellStartupOptions) still returns PtyProcess on this IDE; "
                    + "the premise of the compatibility patch changed");
        }
        System.out.println("[smoke-263] platform entry point: createTtyConnector" + DISPATCH_DESCRIPTOR
            + " on " + RUNNER_CLASS + " is public, non-static and non-final");
    }

    /**
     * An entry point the subclass must replace has to be callable dispatch
     * around: public, not static, not private and not final. A private or
     * static platform method would never dispatch, and a final one would send
     * every 2026.3 session to the platform implementation.
     */
    private static void checkOverridable(String owner, String[] method) {
        int access = Integer.parseInt(method[2]);
        List<String> blockers = new ArrayList<>();
        if ((access & ACC_PUBLIC) == 0) blockers.add("not public");
        if ((access & 0x0008) != 0) blockers.add("static");
        if ((access & 0x0002) != 0) blockers.add("private");
        if ((access & ACC_FINAL) != 0) blockers.add("final");
        if (!blockers.isEmpty()) {
            throw new AssertionError(owner + ".createTtyConnector" + DISPATCH_DESCRIPTOR
                + " cannot be overridden: " + String.join(", ", blockers));
        }
    }

    private static void checkDispatchBinds(Path pluginJar) throws Exception {
        String customRunner = null;
        try (JarFile jar = new JarFile(pluginJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                ClassFile parsed = ClassFile.parse(jar.getInputStream(entry).readAllBytes());
                if (!RUNNER_CLASS.equals(parsed.superName) || parsed.className.equals(RUNNER_CLASS)) {
                    continue;
                }
                if ((parsed.access & ACC_ABSTRACT) != 0 || (parsed.access & ACC_INTERFACE) != 0) {
                    throw new AssertionError(parsed.className
                        + " extends " + RUNNER_CLASS + " but is abstract or an interface;"
                        + " no object of it can receive the dispatch");
                }
                String[] override = parsed.findMethod("createTtyConnector", DISPATCH_DESCRIPTOR);
                if (override != null) {
                    customRunner = parsed.className;
                    int access = Integer.parseInt(override[2]);
                    List<String> blockers = new ArrayList<>();
                    if ((access & ACC_PUBLIC) == 0) blockers.add("not public");
                    if ((access & 0x0008) != 0) blockers.add("static");
                    if ((access & 0x0002) != 0) blockers.add("private");
                    if (!blockers.isEmpty()) {
                        throw new AssertionError(parsed.className + ".createTtyConnector"
                            + DISPATCH_DESCRIPTOR + " cannot override: " + String.join(", ", blockers));
                    }
                    // A final subclass method is a valid override; it only cannot
                    // itself be overridden, which the platform never does.
                    boolean finalMethod = (access & ACC_FINAL) != 0;
                    System.out.println("[smoke-263] dispatch binds to override: "
                        + parsed.className.replace('/', '.') + ".createTtyConnector"
                        + DISPATCH_DESCRIPTOR + (finalMethod ? " (final method, valid override)" : ""));
                }
            }
        }
        if (customRunner == null) {
            throw new AssertionError(
                "No LocalTerminalDirectRunner subclass declares createTtyConnector" + DISPATCH_DESCRIPTOR
                    + "; the platform default connector would run on 2026.3");
        }
    }

    private static void checkNoBannedRefs(Path pluginJar) throws Exception {
        List<String> violations = new ArrayList<>();
        try (JarFile jar = new JarFile(pluginJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                ClassFile parsed = ClassFile.parse(jar.getInputStream(entry).readAllBytes());
                for (String ref : parsed.memberRefs) {
                    if (ref.startsWith(BANNED_REF_PREFIX)) {
                        violations.add(entry.getName() + ": " + ref);
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new AssertionError(
                "Packaged bytecode references LocalTerminalDirectRunner.createProcess. IntelliJ "
                    + "2026.3 changed that method to return java.lang.Process, so a 2023.3 call "
                    + "site does not resolve:\n" + String.join("\n", violations)
                    + "\nStart the process through TerminalInstance.startTerminalProcess instead.");
        }
    }

    private static void checkConnectorForwards() throws Exception {
        Class<?> connectorClass = Class.forName("org.zoocode.jetbrains.terminal.RawOutputTtyConnector");
        Class<?> callbackClass = Class.forName("org.zoocode.jetbrains.terminal.TerminalRawDataCallback");
        Class<?> processClass = Class.forName("com.pty4j.PtyProcess");
        Class<?> charsetClass = Charset.class;

        Constructor<?> constructor = connectorClass.getConstructor(processClass, charsetClass, callbackClass);
        if (!java.lang.reflect.Modifier.isPublic(constructor.getModifiers())) {
            throw new AssertionError("RawOutputTtyConnector constructor is not public");
        }

        StringBuilder forwarded = new StringBuilder();
        Object callback = Proxy.newProxyInstance(
            callbackClass.getClassLoader(),
            new Class<?>[]{callbackClass},
            (proxy, method, methodArgs) -> {
                if ("onRawData".equals(method.getName())) {
                    forwarded.append((String) methodArgs[0]);
                }
                return null;
            });

        FakePtyProcess process = new FakePtyProcess(
            new java.io.ByteArrayInputStream(MULTIBYTE.getBytes(StandardCharsets.UTF_8)),
            new java.io.ByteArrayOutputStream());
        Object connector = constructor.newInstance(process, StandardCharsets.UTF_8, callback);

        Method read = connectorClass.getMethod("read", char[].class, int.class, int.class);
        char[] buffer = new char[4];
        StringBuilder decoded = new StringBuilder();
        while (true) {
            int count = (Integer) read.invoke(connector, buffer, 0, buffer.length);
            if (count < 0) {
                break;
            }
            decoded.append(buffer, 0, count);
        }

        if (!MULTIBYTE.contentEquals(decoded)) {
            throw new AssertionError("Connector read() returned " + decoded + " instead of " + MULTIBYTE);
        }
        if (!MULTIBYTE.contentEquals(forwarded)) {
            throw new AssertionError("Connector forwarded " + forwarded + " instead of " + MULTIBYTE);
        }
        System.out.println("[smoke-263] RawOutputTtyConnector decoded and forwarded "
            + decoded.length() + " chars on the 2026.3 jediterm and pty4j binaries");
    }

    private static byte[] readClass(Path jarPath, String entryName) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) {
                throw new AssertionError(entryName + " not found in " + jarPath);
            }
            return jar.getInputStream(entry).readAllBytes();
        }
    }

    public static class FakePtyProcess extends com.pty4j.PtyProcess {
        private final java.io.InputStream input;
        private final java.io.OutputStream output;
        private boolean destroyed;

        FakePtyProcess(java.io.InputStream input, java.io.OutputStream output) {
            this.input = input;
            this.output = output;
        }

        @Override public java.io.InputStream getInputStream() { return input; }
        @Override public java.io.InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.OutputStream getOutputStream() { return output; }
        @Override public boolean isAlive() { return !destroyed; }
        @Override public long pid() { return 1; }
        @Override public int exitValue() { return 0; }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public void destroy() { destroyed = true; }
        @Override public Process destroyForcibly() { return this; }
        @Override public ProcessHandle.Info info() { return ProcessHandle.current().info(); }
        @Override public java.util.stream.Stream<ProcessHandle> children() { return ProcessHandle.current().children(); }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() { return ProcessHandle.current().descendants(); }
        @Override public ProcessHandle toHandle() { return ProcessHandle.current(); }
        @Override public java.util.concurrent.CompletableFuture<Process> onExit() {
            return java.util.concurrent.CompletableFuture.completedFuture(this);
        }
        @Override public void setWinSize(com.pty4j.WinSize winSize) { }
        @Override public com.pty4j.WinSize getWinSize() { return new com.pty4j.WinSize(80, 24); }
        @Override public boolean isConsoleMode() { return false; }
    }

    /**
     * Minimal class-file reader for the class and method access flags, the
     * superclass, method descriptors and member references.
     */
    static final class ClassFile {
        int access;
        String className;
        String superName;
        List<String[]> methods = new ArrayList<>();
        List<String> memberRefs = new ArrayList<>();

        String[] findMethod(String name, String descriptor) {
            for (String[] method : methods) {
                if (method[0].equals(name) && method[1].equals(descriptor)) {
                    return method;
                }
            }
            return null;
        }

        static ClassFile parse(byte[] d) {
            Cur c = new Cur(d);
            c.p = 8;
            int count = c.u2();
            String[] utf8 = new String[count];
            int[] classNameIndex = new int[count];
            int[] memberClassIndex = new int[count];
            int[] memberNameAndTypeIndex = new int[count];
            int[] nameAndTypeNameIndex = new int[count];
            int[] nameAndTypeDescriptorIndex = new int[count];

            int slot = 1;
            while (slot < count) {
                int tag = c.u1();
                switch (tag) {
                    case 1 -> {
                        int length = c.u2();
                        utf8[slot] = new String(d, c.p, length, StandardCharsets.UTF_8);
                        c.p += length;
                    }
                    case 3, 4 -> c.p += 4;
                    case 5, 6 -> {
                        c.p += 8;
                        slot++;
                    }
                    case 7 -> classNameIndex[slot] = c.u2();
                    case 8, 16, 19, 20 -> c.p += 2;
                    case 9 -> c.p += 4;
                    case 10, 11 -> {
                        memberClassIndex[slot] = c.u2();
                        memberNameAndTypeIndex[slot] = c.u2();
                    }
                    case 12 -> {
                        nameAndTypeNameIndex[slot] = c.u2();
                        nameAndTypeDescriptorIndex[slot] = c.u2();
                    }
                    case 15 -> c.p += 3;
                    case 17, 18 -> c.p += 4;
                    default -> throw new AssertionError("Unknown constant pool tag " + tag + " at byte " + (c.p - 1));
                }
                slot++;
            }

            ClassFile result = new ClassFile();
            result.access = c.u2();
            int thisIndex = c.u2();
            int superIndex = c.u2();
            result.className = utf8[classNameIndex[thisIndex]];
            result.superName = superIndex == 0 ? null : utf8[classNameIndex[superIndex]];

            int interfaceCount = c.u2();
            c.p += 2 * interfaceCount;

            int fieldCount = c.u2();
            for (int i = 0; i < fieldCount; i++) {
                c.p += 6;
                skipAttributes(c);
            }

            int methodCount = c.u2();
            for (int i = 0; i < methodCount; i++) {
                int methodAccess = c.u2();
                int nameIndex = c.u2();
                int descriptorIndex = c.u2();
                result.methods.add(new String[]{
                    utf8[nameIndex], utf8[descriptorIndex], Integer.toString(methodAccess)});
                skipAttributes(c);
            }

            for (int entry = 1; entry < count; entry++) {
                int classSlot = memberClassIndex[entry];
                int nameAndTypeSlot = memberNameAndTypeIndex[entry];
                if (classSlot == 0 || nameAndTypeSlot == 0) {
                    continue;
                }
                String owner = utf8[classNameIndex[classSlot]];
                String name = utf8[nameAndTypeNameIndex[nameAndTypeSlot]];
                String descriptor = utf8[nameAndTypeDescriptorIndex[nameAndTypeSlot]];
                result.memberRefs.add(owner + "#" + name + "#" + descriptor);
            }
            return result;
        }

        private static void skipAttributes(Cur c) {
            int n = c.u2();
            for (int i = 0; i < n; i++) {
                c.u2();
                int length = (c.u2() << 16) | c.u2();
                c.p += length;
            }
        }
    }

    static final class Cur {
        final byte[] d;
        int p;

        Cur(byte[] d) {
            this.d = d;
        }

        int u1() {
            return d[p++] & 0xFF;
        }

        int u2() {
            int v = ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
            p += 2;
            return v;
        }
    }
}
