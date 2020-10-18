import au.edu.wsu.BBoxObject;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import eu.seebetter.ini.chips.DavisChip;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.event.ApsDvsEvent;
import net.sf.jaer.event.ApsDvsEventPacket;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.eventprocessing.EventFilter;
import net.sf.jaer.eventprocessing.EventFilter2D;
import net.sf.jaer.graphics.Chip2DRenderer;
import net.sf.jaer.graphics.ChipCanvas;
import net.sf.jaer.graphics.FrameAnnotater;
import net.sf.jaer.graphics.ImageDisplay;
import org.jetbrains.annotations.NotNull;
import org.opencv.aruco.Aruco;
import org.opencv.aruco.DetectorParameters;
import org.opencv.aruco.Dictionary;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.swing.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.beans.PropertyChangeEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.opencv.core.CvType.CV_64FC1;
import static org.opencv.core.CvType.CV_8UC3;
import static org.opencv.imgproc.Imgproc.THRESH_BINARY;
import static org.opencv.imgproc.Imgproc.threshold;

// TODO: Initial XML Parsing + Saving/Loading
// NOTE: BBox drawing appears to be a bit shaky for now

/* A class for testing and demonstrating bbox saving, loading, and transformation */

public class BBoxFeatureFilter extends EventFilter2D implements FrameAnnotater {

    static {
        try {
            System.loadLibrary("opencv_java440"); // See `ScratchFilter.java` for static naming justification
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native OpenCV library failed to load.\n" + e);
        }
    }

    private static enum Extraction {
        ResetFrame,
        SignalFrame,
        CDSframe
    }

    final private Logger logger = Logger.getLogger(this.getClass().getName());
    private int tsPreviousWarning;

    private JFrame apsFrame;
    private ImageDisplay apsDisplay;
    private float[] resetBuffer, signalBuffer; // flat-array colored representation

    private float[] CDSBuffer;
    private float CDSoffset = 500; // CDS brightness value
    private Mat CDSMat;
    private int MAX_ADC;
    private float bin_thresh;

    private ArrayList<Mat> corners; // list of Mats containing marker corner coords
    private Mat ids;
    private Map<Integer,Mat> squares;
    private Map<Integer,Mat> oldSquares;
    private ArrayList<double[]> centroids;

    private Mat cameraMatrix;
    private Mat distCoeff;

    private final ArrayList<BBoxObject> bboxList = new ArrayList<>();
    private final ArrayList<BBoxObject> existent = new ArrayList<>(); // display array
    private final BBoxObject currBboxPoints = new BBoxObject();
    // private final Mat transform = Mat.eye(4,4, CvType.CV_8UC1); // the global transformation matrix at the current ts

