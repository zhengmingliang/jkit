package com.alianga.jkit.captcha;

/**
 * 统计字形内部的封闭孔。孔数能把 8/B 与 S、D 与 B 这类外形接近的字分开。
 */
final class HoleCount {
    private HoleCount() {
    }

    static int count(byte[] pixels, int width, int height) {
        byte[] seen = new byte[pixels.length];
        int[] stackX = new int[pixels.length];
        int[] stackY = new int[pixels.length];
        floodBorder(pixels, seen, stackX, stackY, width, height);
        int holes = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (pixels[index] != 0 || seen[index] != 0) {
                    continue;
                }
                int area = floodHole(pixels, seen, stackX, stackY, width, height, x, y);
                if (area >= 3) {
                    holes++;
                }
            }
        }
        return holes > 2 ? 2 : holes;
    }

    /**
     * 该字符在常见字体里稳定的孔数。不确定的返回 -1，调用方不要用它否决模板。
     *
     * @param label 候选字符
     * @return 0、1、2，或 -1
     */
    static int expected(char label) {
        switch (label) {
            case '8':
            case 'B':
                return 2;
            case '0':
            case '6':
            case '9':
            case 'A':
            case 'D':
            case 'O':
            case 'P':
            case 'Q':
            case 'R':
            case 'a':
            case 'b':
            case 'd':
            case 'e':
            case 'o':
            case 'p':
            case 'q':
                return 1;
            case '1':
            case '2':
            case '3':
            case '5':
            case '7':
            case 'C':
            case 'E':
            case 'F':
            case 'H':
            case 'I':
            case 'K':
            case 'L':
            case 'M':
            case 'N':
            case 'S':
            case 'T':
            case 'U':
            case 'V':
            case 'W':
            case 'X':
            case 'Y':
            case 'Z':
            case 'c':
            case 'f':
            case 'h':
            case 'k':
            case 'm':
            case 'n':
            case 'r':
            case 's':
            case 't':
            case 'u':
            case 'v':
            case 'w':
            case 'x':
            case 'y':
            case 'z':
                return 0;
            default:
                return -1;
        }
    }

    private static void floodBorder(byte[] pixels, byte[] seen, int[] stackX, int[] stackY,
                                    int width, int height) {
        for (int x = 0; x < width; x++) {
            floodHole(pixels, seen, stackX, stackY, width, height, x, 0);
            floodHole(pixels, seen, stackX, stackY, width, height, x, height - 1);
        }
        for (int y = 0; y < height; y++) {
            floodHole(pixels, seen, stackX, stackY, width, height, 0, y);
            floodHole(pixels, seen, stackX, stackY, width, height, width - 1, y);
        }
    }

    private static int floodHole(byte[] pixels, byte[] seen, int[] stackX, int[] stackY,
                                 int width, int height, int startX, int startY) {
        int index = startY * width + startX;
        if (pixels[index] != 0 || seen[index] != 0) {
            return 0;
        }
        int top = 0;
        stackX[0] = startX;
        stackY[0] = startY;
        seen[index] = 1;
        int area = 0;
        while (top >= 0) {
            int x = stackX[top];
            int y = stackY[top];
            top--;
            area++;
            top = push(pixels, seen, stackX, stackY, top, width, height, x + 1, y);
            top = push(pixels, seen, stackX, stackY, top, width, height, x - 1, y);
            top = push(pixels, seen, stackX, stackY, top, width, height, x, y + 1);
            top = push(pixels, seen, stackX, stackY, top, width, height, x, y - 1);
        }
        return area;
    }

    private static int push(byte[] pixels, byte[] seen, int[] stackX, int[] stackY, int top,
                            int width, int height, int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return top;
        }
        int index = y * width + x;
        if (pixels[index] != 0 || seen[index] != 0) {
            return top;
        }
        seen[index] = 1;
        top++;
        stackX[top] = x;
        stackY[top] = y;
        return top;
    }
}
