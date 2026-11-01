package com.alianga.jkit.captcha;

import org.junit.Assert;
import org.junit.Test;

public class OpRepairTest {

    @Test
    public void trailingJunkBecomesPlus() {
        Assert.assertEquals("15+10", OpRepair.repair("15t10e7"));
    }

    @Test
    public void junkedTimesBecomesStar() {
        Assert.assertEquals("5*8", OpRepair.repair("5x8e7"));
    }

    @Test
    public void cleanLetterXStays() {
        Assert.assertEquals("7x8", OpRepair.repair("7x8"));
    }

    @Test
    public void alreadyValidExpressionStays() {
        Assert.assertEquals("14+8", OpRepair.repair("14+8"));
    }
}
