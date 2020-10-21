package au.edu.wsu;

import java.io.Serializable;

public class dblPoint implements Serializable {
    public double x;
    public double y;

    public dblPoint(double t_x, double t_y) {
        x = t_x;
        y = t_y;
    }

    public dblPoint() {
        x = 0;
        y = 0;
    }
}
