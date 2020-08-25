import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.event.BasicEvent;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.eventprocessing.EventFilter2D;
import net.sf.jaer.graphics.Chip2DRenderer;
import net.sf.jaer.graphics.ChipCanvas;
import net.sf.jaer.graphics.FrameAnnotater;
import org.opencv.core.*;

import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.opencv.core.CvType.CV_8UC1;
import static org.opencv.imgproc.Imgproc.*;

// TODO: Implement buffering of threshold data

// TODO: Simple marker annotation at centroid with shape detection

// Buffer "memory" will be implemented through pixel decay

public class ScratchFilter extends EventFilter2D implements FrameAnnotater {
    static {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME); // Load in the appropriate OpenCV library
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native OpenCV library failed to load.\n" + e);
        }
    }

    private Logger logger = Logger.getLogger(this.getClass().getName());

    private boolean hasSaved = false;
    private boolean addedMouseListener = false;

    // TODO: How do you obtain these dynamically for different devices?
    // Currently set to the DAVIS-346 dimensions
    private int CHIP_WIDTH = 346;
    private int CHIP_HEIGHT = 260;

    private boolean thresholdOn = false;
    private boolean contoursOn = false;

    final private Mat postBuffer = new Mat(CHIP_WIDTH, CHIP_HEIGHT, CV_8UC1, new Scalar(0));
    final private Mat buffer = new Mat(CHIP_WIDTH, CHIP_HEIGHT, CV_8UC1, new Scalar(0));
    final private ArrayList<Point> bboxPoints = new ArrayList<>();

    private int bufferCycleLength = 20; // for specifying whole frame retention
    private int thresh = 30; // pixel filtering threshold
    private int kdim = 3;
    private int ptSize = 2;
    private int cannyMax = 255;
    private int cannyMin = 0;

    public void doToggleContours() {
        contoursOn = !contoursOn;
    }

    public void setCannyMin(int cannyMin) {
        this.cannyMin = cannyMin;
    }

    public int getCannyMin() {
        return cannyMin;
    }

    public void setCannyMax(int cannyMax) {
        this.cannyMax = cannyMax;
    }

    public int getCannyMax() {
        return cannyMax;
    }

    private float sigma = getFloat("sigma",1f); // Can't see this entry in the GUI

    public float getSigma(){
        return sigma;
    }

    public void setFloatProperty(final float NewFloat) { // TODO: get this showing up in the UI
        putFloat("sigma",NewFloat);
        float OldValue = this.sigma;
        this.sigma = NewFloat;
        support.firePropertyChange("sigma",OldValue,NewFloat);
    }

    public void doToggleThreshold() {
        thresholdOn = !thresholdOn;
    }

    public void setThresh(int thresh) {
        this.thresh = thresh;
    }

    public int getThresh() {
        return thresh;
    }

    public void setKdim(int kdim) {
        this.kdim = kdim;
    }

    public int getKdim() {
        return kdim;
    }

    public int getBufferCycleLength() {
        return bufferCycleLength;
    }

    public void setBufferCycleLength(int bufferCycleLength) {
        this.bufferCycleLength = bufferCycleLength;
    }

    public int getPtSize() {
        return ptSize;
    }

    public void setPtSize(int ptSize) {
        this.ptSize = ptSize;
    }

    /**
     * Subclasses should call this super initializer
     *
     * @param chip
     */
    public ScratchFilter(AEChip chip) {
        super(chip);
        /* // Do these even work in this context?
        CHIP_WIDTH = chip.getSizeX();
        CHIP_HEIGHT = chip.getSizeY();
         */
    }

    @Override
    public EventPacket<?> filterPacket(EventPacket<?> in) {

        // TODO: This decay method isn't going to work with the new byte config stuff. Will have to reformulate.
        byte decayDelta = (byte) (255 / bufferCycleLength);

        for (int i = 0; i < CHIP_WIDTH; i++) {
            for (int j = 0; j < CHIP_HEIGHT; j++) {
                byte[] temp = new byte[1];
                buffer.get(i,j,temp);

                if (true || temp[0] - 1 < 0) {
                    buffer.put(i,j,0);
                } else {
                    buffer.put(i,j,temp[0] - decayDelta);
                }
            }
        }

        for (BasicEvent ev: in) {
            // `byte` is interpreted as signed in Java. -ve values occur for 127 > n
            buffer.put(ev.getX(), ev.getY(), new byte[]{(byte) 100});
        }

        return in;
    }

    @Override
    public void resetFilter() {

    }

    @Override
    public void initFilter() {
    }

    @Override
    public void annotate(GLAutoDrawable drawable) {
        if (!addedMouseListener) {
            ChipCanvas theCanvas = chip.getCanvas();

            if (theCanvas != null) {
                Chip2DRenderer renderer = chip.getRenderer();

                theCanvas.getCanvas().addMouseListener(new MouseListener() {
                    @Override
                    public void mouseClicked(MouseEvent mouseEvent) {
                    }

                    @Override
                    public void mousePressed(MouseEvent mouseEvent) {
                    }

                    @Override
                    public void mouseReleased(MouseEvent mouseEvent) {
                        logger.log(Level.INFO, "Look ma, I'm pressing the mouse!");

                        final Point p = theCanvas.getMousePixel();

                        if (mouseEvent.getButton() == MouseEvent.BUTTON1) {
                            renderer.setXsel((short) p.x);
                            renderer.setYsel((short) p.y);

                            bboxPoints.add(new Point(renderer.getXsel(), renderer.getYsel()));
                            String s = "Point = " + renderer.getXsel() + " " + renderer.getYsel();
                            logger.log(Level.INFO, s);
                        }

                    }

                    @Override
                    public void mouseEntered(MouseEvent mouseEvent) {

                    }

                    @Override
                    public void mouseExited(MouseEvent mouseEvent) {

                    }
                });
            } else {
                // TODO: (Ivy) Log about it!!
            }

            addedMouseListener = true;
        }

        GL2 gl = drawable.getGL().getGL2();

        gl.glPushMatrix();

        gl.glPointSize(ptSize);

        // blank background -- drawn clockwise
//        gl.glBegin(GL2.GL_QUADS);
//        gl.glColor3f(1,1, 1);
//        gl.glVertex2d(0, 0); // bottom left
//        gl.glVertex2d(0, CHIP_HEIGHT); // top left
//        gl.glVertex2d(CHIP_WIDTH, CHIP_HEIGHT); // top right
//        gl.glVertex2d(CHIP_WIDTH, 0); // bottom right
//        gl.glEnd();

        Mat out = buffer.clone();
        // TODO: Warn user in a tooltip that an even kdim will be converted to kdim + 1
        if (kdim % 2 == 0) { kdim = kdim + 1; }
        GaussianBlur(out, out, new Size(kdim,kdim), 0, 0, Core.BORDER_DEFAULT);
        // blur(out, out, new Size(kdim,kdim));

        if (thresholdOn) {
            threshold(out, out, thresh, 255, ADAPTIVE_THRESH_MEAN_C);
        }

        Canny(out, out, cannyMin, cannyMax); // (20,85) seems to work pretty well

        /* Hoping that this works some day...
        java.util.List<Mat> corners = new ArrayList<>();
        Mat ids = new Mat();
        Dictionary dic = Aruco.getPredefinedDictionary(Aruco.DICT_6X6_250);
        Aruco.detectMarkers(out, dic, corners, ids);
        Mat drawn = buffer.clone().setTo(new Scalar(0));
        Aruco.drawDetectedMarkers(drawn, corners, ids);
         */

        gl.glBegin(GL2.GL_POINTS);

        for (int i = 0; i < CHIP_WIDTH; i++) {
            for (int j = 0; j < CHIP_HEIGHT; j++) {
                byte[] temp = new byte[1];
                out.get(i,j,temp);

                if ((temp[0] & 0xFF) > 0) {
                    float level = 1f - ((float) temp[0]) / 100f;
                    gl.glColor3f(level,0,0);
                    gl.glVertex2d(i, j);
                }
            }
        }

        gl.glEnd();

        gl.glBegin(GL2.GL_POINTS);

        if (contoursOn) {
            java.util.List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            findContours(out, contours, hierarchy, RETR_TREE, CHAIN_APPROX_SIMPLE);
            Mat contourOut = Mat.zeros(out.size(), CV_8UC1);
            for (int i = 0; i < contours.size(); i++) {
                // Contours currently form way too tightly around noise. Mitigate this somehow
                drawContours(contourOut, contours, i, new Scalar(255), 1, LINE_8, hierarchy, 0, new org.opencv.core.Point());
            }

            for (int i = 0; i < CHIP_WIDTH; i++) {
                for (int j = 0; j < CHIP_HEIGHT; j++) {
                    byte[] temp = new byte[1];
                    contourOut.get(i,j,temp);

                    if ((temp[0] & 0xFF) > 0) {
                        float level = 1f - ((float) temp[0]) / 100f;
                        gl.glColor3f(0,0,level);
                        gl.glVertex2d(i, j);
                    }
                }
            }
        }

        gl.glEnd();

        gl.glPointSize(8f);

        gl.glBegin(GL2.GL_POINTS);

        gl.glColor3f(0,1f,0);

        for (int i = 0; i < bboxPoints.size(); i++) {
            final Point p = bboxPoints.get(i);
            gl.glVertex2d(p.x, p.y);
        }

        gl.glEnd();

        gl.glPopMatrix();

    }
}
