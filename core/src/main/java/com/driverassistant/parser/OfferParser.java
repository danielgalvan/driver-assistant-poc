package com.driverassistant.parser;

import com.driverassistant.domain.TripOffer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Context-driven parser for transient accessibility snapshots. Raw text must not be persisted. */
public final class OfferParser {
    private static final Pattern MONEY = Pattern.compile(
            "(?i)(?:R\\$|BRL)\\s*([0-9][0-9.,]*)|([0-9][0-9.,]*)\\s*(?:R\\$|BRL)");
    private static final Pattern DISTANCE = Pattern.compile(
            "(?i)([0-9][0-9.,]*)\\s*(km|quil[oô]metros?|m|metros?)\\b");
    private static final Pattern DURATION = Pattern.compile(
            "(?i)([0-9][0-9.,]*)\\s*(h(?:oras?)?|min(?:utos?)?)\\b");
    private static final Pattern COMPOUND_SHORT_MINUTES = Pattern.compile(
            "(?i)[0-9][0-9.,]*\\s*h(?:oras?)?\\s*([0-9][0-9.,]*)\\s*m\\b");
    private static final Pattern RATING = Pattern.compile(
            "(?i)\\b(?:nota|avalia[cç][aã]o|rating)\\b\\s*:?\\s*([0-5](?:[.,][0-9]{1,3})?)|★\\s*([0-5](?:[.,][0-9]{1,3})?)");
    private static final Pattern CATEGORY_LABEL = Pattern.compile("(?i)\\b(?:categoria|tipo)\\b\\s*:?\\s*([\\p{L}0-9 -]{2,35})");
    private static final Pattern CATEGORY = Pattern.compile("(?i)\\b(Uber\\s*(?:X|Black|Comfort|Flash|Moto|Taxi|Bag|Pool)|Comfort|Black|Flash|Moto|Taxi)\\b");
    private static final String[] EXCLUDED_SCREENS = {
            "histórico", "historico", "carteira", "saldo da", "ganhos acumulados",
            "corrida em andamento", "viagem em andamento", "corrida iniciada",
            "viagem iniciada", "navegação ativa", "navegacao ativa", "viagem finalizada"
    };
    private static final String[] EXCLUDED_MONEY_CONTEXT = {
            "saldo", "carteira", "ganhos acumulados", "promoção", "promocao",
            "bônus", "bonus", "preço dinâmico", "preco dinamico", "multiplicador"
    };
    private static final String[] OFFER_AMOUNT_CUES = {
            "oferta", "valor", "aceitar", "selecionar", "corrida", "uberx", "comfort", "black"
    };
    private static final String[] PICKUP_CUES = {
            "embarque", "pickup", "buscar passageiro", "até o passageiro", "ate o passageiro",
            "até embarque", "ate embarque", "para buscar"
    };
    private static final String[] TRIP_CUES = {
            "distância da viagem", "distancia da viagem", "tempo de viagem", "duração da viagem",
            "duracao da viagem", "trajeto", "destino", "viagem", "trip distance", "trip duration", "com passageiro"
    };
    private static final String[] RATING_CUES = {"nota", "avaliação", "avaliacao", "rating", "passageiro"};

    public Optional<TripOffer> parse(List<String> accessibleText) {
        if (accessibleText == null) return Optional.empty();
        List<AccessibilityNode> nodes = new ArrayList<>();
        for (int index = 0; index < accessibleText.size(); index++) {
            nodes.add(AccessibilityNode.textOnly(accessibleText.get(index), index));
        }
        return Optional.ofNullable(parseNodes(nodes).offer());
    }

