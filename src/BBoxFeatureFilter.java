import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.eventprocessing.EventFilter2D;
import net.sf.jaer.graphics.Chip2DRenderer;
import net.sf.jaer.graphics.ChipCanvas;
import net.sf.jaer.graphics.FrameAnnotater;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;


/* A class for testing and demonstrating bbox saving, loading, and transformation */

public class BBoxFeatureFilter extends EventFilter2D implements FrameAnnotater {

    static {
        try {
            System.loadLibrary("opencv_java440"); // See `ScratchFilter.java` for static naming justification
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native OpenCV library failed to load.\n" + e);
        }
    }

    final private Logger logger = Logger.getLogger(this.getClass().getName());

    private final ArrayList<ArrayList<Point>> bboxList = new ArrayList<>();
    private final ArrayList<Point> bboxPoints = new ArrayList<>();

    private int currObjIndex = -1;

    private int CHIP_WIDTH = -1;
    private int CHIP_HEIGHT = -1;

    /**
     * Subclasses should call this super initializer
     *
     * @param chip
     */
    public BBoxFeatureFilter(AEChip chip) {
        super(chip);
    }

    public void doClearBboxPoints() {
        bboxPoints.clear();
        chip.getCanvas().paintFrame(); // force-paint the frame
    }

    @Override
    public EventPacket<?> filterPacket(EventPacket<?> in) {
        return in;
    }

    @Override
    public void resetFilter() {
        // nothing.
    }

    // TODO: Manage multiple objects + Initial XML Parsing + Saving/Loading

    @Override
    public void initFilter() {
        CHIP_WIDTH = chip.getSizeX();
        CHIP_HEIGHT = chip.getSizeY();

        logger.log(Level.INFO, "width=" + CHIP_WIDTH + " height=" + CHIP_HEIGHT);

        ChipCanvas theCanvas = chip.getCanvas();

        if (theCanvas != null) {
            Chip2DRenderer renderer = chip.getRenderer();

            chip.getAeViewer().getAePlayer().pausePlayAction.addPropertyChangeListener(new PropertyChangeListener() {
                @Override
                public void propertyChange(PropertyChangeEvent propertyChangeEvent) {
                    PropertyChangeEvent p = propertyChangeEvent; // what a long name!
                    logger.info(p.getPropertyName() + " " + p.getNewValue());

                    if (p.getPropertyName() == "Name" &&
                            p.getOldValue() == "Pause" &&
                            p.getNewValue() == "Play") {
                        /* current shape += 1-- some checks:
                            - at least three points in the current object
                            - ... what else?
                         */
                    }
                }
            });

            theCanvas.getCanvas().addMouseListener(new MouseListener() {
                @Override
                public void mouseClicked(MouseEvent mouseEvent) {
                }

                @Override
                public void mousePressed(MouseEvent mouseEvent) {
                }

                @Override
                public void mouseReleased(MouseEvent mouseEvent) {
                    if (!chip.getAeViewer().isPaused()) {
                        return;
                    }

                    logger.log(Level.INFO, "Look ma, I'm pressing the mouse!");

                    final java.awt.Point p = theCanvas.getMousePixel();

                    if (mouseEvent.getButton() == MouseEvent.BUTTON1) {
                        renderer.setXsel((short) p.x);
                        renderer.setYsel((short) p.y);

                        bboxPoints.add(new java.awt.Point(renderer.getXsel(), renderer.getYsel()));
                        chip.getCanvas().paintFrame(); // force-paint the frame

                        String s = "Point = " + renderer.getXsel() + " " + renderer.getYsel();
                        logger.log(Level.INFO, s);
                    }
                }

                @Override
                public void mouseEntered(MouseEvent mouseEvent) {
                    // OPTIONAL: Probably show coordinates by drawing on the canvas or via a tooltip
                }

                @Override
                public void mouseExited(MouseEvent mouseEvent) {
                }
            });
        } else {
            logger.log(Level.SEVERE, "Chip's returned canvas is null!");
        }
    }

    private Point getCentroid(ArrayList<Point> pointArrayList) {
        int x_acc = 0;
        int y_acc = 0;
        int k = pointArrayList.size();

        for (final Point p : pointArrayList) {
            x_acc += p.x;
            y_acc += p.y;
        }

        return new Point(x_acc / k, y_acc / k);
    }

    @Override
    public void annotate(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();
        gl.glPushMatrix();

        gl.glPointSize(8f);
        gl.glColor3f(0,1f,0);

        gl.glBegin(GL2.GL_POINTS);
        for (final Point p : bboxPoints) {
            gl.glVertex2d(p.x, p.y);
        }

        gl.glEnd();

        // Draw the loop that goes through all the bbox points
        gl.glLineWidth(2f);
        gl.glBegin(GL2.GL_LINE_LOOP);
        for (final ArrayList<Point> points: bboxList) {
            for (final Point p : points) {
                gl.glVertex2d(p.x, p.y);
            }
        }
        gl.glEnd();

        // Draw the 2D centroid -- just to see what happens
        // definition: x = x_mean; y = y_mean.
        // This might become a handy result later. Implemented in getCentroid()
        if (bboxPoints.size() >= 3) {
            Point centroid = getCentroid(bboxPoints);
            gl.glPointSize(8f);
            gl.glColor3f(0,0,1f);
            gl.glBegin(GL2.GL_POINTS);
            gl.glVertex2d(centroid.x, centroid.y);
            gl.glEnd();
        }

        gl.glPopMatrix();

    }
}
