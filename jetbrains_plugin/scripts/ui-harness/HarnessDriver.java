package org.zoocode.harnessdriver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TEST-ONLY harness driver. Installed only into disposable harness IDE profiles
 * under jetbrains_plugin/build/ui-harness. Never shipped, never registered by the
 * Zoo Code plugin. Observes the Zoo Code tool window and records evidence.
 * All Zoo plugin and JCEF classes are accessed by reflection so this plugin keeps
 * loading when the JCEF classes are absent.
 */
public final class HarnessDriver implements AppLifecycleListener {
    private static final Logger LOG = Logger.getInstance(HarnessDriver.class);
    private static final String TOOL_WINDOW_ID = "Zoo Code JetBrains";
    // The only commands the fixture prompts ask for. Anything else fails the run.
    private static final java.util.regex.Pattern FIXTURE_COMMAND = java.util.regex.Pattern.compile(
        "printf SOLHEIM_(FIRST|SECOND|THIRD)(; sleep [0-9]+)?(; cat src/Main\\.java)?");
    private long lastApprovedTs = -1;
    private final java.util.Set<String> approvedCommands = new java.util.HashSet<>();
    private final boolean fixtureMode = Boolean.getBoolean("zoo.harness.fixture");
    private final boolean windowsLive = !fixtureMode && System.getProperty("os.name", "").startsWith("Windows");

    private String windowsCommand(String step) {
        return "powershell.exe -NoProfile -File .focus-ci/" + step + ".ps1";
    }

    private final Object evidenceLock = new Object();
    private Path evidenceDir;
    private Path progressLog;
    private List<String> checks = new ArrayList<>();
    private volatile boolean startupComplete;

    @Override
    public void appFrameCreated(List<String> commandLineArgs) {
        String dir = System.getProperty("zoo.harness.evidence");
        if (dir == null) {
            return;
        }
        evidenceDir = Paths.get(dir);
        progressLog = evidenceDir.resolve("progress.log");
        log("app frame created, harness driver active, args=" + commandLineArgs);
        AppExecutorUtil.getAppExecutorService().submit(() -> dismissStartupDialogs(300));
        AppExecutorUtil.getAppExecutorService().submit(this::waitForProjectAndRun);
    }

    private void waitForProjectAndRun() {
        String mode = "P";
        long deadline = System.currentTimeMillis() + 600_000;
        Project project = null;
        while (System.currentTimeMillis() < deadline) {
            Project[] open = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            if (open.length > 0 && open[0].isOpen() && !open[0].isDisposed()) {
                project = open[0];
                break;
            }
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (project == null) {
            log("no project opened within timeout");
            return;
        }
        log("project detected: " + project.getName() + ", mode=" + mode);
        run(project, mode);
    }

    private void run(Project project, String mode) {
        log("driver started, mode=" + mode);
        try {
            record("jcefState", reflectJcefSupport());
            record("ideBuild", com.intellij.openapi.application.ApplicationInfo.getInstance().getBuild().asString());
            record("javaVersion", System.getProperty("java.version"));

            ToolWindow tw = (ToolWindow) poll(120_000, () -> {
                ToolWindow w = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
                return w != null ? w : null;
            });
            if (tw == null) {
                fail("toolWindowNotFound", "tool window " + TOOL_WINDOW_ID + " did not appear");
                return;
            }
            runOnEdt(() -> tw.show(null));
            waitEdt();
            Thread.sleep(2_000);
            screenshot("01-toolwindow-shown");
            record("toolWindowShown", true);

            runRealConversation(project);
        } catch (Throwable t) {
            fail("driverException", String.valueOf(t));
        }
    }

    // ---- Test A: real JCEF browser render plus JS round trip ---------------

    private ClassLoader zooClassLoader() throws Exception {
        Object plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("ZooCode"));
        if (plugin == null) {
            throw new IllegalStateException("ZooCode plugin not found");
        }
        return (ClassLoader) plugin.getClass().getMethod("getPluginClassLoader").invoke(plugin);
    }

    private Class<?> zooClass(String name) throws Exception {
        return Class.forName(name, true, zooClassLoader());
    }

