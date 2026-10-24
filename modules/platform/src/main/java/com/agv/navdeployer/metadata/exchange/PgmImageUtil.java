package com.agv.navdeployer.metadata.exchange;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * PGM(P5)/PNG 栅格图像工具：global.pgm / global.png 的生成与解析。
 *
 * <p>语义对齐 ROS map_server：占据(>65)→0 黑，空闲(<20)→254 白，未知(-1)→205 灰；
 * pgm 第 0 行是世界 y 最大一侧，data 第 0 行是 y 最小一侧，读写都做行翻转。
 */
public final class PgmImageUtil {

    private PgmImageUtil() {
    }

    /** 占据栅格 → P5 二进制 pgm。data：-1 未知 / 0 空闲 / 1~100 占据，行优先（第 0 行 y 最小）。 */
    public static byte[] buildPgm(int width, int height, int[] data) {
        byte[] pixels = new byte[width * height];
        for (int row = 0; row < height; row++) {
            int srcRow = height - 1 - row;
            for (int col = 0; col < width; col++) {
                pixels[row * width + col] = (byte) pgmValue(data[srcRow * width + col]);
            }
        }
        String header = "P5\n" + width + " " + height + "\n255\n";
        byte[] head = header.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + pixels.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(pixels, 0, out, head.length, pixels.length);
        return out;
    }

    /** 占据栅格 → 灰度 PNG 预览（与 pgm 同映射）。 */
    public static byte[] buildPng(int width, int height, int[] data) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            int srcRow = height - 1 - y;
            for (int x = 0; x < width; x++) {
                int grey = pgmValue(data[srcRow * width + x]) & 0xFF;
                image.setRGB(x, y, grey << 16 | grey << 8 | grey);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** P5 pgm → 占据栅格（行翻转回 data 语义）。 */
    public static ParsedPgm parsePgm(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 2 || bytes[0] != 'P' || bytes[1] != '5') {
            throw new IllegalArgumentException("pgm 不是 P5 格式");
        }
        int[] idx = {2};
        int width = readToken(bytes, idx);
        int height = readToken(bytes, idx);
        int maxval = readToken(bytes, idx);
        if (maxval > 255) {
            throw new IllegalArgumentException("pgm maxval > 255 不支持");
        }
        idx[0]++; // P5 规范：maxval 后恰好一个空白符
        if (idx[0] + (long) width * height > bytes.length) {
            throw new IllegalArgumentException("pgm 像素数据不完整");
        }
        int[] data = new int[width * height];
        for (int row = 0; row < height; row++) {
            int outRow = height - 1 - row;
            for (int col = 0; col < width; col++) {
                data[outRow * width + col] = occupancyFromPgm(
                        bytes[(int) (idx[0] + row * (long) width + col)] & 0xFF);
            }
        }
        return new ParsedPgm(width, height, data);
    }

    /** 占据值 → pgm 灰度。 */
    public static int pgmValue(int occupancy) {
        if (occupancy == -1) {
            return 205;
        }
        if (occupancy > 65) {
            return 0;
        }
        if (occupancy < 20) {
            return 254;
        }
        return 255 - Math.round(occupancy * 255f / 100f);
    }

    /** pgm 灰度 → 占据值。 */
    public static int occupancyFromPgm(int grey) {
        if (grey == 205) {
            return -1;
        }
        if (grey > 254 - 60) {
            return 0;
        }
        if (grey < 65) {
            return 100;
        }
        return Math.round((255 - grey) * 100f / 255f);
    }

    private static int readToken(byte[] bytes, int[] idx) {
        while (idx[0] < bytes.length && Character.isWhitespace(bytes[idx[0]])) {
            idx[0]++;
        }
        int start = idx[0];
        while (idx[0] < bytes.length && !Character.isWhitespace(bytes[idx[0]])) {
            idx[0]++;
        }
        return Integer.parseInt(new String(bytes, start, idx[0] - start, StandardCharsets.US_ASCII));
    }

    /** 解析结果：占据栅格（行优先，第 0 行 y 最小）。 */
    public record ParsedPgm(int width, int height, int[] data) {
    }
}
