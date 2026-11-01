package com.alianga.jkit.captcha;

import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

public class MinusInsertTest {

    @Test
    public void fontMinusBetweenDigitsIsRestored() {
        BufferedImage image = darkPlate(220, 58);
        Graphics2D graphics = image.createGraphics();
        graphics.setFont(new Font("Verdana", Font.BOLD, 36));
        graphics.setColor(new Color(230, 150, 70));
        graphics.drawString("23", 16, 44);
        graphics.setColor(new Color(200, 200, 210));
        graphics.fillRect(92, 30, 22, 5);
        graphics.setColor(new Color(90, 190, 230));
        graphics.drawString("10", 130, 44);
        graphics.dispose();
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        try {
            Assert.assertEquals("23-10", recognizer.recognize(image).getText());
        } finally {
            recognizer.close();
        }
    }

    @Test
    public void digitsWithoutAGapBarStayDigits() {
        BufferedImage image = darkPlate(200, 58);
        Graphics2D graphics = image.createGraphics();
        graphics.setFont(new Font("Verdana", Font.BOLD, 36));
        graphics.setColor(new Color(230, 150, 70));
        graphics.drawString("4130", 16, 44);
        graphics.setColor(new Color(170, 170, 180));
        graphics.drawLine(0, 10, 199, 48);
        graphics.dispose();
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        try {
            String text = recognizer.recognize(image).getText();
            Assert.assertFalse(text, text.contains("-"));
        } finally {
            recognizer.close();
        }
    }

    @Test
    public void historicalPlusStaysPlus() throws Exception {
        BufferedImage image = ImageIO.read(new File("src/test/resources/samples/math/46+33.jpeg"));
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        try {
            Assert.assertEquals("46+33", recognizer.recognize(image).getExpression());
        } finally {
            recognizer.close();
        }
    }

    @Test
    public void historicalMinusStaysMinus() throws Exception {
        BufferedImage image = ImageIO.read(new File("src/test/resources/samples/math/48-36.jpeg"));
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        try {
            Assert.assertEquals("48-36", recognizer.recognize(image).getExpression());
        } finally {
            recognizer.close();
        }
    }

    private static BufferedImage darkPlate(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(24, 24, 32));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }
}
