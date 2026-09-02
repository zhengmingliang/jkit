package com.alianga.jkit;

import com.alianga.jkit.log.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class LogTest {
    private static final String LOGGER_NAME = "com.alianga.jkit.test.LogTarget";

    private Logger jul;
    private RecordingHandler handler;

    private static class RecordingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<LogRecord>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Before
    public void setUp() {
        jul = Logger.getLogger(LOGGER_NAME);
        jul.setUseParentHandlers(false);
        jul.setLevel(Level.ALL);
        handler = new RecordingHandler();
        jul.addHandler(handler);
    }

    @After
    public void tearDown() {
        jul.removeHandler(handler);
    }

    @Test
    public void testPlaceholderFormat() {
        Log log = Log.get(LOGGER_NAME);
        log.info("user = {}, age = {}", "Tom", 18);
        assertEquals(1, handler.records.size());
        assertEquals("user = Tom, age = 18", handler.records.get(0).getMessage());
    }

    @Test
    public void testThrowableTailArg() {
        Log log = Log.get(LOGGER_NAME);
        RuntimeException ex = new RuntimeException("demo");
        log.error("读写失败", ex);
        assertEquals(1, handler.records.size());
        LogRecord record = handler.records.get(0);
        assertNotNull(record.getThrown());
        assertSame(ex, record.getThrown());
        assertEquals(Level.SEVERE, record.getLevel());
    }

    @Test
    public void testLevelMethods() {
        Log log = Log.get(LOGGER_NAME);
        log.debug("debug msg {}", 1);
        log.warn("warn msg");
        assertEquals(2, handler.records.size());
        assertEquals(Level.FINE, handler.records.get(0).getLevel());
        assertEquals(Level.WARNING, handler.records.get(1).getLevel());
    }
}
