import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Manual, JDK-only diagnostic for keys whose release reaches the application
 * without a recorded press. A Robot types keys into a Swing text area. A
 * KeyEventDispatcher counts KEY_PRESSED, KEY_TYPED, and KEY_RELEASED
 * deliveries. A KeyboardFocusManager listener counts focus-owner changes.
 *
 * Usage: java KeyDropProbe.java [control|churn] [keys] [churnEvery] [style]
 *   Defaults: control, 120 keys, churn every 7 keys, style hidden-frame.
 *   Styles (churn mode only): hidden-frame | visible-frame | self-refocus
 *     | dispose-without-return.
 *
 * Needs a display and a headful JDK or JRE. Run it under a desktop session or
 * Xvfb. A headless runtime exits with HeadlessException.
 *
 * This probe is analogous evidence only. It contains no IDE, plugin, or JCEF
 * code, and its churn styles move the focus owner while the recorded IDE loss
 * kept the owner stable. A loss here does not identify the initiating IDE
 * platform call, does not prove a JDK root cause, and does not clear the
 * product defect described in docs/validation/chat-focus-preservation.md.
 */
public class KeyDropProbe {
    private static final Set<String> STYLES = Set.of(
            "hidden-frame", "visible-frame", "self-refocus", "dispose-without-return");
    private static final AtomicInteger pressed = new AtomicInteger();
    private static final AtomicInteger typed = new AtomicInteger();
    private static final AtomicInteger released = new AtomicInteger();
    private static volatile String ownerDesc = "none";
    private static int ownerChanges = 0;

    public static void main(String[] args) throws Exception {
        String mode = arg(args, 0, "control").toLowerCase(Locale.ROOT);
        int keys = Integer.parseInt(arg(args, 1, "120"));
        int churnEvery = Integer.parseInt(arg(args, 2, "7"));
        String style = arg(args, 3, "hidden-frame").toLowerCase(Locale.ROOT);
        if (!mode.equals("control") && !mode.equals("churn")) {
            System.err.println("mode must be control or churn, got: " + mode);
            System.exit(2);
        }
        if (mode.equals("churn") && !STYLES.contains(style)) {
            System.err.println("churn style must be one of " + STYLES + ", got: " + style);
            System.exit(2);
        }
        if (keys < 1 || churnEvery < 1) {
            System.err.println("keys and churnEvery must be at least 1");
            System.exit(2);
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display or headless runtime: run under a desktop session or Xvfb with a headful JDK.");
            System.exit(2);
        }

        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        kfm.addKeyEventDispatcher(KeyDropProbe::countDelivery);
        kfm.addPropertyChangeListener(event -> {
            if ("focusOwner".equals(event.getPropertyName())) {
                ownerChanges++;
                Object newOwner = event.getNewValue();
                ownerDesc = newOwner == null ? "null" : newOwner.getClass().getSimpleName();
                System.out.println("[owner-change #" + ownerChanges + "] new=" + ownerDesc);
            }
        });

        JFrame[] mainFrame = new JFrame[1];
        JTextArea[] mainArea = new JTextArea[1];
        SwingUtilities.invokeAndWait(() -> {
            mainFrame[0] = new JFrame("main");
            mainArea[0] = new JTextArea();
            mainFrame[0].add(mainArea[0]);
            mainFrame[0].setSize(420, 300);
            mainFrame[0].setLocation(10, 10);
            mainFrame[0].setVisible(true);
        });
        Robot robot = new Robot();
        robot.setAutoDelay(30);
        Point areaOrigin = mainArea[0].getLocationOnScreen();
        robot.mouseMove(areaOrigin.x + 40, areaOrigin.y + 15);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(400);
        Component startOwner = kfm.getFocusOwner();
        String shape = mode.equals("churn") ? " mode=churn style=" + style : " mode=control";
        System.out.println("run:" + shape + " keys=" + keys + " churnEvery=" + churnEvery
                + " startOwner=" + (startOwner == null ? "null" : startOwner.getClass().getName()));

        Thread churner = new Thread(() -> churnLoop(mode, style, keys, churnEvery, mainArea[0]));
        churner.start();

        int lostKeys = 0;
        for (int key = 0; key < keys; key++) {
            int pressBefore = pressed.get();
            int typedBefore = typed.get();
            int releaseBefore = released.get();
            robot.keyPress(KeyEvent.VK_X);
            robot.keyRelease(KeyEvent.VK_X);
            Thread.sleep(65);
            int pressDelta = pressed.get() - pressBefore;
            int typedDelta = typed.get() - typedBefore;
            int releaseDelta = released.get() - releaseBefore;
            if (releaseDelta >= 1 && pressDelta == 0) {
                lostKeys++;
                System.out.println("[LOSS] key " + key + ": release delivered, press missing."
                        + " typedDelta=" + typedDelta
                        + " focusOwner=" + ownerDesc
                        + " ownerChangesSoFar=" + ownerChanges);
            } else if (pressDelta >= 1 && releaseDelta == 0) {
                System.out.println("[ODD] key " + key + ": press without release.");
            } else if (pressDelta >= 1 && releaseDelta >= 1 && typedDelta == 0) {
                System.out.println("[ODD] key " + key + ": press and release without typed.");
            }
        }
        churner.join();
        System.out.println("RESULT" + shape + " lostReleaseWithoutPress=" + lostKeys + "/" + keys
                + " press=" + pressed.get() + " typed=" + typed.get() + " released=" + released.get()
                + " ownerChanges=" + ownerChanges + " finalOwner=" + ownerDesc);
        System.exit(0);
    }

