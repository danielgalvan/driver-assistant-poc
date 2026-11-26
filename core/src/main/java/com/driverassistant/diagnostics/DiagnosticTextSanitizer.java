package com.driverassistant.diagnostics;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts personal information while retaining offer-related text for local diagnostics. */
public final class DiagnosticTextSanitizer {
    private static final Pattern ADDRESS = Pattern.compile(
            "(?i)\\b(?:rua|r\\.|avenida|av\\.|alameda|al\\.|travessa|tv\\.|rodovia|estrada|pra[cç]a)\\s+[^|;\\n]*?(?=(?:\\s+(?:R\\$|BRL|[0-9][0-9.,]*\\s*(?:km|quil[oô]metros?|m|metros?|min(?:utos?)?|h(?:oras?)?)\\b)|\\s*[·|]\\s*(?:passageiro|nota|oferta|categoria|aceitar|selecionar|destino|viagem)\\b|\\s*$))");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?55[\\s.-]*)?(?:\\(?\\d{2}\\)?[\\s.-]*)?9?\\d{4}[\\s.-]?\\d{4}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}\\b");
    private static final Pattern LABELED_PERSON = Pattern.compile(
            "(?i)\\b(nome|passageiro|cliente|perfil|avatar|foto de)\\s*[:\\-]\\s*[\\p{L}][\\p{L}'’-]*(?:\\s+[\\p{L}][\\p{L}'’-]*){0,2}");
    private static final Pattern NAME_BEFORE_RATING = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([\\p{Lu}][\\p{L}'’-]+(?:\\s+[\\p{Lu}][\\p{L}'’-]+){0,2})(?=\\s*(?:[·|,;:]\\s*)?(?:[★⭐]\\s*)?[0-5][,.][0-9]{1,3})");
    private static final Pattern RELEVANT = Pattern.compile(
            "(?i)(?:R\\$|\\bBRL\\b|[0-9][0-9.,]*\\s*(?:km|quil[oô]metros?|m|metros?|min(?:utos?)?|h(?:oras?)?)\\b|[★⭐]|\\b(?:aceitar|selecionar|recusar|expirada|expirou|oferta|corrida|uberx|comfort|black|priority|flash|moto|taxi|nota|avalia[cç][aã]o|rating|passageiro|embarque|retirada|destino|viagem|bonus|b[oô]nus|pre[cç]o din[aâ]mico|multiplicador|ficar online|oportunidades|mapa|saldo|carteira|hist[oó]rico)\\b)");
    private static final String[] SAFE_NAME_PHRASES = {
            "uberx", "uber x", "uber black", "uber comfort", "uber flash", "uber moto", "uber taxi", "uber pool",
            "comfort", "black", "priority", "flash", "moto", "taxi", "uber driver", "rota pro"
    };

    private DiagnosticTextSanitizer() { }

    /** Returns sanitized text; null/irrelevant input becomes an empty string. */
    public static String sanitizeRelevant(String value) {
        if (value == null || value.isBlank()) return "";
        String sanitized = value.replace('\u00a0', ' ').trim();
        sanitized = ADDRESS.matcher(sanitized).replaceAll("[ENDERECO] ");
        sanitized = PHONE.matcher(sanitized).replaceAll("[TELEFONE]");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[EMAIL]");
        sanitized = LABELED_PERSON.matcher(sanitized).replaceAll("$1: [PESSOA]");
        sanitized = redactNamesBeforeRating(sanitized);
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        if (!RELEVANT.matcher(sanitized).find()) return "";
        if (sanitized.length() > 240) sanitized = sanitized.substring(0, 240) + "…";
        return sanitized;
    }

    /** Keeps only known ride categories in diagnostic fields; arbitrary labels may contain a person's name. */
    public static String sanitizeCategory(String value) {
        if (value == null) return null;
        String normalized = value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "uberx", "uber x" -> "UberX";
            case "comfort", "uber comfort" -> "Comfort";
            case "black", "uber black" -> "Black";
            case "flash", "uber flash" -> "Flash";
            case "moto", "uber moto" -> "Moto";
            case "taxi", "uber taxi" -> "Taxi";
            case "priority" -> "Priority";
            case "bag", "uber bag" -> "Uber Bag";
            case "pool", "uber pool" -> "Uber Pool";
            default -> null;
        };
    }

    private static String redactNamesBeforeRating(String input) {
        Matcher matcher = NAME_BEFORE_RATING.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String candidate = matcher.group(1).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            matcher.appendReplacement(output, Matcher.quoteReplacement(
                    isSafePhrase(candidate) ? matcher.group(1) : "[PESSOA]"));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static boolean isSafePhrase(String candidate) {
        for (String safe : SAFE_NAME_PHRASES) if (safe.equals(candidate)) return true;
        return false;
    }
}
