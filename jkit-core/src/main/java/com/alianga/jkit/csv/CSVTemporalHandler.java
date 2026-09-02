package com.alianga.jkit.csv;

import com.alianga.jkit.json.internal.beans.DateParser;
import com.alianga.jkit.json.internal.beans.GregorianDate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * @time 2024/1/12 10:08
 */
public interface CSVTemporalHandler {
    /**
     * {@link LocalDate} 类型的 CSV 字段处理器
     */
    class CSVLocalDateHandler extends CSVTypeHandler<LocalDate> {
        @Override
        public LocalDate handle(String input, Class<LocalDate> type) throws Throwable {
            if (input == null || input.length() == 0) {
                return null;
            }
            GregorianDate date = DateParser.parseDate(input);
            return LocalDate.of(date.getYear(), date.getMonth(), date.getDay());
        }
    }

    /**
     * {@link LocalDateTime} 类型的 CSV 字段处理器
     */
    class CSVLocalDateTimeHandler extends CSVTypeHandler<LocalDateTime> {
        @Override
        public LocalDateTime handle(String input, Class<LocalDateTime> type) throws Throwable {
            if (input == null || input.length() == 0) {
                return null;
            }
            GregorianDate date = DateParser.parseDate(input);
            return LocalDateTime.of(date.getYear(), date.getMonth(), date.getDay(), date.getHourOfDay(),
                    date.getMinute(), date.getSecond());
        }
    }

    /**
     * {@link LocalTime} 类型的 CSV 字段处理器
     */
    class CSVLocalTimeHandler extends CSVTypeHandler<LocalTime> {
        @Override
        public LocalTime handle(String input, Class<LocalTime> type) throws Throwable {
            if (input == null || input.length() == 0) {
                return null;
            }
            return LocalTime.parse(input);
        }
    }

}