    private static boolean countDelivery(KeyEvent event) {
        switch (event.getID()) {
            case KeyEvent.KEY_PRESSED -> pressed.incrementAndGet();
            case KeyEvent.KEY_TYPED -> typed.incrementAndGet();
            case KeyEvent.KEY_RELEASED -> released.incrementAndGet();
        }
        return false;
    }

    private static void churnLoop(String mode, String style, int keys, int churnEvery, JTextArea mainArea) {
        if (mode.equals("control")) {
            return;
        }
        for (int i = 0; i < keys / churnEvery + 1; i++) {
            try {
                Thread.sleep(churnEvery * 95L);
                final int round = i;
                SwingUtilities.invokeAndWait(() -> churnOnce(style, mainArea, round));
            } catch (InterruptedException | InvocationTargetException stopped) {
                System.err.println("[CHURN-STOPPED] round " + i + ": " + stopped);
                return;
            }
        }
    }

    private static void churnOnce(String style, JTextArea mainArea, int round) {
        switch (style) {
            case "hidden-frame" -> {
                JFrame side = newSideFrame(round);
                sideTextArea(side).requestFocus();
                side.dispose();
            }
            case "visible-frame" -> {
                JFrame side = newSideFrame(round);
                side.setVisible(true);
                sideTextArea(side).requestFocus();
                mainArea.requestFocus();
                side.dispose();
            }
            case "self-refocus" -> mainArea.requestFocusInWindow();
            case "dispose-without-return" -> {
                JFrame side = newSideFrame(round);
                side.setVisible(true);
                sideTextArea(side).requestFocus();
                side.dispose();
                mainArea.requestFocusInWindow();
            }
            default -> throw new IllegalArgumentException("unknown churn style: " + style);
        }
    }

    private static JFrame newSideFrame(int round) {
        JFrame side = new JFrame("probe" + round);
        side.add(new JTextArea());
        side.setSize(200, 120);
        side.setLocation(500, 60);
        return side;
    }

    private static JTextArea sideTextArea(JFrame side) {
        return (JTextArea) side.getContentPane().getComponent(0);
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index ? args[index] : fallback;
    }
}
