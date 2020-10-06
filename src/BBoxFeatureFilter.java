import au.edu.wsu.BBoxObject;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.eventprocessing.EventFilter2D;
import net.sf.jaer.graphics.Chip2DRenderer;
import net.sf.jaer.graphics.ChipCanvas;
import net.sf.jaer.graphics.FrameAnnotater;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.swing.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.beans.PropertyChangeEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// TODO: Initial XML Parsing + Saving/Loading
// NOTE: BBox drawing appears to be a bit shaky for now

/* A class for testing and demonstrating bbox saving, loading, and transformation */

public class BBoxFeatureFilter extends EventFilter2D implements FrameAnnotater {

    /* Not needed, for now
    static {
        try {
            System.loadLibrary("opencv_java440"); // See `ScratchFilter.java` for static naming justification
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native OpenCV library failed to load.\n" + e);
        }
    }
     */

    final private Logger logger = Logger.getLogger(this.getClass().getName());

    private final ArrayList<BBoxObject> bboxList = new ArrayList<>();
    private final BBoxObject currBboxPoints = new BBoxObject();
    private final String transform = "";

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();

    private int CHIP_WIDTH = -1;
    private int CHIP_HEIGHT = -1;

    /**
     * Subclasses should call this super initializer
     *
     * @param chip
     */
    public BBoxFeatureFilter(AEChip chip) throws ParserConfigurationException {
        super(chip);
    }

    public void doSaveAsNewObject() {
        if (currBboxPoints.size() < 3) {
            logger.warning("Latest bbox had less than 3 points. Discarding.");
        } else {
            BBoxObject newPoints = new BBoxObject();
            newPoints.addAll(currBboxPoints);
            newPoints.setTimestamp(chip.getAeViewer().getAePlayer().getTime());
            bboxList.add(newPoints);
        }

        currBboxPoints.clear();
        chip.getCanvas().paintFrame(); // force-paint the frame
    }

    public void doClearBboxPoints() {
        bboxList.clear();
        chip.getCanvas().paintFrame(); // force-paint the frame
    }

    // Probably need simple bounds checking in here some day
    public void doOpenFrootsFile() {
        JFileChooser chooser = new JFileChooser();
        InputStream inputStream;
        File file;

        doClearBboxPoints();

        int retVal = chooser.showOpenDialog(null);

        if (retVal == JFileChooser.APPROVE_OPTION) {
            file = chooser.getSelectedFile();
        } else {
            logger.warning("Selected file could not be opened!");
            return;
        }

        try {
            inputStream = new FileInputStream(file);
            Document doc = builder.parse(inputStream);

            NodeList objectlist = doc.getElementsByTagName("objectlist").item(0).getChildNodes();

            for (int i = 0; i < objectlist.getLength(); i++) {
                Node currentNode = objectlist.item(i);
                NodeList children = currentNode.getChildNodes();

                ArrayList<Point> pointArrayList = new ArrayList<>();

                for (int objNodeIdx = 0; objNodeIdx < children.getLength(); objNodeIdx++) {
                    Node objNode = children.item(objNodeIdx);

                    if (objNode.getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }

                    logger.info(objNode.getTextContent());
                    Point point = parseCoordinates(objNode.getTextContent());
                    pointArrayList.add(point);
                }

                BBoxObject temp = new BBoxObject(384210645);
                temp.addAll(pointArrayList);

                bboxList.add(temp);

                NamedNodeMap attributes = currentNode.getAttributes();

                if (attributes != null) {
                    for (int j = 0; j < attributes.getLength(); j++) {
                        logger.info(attributes.item(j).getTextContent());
                    }
                }

                logger.info("bboxlist len: " + bboxList.size());
            }

        } catch (IOException | SAXException e) {
            logger.warning(e.toString());
        }
    }

    // Doesn't validate input -- could get ugly
    private Point parseCoordinates(String coords) {
        Point p = new Point(-1,-1);

        Pattern pattern = Pattern.compile("(\\d+)");
        Matcher matcher = pattern.matcher(coords);

        if (matcher.find())
            p.x = Integer.parseInt(matcher.group(1));
        if (matcher.find())
            p.y = Integer.parseInt(matcher.group(1));

        return p;
    }

