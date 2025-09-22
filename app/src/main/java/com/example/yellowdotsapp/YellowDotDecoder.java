package yellowdots;

import android.graphics.Bitmap;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts yellow tracking dots from a bitmap and decodes them
 * using the DocuColorDecoder.
 */
public class YellowDotDecoder {

    public static class DecodeOutput {
        public boolean success;
        public String error;
        public String model = "Unknown";
        public String serial;
        public String timestamp;
        public int year, month, day, hour, minute;
        public boolean[][] bits;

        @Override
        public String toString() {
            if (!success) return "Decode failed: " + error;
            return "Model: " + model + "\n" +
                   "Serial: " + serial + "\n" +
                   "Printed: " + timestamp + "\n";
        }
    }

    // ---------- Public API ----------
    public DecodeOutput process(Bitmap input) {
        DecodeOutput out = new DecodeOutput();
        if (input == null) {
            out.success = false;
            out.error = "Bitmap null";
            return out;
        }

        Bitmap bmp = maybeDownscale(input, 3000);
        Mat bgr = new Mat();
        Utils.bitmapToMat(bmp, bgr);
        Imgproc.cvtColor(bgr, bgr, Imgproc.COLOR_RGBA2BGR);

        // 1) Isolate yellow
        Mat mask = maskYellow(bgr);

        // 2) Clean
        Imgproc.medianBlur(mask, mask, 3);
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN,
                Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3)));
        Imgproc.dilate(mask, mask,
                Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(2, 2)));

        // 3) Detect dots
        List<Point> dots = detectDots(mask);
        if (dots.size() < 40) {
            out.success = false;
            out.error = "Too few dots (" + dots.size() + ")";
            return out;
        }

        // 4) Fit lattice
        boolean[][] bits = fitAndOrientGrid(dots);
        if (bits == null) {
            out.success = false;
            out.error = "Could not fit 8x15 grid";
            return out;
        }
        out.bits = bits;

        // 5) Decode
        DocuColorDecoder.Result r = DocuColorDecoder.decode(bits);
        if (!r.success) {
            out.success = false;
            out.error = r.error;
            return out;
        }
        out.success = true;
        out.model = "Xerox DocuColor 15x8";
        out.serial = r.serial;
        out.year = r.year;
        out.month = r.month;
        out.day = r.day;
        out.hour = r.hour;
        out.minute = r.minute;
        out.timestamp = String.format("%04d-%02d-%02d %02d:%02d",
                r.year, r.month, r.day, r.hour, r.minute);
        return out;
    }

    private Mat maskYellow(Mat bgr) {
        Mat hsv = new Mat();
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);
        Mat mask = new Mat(hsv.size(), CvType.CV_8UC1);
        Core.inRange(hsv, new Scalar(20, 60, 60), new Scalar(35, 255, 255), mask);
        return mask;
    }

    private List<Point> detectDots(Mat mask) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(mask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<Point> centers = new ArrayList<>();
        for (MatOfPoint c : contours) {
            Rect bb = Imgproc.boundingRect(c);
            double area = Imgproc.contourArea(c);
            if (area < 2 || area > 500) continue;
            if (bb.width > 20 || bb.height > 20) continue;
            Point centroid = contourCentroid(c);
            if (centroid != null) centers.add(centroid);
        }
        return centers;
    }

    private Point contourCentroid(MatOfPoint c) {
        MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
        org.opencv.imgproc.Moments m = Imgproc.moments(c2f);
        if (m.get_m00() == 0) return null;
        return new Point(m.get_m10() / m.get_m00(), m.get_m01() / m.get_m00());
    }

    // ---------- Grid fit ----------
    private boolean[][] fitAndOrientGrid(List<Point> pts) {
        if (pts.size() < 40) return null;

        // Compute bounding box
        double minX = Double.MAX_VALUE, maxX = -1e9;
        double minY = Double.MAX_VALUE, maxY = -1e9;
        for (Point p : pts) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }

        int cols = 15, rows = 8;
        double stepX = (maxX - minX) / (cols - 1);
        double stepY = (maxY - minY) / (rows - 1);

        boolean[][] grid = new boolean[rows][cols];
        double tolX = stepX * 0.4, tolY = stepY * 0.4;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double gx = minX + c * stepX;
                double gy = minY + r * stepY;
                for (Point p : pts) {
                    if (Math.abs(p.x - gx) < tolX && Math.abs(p.y - gy) < tolY) {
                        grid[r][c] = true;
                        break;
                    }
                }
            }
        }

        if (!checkParity(grid)) return null;
        return grid;
    }

    private boolean checkParity(boolean[][] bits) {
        if (bits == null) return false;
        for (int c = 0; c < bits[0].length; c++) {
            int ones = 0;
            for (int r = 0; r < bits.length; r++)
                if (bits[r][c]) ones++;
            if (ones % 2 == 0) return false;
        }
        return true;
    }

    private Bitmap maybeDownscale(Bitmap src, int maxDim) {
        int w = src.getWidth(), h = src.getHeight();
        int m = Math.max(w, h);
        if (m <= maxDim) return src;
        float scale = maxDim / (float) m;
        int nw = Math.round(w * scale);
        int nh = Math.round(h * scale);
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }
}