    public OfferParseResult parseNodes(List<AccessibilityNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return rejected("empty_tree");
        List<String> lines = new ArrayList<>(nodes.size());
        List<String> contextLines = new ArrayList<>(nodes.size());
        List<Integer> starts = new ArrayList<>(nodes.size());
        StringBuilder composedText = new StringBuilder();
        for (AccessibilityNode node : nodes) {
            String visibleText = node == null ? "" : node.accessibleText();
            if (!composedText.isEmpty()) composedText.append(' ');
            starts.add(composedText.length());
            composedText.append(visibleText);
            lines.add(visibleText);
            String resourceCue = node == null || node.resourceId() == null ? ""
                    : node.resourceId().replaceAll("[^a-zA-Z0-9]+", " ");
            contextLines.add((visibleText + " " + resourceCue).trim());
        }
        String joinedText = composedText.toString();
        String allText = joinedText.toLowerCase(Locale.ROOT);
        for (String excluded : EXCLUDED_SCREENS) {
            if (allText.contains(excluded)) return rejected("screen_context:" + excluded.replace(' ', '_'));
        }

        Double amount = findOfferAmount(lines);
        Double rating = findRating(contextLines);
        String category = findCategory(contextLines);
        Double pickupDistance = null;
        Double pickupMinutes = null;
        Double tripDistance = null;
        Double tripMinutes = null;

        Matcher distanceMatcher = DISTANCE.matcher(joinedText);
        while (distanceMatcher.find()) {
            String unit = distanceMatcher.group(2).toLowerCase(Locale.ROOT);
            double parsed = number(distanceMatcher.group(1), LocalizedNumberParser.Context.DISTANCE);
            if (!Double.isFinite(parsed) || parsed < 0) continue;
            if (unit.equals("m") || unit.startsWith("metro")) parsed /= 1000.0;
            int contextKind = nearestTravelContext(distanceMatcher.start(), nodes, starts);
            if (contextKind == 1 && pickupDistance == null) pickupDistance = parsed;
            else if (contextKind == 2 && tripDistance == null) tripDistance = parsed;
        }

        double pickupDuration = 0;
        double tripDuration = 0;
        boolean foundPickupDuration = false;
        boolean foundTripDuration = false;
        Matcher durationMatcher = DURATION.matcher(joinedText);
        while (durationMatcher.find()) {
            double parsed = number(durationMatcher.group(1), LocalizedNumberParser.Context.DURATION);
            if (!Double.isFinite(parsed) || parsed < 0) continue;
            if (durationMatcher.group(2).toLowerCase(Locale.ROOT).startsWith("h")) parsed *= 60.0;
            int contextKind = nearestTravelContext(durationMatcher.start(), nodes, starts);
            if (contextKind == 1) { pickupDuration += parsed; foundPickupDuration = true; }
            else if (contextKind == 2) { tripDuration += parsed; foundTripDuration = true; }
        }
        Matcher shortMinutes = COMPOUND_SHORT_MINUTES.matcher(joinedText);
        while (shortMinutes.find()) {
            double parsed = number(shortMinutes.group(1), LocalizedNumberParser.Context.DURATION);
            if (!Double.isFinite(parsed) || parsed < 0) continue;
            int contextKind = nearestTravelContext(shortMinutes.start(1), nodes, starts);
            if (contextKind == 1) { pickupDuration += parsed; foundPickupDuration = true; }
            else if (contextKind == 2) { tripDuration += parsed; foundTripDuration = true; }
        }
        if (foundPickupDuration) pickupMinutes = pickupDuration;
        if (foundTripDuration) tripMinutes = tripDuration;

        String lowerAll = allText;
        boolean explicitOffer = containsAny(lowerAll, "oferta", "aceitar", "selecionar", "nova corrida", "chamada recebida");
        boolean travelContext = containsAny(lowerAll, PICKUP_CUES) || containsAny(lowerAll, TRIP_CUES);
        boolean categoryContext = category != null;
        if (lowerAll.contains("mapa") && !explicitOffer) return rejected("screen_context:map");
        boolean hasOfferContext = explicitOffer || (categoryContext && (amount != null || travelContext));
        if (!hasOfferContext) return rejected("no_offer_context");

        TripOffer offer = new TripOffer(amount, category, rating, pickupDistance, pickupMinutes,
                tripDistance, tripMinutes, null, null);
        Set<String> found = new LinkedHashSet<>();
        addIf(found, "offered_amount", amount);
        addIf(found, "category", category);
        addIf(found, "passenger_rating", rating);
        addIf(found, "pickup_distance", pickupDistance);
        addIf(found, "pickup_time", pickupMinutes);
        addIf(found, "trip_distance", tripDistance);
        addIf(found, "trip_time", tripMinutes);
        Set<String> unavailable = new LinkedHashSet<>(List.of("offered_amount", "category", "passenger_rating",
                "pickup_distance", "pickup_time", "trip_distance", "trip_time"));
        unavailable.removeAll(found);
        return new OfferParseResult(offer, null, found, unavailable);
    }