    // global 2D offsets
    Map<Integer,Point> tsToOffset = new HashMap<>();
    int offsetX;
    int offsetY;
    double rotation;
    Point prevOffset; // what?

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
        apsDisplay = ImageDisplay.createOpenGLCanvas();
        apsFrame = new JFrame("APS ArUco Detection Frame");
        apsFrame.setPreferredSize(new Dimension(640,480));
        apsFrame.getContentPane().add(apsDisplay, BorderLayout.CENTER);
        apsFrame.pack();
        apsFrame.setVisible(true);
    }

    @Override
    public void initFilter() {
        CHIP_WIDTH = chip.getSizeX();
        CHIP_HEIGHT = chip.getSizeY();

        logger.info("width=" + CHIP_WIDTH + " height=" + CHIP_HEIGHT);

        setBin_thresh(0.6f);

        MAX_ADC = ((DavisChip) chip).getMaxADC();

        cameraMatrix = new Mat(3,3,CV_64FC1);
        cameraMatrix.put(0,0, 4.732165566055414843e+02);
        cameraMatrix.put(0,2, 2.148958459598799777e+02);
        cameraMatrix.put(1,1, 4.837252805142607031e+02);
        cameraMatrix.put(1,2, 1.234739411993485589e+02);
        cameraMatrix.put(2,2, 1.0);

        double[] temp = { 2.303407800133863703e-01,-4.906656853722137335e+00,1.437253645436794387e-03,6.785196876901256752e-03,2.673068725658337286e+01 };
        distCoeff = new Mat(1,5,CV_64FC1,new Scalar(temp));

        apsDisplay.setImageSize(CHIP_WIDTH,CHIP_HEIGHT);

        resetBuffer = new float[CHIP_WIDTH * CHIP_HEIGHT];
        signalBuffer = new float[CHIP_WIDTH * CHIP_HEIGHT];
        CDSBuffer = new float[CHIP_WIDTH * CHIP_HEIGHT];

        Arrays.fill(resetBuffer, 0f);
        Arrays.fill(signalBuffer, 0f);
        Arrays.fill(CDSBuffer, 0f);
        CDSMat = new Mat(CHIP_WIDTH, CHIP_HEIGHT, CvType.CV_32FC3, new Scalar(0,0,0));
        centroids = new ArrayList<>();
        corners = new ArrayList<>();
        ids = new Mat();
        squares = new HashMap<>();

        ChipCanvas theCanvas = chip.getCanvas();

        if (theCanvas != null) {
            Chip2DRenderer renderer = chip.getRenderer();

            // Pause/play detection is actually not a good way of knowing when the user wants
            // to create an object. Good example: if they want to create multiple objects at
            // the same timestamp, there would be no physical way to do it -- in the time it
            // takes to press the button again, the timestamp will have changed (>1μs)
            chip.getAeViewer().getAePlayer().pausePlayAction.addPropertyChangeListener(propertyChangeEvent -> {
                PropertyChangeEvent p = propertyChangeEvent; // what a long name!

                // For some reason, these are only valid when they are swapped? TODO: figure this out
                if (p.getOldValue() == "Play" && p.getNewValue() == "Pause") {
                    currBboxPoints.clear();
                }

                // logger.info(p.getPropertyName() + " " + p.getNewValue());
            });

            chip.getAeViewer().addPropertyChangeListener(propertyChangeEvent -> {
                PropertyChangeEvent p = propertyChangeEvent; // what a long name!

                /*
                if (p.getNewValue() == "true") {
                    offsetX = 0;
                    offsetY = 0;
                }
                 */

                logger.info(p.getPropertyName() + " " + p.getNewValue());
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
                            renderer.setXsel((short)(p.x));
                            renderer.setYsel((short)(p.y));
                            // get{}sel() converts on-screen pixel positions to viewer positions
                            currBboxPoints.add(new java.awt.Point(renderer.getXsel(), renderer.getYsel()));
                            chip.getCanvas().paintFrame(); // force-paint the frame

                            String s = "Point = " + renderer.getXsel() + " " + renderer.getYsel();
                            logger.log(Level.INFO, s);
                            break;
                        case MouseEvent.BUTTON3:
                            if (!currBboxPoints.isEmpty()) {
                                currBboxPoints.remove(currBboxPoints.size() - 1);
                                chip.getCanvas().paintFrame(); // force-paint the frame
                            }
                            break;
                        case MouseEvent.BUTTON2:
                            doSaveAsNewObject();
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

    public void doEmptyCalculatedOffsets() {
        tsToOffset.clear();
        // reset the reference points to the middle of the screen at the ts
        offsetX = 0;
        offsetY = 0;
    }

    public float getBin_thresh() {
        return this.bin_thresh;
    }

    public void setBin_thresh(float val) {
        this.bin_thresh = val;
        /*
        float oldval = this.bin_thresh;
        putFloat("bin_thresh",val);
        this.bin_thresh = val;
        support.firePropertyChange("bin_thresh",oldval,val);
         */
    }

    public float getCDSoffset() {
        return this.CDSoffset;
    }

    public void setCDSoffset(float val) {
        float oldval = this.CDSoffset;
        putFloat("offset_x",val);
        this.CDSoffset = val;
        support.firePropertyChange("offset_x",oldval,val);
        chip.getCanvas().paintFrame(); // force-paint the frame
    }

    public void doSaveAsNewObject() {
        if (currBboxPoints.size() < 3) {
            logger.warning("Latest bbox had less than 3 points. Discarding.");
        } else {
            BBoxObject newPoints = new BBoxObject();
            for (Point p : currBboxPoints) {
                // negate the display offsets
                p.x -= offsetX;
                p.y -= offsetY;
                newPoints.add(p);
            }
            newPoints.addAll(currBboxPoints);
            newPoints.setTimestamp(chip.getAeViewer().getAePlayer().getTime());
            bboxList.add(newPoints);
        }

        currBboxPoints.clear();
        chip.getCanvas().paintFrame(); // force-paint the frame
    }

    public void doSaveToFile() throws TransformerException {
        Document doc = builder.newDocument();
        Charset charset = StandardCharsets.UTF_8;

        doc.setXmlStandalone(true);

        Element root = doc.createElement("bboxlist");
        doc.appendChild(root);

        Element thing = doc.createElement("objectlist");
        root.appendChild(thing);


        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();

        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(new File("./froot-files/target.xml"));
        transformer.transform(source, result);
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

        /*
        int retVal = chooser.showOpenDialog(null);

        if (retVal == JFileChooser.APPROVE_OPTION) {
            file = chooser.getSelectedFile();
        } else {
            logger.warning("Selected file could not be opened!");
            return;
        }
        */

        file = new File("./froot-files/bboxprototype.xml");

        try {
            inputStream = new FileInputStream(file);
            Document doc = builder.parse(inputStream);

            NodeList objectlist = doc.getElementsByTagName("objectlist").item(0).getChildNodes();

            for (int i = 0; i < objectlist.getLength(); i++) {
                Node currentNode;
                NodeList children;

                currentNode = objectlist.item(i);

                if (currentNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }

                children = currentNode.getChildNodes();

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

                BBoxObject temp = new BBoxObject();
                temp.addAll(pointArrayList);

                String ts_string = "";
                NamedNodeMap attributes = currentNode.getAttributes();

                if (attributes != null) {
                    Node tsAttr = attributes.getNamedItem("timestamp");
                    ts_string = tsAttr.getNodeValue();
                }

                if (!ts_string.equals("")) {
                    logger.info("ts_string = " + ts_string);
                    temp.setTimestamp(Integer.parseInt(ts_string));
                    bboxList.add(temp);
                } else {
                    logger.warning("bbox object missing timestamp. discarding");
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
        final ApsDvsEventPacket<?> packet = (ApsDvsEventPacket<?>) in;

        if (packet.getEventClass() != ApsDvsEvent.class) {
            return in;
        }

        apsDisplay.checkPixmapAllocation();

        if (packet.getEventClass() != ApsDvsEvent.class) {
            EventFilter.log.warning("wrong input event class, got " + packet.getEventClass() + " but we need to have " + ApsDvsEvent.class);
            return null;
        }
        final Iterator<?> apsItr = packet.fullIterator();
        while (apsItr.hasNext()) {
            final ApsDvsEvent e = (ApsDvsEvent) apsItr.next();
            if (e.isApsData()) {
                ApsDvsEvent.ReadoutType type = e.getReadoutType();
                final float val = e.getAdcSample();
                final int idx = getIndex(e.getX(), e.getY());

                switch (type) {
                    case SignalRead:
                        signalBuffer[idx] = val;
                        break;
                    case ResetRead:
                    default: // FALLTHROUGH!!
                        resetBuffer[idx] = val;
                        break;
                }

                // TODO: the scaled value of the CDSBuffer is too low;
                // an offset of about 500 units appears to be good.

                CDSBuffer[idx] = signalBuffer[idx] - resetBuffer[idx];
                DavisChip apsChip = (DavisChip) chip;
                float scaled_val = (CDSBuffer[idx] + CDSoffset) / (float) MAX_ADC;
                float inv = 1 - scaled_val;
                float[] tempList = new float[3];
                Arrays.fill(tempList,inv);
                CDSMat.put(e.getX(), e.getY(), tempList);
                // apsDisplay.setPixmapGray(e.getX(),e.getY(),scaled_val);
            }
        }

        threshold(CDSMat, CDSMat, bin_thresh, MAX_ADC, THRESH_BINARY);
        Mat detectionInput = CDSMat.clone();
        detectionInput.convertTo(detectionInput, CV_8UC3);

        corners = new ArrayList<>();
        ids = new Mat();
        DetectorParameters parameters = DetectorParameters.create();
        ArrayList<Mat> rejected = new ArrayList<>();
        Dictionary dic = Aruco.getPredefinedDictionary(Aruco.DICT_6X6_250);
        Aruco.detectMarkers(detectionInput, dic, corners, ids, parameters, rejected, cameraMatrix, distCoeff);

        for (int i = 0; i < CHIP_WIDTH; i++) {
            for (int j = 0; j < CHIP_HEIGHT; j++) {
                apsDisplay.setPixmapGray(i,j,(float) CDSMat.get(i,j)[0]);
            }
        }

        /* Doesn't apply GL changes -- needs to obtain drawable surface (?)
        GL2 gl = apsDisplay.getGL().getGL2();

        gl.glPushMatrix();

        gl.glPointSize(8f);
        gl.glColor3f(0,1f,0);

        gl.glBegin(GL2.GL_POINTS);
        for (Mat mat : corners) {
            for (int i = 0; i < 4; ++i) {
                gl.glVertex2d(mat.get(0, i)[0], mat.get(0,i)[1]);
            }
        }
        gl.glEnd();

        gl.glPopMatrix();
        */

        apsDisplay.repaint();

        return in;
    }

    private int getIndex(final int x, final int y) {
        return (y * CHIP_WIDTH) + x;
    }

    @Override
    public void resetFilter() {
        doClearBboxPoints();
        // and eventually others
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
    public void annotate(@NotNull GLAutoDrawable drawable) {
        int ts = chip.getAeViewer().getAePlayer().getTime();
        GL2 gl = drawable.getGL().getGL2();

        gl.glPushMatrix();

        gl.glPointSize(8f);
        gl.glColor3f(0,1f,1f);

        // TODO: it appears that it is not possible to use ArrayList operations between glBegin() and glEnd(); most likely because of thread safety
        // change it to Vector to see if it allows it to occur.

        Point currentOffset = new Point(0,0); // containing fallback offsets

        // TODO (BUG): By using the hashtable to store the offsets statically, the calculated values start bouncing around when markers appear into the frame
        if (tsToOffset.containsKey(ts)) { // there is already an entry for the timestamp
            // log.info("found stored offset at " + ts);
            Point saved = tsToOffset.get(ts);
            offsetX = saved.x;
            offsetY = saved.y;
        } else { // calculate offset and store it for this timestamp
            // log.info("no stored offset at " + ts);
            if (corners != null && !corners.isEmpty()) { // corners is non-zero

                ArrayList<Integer> idList = new ArrayList<>();

                // populate the new ID list
                for (int i = 0; i < ids.size().height; i++) {
                    idList.add((int) ids.get(i, 0)[0]);
                }

                // make the list of keys
                ArrayList<Integer> oldIds = new ArrayList<>(squares.keySet());

                // if an id doesn't appear in the new list, nullify the entry in squares
                for (int oldId : oldIds ) { // mightily inefficient
                    if (!idList.contains(oldId)) { // a marker disappeared
                        squares.remove(oldId); // nullify entry
                    }
                }

                // build up the new list of squares and calculate offsets
                for (int i = 0; i < idList.size(); i++) {
                    int id = idList.get(i); // IDs have the same ordering as the markers
                    Mat current = corners.get(i); // current marker square
                    Mat old = squares.get(id);

                    // logger.info("Adding square #" + id + " to squares");

                    if (old != null) {
                        currentOffset.x = (int)(current.get(0,1)[1] - old.get(0,1)[1]);
                        currentOffset.y = (int)(current.get(0,1)[0] - old.get(0,1)[0]);
                    } else {
                        log.info("inserting new marker: id=" + id + " (" + current.get(0,1)[1] + " " + current.get(0,1)[0] + ")");
                    }

                    // insert marker using its id
                    // marker will be present for the next iteration if it is currently null
                    squares.put(id,current);
                }
            } else {
                // nothing? manage branching effects?
            }

            // log.info("Oldsquares:\n" + oldSquares.toString());
            // log.info("Squares:\n" + squares.toString());

            offsetX += currentOffset.x;
            offsetY += currentOffset.y;


            // save offsets to the hashtable
            tsToOffset.put(ts,new Point(offsetX, offsetY));
            Point temp = new Point();
            temp.x = tsToOffset.get(ts).x;
            temp.y = tsToOffset.get(ts).y;
            log.info("current offset: " + offsetX + ", " + offsetY + "\n"
            + "saved offset: " + temp.x + ", " + temp.y);
        }

        gl.glPointSize(8f);
        gl.glColor3f(0,1f,1f);

        gl.glBegin(GL2.GL_POINTS);

        for (Mat current : corners) {
            // coords are flipped ???
            gl.glVertex2d(current.get(0, 1)[1], current.get(0, 1)[0]);
        }

        gl.glEnd();

        gl.glPointSize(8f);
        gl.glColor3f(0,0,1f);

        // Draw the bbox points of each object
        gl.glBegin(GL2.GL_POINTS);

        for (BBoxObject obj : bboxList) {
            if (obj.getTimestamp() > ts) {
                continue; // bbox doesn't exist yet
            }

            for (Point p : obj) {
                gl.glVertex2d(p.getX() + offsetX, p.getY() + offsetY);
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
                gl.glVertex2d(p.getX() + offsetX, p.getY() + offsetY);
            }
            gl.glEnd();
        }

        gl.glColor3f(0,1f,0);

        gl.glBegin(GL2.GL_LINE_LOOP);
        for (final Point p : currBboxPoints) {
            gl.glVertex2d(p.getX(), p.getY());
        }
        gl.glEnd();

        gl.glPopMatrix();
    }
}
