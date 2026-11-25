package com.driverassistant.parser;

import com.driverassistant.domain.TripOffer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser tolerante: usa apenas rótulos e unidades explícitos presentes na tela. */
public final class OfferParser {
    private static final Pattern MONEY = Pattern.compile(
            "(?i)(?:R\\$|BRL)\\s*([+-]?[\\d.,]+)|([+-]?[\\d.,]+)\\s*(?:R\\$|BRL)");
    private static final Pattern DISTANCE = Pattern.compile(
            "(?i)([+-]?[\\d.,]+)\\s*(km|quil[oô]metros?|m)\\b");
    private static final Pattern DURATION = Pattern.compile(
            "(?i)([+-]?[\\d.,]+)\\s*(min(?:utos?)?|h(?:oras?)?)\\b");
    private static final Pattern RATING = Pattern.compile(
            "(?i)(?:nota|avalia[cç][aã]o|rating)\\s*:?\\s*([0-5](?:[.,]\\d{1,2})?)|★\\s*([0-5](?:[.,]\\d{1,2})?)");
    private static final Pattern CATEGORY_LABEL = Pattern.compile("(?i)^(?:categoria|tipo)\\s*:?\\s*(.+)$");
    private static final Pattern PICKUP_LABEL = Pattern.compile("(?i)^(?:embarque|origem|pickup)\\s*:?\\s*(.+)$");
    private static final Pattern DESTINATION_LABEL = Pattern.compile("(?i)^(?:destino|destination)\\s*:?\\s*(.+)$");

    public Optional<TripOffer> parse(List<String> accessibleText) {
        if (accessibleText == null || accessibleText.isEmpty()) return Optional.empty();

        Double amount = null;
        Double rating = null;
        Double pickupDistance = null;
        Double pickupMinutes = null;
        Double tripDistance = null;
        Double tripMinutes = null;
        String category = null;
        String pickup = null;
        String destination = null;
        boolean offerCue = false;

        for (String rawLine : accessibleText) {
            if (rawLine == null || rawLine.trim().isEmpty()) continue;
            String line = rawLine.trim();
            String normalized = line.toLowerCase(Locale.ROOT);

            if (amount == null) amount = firstMoney(line);
            if (rating == null) rating = firstRating(line);

            Matcher categoryMatcher = CATEGORY_LABEL.matcher(line);
            if (category == null && categoryMatcher.matches()) category = safeLabel(categoryMatcher.group(1));
            if (category == null) category = inferCategory(line);

            Matcher pickupMatcher = PICKUP_LABEL.matcher(line);
            if (pickup == null && pickupMatcher.matches()) pickup = safeLabel(pickupMatcher.group(1));
            Matcher destinationMatcher = DESTINATION_LABEL.matcher(line);
            if (destination == null && destinationMatcher.matches()) destination = safeLabel(destinationMatcher.group(1));

            boolean pickupContext = hasAny(normalized, "embarque", "pickup", "buscar", "retirada");
            boolean tripContext = hasAny(normalized, "viagem", "trajeto", "trip", "destino", "corrida");
            Matcher distanceMatcher = DISTANCE.matcher(line);
            while (distanceMatcher.find()) {
                Double distance = LocalizedNumberParser.parse(distanceMatcher.group(1));
                if (distance == null || distance < 0) continue;
                if ("m".equalsIgnoreCase(distanceMatcher.group(2))) distance /= 1000.0;
                if (pickupContext && pickupDistance == null) pickupDistance = distance;
                else if (tripContext && tripDistance == null) tripDistance = distance;
                else if (!pickupContext && !tripContext) {
                    if (tripDistance == null) tripDistance = distance;
                }
            }

            Matcher durationMatcher = DURATION.matcher(line);
            while (durationMatcher.find()) {
                Double duration = LocalizedNumberParser.parse(durationMatcher.group(1));
                if (duration == null || duration < 0) continue;
                String unit = durationMatcher.group(2).toLowerCase(Locale.ROOT);
                if (unit.startsWith("h")) duration *= 60.0;
                if (pickupContext && pickupMinutes == null) pickupMinutes = duration;
                else if (tripContext && tripMinutes == null) tripMinutes = duration;
                else if (!pickupContext && !tripContext && tripMinutes == null) tripMinutes = duration;
            }

            offerCue |= hasAny(normalized, "corrida", "viagem", "embarque", "pickup", "uberx", "ride", "categoria");
        }

        if (amount == null || !offerCue) return Optional.empty();
        return Optional.of(new TripOffer(amount, category, rating, pickupDistance, pickupMinutes,
                tripDistance, tripMinutes, pickup, destination));
    }

    private static Double firstMoney(String line) {
        Matcher matcher = MONEY.matcher(line);
        if (!matcher.find()) return null;
        Double amount = LocalizedNumberParser.parse(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        return amount != null && amount >= 0 ? amount : null;
    }

    private static Double firstRating(String line) {
        Matcher matcher = RATING.matcher(line);
        if (!matcher.find()) return null;
        Double rating = LocalizedNumberParser.parse(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        return rating != null && rating >= 0 && rating <= 5 ? rating : null;
    }

    private static String inferCategory(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        for (String candidate : new String[]{"Uber Black", "Uber Comfort", "UberX", "Uber Flash", "Uber Moto"}) {
            if (normalized.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return null;
    }

    private static String safeLabel(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() ? null : clean.substring(0, Math.min(clean.length(), 140));
    }

    private static boolean hasAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