    private static Double findOfferAmount(List<String> lines) {
        Double selected = null;
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < lines.size(); index++) {
            String context = window(lines, index, 2).toLowerCase(Locale.ROOT);
            Matcher matcher = MONEY.matcher(context);
            while (matcher.find()) {
                int localStart = Math.max(0, matcher.start() - 48);
                String localContext = context.substring(localStart,
                        Math.min(context.length(), matcher.end() + 48));
                int localValueIndex = matcher.start() - localStart;
                int excludedDistance = nearestCueDistance(localContext, localValueIndex, EXCLUDED_MONEY_CONTEXT);
                int offerDistance = nearestCueDistance(localContext, localValueIndex, OFFER_AMOUNT_CUES);
                if (excludedDistance <= offerDistance) continue;
                Double value = LocalizedNumberParser.parse(matcher.group(1) != null ? matcher.group(1) : matcher.group(2),
                        LocalizedNumberParser.Context.MONEY);
                if (value == null || value < 0) continue;
                int score = 0;
                String lowerLocalContext = localContext.toLowerCase(Locale.ROOT);
                if (lowerLocalContext.contains("oferta") || lowerLocalContext.contains("valor da corrida")
                        || lowerLocalContext.contains("valor da viagem")) score += 8;
                if (lowerLocalContext.contains("aceitar") || lowerLocalContext.contains("selecionar")) score += 4;
                if (lowerLocalContext.contains("r$") || lowerLocalContext.contains("brl")) score += 2;
                if (lowerLocalContext.contains("uberx") || lowerLocalContext.contains("comfort")
                        || lowerLocalContext.contains("black")) score += 1;
                if (score > bestScore) { bestScore = score; selected = value; }
            }
        }
        return selected;
    }

    private static Double findRating(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            String context = window(lines, index, 2);
            Matcher matcher = RATING.matcher(context);
            if (matcher.find()) {
                return LocalizedNumberParser.parse(matcher.group(1) != null ? matcher.group(1) : matcher.group(2),
                        LocalizedNumberParser.Context.RATING);
            }
            if (containsAny(context.toLowerCase(Locale.ROOT), "passageiro", "avaliação", "avaliacao", "rating")) {
                Matcher candidate = Pattern.compile("(?<![\\w])([0-5](?:[.,][0-9]{1,3})?)(?![\\w])").matcher(context);
                int bestDistance = Integer.MAX_VALUE;
                Double best = null;
                while (candidate.find()) {
                    int distance = nearestCueDistance(context.toLowerCase(Locale.ROOT), candidate.start(), RATING_CUES);
                    if (distance < bestDistance) {
                        Double parsed = LocalizedNumberParser.parse(candidate.group(1), LocalizedNumberParser.Context.RATING);
                        if (parsed != null) { bestDistance = distance; best = parsed; }
                    }
                }
                if (best != null) return best;
            }
        }
        return null;
    }

    private static String findCategory(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            String context = window(lines, index, 1);
            Matcher explicit = CATEGORY_LABEL.matcher(context);
            if (explicit.find()) return cleanCategory(explicit.group(1));
            Matcher known = CATEGORY.matcher(context);
            if (known.find()) return normalizeCategory(known.group(1));
        }
        return null;
    }

    private static String cleanCategory(String category) {
        if (category == null) return null;
        String clean = category.trim().replaceAll("\\s+", " ");
        if (clean.length() > 35 || clean.matches("(?i).*(rua|avenida|bairro|n[uú]mero).*")) return null;
        return clean.isBlank() ? null : normalizeCategory(clean);
    }

    private static String normalizeCategory(String category) {
        String value = category.trim().replaceAll("\\s+", " ");
        if (value.toLowerCase(Locale.ROOT).startsWith("uber")) {
            String suffix = value.substring(4).trim();
            if (suffix.equalsIgnoreCase("x")) return "UberX";
            if (!suffix.isEmpty()) return "Uber " + suffix.substring(0, 1).toUpperCase(Locale.ROOT) + suffix.substring(1);
            return "Uber";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    /** Returns 1 for pickup, 2 for trip and 0 if no single context is sufficiently close. */
    private static int nearestTravelContext(int valueIndex, List<AccessibilityNode> nodes, List<Integer> starts) {
        int nodeIndex = nodeIndexAt(starts, valueIndex);
        if (nodeIndex >= 0 && nodeIndex < nodes.size()) {
            AccessibilityNode node = nodes.get(nodeIndex);
            String id = node == null || node.resourceId() == null ? "" : node.resourceId().toLowerCase(Locale.ROOT);
            int resourceKind = resourceTravelContext(id);
            if (resourceKind != 0) return resourceKind;

            String ownText = node == null ? "" : node.accessibleText().toLowerCase(Locale.ROOT);
            int ownKind = travelContextInText(ownText, valueIndex - starts.get(nodeIndex));
            if (ownKind != 0) return ownKind;

            int parent = node == null ? -1 : node.parentOrder();
            int levels = 0;
            while (parent >= 0 && parent < nodes.size() && levels++ < 8) {
                AccessibilityNode parentNode = nodes.get(parent);
                if (parentNode == null) break;
                int parentKind = resourceTravelContext(parentNode.resourceId() == null ? ""
                        : parentNode.resourceId().toLowerCase(Locale.ROOT));
                if (parentKind == 0) parentKind = travelContextInText(parentNode.accessibleText().toLowerCase(Locale.ROOT), 0);
                if (parentKind != 0) return parentKind;
                parent = parentNode.parentOrder();
            }

            int nearbyKind = nearestNodeTravelContext(nodes, nodeIndex);
            if (nearbyKind != 0) return nearbyKind;
        }
        return 0;
    }

    private static int resourceTravelContext(String resourceId) {
        boolean pickup = containsAny(resourceId, "pickup", "pick_up", "embarque");
        boolean trip = containsAny(resourceId, "trip", "destination", "destino", "route", "viagem");
        return pickup == trip ? 0 : pickup ? 1 : 2;
    }

    private static int travelContextInText(String text, int valueIndex) {
        int pickupDistance = nearestCueDistance(text, valueIndex, PICKUP_CUES);
        int tripDistance = nearestCueDistance(text, valueIndex, TRIP_CUES);
        if (pickupDistance == Integer.MAX_VALUE && tripDistance == Integer.MAX_VALUE) return 0;
        if (pickupDistance == tripDistance) return 0;
        return pickupDistance < tripDistance ? 1 : 2;
    }

    private static int nearestNodeTravelContext(List<AccessibilityNode> nodes, int nodeIndex) {
        int nearestDistance = Integer.MAX_VALUE;
        int selected = 0;
        int start = Math.max(0, nodeIndex - 2);
        int end = Math.min(nodes.size() - 1, nodeIndex + 2);
        for (int index = start; index <= end; index++) {
            if (index == nodeIndex) continue;
            AccessibilityNode nearby = nodes.get(index);
            if (nearby == null) continue;
            int kind = resourceTravelContext(nearby.resourceId() == null ? ""
                    : nearby.resourceId().toLowerCase(Locale.ROOT));
            if (kind == 0) kind = travelContextInText(nearby.accessibleText().toLowerCase(Locale.ROOT), 0);
            if (kind == 0) continue;
            int distance = Math.abs(index - nodeIndex);
            if (distance < nearestDistance) { nearestDistance = distance; selected = kind; }
            else if (distance == nearestDistance && selected != kind) selected = 0;
        }
        return selected;
    }

    private static int nodeIndexAt(List<Integer> starts, int characterIndex) {
        for (int index = starts.size() - 1; index >= 0; index--) {
            if (characterIndex >= starts.get(index)) return index;
        }
        return -1;
    }

    private static int nearestCueDistance(String text, int valueIndex, String[] cues) {
        int nearest = Integer.MAX_VALUE;
        for (String cue : cues) {
            int from = 0;
            while (from < text.length()) {
                int index = text.indexOf(cue, from);
                if (index < 0) break;
                int distance = valueIndex < index ? index - valueIndex : Math.max(0, valueIndex - (index + cue.length()));
                nearest = Math.min(nearest, distance);
                from = index + cue.length();
            }
        }
        return nearest;
    }

    private static double number(String raw, LocalizedNumberParser.Context context) {
        Double value = LocalizedNumberParser.parse(raw, context);
        return value == null ? Double.NaN : value;
    }

    private static String window(List<String> lines, int center, int radius) {
        StringBuilder text = new StringBuilder();
        int start = Math.max(0, center - radius);
        int end = Math.min(lines.size() - 1, center + radius);
        for (int index = start; index <= end; index++) {
            if (!text.isEmpty()) text.append(' ');
            text.append(lines.get(index));
        }
        return text.toString();
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private static void addIf(Set<String> found, String name, Object value) { if (value != null) found.add(name); }

    private static OfferParseResult rejected(String reason) {
        Set<String> unavailable = new LinkedHashSet<>(List.of("offered_amount", "category", "passenger_rating",
                "pickup_distance", "pickup_time", "trip_distance", "trip_time"));
        return new OfferParseResult(null, reason, Set.of(), unavailable);
    }
}
