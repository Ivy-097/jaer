package au.edu.wsu;

import java.awt.*;
import java.util.ArrayList;

public class BBoxObject extends ArrayList<Point> {
    private int timestamp;

    public BBoxObject() {
        super();
    }
    public BBoxObject(int ts) {
        this.timestamp = ts;
    }

    public void setTimestamp(int timestamp) {
        this.timestamp = timestamp;
    }

    public int getTimestamp() {
        return timestamp;
    }
}
