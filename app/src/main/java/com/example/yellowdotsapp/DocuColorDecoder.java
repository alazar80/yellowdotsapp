package yellowdots;

/**
 * Decoder for Xerox DocuColor-style 15x8 grid per EFF mapping:
 *  - Columns numbered 1..15 left-to-right. We read bytes right-to-left.
 *  - Row 0 is the parity row (excluded from bytes); Column 1 is row parity; Column 0 is the left parity column.
 *  - Each column encodes a 7-bit value from rows 1..7, top-to-bottom (MSB at row1).
 *  - Column mapping (left-to-right numbering, per EFF):
 *      15: unknown (often constant)
 *      14,13,12,11: serial number (BCD, two decimal digits per byte; sometimes only 13..11 used)
 *      10: separator (typically all ones / 0x7F)
 *      9: unused
 *      8: year (00..99)
 *      7: month (1..12)
 *      6: day (1..31)
 *      5: hour (0..23)
 *      4: unused
 *      3: unused
 *      2: minute (0..59)
 *      1: row parity bit
 */
public class DocuColorDecoder {

    public static class Result {
        public boolean success;
        public String error;
        public String serial;
        public int year, month, day, hour, minute;
    }

    public static Result decode(boolean[][] bits) {
        Result r = new Result();
        if (bits == null || bits.length != 8 || bits[0].length != 15) {
            r.success = false;
            r.error = "Expected 8x15 bit grid.";
            return r;
        }
        // Extract 7-bit column bytes from rows 1..7 (exclude row0 parity)
        int[] colVal = new int[16]; // 1..15 used
        for (int c = 0; c < 15; c++) {
            int val = 0;
            for (int row = 1; row <= 7; row++) {
                val = (val << 1) | (bits[row][c] ? 1 : 0);
            }
            colVal[c + 1] = val;
        }

        // Serial: columns 14..11
        StringBuilder serial = new StringBuilder();
        for (int c = 14; c >= 11; c--) {
            serial.append(toTwoDigits(colVal[c]));
        }
        String serialStr = serial.toString();
        if (serialStr.matches("^0+$")) {
            serialStr = toTwoDigits(colVal[13]) + toTwoDigits(colVal[12]) + toTwoDigits(colVal[11]);
        } else {
            serialStr = serialStr.replaceFirst("^0+(?!$)", "");
        }

        // Date/time
        int yy = colVal[8];
        int mm = colVal[7];
        int dd = colVal[6];
        int hh = colVal[5];
        int mi = colVal[2];

        if (mm < 1 || mm > 12 || dd < 1 || dd > 31 || hh < 0 || hh > 23 || mi < 0 || mi > 59) {
            r.success = false;
            r.error = "Unreasonable date/time values.";
            return r;
        }

        int yearFull = (yy <= 90 ? 2000 + yy : 1900 + yy);

        r.success = true;
        r.serial = serialStr;
        r.year = yearFull;
        r.month = mm;
        r.day = dd;
        r.hour = hh;
        r.minute = mi;
        return r;
    }

    private static String toTwoDigits(int val) {
        int v = Math.max(0, Math.min(99, val));
        if (v < 10) return "0" + v;
        return Integer.toString(v);
    }
}
