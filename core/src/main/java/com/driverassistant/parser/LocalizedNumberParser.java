package com.driverassistant.parser;

public final class LocalizedNumberParser {
    private LocalizedNumberParser() { }

    /** Aceita formato brasileiro (1.234,56) e formatos com ponto decimal (1234.56). */
    public static Double parse(String raw) {
        if (raw == null) return null;
        String value = raw.trim()
                .replace("R$", "")
                .replace("BRL", "")
                .replace("US$", "")
                .replace("$", "")
                .replace(" ", "");
        if (value.isEmpty()) return null;

        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            char decimalSeparator = comma > dot ? ',' : '.';
            char groupingSeparator = decimalSeparator == ',' ? '.' : ',';
            if (value.indexOf(decimalSeparator) != value.lastIndexOf(decimalSeparator)) return null;
            String integerPart = value.substring(0, value.lastIndexOf(decimalSeparator));
            String fractionPart = value.substring(value.lastIndexOf(decimalSeparator) + 1);
            String unsignedInteger = integerPart.startsWith("-") || integerPart.startsWith("+")
                    ? integerPart.substring(1) : integerPart;
            if (!validGroupedInteger(unsignedInteger, groupingSeparator) || !digits(fractionPart)) return null;
            value = value.substring(0, value.lastIndexOf(decimalSeparator))
                    .replace(String.valueOf(groupingSeparator), "") + "." + fractionPart;
            value = replaceLast(value, decimalSeparator, '.');
        } else if (comma >= 0) {
            value = normalizeSingleSeparator(value, ',');
        } else if (dot >= 0) {
            value = normalizeSingleSeparator(value, '.');
        }

        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeSingleSeparator(String value, char separator) {
        int first = value.indexOf(separator);
        int last = value.lastIndexOf(separator);
        if (first != last) {
            String unsigned = value.startsWith("-") || value.startsWith("+") ? value.substring(1) : value;
            if (!validGroupedInteger(unsigned, separator)) return "invalid";
            return value.replace(String.valueOf(separator), "");
        }
        int digitsAfter = value.length() - last - 1;
        String integerPart = value.substring(0, last);
        String unsignedInteger = integerPart.startsWith("-") || integerPart.startsWith("+")
                ? integerPart.substring(1) : integerPart;
        String fractionPart = value.substring(last + 1);
        if (!digits(unsignedInteger) || !digits(fractionPart)) return "invalid";
        if (digitsAfter == 3 && last > 0) return value.replace(String.valueOf(separator), "");
        if (separator == ',') return replaceLast(value, ',', '.');
        return value;
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

    private static String replaceLast(String value, char oldChar, char newChar) {
        int index = value.lastIndexOf(oldChar);
        if (index < 0) return value;
        return value.substring(0, index) + newChar + value.substring(index + 1);
    }
}
