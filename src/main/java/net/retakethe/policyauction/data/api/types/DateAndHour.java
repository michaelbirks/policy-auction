package net.retakethe.policyauction.data.api.types;

import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import org.apache.commons.lang.time.DateFormatUtils;

/**
 * Cassandra type for values grouped into date+hour intervals.
 *
 * @author Nick Clarke
 */
public class DateAndHour implements Serializable {
    private static final long serialVersionUID = 0L;

    private static final String DATE_AND_HOUR_FORMAT = "yyyyMMdd-HH00";
    private static final String DATE_AND_HOUR_PARSE_FORMAT = "yyyyMMdd-HH00-z";

    private final GregorianCalendar time;

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT"); 

    /**
     * Construct by parsing timestring in GMT timezone
     *
     * @param gmtDateAndHourString must be in "yyyyMMdd-HH00" format, GMT timezone assumed
     * @throws IllegalArgumentException if date in wrong format
     * @see #getGMTDateAndHourString()
     */
    public static DateAndHour fromGMTString(String gmtDateAndHourString) {
        return new DateAndHour(parseDateAndHourString(gmtDateAndHourString, GMT));
    }

    /**
     * Construct by parsing timestring in GMT timezone
     *
     * @param localDateAndHourString must be in "yyyyMMdd-HH00" format, local timezone assumed
     * @throws IllegalArgumentException if date in wrong format
     * @see #getLocalDateAndHourString()
     * @see TimeZone#getDefault()
     */
    public static DateAndHour fromLocalTimeZoneString(String localDateAndHourString) {
        return new DateAndHour(parseDateAndHourString(localDateAndHourString, TimeZone.getDefault()));
    }

    /**
     * Initialize with the date and hour in which the current local time falls
     */
    public DateAndHour() {
        this(System.currentTimeMillis());
    }

    /**
     * Initialize with the date and hour in which the specified time falls
     */
    public DateAndHour(Date date) {
        this(date.getTime());
    }

    /**
     * Initialize with the date and hour in which the specified time falls
     */
    public DateAndHour(Calendar calendar) {
        this(calendar.getTimeInMillis());
    }

    /**
     * Initialize with the date and hour in which the specified time falls
     */
    public DateAndHour(GregorianCalendar time) {
        this.time = (GregorianCalendar) time.clone();
    }

    /**
     * Initialize with the date and hour in which the specified time falls
     */
    public DateAndHour(long timeInMillis) {
        this.time = new GregorianCalendar();
        this.time.setTimeInMillis(timeInMillis);
    }

    /**
     * Get the date and hour string in GMT timezone, e.g. "20110810-1300"
     */
    public String getGMTDateAndHourString() {
        return DateFormatUtils.formatUTC(time.getTimeInMillis(), DATE_AND_HOUR_FORMAT);
    }

    /**
     * Get the date and hour string in the default timezone, e.g. "20110810-1300"
     *
     * @see java.util.TimeZone#getDefault()
     */
    public String getLocalDateAndHourString() {
        return DateFormatUtils.format(time.getTimeInMillis(), DATE_AND_HOUR_FORMAT);
    }

    private static Date parseDateAndHourString(String gmtDateAndHourString, TimeZone timezone) {
        try {
            return new SimpleDateFormat(DATE_AND_HOUR_PARSE_FORMAT).parse(gmtDateAndHourString + "-" + timezone.getID());
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid date and hour string, expected format '"
                    + DATE_AND_HOUR_FORMAT + "', got '" + gmtDateAndHourString + "'", e);
        }
    }
}
