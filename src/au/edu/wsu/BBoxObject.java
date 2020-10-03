package au.edu.wsu;

import java.awt.*;
import java.util.ArrayList;

public class BBoxObject extends ArrayList<Point> {
    private int timestamp;

    public void setTimestamp(int timestamp) {
        this.timestamp = timestamp;
    }

    public int getTimestamp() {
        return timestamp;
    }
}
