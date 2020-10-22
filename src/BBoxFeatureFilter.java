import au.edu.wsu.BBoxObject;
import au.edu.wsu.dblPoint;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import eu.seebetter.ini.chips.DavisChip;
import javafx.util.Pair;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.event.ApsDvsEvent;
import net.sf.jaer.event.ApsDvsEventPacket;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.eventprocessing.EventFilter;
import net.sf.jaer.eventprocessing.EventFilter2D;
import net.sf.jaer.graphics.*;
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

    private int tsBegin;

    private JFrame apsFrame;
    private ImageDisplay apsDisplay;
    private float[] resetBuffer, signalBuffer; // flat-array colored representation

    private float[] CDSBuffer;
    private float CDSoffset = 500; // CDS brightness value
    private Mat CDSMat;
    private int MAX_ADC;
    private float bin_thresh;

    private ArrayList<Mat> corners = new ArrayList<>(); // list of Mats containing marker corner coords
    private Mat ids = new Mat();
    private Map<Integer,Mat> squares = new HashMap<>(100000);

    private Mat cameraMatrix;
    private Mat distCoeff;

    private int previousTimestamp;
    private final ArrayList<BBoxObject> bboxList = new ArrayList<>();
    // private final ArrayList<BBoxObject> visible = new ArrayList<>(); // box display array
    private final BBoxObject currBboxPoints = new BBoxObject();
    // private final Mat transform = Mat.eye(4,4, CvType.CV_8UC1); // the global transformation matrix at the current ts

    // global 2D offsets
    Map<Integer, Pair<dblPoint,Double>> tsToOffset = new HashMap<>();
    int offsetX;
    int offsetY;
    double rotation; // rotation in radians

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

    @Override
    public void initFilter() {
        AEViewer aeViewer = chip.getAeViewer().getAePlayer().getViewer();
        AEPlayer aePlayer = (AEPlayer) chip.getAeViewer().getAePlayer();

        CHIP_WIDTH = chip.getSizeX();
        CHIP_HEIGHT = chip.getSizeY();

        logger.info("width=" + CHIP_WIDTH + " height=" + CHIP_HEIGHT);

        apsDisplay = ImageDisplay.createOpenGLCanvas();
        apsFrame = new JFrame("APS ArUco Detection Frame");
        apsFrame.setPreferredSize(new Dimension(640,480));
        apsFrame.getContentPane().add(apsDisplay, BorderLayout.CENTER);
        apsFrame.pack();
        apsFrame.setVisible(false);

        setBin_thresh(0.6f);

        /*
        // initial object loading
        for (BBoxObject obj : bboxList) {
            BBoxObject newObj = new BBoxObject();
            newObj.addAll(obj);
            newObj.setTimestamp(obj.getTimestamp());
            // visible.add(newObj);
        }
         */

        MAX_ADC = ((DavisChip) chip).getMaxADC();

        // OpenCV-specific parameters
        cameraMatrix = new Mat(3,3,CV_64FC1);
        cameraMatrix.put(0,0, 4.732165566055414843e+02);
        cameraMatrix.put(0,2, 2.148958459598799777e+02);
        cameraMatrix.put(1,1, 4.837252805142607031e+02);
        cameraMatrix.put(1,2, 1.234739411993485589e+02);
        cameraMatrix.put(2, 2, 1.0);
        double[] temp = new double[]{
                2.303407800133863703e-01,
                -4.906656853722137335e+00,
                1.437253645436794387e-03,
                6.785196876901256752e-03,
                2.673068725658337286e+01
        };
        distCoeff = new Mat(1,5,CV_64FC1,new Scalar(temp));

        apsDisplay.setImageSize(CHIP_WIDTH,CHIP_HEIGHT);

        resetBuffer = new float[CHIP_WIDTH * CHIP_HEIGHT];
        signalBuffer = new float[CHIP_WIDTH * CHIP_HEIGHT];
        CDSBuffer = new float[CHIP_WIDTH * CHIP_HEIGHT];

        Arrays.fill(resetBuffer, 0f);
        Arrays.fill(signalBuffer, 0f);
        Arrays.fill(CDSBuffer, 0f);
        CDSMat = new Mat(CHIP_WIDTH, CHIP_HEIGHT, CvType.CV_32FC3, new Scalar(0,0,0));

        ChipCanvas theCanvas = chip.getCanvas();

        if (theCanvas != null) {
            Chip2DRenderer renderer = chip.getRenderer();

            chip.getAeViewer().getAePlayer().pausePlayAction.addPropertyChangeListener(propertyChangeEvent -> {
                // For some reason, these are only valid when they are swapped? TODO: figure this out
                if (propertyChangeEvent.getPropertyName().equals(AEViewer.EVENT_PLAYMODE)) {
                    currBboxPoints.clear();
                }

                // logger.info(p.getPropertyName() + " " + p.getNewValue());
            });

            // TODO: get rewind detection working
            // general property change event manager
            chip.getAeViewer().addPropertyChangeListener(propertyChangeEvent -> {
                logger.info(propertyChangeEvent.getPropertyName() + " " + propertyChangeEvent.getNewValue());
                switch (propertyChangeEvent.getPropertyName()) {
                    case AEPlayer.EVENT_FILEOPEN:
                        tsBegin = aePlayer.getFirstTimestamp();
                        System.out.println("tsBegin = " + tsBegin);
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

                    switch (mouseEvent.getButton()) {
                        case MouseEvent.BUTTON1:
                            renderer.setXsel((short)(p.x));
                            renderer.setYsel((short)(p.y));
                            // get{}sel() converts on-screen pixel positions to viewer positions
                            currBboxPoints.add(new dblPoint(renderer.getXsel(), renderer.getYsel()));
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

    @Override
    public void resetFilter() {
        doClearBboxPoints();
        // and eventually others
    }

    @Override
    public EventPacket<?> filterPacket(EventPacket<?> in) {
        final ApsDvsEventPacket<?> packet = (ApsDvsEventPacket<?>) in;

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

        // standard OpenCV ArUco detection
        threshold(CDSMat, CDSMat, bin_thresh, MAX_ADC, THRESH_BINARY);
        Mat detectionInput = CDSMat.clone();
        detectionInput.convertTo(detectionInput, CV_8UC3);

        corners = new ArrayList<>();
        ids = new Mat();
        DetectorParameters parameters = DetectorParameters.create();
        ArrayList<Mat> rejected = new ArrayList<>();
        Dictionary dic = Aruco.getPredefinedDictionary(Aruco.DICT_6X6_250);
        Aruco.detectMarkers(detectionInput, dic, corners, ids, parameters, rejected, cameraMatrix, distCoeff);

        if (corners.isEmpty()) {
            return in;
        }

        apsDisplay.repaint();

        int ts = chip.getAeViewer().getAePlayer().getTime();

        if (ts < previousTimestamp) {
            previousTimestamp = ts;
            return in;
        }

        // Populate the pixmap
        for (int i = 0; i < CHIP_WIDTH; i++) {
            for (int j = 0; j < CHIP_HEIGHT; j++) {
                apsDisplay.setPixmapGray(i,j,(float) CDSMat.get(i,j)[0]);
            }
        }

        if (ts != previousTimestamp) {
            Point currentOffset = new Point(0,0); // containing fallback offsets
            double angle = 0;

            // TODO (BUG): By using the hashtable to store the offsets statically, the
            // calculated values start bouncing around when markers appear into the frame
            // The positive section of the if block appears to be called every second,
            // regardless of the actual timestamp. Probably something to do with the
            // annotate function?

            if (tsToOffset.containsKey(ts)) { // there is already an entry for the timestamp
                // Print of the next line in the first run of the data indicates that the bbox bouncing is caused by
                System.out.println("up here! " + ts);
                Pair<dblPoint,Double> saved = tsToOffset.get(ts);
                dblPoint savedPoint = saved.getKey();
                offsetX = (int) savedPoint.x;
                offsetY = (int) savedPoint.y;
                // rotation = saved.getValue(); // get saved rotation
                // log.info("found stored offset at " + ts + "\n" + saved.x + ", " + saved.y);
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
                            // System.out.println(oldId + " disappeared");
                        }
                    }
                    // build up the new list of squares and calculate offsets
                    for (int i = 0; i < idList.size(); i++) {
                        int id = idList.get(i); // IDs have the same ordering as the markers
                        Mat current = corners.get(i); // current marker square
                        Mat old = squares.get(id);
                        // logger.info("Adding square #" + id + " to squares");

                        // TODO: get rotation detection working
                        if (old != null) {
                            currentOffset.x = (int)(current.get(0,0)[1] - old.get(0,0)[1]);
                            currentOffset.y = (int)(current.get(0,0)[0] - old.get(0,0)[0]);
                            double[] topLeft = current.get(0,0);
                            double[] topRight = current.get(0,1);
                            double dist = getDistance(topLeft,topRight); // Euclidean distance
                            double sinVal = (topRight[0] - topLeft[0]) * dist;
                            angle = (topRight[1] >= topLeft[1]) ? Math.asin(sinVal) : Math.PI - Math.asin(sinVal);
                        } else {
                            // System.out.println("inserting new marker: id=" + id + " (" + current.get(0,1)[1] + " " + current.get(0,1)[0] + ")");
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

                if (ts > previousTimestamp) { // temporary fix for non-monotonic ts issue
                    offsetX += currentOffset.x;
                    offsetY += currentOffset.y;
                    rotation = 0;
                    // dblPoint temp = new dblPoint(offsetX,offsetY);
                    // log.info("current offset: " + offsetX + ", " + offsetY + "\n" + "saved offset for " + ts + " : \n" + temp.x + ", " + temp.y);
                    tsToOffset.put(ts,new Pair<dblPoint,Double>(new dblPoint(offsetX, offsetY), rotation));
                } else {
                    // maybe something here?
                }
            }
        }

        previousTimestamp = ts;

        return in;
    }

    @Override
    public void annotate(@NotNull GLAutoDrawable drawable) {
        // TODO: it appears that it is not possible to use ArrayList operations between glBegin() and glEnd(); most likely because of thread safety
        // change it to Vector to see if it allows it to occur.
        int ts = chip.getAeViewer().getAePlayer().getTime();

        GL2 gl = drawable.getGL().getGL2();

        gl.glPushMatrix();

        gl.glPointSize(8f);
        gl.glColor3f(0,1f,1f);

        // Draw marker corners
        gl.glPointSize(8f);
        gl.glColor3f(0,1f,1f);
        gl.glBegin(GL2.GL_POINTS);
        for (Mat current : corners) {
            // top-left. coords are flipped ???
            gl.glVertex2d(current.get(0, 0)[1], current.get(0, 0)[0]);
        }
        gl.glEnd();

        /*
        // Draw the bbox points of each object
        gl.glPointSize(8f);
        gl.glColor3f(0,0,1f);
        gl.glBegin(GL2.GL_POINTS);
        for (BBoxObject obj : bboxList) {
            // bbox exists; apply offsets
            if (ts < obj.getTimestamp()) {
                continue; // bbox doesn't exist yet or is already included
            }

            for (dblPoint p : obj) {
                double x = p.x + offsetX;
                double y = p.y + offsetY;
                change the visible position of points
                p.x = x*Math.cos(rotation) - y*Math.sin(rotation);
                p.y = x*Math.sin(rotation) + y*Math.cos(rotation);
                p.x = x;
                p.y = y;
                gl.glVertex2d(x,y);
            }
        }
        gl.glEnd();
         */

        // draw bbox lines
        gl.glPointSize(3f);
        gl.glColor3f(0,0,1f);
        for (BBoxObject obj : bboxList) {
            gl.glBegin(GL2.GL_LINE_LOOP);
            // bbox exists; apply offsets
            if (ts >= obj.getTimestamp()) {
                for (dblPoint p : obj) {
                    double x = p.x + offsetX;
                    double y = p.y + offsetY;
                    /* change the visible position of points
                p.x = x*Math.cos(rotation) - y*Math.sin(rotation);
                p.y = x*Math.sin(rotation) + y*Math.cos(rotation);
                p.x = x;
                p.y = y;
                 */
                    gl.glVertex2d(x,y);
                }
            }
            gl.glEnd();
        }

        // draw the bbox points of the pending object
        gl.glPointSize(8f);
        gl.glColor3f(0,1f,0);
        gl.glBegin(GL2.GL_POINTS);
        for (dblPoint p : currBboxPoints) {
            gl.glVertex2d(p.x,p.y);
        }
        gl.glEnd();

        /*
        // Draw the loops that go through all the bbox points
        gl.glLineWidth(2f);
        gl.glColor3f(0,0,1f);
        for (final BBoxObject obj : bboxList) {
            if (obj.getTimestamp() > ts) {
                continue; // bbox doesn't exist yet
            }

            gl.glBegin(GL2.GL_LINE_LOOP);

            for (final Point p : obj) {
                double x = p.getX();
                double y = p.getY();
                vertexCalcMacro(gl,x,y);
            }
            gl.glEnd();
        }
        gl.glColor3f(0,1f,0);

        gl.glBegin(GL2.GL_LINE_LOOP);
        for (final Point p : currBboxPoints) {
            double x = p.getX();
            double y = p.getY();
            vertexCalcMacro(gl,x,y);
        }
        gl.glEnd();
         */

        gl.glPopMatrix();
    }

    /*
    public void doClearVisibleBboxPoints() {
        visible.clear();
    }
     */

    public void doEmptyCalculatedOffsets() {
        tsToOffset.clear();
        // reset the reference points to the middle of the screen at the ts
        offsetX = 0;
        offsetY = 0;
        rotation = 0;
    }

    public void doFlushDetectedSquares() {
        corners = new ArrayList<>();
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
        } else { // TODO: drastically reformulate this
            BBoxObject newPoints = new BBoxObject();
            newPoints.addAll(currBboxPoints);
            newPoints.setTimestamp(chip.getAeViewer().getAePlayer().getTime());
            bboxList.add(newPoints);
            /*
            newPoints = new BBoxObject();
            newPoints.addAll(currBboxPoints);
            newPoints.setTimestamp(chip.getAeViewer().getAePlayer().getTime());
            visible.add(newPoints);
             */
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

                ArrayList<dblPoint> pointArrayList = new ArrayList<>();

                for (int objNodeIdx = 0; objNodeIdx < children.getLength(); objNodeIdx++) {
                    Node objNode = children.item(objNodeIdx);

                    if (objNode.getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }

                    logger.info(objNode.getTextContent());
                    dblPoint point = parseCoordinates(objNode.getTextContent());
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
    private dblPoint parseCoordinates(String coords) {
        dblPoint p = new dblPoint(-1,-1);

        Pattern pattern = Pattern.compile("(\\d+)");
        Matcher matcher = pattern.matcher(coords);

        if (matcher.find())
            p.x = Integer.parseInt(matcher.group(1));
        if (matcher.find())
            p.y = Integer.parseInt(matcher.group(1));

        return p;
    }

    private double getDistance(double[] p0, double[] p1) {
        // Note that OpenCV has y and x as the first and second coordinates, respectively,
        // but the order does not matter in the calculation of the Euclidean distance.
        return Math.sqrt(Math.pow(p0[0] - p1[0], 2) + Math.pow(p0[1] - p1[1], 2)) ;
    }

    private int getIndex(final int x, final int y) {
        return (y * CHIP_WIDTH) + x;
    }


}
