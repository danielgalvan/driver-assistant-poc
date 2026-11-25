package com.driverassistant.parser;

/** Parses localized numeric values only when their separators are unambiguous in context. */
public final class LocalizedNumberParser {
    public enum Context { MONEY, DISTANCE, DURATION, RATING, GENERIC }

    private LocalizedNumberParser() { }

    /** Generic parsing rejects a single separator followed by three digits as ambiguous. */
    public static Double parse(String raw) {
        return parse(raw, Context.GENERIC);
    }

    public static Double parse(String raw, Context context) {
        if (raw == null || context == null) return null;
        String value = raw.trim()
                .replace("R$", "").replace("BRL", "").replace("US$", "").replace("$", "")
                .replace("\u00a0", "").replace(" ", "");
        if (value.isEmpty()) return null;

        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            char decimal = comma > dot ? ',' : '.';
            char grouping = decimal == ',' ? '.' : ',';
            if (value.indexOf(decimal) != value.lastIndexOf(decimal)) return null;
            String integer = value.substring(0, value.lastIndexOf(decimal));
            String fraction = value.substring(value.lastIndexOf(decimal) + 1);
            if (!validGroupedInteger(unsigned(integer), grouping) || !digits(fraction)) return null;
            value = integer.replace(String.valueOf(grouping), "") + "." + fraction;
        } else if (comma >= 0 || dot >= 0) {
            char separator = comma >= 0 ? ',' : '.';
            int first = value.indexOf(separator);
            int last = value.lastIndexOf(separator);
            if (first != last) {
                if (!validGroupedInteger(unsigned(value), separator)) return null;
                value = value.replace(String.valueOf(separator), "");
            } else {
                String integer = value.substring(0, last);
                String fraction = value.substring(last + 1);
                if (!digits(unsigned(integer)) || !digits(fraction)) return null;
                if (fraction.length() == 3) {
                    if (context == Context.GENERIC) return null;
                    if (context == Context.MONEY && separator == ',') return null;
                    if (context == Context.MONEY && separator == '.'
                            && validGroupedInteger(unsigned(integer) + separator + fraction, separator)) {
                        value = integer + fraction;
                    } else {
                        value = integer + "." + fraction;
                    }
                } else {
                    value = integer + "." + fraction;
                }
            }
        }

        if (!validSignedDecimal(value)) return null;
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) return null;
            if (context == Context.RATING && (parsed < 0 || parsed > 5)) return null;
            if (context != Context.GENERIC && parsed < 0) return null;
            return parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String unsigned(String value) {
        return value.startsWith("-") || value.startsWith("+") ? value.substring(1) : value;
    }

    private static boolean validSignedDecimal(String value) {
        String unsigned = unsigned(value);
        int dot = unsigned.indexOf('.');
        if (dot >= 0 && (dot != unsigned.lastIndexOf('.') || !digits(unsigned.substring(0, dot))
                || !digits(unsigned.substring(dot + 1)))) return false;
        return dot >= 0 || digits(unsigned);
    }

    private static boolean validGroupedInteger(String value, char separator) {
        if (value.indexOf(separator) < 0) return digits(value);
        String[] groups = value.split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
        if (groups.length < 2 || groups[0].isEmpty() || groups[0].length() > 3 || !digits(groups[0])) return false;
        for (int index = 1; index < groups.length; index++) {
            if (groups[index].length() != 3 || !digits(groups[index])) return false;
        }
        return true;
    }

    private static boolean digits(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) return false;
        }
        return true;
    }
}