    @Override
    public EventPacket<?> filterPacket(EventPacket<?> in) {
        return in;
    }

    @Override
    public void resetFilter() {
        doClearBboxPoints();
        // and eventually others
    }

    @Override
    public void initFilter() {
        CHIP_WIDTH = chip.getSizeX();
        CHIP_HEIGHT = chip.getSizeY();

        logger.info("width=" + CHIP_WIDTH + " height=" + CHIP_HEIGHT);

        ChipCanvas theCanvas = chip.getCanvas();

        if (theCanvas != null) {
            Chip2DRenderer renderer = chip.getRenderer();

            // Pause/play detection is actually not a good way of knowing when the user wants
            // to create an object. Good example: if they want to create multiple objects at
            // the same timestamp, there would be no practical way of doing it.
            chip.getAeViewer().getAePlayer().pausePlayAction.addPropertyChangeListener(propertyChangeEvent -> {
                PropertyChangeEvent p = propertyChangeEvent; // what a long name!

                // For some reason, these are only valid when they are swapped?
                if (p.getOldValue() == "Play" && p.getNewValue() == "Pause") {
                    currBboxPoints.clear();
                }

                // logger.info(p.getPropertyName() + " " + p.getNewValue());
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

                    switch (mouseEvent.getButton()) {
                        case MouseEvent.BUTTON1:
                            renderer.setXsel((short) p.x);
                            renderer.setYsel((short) p.y);
                            // get*sel() converts on-screen pixel positions to viewer positions
                            currBboxPoints.add(new java.awt.Point(renderer.getXsel(), renderer.getYsel()));
                            chip.getCanvas().paintFrame(); // force-paint the frame

                            String s = "Point = " + renderer.getXsel() + " " + renderer.getYsel();
                            logger.log(Level.INFO, s);
                            break;
                        case MouseEvent.BUTTON3:
                            if (currBboxPoints.size() > 0) {
                                currBboxPoints.remove(currBboxPoints.size() - 1);
                                chip.getCanvas().paintFrame(); // force-paint the frame
                            }
                            break;
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
        int ts = chip.getAeViewer().getAePlayer().getTime();
        GL2 gl = drawable.getGL().getGL2();
        gl.glPushMatrix();

        gl.glPointSize(8f);
        gl.glColor3f(0,0,1f);

        // Draw the bbox points of each object
        gl.glBegin(GL2.GL_POINTS);
        for (BBoxObject obj : bboxList) {
            if (obj.getTimestamp() > ts) {
                continue; // bbox doesn't exist yet
            }

            for (Point p : obj) {
                gl.glVertex2d(p.getX(), p.getY());
            }
        }
        gl.glEnd();

        gl.glBegin(GL2.GL_POINTS);
        gl.glColor3f(0,1f,0);

        for (Point p : currBboxPoints) {
            gl.glVertex2d(p.getX(), p.getY());
        }

        gl.glEnd();

        // Draw the loops that go through all the bbox points
        gl.glLineWidth(2f);
        gl.glColor3f(0,0,1f);

        for (final BBoxObject obj : bboxList) {
            if (obj.getTimestamp() > ts) {
                continue; // bbox doesn't exist yet
            }

            gl.glBegin(GL2.GL_LINE_LOOP);

            for (final Point p : obj) {
                gl.glVertex2d(p.getX(), p.getY());
            }
            gl.glEnd();
        }

        gl.glColor3f(0,1f,0);

        gl.glBegin(GL2.GL_LINE_LOOP);
        for (final Point p : currBboxPoints) {
            gl.glVertex2d(p.getX(), p.getY());
        }
        gl.glEnd();

        // Draw the 2D centroid -- just to see what happens
        // definition: x = x_mean; y = y_mean.
        // This might become a handy result later. Implemented in getCentroid()
        /*
        if (bboxPoints.size() >= 3) {
            Point centroid = getCentroid(bboxPoints);
            gl.glPointSize(8f);
            gl.glColor3f(0,0,1f);
            gl.glBegin(GL2.GL_POINTS);
            gl.glVertex2d(centroid.x, centroid.y);
            gl.glEnd();
        }
         */

        gl.glPopMatrix();

    }
}
