package com.alianga.jkit.captcha;

import org.junit.Assert;
import org.junit.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public class CaseVoteTest {

    @Test
    public void tallUppercaseLettersStayUpper() {
        BufferedImage image = new BufferedImage(220, 58, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 220, 58);
        graphics.setColor(new Color(30, 30, 40));
        graphics.setFont(new Font("Verdana", Font.BOLD, 36));
        graphics.drawString("NASG", 24, 44);
        graphics.dispose();
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        try {
            Assert.assertEquals("NASG", recognizer.recognize(image).getText());
        } finally {
            recognizer.close();
        }
    }

    @Test
    public void questionnairePlateIsLeftAlone() {
        BufferedImage image = new BufferedImage(284, 80, BufferedImage.TYPE_INT_RGB);
        TextModel model = new TextModel();
        try {
            Assert.assertEquals("V7QZ", CaseVote.vote(image, "V7QZ", model));
        } finally {
            model.close();
        }
    }

    @Test
    public void shortPlateIsNotRecasedHere() {
        BufferedImage image = new BufferedImage(150, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 150, 40);
        graphics.dispose();
        TextModel model = new TextModel();
        try {
            Assert.assertEquals("ab", CaseVote.vote(image, "ab", model));
        } finally {
            model.close();
        }
    }
}