    private boolean addTitleHandler(Object jbCefBrowser, Object cefBrowser, AtomicReference<String> capturedTitle) {
        try {
            Object jbCefClient = invoke(jbCefBrowser, "getJBCefClient");
            // Newer IDEs isolate JCEF from this test plugin's class loader.
            // Use the interface exposed by the running client's API so the proxy
            // has the exact type identity required by addDisplayHandler.
            Method register = null;
            for (Method method : jbCefClient.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("addDisplayHandler") && parameters.length == 2 &&
                        parameters[0].getName().equals("org.cef.handler.CefDisplayHandler")) {
                    register = method;
                    break;
                }
            }
            if (register == null) throw new IllegalStateException("JCEF addDisplayHandler API unavailable");
            Class<?> handlerIfc = register.getParameterTypes()[0];
            Object proxy = Proxy.newProxyInstance(handlerIfc.getClassLoader(), new Class<?>[]{handlerIfc},
                    (p, m, a) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                            if (m.getName().equals("equals")) return p == a[0];
                            if (m.getName().equals("toString")) return "Zoo focus harness display handler";
                        }
                        if ("onTitleChange".equals(m.getName()) && a.length >= 2 && a[1] instanceof String) {
                            capturedTitle.set((String) a[1]);
                            if (((String)a[1]).startsWith("ZFE2E:")) {
                                JsonObject reply=JsonParser.parseString(((String)a[1]).substring(6)).getAsJsonObject();
                                realReplies.put(reply.get("snapshot").getAsInt(),reply);
                            }
                            log("TITLE-EVENT " + a[1]);
                        }
                        if ("onConsoleMessage".equals(m.getName()) && a.length >= 4) {
                            String msg = String.valueOf(a[2]);
                            log("PAGE-CONSOLE " + a[1] + " " + msg);
                            if (msg.startsWith("ZFC:")) {
                                consoleLines.add(msg);
                            }
                        }
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) {
                            return Boolean.FALSE;
                        }
                        if (rt == int.class) {
                            return 0;
                        }
                        if (rt == long.class) {
                            return 0L;
                        }
                        return null;
                    });
            register.invoke(jbCefClient, proxy, cefBrowser);
            return true;
        } catch (Throwable t) {
            LOG.warn("title handler attach failed", t);
            return false;
        }
    }

    private long batteryStart = 0;
    private final java.util.List<String> consoleLines = java.util.Collections.synchronizedList(new ArrayList<>());

    private void installFocusRecorder() {
        batteryStart = System.currentTimeMillis();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener(evt -> {
            if (evt.getPropertyName().contains("focusOwner") || evt.getPropertyName().contains("activeWindow")) {
                StringWriter stack = new StringWriter();
                new Throwable("focusOwner transition").printStackTrace(new PrintWriter(stack));
                log("FOCUS-STACK " + stack);
                log("FOCUS-TIME ts=" + (System.currentTimeMillis() - batteryStart)
                        + " prop=" + evt.getPropertyName()
                        + " old=" + compName(evt.getOldValue())
                        + " new=" + compName(evt.getNewValue()));
            }
        });
        log("focus recorder installed");
    }

    private static String compName(Object c) {
        return c instanceof Component ? ((Component) c).getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(c)) : String.valueOf(c);
    }

    private void mark(String step) {
        log("BATTERY-MARK ts=" + (System.currentTimeMillis() - batteryStart) + " step=" + step);
    }

    private int realSequence=0;
    private final java.util.concurrent.ConcurrentHashMap<Integer,JsonObject> realReplies=new java.util.concurrent.ConcurrentHashMap<>();

    // Manual provider-backed battery: real React textarea, real extension tools.
    private JsonObject realSnapshot(Object webview, AtomicReference<String> title) throws Exception {
        int id=++realSequence;
        invoke(webview, "executeJavaScript", "document.title='ZFE2E:' + JSON.stringify((()=>{let e=[...document.querySelectorAll('textarea')].find(x=>x.offsetWidth&&x.offsetHeight);let r=e&&e.getBoundingClientRect();let z=window.__focusE2E||{};let m=z.messages||[];let last=m[m.length-1]||{};return {snapshot:"+id+",input:!!e,value:e?e.value:null,active:e===document.activeElement,windowFocused:document.hasFocus(),disabled:e?e.disabled:null,rect:r?{x:r.x,y:r.y,w:r.width,h:r.height}:null,messages:m.length,events:z.events||0,partial:z.partial||0,lastAsk:last.ask||null,lastTs:last.ts||null,lastText:typeof last.text==='string'?last.text.slice(0,300):null,lastSay:last.say||null,lastPartial:!!last.partial,provider:z.provider||null,body:document.body.innerText.slice(-1200)};})());");
        long deadline=System.currentTimeMillis()+30000;
        while(System.currentTimeMillis()<deadline) {
            JsonObject reply=realReplies.remove(id);
            if(reply!=null)return reply;
            Thread.sleep(50);
        }
        throw new IllegalStateException("real DOM snapshot unavailable; pageLoaded="+invoke(webview,"isPageLoaded")+" disposed="+invoke(webview,"isDisposed"));
    }

    private void realSend(Object webview, String json) throws Exception {
        invoke(webview, "executeJavaScript", "window.sendMessageToPlugin(" + json + ");");
    }

    private void runRealConversation(Project project) throws Exception {
        installFocusRecorder();
        com.intellij.ide.IdeEventQueue.EventDispatcher pre = event -> {
            if (event instanceof KeyEvent) {
                KeyEvent k=(KeyEvent)event;
                if (k.getKeyCode()==KeyEvent.VK_X || k.getKeyCode()==KeyEvent.VK_Y || k.getKeyChar()=='x' || k.getKeyChar()=='y')
                    log("E2EAWT PRE ts="+System.currentTimeMillis()+" when="+k.getWhen()+" id="+k.getID()+" key="+k.getKeyCode()+" char="+((int)k.getKeyChar())+" consumed="+k.isConsumed()+" source="+compName(k.getSource())+" owner="+compName(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
            }
            return false;
        };
        com.intellij.ide.IdeEventQueue.EventDispatcher post = event -> {
            if (event instanceof KeyEvent) {
                KeyEvent k=(KeyEvent)event;
                if (k.getKeyCode()==KeyEvent.VK_X || k.getKeyCode()==KeyEvent.VK_Y || k.getKeyChar()=='x' || k.getKeyChar()=='y')
                    log("E2EAWT POST ts="+System.currentTimeMillis()+" when="+k.getWhen()+" id="+k.getID()+" key="+k.getKeyCode()+" consumed="+k.isConsumed()+" source="+compName(k.getSource()));
            }
            return false;
        };
        runOnEdt(()->{com.intellij.ide.IdeEventQueue.getInstance().addPreprocessor(pre,project);com.intellij.ide.IdeEventQueue.getInstance().addPostprocessor(post,project);});
        Object manager = project.getService(zooClass("org.zoocode.jetbrains.webview.WebViewManager"));
        // Startup can replace a webview. Select the loaded, mounted browser after
        // indexing rather than waiting forever on an instance that was retired.
        Object webview = poll(180000, () -> {
            Object candidate = invoke(manager, "getLatestWebView");
            if (candidate == null || !Boolean.TRUE.equals(invoke(candidate, "isPageLoaded")) ||
                    Boolean.TRUE.equals(invoke(candidate, "isDisposed")) ||
                    com.intellij.openapi.project.DumbService.getInstance(project).isDumb()) return null;
            final boolean[] showing = {false};
            runOnEdt(() -> showing[0] = ((Component) invoke(field(candidate, "browser"), "getComponent")).isShowing());
            return showing[0] ? candidate : null;
        });
        if (webview == null) throw new IllegalStateException("No loaded, mounted chat browser after indexing");
        startupComplete = true;
        Object browser = field(webview, "browser");
        AtomicReference<String> title = new AtomicReference<>();
        if (!addTitleHandler(browser, invoke(browser, "getCefBrowser"), title)) {
            throw new IllegalStateException("Cannot register JCEF snapshot handler; see IDE log for cause");
        }
        invoke(webview, "executeJavaScript", "window.__focusE2E={messages:[],events:0,partial:0};window.addEventListener('message',e=>{let d=e.data,z=window.__focusE2E;if(!d)return;z.events++;if(d.type==='state'&&d.state){if(d.state.clineMessages)z.messages=d.state.clineMessages.slice();z.provider=d.state.apiConfiguration&&d.state.apiConfiguration.apiProvider;}if(d.type==='messageUpdated'||d.type==='partialMessage'){let m=d.clineMessage||d.partialMessage;if(m){if(m.partial)z.partial++;let i=z.messages.findIndex(x=>x.ts===m.ts);if(i>=0)z.messages[i]=m;else z.messages.push(m);}}});");
        invoke(webview,"executeJavaScript","window.__focusNodeSeq=0;function e2eNode(e){if(!e)return null;if(!e.__e2eid)e.__e2eid=++window.__focusNodeSeq;return e.tagName+'@'+e.__e2eid;}for(let kind of ['keydown','input','focusin','focusout'])document.addEventListener(kind,e=>{if(kind==='keydown'&&!['x','y'].includes(e.key))return;console.info('E2EKEY:'+JSON.stringify({kind,ts:Date.now(),key:e.key||null,mod:e.ctrlKey||e.altKey||e.metaKey||false,node:e2eNode(e.target),active:e2eNode(document.activeElement),hasFocus:document.hasFocus(),length:typeof e.target.value==='string'?e.target.value.length:null}));},true);for(let kind of ['focus','blur'])window.addEventListener(kind,e=>console.info('E2EWINDOW:'+JSON.stringify({kind,ts:Date.now(),active:e2eNode(document.activeElement),hasFocus:document.hasFocus()})),true);");
        realSend(webview, "{\"type\":\"webviewDidLaunch\"}");
        realSend(webview, "{\"type\":\"mode\",\"text\":\"code\"}");
        Thread.sleep(1500);
        String configuration = "{\"type\":\"upsertApiConfiguration\",\"text\":\"focus-solheim\",\"apiConfiguration\":{\"apiProvider\":\"openai\",\"openAiBaseUrl\":\"http://127.0.0.1:19487/v1\",\"openAiApiKey\":\"LOCAL_TOKEN\",\"openAiModelId\":\"qwen3.8-27b\",\"openAiStrictToolSchemas\":false,\"openAiExtraBody\":\"{\\\"chat_template_kwargs\\\":{\\\"enable_thinking\\\":false}}\",\"openAiCustomModelInfo\":{\"contextWindow\":131072,\"maxTokens\":4096,\"supportsImages\":false,\"supportsPromptCache\":false,\"supportsNativeTools\":true,\"inputPrice\":0,\"outputPrice\":0}}}";
        JsonObject config = JsonParser.parseString(configuration).getAsJsonObject();
        JsonObject apiConfig = config.getAsJsonObject("apiConfiguration");
        apiConfig.addProperty("openAiApiKey", fixtureMode ? "focus-fixture-local-only" : System.getenv("SOLHEIM_RELAY_TOKEN"));
        if (fixtureMode) {
            apiConfig.addProperty("openAiModelId", "focus-fixture");
            apiConfig.remove("openAiExtraBody");
        }
        if (fixtureMode) config.addProperty("text", "focus-fixture");
        realSend(webview, config.toString());
        realSend(webview, "{\"type\":\"updateSettings\",\"updatedSettings\":{\"autoApprovalEnabled\":true,\"alwaysAllowReadOnly\":true,\"alwaysAllowWrite\":true,\"alwaysAllowExecute\":true,\"allowedCommands\":[\"printf\",\"sleep\",\"cat\",\"pwd\"],\"terminalShellIntegrationDisabled\":false,\"destructiveCommandGuardEnabled\":false,\"maxRequests\":20,\"telemetrySetting\":\"disabled\"}}");
        if (!fixtureMode) realSend(webview, "{\"type\":\"updateSettings\",\"updatedSettings\":{\"alwaysAllowExecute\":false}}");
        realSend(webview, "{\"type\":\"didShowAnnouncement\"}");
        invoke(webview, "executeJavaScript", "[...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='Close')?.click();");
        JsonObject snap = null;
        for (int i=0;i<30;i++) { Thread.sleep(1000); snap=realSnapshot(webview,title); if(snap.get("input").getAsBoolean()&&"openai".equals(snap.get("provider").isJsonNull()?"":snap.get("provider").getAsString()))break; }
        record("P_configuredDOM", snap);
        if (!snap.get("input").getAsBoolean()) { fail("realInputMissing", "real React textarea was not rendered"); return; }
        runOnEdt(() -> ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID).show(null));
        Thread.sleep(1000);
        Object ready = poll(180000, () -> {
            final boolean[] showing = {false};
            runOnEdt(() -> showing[0] = ((Component) invoke(browser, "getComponent")).isShowing());
            return showing[0] && !com.intellij.openapi.project.DumbService.getInstance(project).isDumb();
        });
        if (!Boolean.TRUE.equals(ready)) throw new IllegalStateException("Chat remained hidden during indexing");
        snap = realSnapshot(webview,title);
        screenshot("p-configured");
        clickInBrowser(browser, snap.getAsJsonObject("rect"));
        {
            typeText("smoke");
            Thread.sleep(500);
            JsonObject smoke = realSnapshot(webview, title);
            record("P_nativeChatTypingSmoke", smoke);
            if (!"smoke".equals(smoke.get("value").getAsString()) ||
                    !smoke.get("active").getAsBoolean() || !smoke.get("windowFocused").getAsBoolean()) {
                fail("nativeChatTypingUnavailable", "Native keys did not reach the focused React textarea");
                return;
            }
            Robot clear = new Robot();
            clear.keyPress(KeyEvent.VK_CONTROL); clear.keyPress(KeyEvent.VK_A);
            clear.keyRelease(KeyEvent.VK_A); clear.keyRelease(KeyEvent.VK_CONTROL);
            clear.keyPress(KeyEvent.VK_BACK_SPACE); clear.keyRelease(KeyEvent.VK_BACK_SPACE);
            Thread.sleep(300);
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
            new java.awt.datatransfer.StringSelection(fixtureMode ? "Stream the fixture response and finish with attempt_completion." : windowsLive ? "Use tools in this throwaway Windows workspace. Run execute_command with exactly: " + windowsCommand("first") + ". Read src/Main.java, then use apply_diff to replace throwaway with solheim_e2e. Run execute_command with exactly: " + windowsCommand("second") + ". Do not edit .focus-ci or files outside this workspace. Explain each step and finish with attempt_completion. Do not ask questions." : "Use tools in this throwaway workspace: execute_command printf SOLHEIM_FIRST; sleep 5. Read src/Main.java, then apply_diff replacing throwaway with solheim_e2e. Execute printf SOLHEIM_SECOND; sleep 5; cat src/Main.java. No files outside this workspace. Briefly explain each step and finish with attempt_completion. Do not ask questions."),null);
        Robot robot = new Robot();
        robot.setAutoDelay(90);
        robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V); robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(1000);
        record("P_promptEntered",realSnapshot(webview,title));
        robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER);
        mark("P-real-conversation-submitted");
        Thread.sleep(2000);
        snap = realSnapshot(webview,title);
        if (!snap.get("value").isJsonNull() && !snap.get("value").getAsString().isEmpty()) { record("P_afterSubmit",snap); fail("realSubmitFailed","Enter did not submit the real chat input");return; }
        if (snap.get("messages").getAsInt()==0) { record("P_afterSubmit",snap); fail("realTaskMissing","No task appeared after submitting prompt");return; }
        // Establish a single focus point before tools run; subsequent sampling never refocuses.
        clickInBrowser(browser,snap.getAsJsonObject("rect"));
        String expected="";
        boolean typingHealthy=true;
        boolean focusHealthy=true;
        int initialPartial=snap.get("partial").getAsInt();
        boolean completed=false;
        int initialMessages=snap.get("messages").getAsInt();
        long deadline=System.currentTimeMillis()+240000;
        int sample=0;
        while(System.currentTimeMillis()<deadline) {
            typeText("x"); expected += "x";
            Thread.sleep(2000);
            snap=realSnapshot(webview,title);
            boolean exact=expected.equals(snap.get("value").isJsonNull()?null:snap.get("value").getAsString());
            typingHealthy &= exact;
            focusHealthy &= snap.get("active").getAsBoolean() && snap.get("windowFocused").getAsBoolean();
            record("P_sample_"+(sample++),snap);
            record("P_expected_"+sample,expected);
            record("P_owner_"+sample,swingFocusOwnerIdentity());
            if (!exact) record("P_typingLossAtSample",sample);
            String ask=snap.get("lastAsk").isJsonNull()?"":snap.get("lastAsk").getAsString();
            if ("completion_result".equals(ask) && !snap.get("lastPartial").getAsBoolean()) {completed=true;break;}
            if (!answerCommandAsk(webview, snap, ask)) return;
        }
        record("P_realTaskCompleted",completed);
        boolean historyGrew = snap.get("messages").getAsInt()>initialMessages;
        record("P_realHistoryGrew",historyGrew);
        record("P_realTypingSurvived",typingHealthy);
        record("P_final",snap);
        boolean firstCompleted=completed;
        boolean firstStreamed=snap.get("partial").getAsInt()>initialPartial;
        record("P_firstTurnStreamedWhileTyping",firstStreamed);
        boolean followupStreamed=false;
        if (completed) {
            mark("P-followup-user-turn");
            String followupPrompt = fixtureMode ? "Stream a second fixture response and finish with attempt_completion." : windowsLive ? "Run execute_command with exactly: " + windowsCommand("third") + ". Do not change any files. Finish with attempt_completion." : "Run printf SOLHEIM_THIRD; sleep 5 in this workspace and finish with attempt_completion. Do not change files.";
            // A new user turn starts with a deliberate click. Never refocus inside
            // either draft-preservation loop or repair a failed typing sample.
            clickInBrowser(browser, snap.getAsJsonObject("rect"));
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(followupPrompt),null);
            robot.keyPress(KeyEvent.VK_CONTROL);robot.keyPress(KeyEvent.VK_A);robot.keyRelease(KeyEvent.VK_A);robot.keyRelease(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_CONTROL);robot.keyPress(KeyEvent.VK_V);robot.keyRelease(KeyEvent.VK_V);robot.keyRelease(KeyEvent.VK_CONTROL);
            Thread.sleep(800);
            snap=realSnapshot(webview,title);
            record("P_followupPromptEntered",snap);
            if (!followupPrompt.equals(snap.get("value").isJsonNull()?null:snap.get("value").getAsString())) {
                fail("followupPromptMismatch", "Native paste did not enter the requested followup; refusing to submit a draft");
                return;
            }
            robot.keyPress(KeyEvent.VK_ENTER);robot.keyRelease(KeyEvent.VK_ENTER);
            Thread.sleep(2000);
            expected="";completed=false;
            initialPartial=realSnapshot(webview,title).get("partial").getAsInt();
            deadline=System.currentTimeMillis()+150000;
            int followup=0;
            while(System.currentTimeMillis()<deadline) {
                typeText("y");expected+="y";Thread.sleep(2000);
                snap=realSnapshot(webview,title);
                boolean exact=expected.equals(snap.get("value").isJsonNull()?null:snap.get("value").getAsString());
                typingHealthy &= exact;
                focusHealthy &= snap.get("active").getAsBoolean() && snap.get("windowFocused").getAsBoolean();
                record("P_followup_"+(followup++),snap);
                record("P_followupExpected_"+followup,expected);
                String ask=snap.get("lastAsk").isJsonNull()?"":snap.get("lastAsk").getAsString();
                if ("completion_result".equals(ask)&&!snap.get("lastPartial").getAsBoolean()&&followup>2) {completed=true;break;}
                if (!answerCommandAsk(webview, snap, ask)) return;
            }
            followupStreamed=snap.get("partial").getAsInt()>initialPartial;
        }
        boolean commandsCovered = fixtureMode || (windowsLive
                ? approvedCommands.containsAll(java.util.Arrays.asList(windowsCommand("first"), windowsCommand("second"), windowsCommand("third")))
                : java.util.Arrays.asList("SOLHEIM_FIRST", "SOLHEIM_SECOND", "SOLHEIM_THIRD").stream()
                    .allMatch(marker -> approvedCommands.stream().anyMatch(command -> command.contains(marker))));
        record("P_requiredCommandsApproved",commandsCovered);
        record("P_followupCompleted",completed);
        record("P_bothTurnsTypingSurvived",typingHealthy);
        record("P_bothTurnsFocusPreserved",focusHealthy);
        record("P_followupStreamedWhileTyping",followupStreamed);
        boolean fixtureChanged = fixtureMode || Files.readString(Paths.get(project.getBasePath(),"src/Main.java")).contains("solheim_e2e");
        if (!fixtureMode) record("P_fixtureChanged",fixtureChanged);
        record("providerMode",fixtureMode ? "local-streaming-fixture" : "live-solheim");
        screenshot("p-real-conversation");
        finish(fixtureMode ? "testP-streaming-fixture" : "testP-real-solheim-conversation", firstCompleted&&completed&&commandsCovered&&typingHealthy&&focusHealthy&&firstStreamed&&followupStreamed&&historyGrew&&fixtureChanged?"PASS":"FAIL-REAL-CONVERSATION");
    }

    private void clickInBrowser(Object jbCefBrowser, JsonObject rect) throws Exception {
        final Point[] loc = new Point[1];
        runOnEdt(() -> {
            try {
                Component comp = (Component) invoke(jbCefBrowser, "getComponent");
                for (Component parent = comp; parent != null; parent = parent.getParent())
                    log("browser hierarchy " + compName(parent) + " visible=" + parent.isVisible() + " showing=" + parent.isShowing());
                loc[0] = comp.getLocationOnScreen();
            } catch (Throwable t) {
                log("browser coordinate failure: " + t);
                loc[0] = null;
            }
        });
        if (loc[0] == null) {
            throw new IllegalStateException("browser component not showing");
        }
        int cx = loc[0].x + rect.get("x").getAsInt() + rect.get("w").getAsInt() / 2;
        int cy = loc[0].y + rect.get("y").getAsInt() + rect.get("h").getAsInt() / 2;
        Robot robot = new Robot();
        robot.setAutoDelay(60);
        robot.mouseMove(cx, cy);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        log("clicked browser at " + cx + "," + cy);
    }

    private void typeText(String text) throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(90);
        for (char c : text.toCharArray()) {
            int code = KeyEvent.getExtendedKeyCodeForChar(c);
            if (code == KeyEvent.VK_UNDEFINED) {
                continue;
            }
            robot.keyPress(code);
            robot.keyRelease(code);
        }
        log("typed text of length " + text.length());
    }

    private String swingFocusOwnerClass() {
        final String[] r = {null};
        runOnEdt(() -> {
            Component o = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            r[0] = o == null ? "null" : o.getClass().getName();
        });
        return r[0];
    }

    /** Exact component identity of the Swing focus owner, not just its class. */
    private String swingFocusOwnerIdentity() {
        final String[] r = {null};
        runOnEdt(() -> {
            Component o = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            r[0] = o == null ? "null" : o.getClass().getName() + "@" + System.identityHashCode(o);
        });
        return r[0];
    }

    // ---- Tests B and C: JCEF unusable, fallback expected --------------------

    private void dismissStartupDialogs(int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (!startupComplete && System.currentTimeMillis() < deadline) {
            runOnEdt(() -> {
                if (startupComplete) return;
                for (Window w : Window.getWindows()) {
                    if (!(w instanceof Dialog) || !w.isShowing() || (w instanceof JFrame)) {
                        continue;
                    }
                    handleDialog((Dialog) w);
                }
            });
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void handleDialog(Dialog d) {
        com.intellij.openapi.ui.DialogWrapper wrapper = com.intellij.openapi.ui.DialogWrapper.findInstance(d);
        if (wrapper != null && (wrapper.getClass().getName().startsWith("com.intellij.llmInstaller.ui.promo") ||
                containsAiPromotion(d))) {
            log("Cancelling startup AI promotion: " + wrapper.getClass().getName());
            wrapper.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE);
            return;
        }
        List<JButton> buttons = new ArrayList<>();
        List<JCheckBox> boxes = new ArrayList<>();
        collect(d, buttons, boxes);
        for (JCheckBox box : boxes) {
            String t = (box.getText() == null ? "" : box.getText()).toLowerCase();
            if ((t.contains("accept") || t.contains("agree")) && !box.isSelected()) {
                box.setSelected(true);
                log("dialog checkbox selected: " + box.getText());
            }
        }
        String[] prefer = {"accept", "agree", "continue", "don't send", "dont send", "start", "skip", "no thanks", "got it", "close", "ok"};
        for (String p : prefer) {
            for (JButton b : buttons) {
                String t = (b.getText() == null ? "" : b.getText()).toLowerCase();
                if (t.contains(p) && b.isEnabled() && b.isShowing()) {
                    log("dialog button clicked: " + b.getText() + " (window: " + d.getName() + ")");
                    b.doClick();
                    return;
                }
            }
        }
    }

    private boolean containsAiPromotion(Component component) {
        if (component.getClass().getName().startsWith("com.intellij.llmInstaller.ui.promo")) return true;
        if (component instanceof JLabel) {
            String text = ((JLabel) component).getText();
            if (text != null && (text.contains("WebStorm Goes AI") ||
                    text.contains("Unlock next-level development with free AI"))) return true;
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                if (containsAiPromotion(child)) return true;
            }
        }
        return false;
    }

    private void collect(Component c, List<JButton> buttons, List<JCheckBox> boxes) {
        if (c instanceof JButton) {
            buttons.add((JButton) c);
        }
        if (c instanceof JCheckBox) {
            boxes.add((JCheckBox) c);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collect(child, buttons, boxes);
            }
        }
    }

    // ---- Utilities ----------------------------------------------------------

    private String reflectJcefSupport() {
        try {
            Class<?> cls = zooClass("org.zoocode.jetbrains.util.JcefSupport");
            Object companion = cls.getField("Companion").get(null);
            Object state = companion.getClass().getMethod("current").invoke(companion);
            return ((Enum<?>) state).name();
        } catch (Throwable t) {
            return "PROBE-FAILED: " + t;
        }
    }

    private Object invoke(Object target, String name) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m.invoke(target);
                }
            }
        } catch (Throwable t) {
            throw new RuntimeException("invoke " + name + " failed", t);
        }
        throw new RuntimeException("method not found: " + name);
    }

    private Object invoke(Object target, String name, Object... args) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                    return m.invoke(target, args);
                }
            }
        } catch (java.lang.reflect.InvocationTargetException t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            throw new RuntimeException("invoke " + name + "/" + args.length + " failed: "
                    + cause.getClass().getName() + ": " + cause.getMessage(), cause);
        } catch (Throwable t) {
            throw new RuntimeException("invoke " + name + "/" + args.length + " failed: " + t.getClass().getName()
                    + ": " + t.getMessage(), t);
        }
        throw new RuntimeException("method not found: " + name + "/" + args.length);
    }

    private Object field(Object target, String name) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            throw new RuntimeException("field " + name + " failed", t);
        }
        throw new RuntimeException("field not found: " + name);
    }

    private interface Poll {
        Object get();
    }

    private Object poll(long timeoutMs, Poll p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Object v = p.get();
            if (v != null && !(v instanceof Boolean) || v instanceof Boolean && (Boolean) v) {
                return v;
            }
            Thread.sleep(1_000);
        }
        return p.get();
    }

    private void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) { action.run(); return; }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
            finally { latch.countDown(); }
        });
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("EDT timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
        if (failure.get() != null) throw new IllegalStateException("EDT action failed", failure.get());
    }

    private void waitEdt() throws InterruptedException {
        runOnEdt(() -> {
        });
    }

    private void screenshot(String name) {
        try {
            waitEdt();
            Robot robot = new Robot();
            Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage img = robot.createScreenCapture(screen);
            File out = evidenceDir.resolve(name + ".png").toFile();
            ImageIO.write(img, "png", out);
            log("screenshot saved: " + out.getAbsolutePath());
        } catch (Throwable t) {
            record("screenshotError:" + name, String.valueOf(t));
        }
    }

    private void record(String key, Object value) {
        synchronized (evidenceLock) {
            if (value != null) {
                checks.add(key + " = " + value);
            } else {
                checks.add(key + " = null");
            }
            log("RECORD " + key + " = " + value);
        }
    }

    /**
     * Approves one command ask when its text is a fixture command. Any other command fails the run,
     * because the profile runs as the developer. Output asks carry no command and need no check.
     * @return false when the run failed and the caller must stop
     */
    private boolean answerCommandAsk(Object webview, JsonObject snap, String ask) throws Exception {
        if (snap.get("lastPartial").getAsBoolean()) return true;
        if ("command".equals(ask)) {
            JsonElement text = snap.get("lastText");
            JsonElement tsValue = snap.get("lastTs");
            String command = text == null || text.isJsonNull() ? "" : text.getAsString().trim();
            boolean allowed = windowsLive
                    ? java.util.Arrays.asList(windowsCommand("first"), windowsCommand("second"), windowsCommand("third")).contains(command)
                    : FIXTURE_COMMAND.matcher(command).matches();
            if (!allowed) {
                fail("unexpectedCommand", "Refused a command outside the fixture: " + command);
                return false;
            }
            long ts = tsValue == null || tsValue.isJsonNull() ? -1 : tsValue.getAsLong();
            if (ts >= 0 && ts == lastApprovedTs) return true;
            lastApprovedTs = ts;
            approvedCommands.add(command);
            record("P_approvedCommand", command);
        } else if (!"command_output".equals(ask)) {
            return true;
        }
        realSend(webview, "{\"type\":\"askResponse\",\"askResponse\":\"yesButtonClicked\"}");
        return true;
    }

    private void fail(String reason, String detail) {
        log("FAIL reason=" + reason + " detail=" + detail);
        record("failReason", reason + ": " + detail);
        screenshot("failure");
        finish("test", "FAIL");
    }

    private void finish(String label, String status) {
        synchronized (evidenceLock) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"label\": \"").append(label).append("\",\n");
            sb.append("  \"status\": \"").append(status).append("\",\n");
            sb.append("  \"mode\": \"").append(System.getProperty("zoo.harness.mode", "A")).append("\",\n");
            sb.append("  \"checks\": {\n");
            for (int i = 0; i < checks.size(); i++) {
                String kv = checks.get(i);
                int eq = kv.indexOf(" = ");
                String k = kv.substring(0, eq).replace("\"", "'");
                String v = kv.substring(eq + 3).replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append("    \"").append(k).append("\": \"").append(v).append("\"");
                sb.append(i < checks.size() - 1 ? ",\n" : "\n");
            }
            sb.append("  }\n}\n");
            try {
                Files.write(evidenceDir.resolve("evidence.json"), sb.toString().getBytes(StandardCharsets.UTF_8));
                Files.write(evidenceDir.resolve("status.txt"), status.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                LOG.error("harness driver: cannot write evidence", e);
            }
        }
        log("driver finished with " + status);
    }

    private void log(String line) {
        String msg = "[zoo-harness-driver] " + line;
        LOG.info(msg);
        if (progressLog != null) {
            try {
                synchronized (this) {
                    Files.write(progressLog,
                            (msg + "\n").getBytes(StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
