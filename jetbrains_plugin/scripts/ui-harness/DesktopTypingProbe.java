import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Fails before IDE startup if the runner cannot deliver real desktop keys. */
public class DesktopTypingProbe {
    public static void main(String[] args) throws Exception {
        Path evidence = Paths.get(args[0]);
        Files.createDirectories(evidence);
        JFrame frame = new JFrame("Zoo native desktop typing preflight");
        JTextArea input = new JTextArea();
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.add(input);
                frame.setSize(600, 200);
                frame.setLocationRelativeTo(null);
                frame.setAlwaysOnTop(true);
                frame.setVisible(true);
            });
            Robot robot = new Robot();
            robot.setAutoDelay(100);
            Thread.sleep(1000);
            Point[] point = {null};
            SwingUtilities.invokeAndWait(() -> point[0] = input.getLocationOnScreen());
            robot.mouseMove(point[0].x + 40, point[0].y + 40);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            for (char c : "desktop".toCharArray()) {
                int key = KeyEvent.getExtendedKeyCodeForChar(c);
                robot.keyPress(key);
                robot.keyRelease(key);
            }
            Thread.sleep(500);
            ImageIO.write(robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())),
                    "png", evidence.resolve("desktop-preflight.png").toFile());
            String[] actual = {null};
            boolean[] focused = {false};
            SwingUtilities.invokeAndWait(() -> {
                actual[0] = input.getText();
                focused[0] = input.isFocusOwner();
            });
            boolean pass = "desktop".equals(actual[0]) && focused[0];
            Files.writeString(evidence.resolve("desktop-preflight.txt"),
                    "pass=" + pass + " text=" + actual[0] + " focused=" + focused[0]);
            if (!pass) throw new IllegalStateException("Native desktop typing unavailable");
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }
}
