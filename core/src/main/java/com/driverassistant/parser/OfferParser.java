package com.driverassistant.parser;

import com.driverassistant.domain.TripOffer;
import com.driverassistant.diagnostics.DiagnosticTextSanitizer;

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
    private static final Pattern CATEGORY = Pattern.compile(
            "(?i)\\b(Uber\\s*(?:X|Black|Comfort|Flash|Moto|Taxi|Bag|Pool)|Comfort|Black|Flash|Moto|Taxi|Priority)\\b");
    private static final String[] EXCLUDED_SCREENS = {
            "histórico", "historico", "carteira", "saldo da", "ganhos acumulados",
            "corrida em andamento", "viagem em andamento", "corrida iniciada",
            "viagem iniciada", "navegação ativa", "navegacao ativa", "viagem finalizada"
    };
    private static final String[] EXCLUDED_MONEY_CONTEXT = {
            "saldo", "carteira", "ganhos acumulados", "promoção", "promocao",
            "bônus", "bonus", "preço dinâmico", "preco dinamico", "multiplicador",
            "incluído", "incluída", "incluido", "incluida", "para retirada",
            "est. de", "estimativa", "por km", "/km", "por hora", "/h", "adicional"
    };
    private static final String[] OFFER_AMOUNT_CUES = {
            "oferta", "valor", "aceitar", "selecionar", "corrida", "uberx", "comfort", "black", "priority"
    };
    private static final String[] PICKUP_CUES = {
            "embarque", "pickup", "buscar passageiro", "até o passageiro", "ate o passageiro",
            "até embarque", "ate embarque", "para buscar", "retirada", "retirar"
    };
    private static final String[] TRIP_CUES = {
            "distância da viagem", "distancia da viagem", "tempo de viagem", "duração da viagem",
            "duracao da viagem", "trajeto", "destino", "viagem", "trip distance", "trip duration",
            "com passageiro", "viagem longa"
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
        return parseNodes(nodes, false);
    }

    /** Runs the same parser rules and optionally returns their individual decisions for a capture session. */
    public OfferParseResult parseNodes(List<AccessibilityNode> nodes, boolean detailedDiagnostics) {
        List<String> trace = detailedDiagnostics ? new ArrayList<>() : null;
        if (nodes == null || nodes.isEmpty()) return rejected("empty_tree", trace);
        int sourceNodeCount = nodes.size();
        nodes = isolateOfferCard(nodes, trace);
        addTrace(trace, "isolateOfferCard source_nodes=" + sourceNodeCount + "; parsed_nodes=" + nodes.size());
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
            String resourceCue = node == null || !node.visibleToUser() || node.resourceId() == null ? ""
                    : node.resourceId().replaceAll("[^a-zA-Z0-9]+", " ");
            contextLines.add((visibleText + " " + resourceCue).trim());
        }
        String joinedText = composedText.toString();
        String allText = joinedText.toLowerCase(Locale.ROOT);
        String excludedScreen = null;
        for (String excluded : EXCLUDED_SCREENS) {
            if (allText.contains(excluded)) {
                excludedScreen = excluded;
                break;
            }
        }
        addTrace(trace, "EXCLUDED_SCREENS matched=" + (excludedScreen == null ? "none" : excludedScreen.replace(' ', '_')));
        if (excludedScreen != null) return rejected("screen_context:" + excludedScreen.replace(' ', '_'), trace);

        boolean hasAcceptanceAction = containsAny(allText, "aceitar", "selecionar");
        addTrace(trace, "hasAcceptanceAction=" + hasAcceptanceAction);
        boolean opportunityMap = containsAny(allText, "ficar online")
                && containsAny(allText, "oportunidades", "oportunidade", "bônus por região", "bonus por regiao");
        addTrace(trace, "offlineOpportunityMap=" + opportunityMap + "; acceptanceAction=" + hasAcceptanceAction);
        if (opportunityMap && !hasAcceptanceAction) return rejected("screen_context:offline_opportunity_map", trace);

        boolean labeledOffer = containsAny(allText,
                "oferta r$", "oferta brl", "oferta da viagem", "oferta uber", "nova corrida", "nova oferta",
                "chamada recebida", "valor da corrida", "valor da viagem");
        boolean explicitOffer = hasAcceptanceAction || labeledOffer;
        addTrace(trace, "labeledOffer=" + labeledOffer + "; explicitOffer=" + explicitOffer);
        Double rating = findRating(contextLines);
        String category = findCategory(contextLines);
        addTrace(trace, "fields category=" + safeTraceValue(DiagnosticTextSanitizer.sanitizeCategory(category))
                + "; rating=" + safeTraceValue(rating));
        boolean structuredOfferCard = hasStructuredOfferCard(joinedText, category, rating, trace);
        boolean hasOfferSignal = explicitOffer || structuredOfferCard;
        addTrace(trace, "hasOfferSignal=" + hasOfferSignal);
        Double amount = findOfferAmount(joinedText, lines, nodes, starts, hasOfferSignal, trace);
        Double pickupDistance = null;
        Double pickupMinutes = null;
        Double tripDistance = null;
        Double tripMinutes = null;
        List<Double> unassignedDistances = new ArrayList<>();
        List<Double> unassignedDurations = new ArrayList<>();

        Matcher distanceMatcher = DISTANCE.matcher(joinedText);
        while (distanceMatcher.find()) {
            String unit = distanceMatcher.group(2).toLowerCase(Locale.ROOT);
            if (isEstimatedPerUnit(joinedText, distanceMatcher.start(), distanceMatcher.end())) {
                addTrace(trace, "distance_candidate value=" + distanceMatcher.group(1) + unit + "; rejected=estimated_per_unit");
                continue;
            }
            double parsed = number(distanceMatcher.group(1), LocalizedNumberParser.Context.DISTANCE);
            if (!Double.isFinite(parsed) || parsed < 0) {
                addTrace(trace, "distance_candidate value=" + distanceMatcher.group(1) + unit + "; rejected=invalid_number");
                continue;
            }
            if (unit.equals("m") || unit.startsWith("metro")) parsed /= 1000.0;
            int contextKind = nearestTravelContext(distanceMatcher.start(), nodes, starts);
            if (contextKind == 1 && pickupDistance == null) pickupDistance = parsed;
            else if (contextKind == 2 && tripDistance == null) tripDistance = parsed;
            else if (contextKind == 0) unassignedDistances.add(parsed);
            addTrace(trace, "distance_candidate parsed_km=" + parsed + "; context=" + travelContextName(contextKind));
        }

        double pickupDuration = 0;
        double tripDuration = 0;
        boolean foundPickupDuration = false;
        boolean foundTripDuration = false;
        Matcher durationMatcher = DURATION.matcher(joinedText);
        while (durationMatcher.find()) {
            if (isThresholdDuration(joinedText, durationMatcher.start())) {
                addTrace(trace, "duration_candidate value=" + durationMatcher.group(1) + durationMatcher.group(2)
                        + "; rejected=threshold_text");
                continue;
            }
            double parsed = number(durationMatcher.group(1), LocalizedNumberParser.Context.DURATION);
            if (!Double.isFinite(parsed) || parsed < 0) {
                addTrace(trace, "duration_candidate value=" + durationMatcher.group(1) + durationMatcher.group(2)
                        + "; rejected=invalid_number");
                continue;
            }
            if (durationMatcher.group(2).toLowerCase(Locale.ROOT).startsWith("h")) parsed *= 60.0;
            int contextKind = nearestTravelContext(durationMatcher.start(), nodes, starts);
            if (contextKind == 1) { pickupDuration += parsed; foundPickupDuration = true; }
            else if (contextKind == 2) { tripDuration += parsed; foundTripDuration = true; }
            else if (contextKind == 0) unassignedDurations.add(parsed);
            addTrace(trace, "duration_candidate parsed_minutes=" + parsed + "; context=" + travelContextName(contextKind));
        }
        Matcher shortMinutes = COMPOUND_SHORT_MINUTES.matcher(joinedText);
        while (shortMinutes.find()) {
            double parsed = number(shortMinutes.group(1), LocalizedNumberParser.Context.DURATION);
            if (!Double.isFinite(parsed) || parsed < 0) continue;
            int contextKind = nearestTravelContext(shortMinutes.start(1), nodes, starts);
            if (contextKind == 1) { pickupDuration += parsed; foundPickupDuration = true; }
            else if (contextKind == 2) { tripDuration += parsed; foundTripDuration = true; }
        }

        // Some offer cards expose two unlabeled time/distance pairs in visual order.
        // Use that order only when the same snapshot contains an explicit offer action.
        if (hasOfferSignal && unassignedDistances.size() == 2 && pickupDistance == null && tripDistance == null) {
            pickupDistance = unassignedDistances.get(0);
            tripDistance = unassignedDistances.get(1);
        } else if (hasOfferSignal && unassignedDistances.size() == 1) {
            if (pickupDistance == null && tripDistance != null) pickupDistance = unassignedDistances.get(0);
            else if (tripDistance == null && pickupDistance != null) tripDistance = unassignedDistances.get(0);
        }
        if (hasOfferSignal && unassignedDurations.size() == 2 && !foundPickupDuration && !foundTripDuration) {
            pickupDuration = unassignedDurations.get(0);
            foundPickupDuration = true;
            tripDuration = unassignedDurations.get(1);
            foundTripDuration = true;
        } else if (hasOfferSignal && unassignedDurations.size() == 1) {
            if (!foundPickupDuration && foundTripDuration) {
                pickupDuration = unassignedDurations.get(0);
                foundPickupDuration = true;
            } else if (!foundTripDuration && foundPickupDuration) {
                tripDuration = unassignedDurations.get(0);
                foundTripDuration = true;
            }
        }
        if (foundPickupDuration) pickupMinutes = pickupDuration;
        if (foundTripDuration) tripMinutes = tripDuration;
        addTrace(trace, "travel_fields pickup_distance_km=" + safeTraceValue(pickupDistance)
                + "; pickup_minutes=" + safeTraceValue(pickupMinutes)
                + "; trip_distance_km=" + safeTraceValue(tripDistance)
                + "; trip_minutes=" + safeTraceValue(tripMinutes));

        String lowerAll = allText;
        boolean categoryContext = category != null;
        if (lowerAll.contains("mapa") && !hasOfferSignal) {
            addTrace(trace, "mapScreen=true; hasOfferSignal=false; result=rejected");
            return rejected("screen_context:map", trace);
        }
        boolean hasSecondaryOfferData = categoryContext || rating != null || pickupDistance != null
                || pickupMinutes != null || tripDistance != null || tripMinutes != null;
        addTrace(trace, "hasSecondaryOfferData=" + hasSecondaryOfferData);
        // A map can remain behind a request card even when its Accept button is not exposed.
        // Accept that card only with a category and both pickup/trip distance and duration.
        boolean hasOfferContext = amount != null && hasSecondaryOfferData
                && (explicitOffer || structuredOfferCard);
        addTrace(trace, "hasOfferContext=" + hasOfferContext + "; amount=" + safeTraceValue(amount)
                + "; explicitOffer=" + explicitOffer + "; structuredOfferCard=" + structuredOfferCard);
        if (!hasOfferContext) return rejected("missing_accept_action_or_labeled_fare", trace);

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
        addTrace(trace, "parser_result=accepted; found=" + String.join(",", found)
                + "; unavailable=" + String.join(",", unavailable));
        return new OfferParseResult(offer, null, found, unavailable, trace);
    }

    private static Double findOfferAmount(String joinedText, List<String> lines,
                                          List<AccessibilityNode> nodes, List<Integer> starts,
                                          boolean explicitOffer, List<String> trace) {
        Double selected = null;
        int bestScore = Integer.MIN_VALUE;
        Matcher matcher = MONEY.matcher(joinedText);
        while (matcher.find()) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            int nodeIndex = nodeIndexAt(starts, matcher.start());
            // Uber's map promotions are displayed as +R$ values, while the request fare is not.
            if (matcher.start() > 0 && joinedText.charAt(matcher.start() - 1) == '+') {
                addTrace(trace, "money_candidate raw=" + raw + "; node=" + nodeIndex + "; rejected=plus_prefixed_map_addition");
                continue;
            }
            if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
                addTrace(trace, "money_candidate raw=" + raw + "; node=" + nodeIndex + "; rejected=node_not_found");
                continue;
            }
            int fromNode = Math.max(0, nodeIndex - 3);
            int toNode = Math.min(nodes.size() - 1, nodeIndex + 3);
            int fromChar = starts.get(fromNode);
            int toChar = starts.get(toNode) + lines.get(toNode).length();
            String localContext = joinedText.substring(fromChar, Math.min(joinedText.length(), toChar));
            int localValueIndex = matcher.start() - fromChar;
            String lowerLocalContext = localContext.toLowerCase(Locale.ROOT);
            int offerDistance = nearestCueDistance(lowerLocalContext, localValueIndex, OFFER_AMOUNT_CUES);
            AccessibilityNode valueNode = nodes.get(nodeIndex);
            String ownText = lines.get(nodeIndex).toLowerCase(Locale.ROOT);
            int ownValueIndex = matcher.start() - starts.get(nodeIndex);
            int excludedDistance = nearestCueDistance(ownText, ownValueIndex, EXCLUDED_MONEY_CONTEXT);
            String ownId = valueNode == null || !valueNode.visibleToUser() || valueNode.resourceId() == null ? ""
                    : valueNode.resourceId().toLowerCase(Locale.ROOT);
            if (excludedDistance != Integer.MAX_VALUE) {
                addTrace(trace, "money_candidate raw=" + raw + "; node=" + nodeIndex
                        + "; rejected=excluded_money_context");
                continue;
            }
            if (containsAny(ownId, "bonus", "surge", "promotion", "incentive", "pickup_fee", "dynamic")) {
                addTrace(trace, "money_candidate raw=" + raw + "; node=" + nodeIndex
                        + "; rejected=excluded_resource_id");
                continue;
            }

            StringBuilder resourceContext = new StringBuilder();
            for (int index = fromNode; index <= toNode; index++) {
                AccessibilityNode node = nodes.get(index);
                if (node != null && node.visibleToUser() && node.resourceId() != null) resourceContext.append(' ')
                        .append(node.resourceId().toLowerCase(Locale.ROOT));
            }
            String ids = resourceContext.toString();
            if (containsAny(ownId, "bonus", "surge", "promotion", "incentive", "pickup_fee", "dynamic")) {
                addTrace(trace, "money_candidate raw=" + raw + "; node=" + nodeIndex
                        + "; rejected=nearby_excluded_resource_id");
                continue;
            }

            // Bare currency values are accepted only on a snapshot with an explicit offer action.
            if (offerDistance == Integer.MAX_VALUE && !explicitOffer
                    && !containsAny(ids, "fare", "price", "offer", "amount", "payout")) {
                addTrace(trace, "money_candidate raw=" + raw + "; node=" + nodeIndex
                        + "; rejected=no_offer_cue_or_resource_id");
                continue;
            }

            Double value = LocalizedNumberParser.parse(raw, LocalizedNumberParser.Context.MONEY);
            if (value == null || value < 0) {
                addTrace(trace, "money_candidate raw=" + raw + "; node=" + nodeIndex + "; rejected=invalid_money");
                continue;
            }
            int score = 0;
            if (lowerLocalContext.contains("oferta") || lowerLocalContext.contains("valor da corrida")
                    || lowerLocalContext.contains("valor da viagem") || lowerLocalContext.contains("preço total")) score += 8;
            if (lowerLocalContext.contains("aceitar") || lowerLocalContext.contains("selecionar")) score += 4;
            if (lowerLocalContext.contains("r$") || lowerLocalContext.contains("brl")) score += 2;
            if (containsAny(ids, "fare", "price", "offer", "amount", "payout")) score += 6;
            if (containsAny(lowerLocalContext, "uberx", "comfort", "black", "priority")) score += 1;
            boolean selectedCandidate = score > bestScore;
            addTrace(trace, "money_candidate value=" + value + "; node=" + nodeIndex + "; score=" + score
                    + "; offer_cue_distance=" + (offerDistance == Integer.MAX_VALUE ? "none" : offerDistance)
                    + "; selected=" + selectedCandidate);
            if (selectedCandidate) { bestScore = score; selected = value; }
        }
        addTrace(trace, "findOfferAmount selected=" + safeTraceValue(selected) + "; score="
                + (selected == null ? "none" : bestScore));
        return selected;
    }

    private static boolean isEstimatedPerUnit(String text, int start, int end) {
        String before = text.substring(Math.max(0, start - 24), start).toLowerCase(Locale.ROOT);
        String after = text.substring(end, Math.min(text.length(), end + 8)).toLowerCase(Locale.ROOT);
        return before.contains("est.") || before.contains("estimativa")
                || after.startsWith("/km") || before.contains("por km");
    }

    private static boolean hasStructuredOfferCard(String text, String category, Double rating, List<String> trace) {
        if (category == null) {
            addTrace(trace, "hasStructuredOfferCard=false; category_missing=true");
            return false;
        }
        int distances = 0;
        Matcher distanceMatcher = DISTANCE.matcher(text);
        while (distanceMatcher.find()) {
            if (!isEstimatedPerUnit(text, distanceMatcher.start(), distanceMatcher.end())) distances++;
        }
        int durations = 0;
        Matcher durationMatcher = DURATION.matcher(text);
        while (durationMatcher.find()) {
            if (!isThresholdDuration(text, durationMatcher.start())) durations++;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean labeledRoute = containsAny(lower, PICKUP_CUES) && containsAny(lower, TRIP_CUES);
        boolean result = distances >= 2 && durations >= 2 && (rating != null || labeledRoute);
        addTrace(trace, "hasStructuredOfferCard=" + result + "; distances=" + distances + "; durations=" + durations
                + "; ratingPresent=" + (rating != null) + "; labeledRoute=" + labeledRoute);
        return result;
    }

    private static boolean isThresholdDuration(String text, int start) {
        String before = text.substring(Math.max(0, start - 20), start).toLowerCase(Locale.ROOT).trim();
        return before.endsWith("mais de") || before.endsWith("acima de") || before.endsWith(">")
                || before.endsWith("over");
    }

    /** Narrows parsing to the smallest accessibility subtree that contains an offer action and its data. */
    private static List<AccessibilityNode> isolateOfferCard(List<AccessibilityNode> source, List<String> trace) {
        int bestRoot = -1;
        List<Integer> bestDescendants = null;
        int bestScore = -1;
        List<Integer> actions = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            AccessibilityNode node = source.get(index);
            if (node != null && node.visibleToUser()
                    && containsAny(node.accessibleText().toLowerCase(Locale.ROOT), "aceitar", "selecionar")) {
                actions.add(index);
            }
        }
        if (actions.isEmpty()) {
            addTrace(trace, "isolateOfferCard actions=0; result=unchanged");
            return source;
        }

        for (int action : actions) {
            int ancestor = action;
            int levels = 0;
            while (ancestor >= 0 && ancestor < source.size() && levels++ < 32) {
                List<Integer> descendants = descendantsOf(source, ancestor);
                int score = offerSubtreeScore(source, descendants);
                if (score > bestScore || (score == bestScore && bestDescendants != null
                        && descendants.size() < bestDescendants.size())) {
                    bestRoot = ancestor;
                    bestDescendants = descendants;
                    bestScore = score;
                }
                AccessibilityNode parent = source.get(ancestor);
                ancestor = parent == null ? -1 : parent.parentOrder();
            }
        }
        if (bestRoot < 0 || bestDescendants == null || bestDescendants.size() >= source.size()) {
            addTrace(trace, "isolateOfferCard actions=" + actions.size() + "; selected=false; result=unchanged");
            return source;
        }
        addTrace(trace, "isolateOfferCard actions=" + actions.size() + "; selected=true; root=" + bestRoot
                + "; score=" + bestScore + "; scoped_nodes=" + bestDescendants.size());

        int[] remap = new int[source.size()];
        for (int index = 0; index < remap.length; index++) remap[index] = -1;
        for (int index = 0; index < bestDescendants.size(); index++) remap[bestDescendants.get(index)] = index;
        List<AccessibilityNode> scoped = new ArrayList<>(bestDescendants.size());
        int baseDepth = source.get(bestRoot) == null ? 0 : source.get(bestRoot).depth();
        for (int scopedOrder = 0; scopedOrder < bestDescendants.size(); scopedOrder++) {
            int sourceOrder = bestDescendants.get(scopedOrder);
            AccessibilityNode node = source.get(sourceOrder);
            if (node == null) {
                scoped.add(null);
                continue;
            }
            int parent = node.parentOrder() >= 0 && node.parentOrder() < remap.length
                    ? remap[node.parentOrder()] : -1;
            scoped.add(new AccessibilityNode(node.text(), node.contentDescription(), node.resourceId(),
                    node.className(), parent, Math.max(0, node.depth() - baseDepth), scopedOrder,
                    node.visibleToUser()));
        }
        return scoped;
    }

    private static List<Integer> descendantsOf(List<AccessibilityNode> nodes, int root) {
        List<Integer> descendants = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            int current = index;
            int levels = 0;
            while (current >= 0 && current < nodes.size() && levels++ < 32) {
                if (current == root) {
                    descendants.add(index);
                    break;
                }
                AccessibilityNode node = nodes.get(current);
                current = node == null ? -1 : node.parentOrder();
            }
        }
        return descendants;
    }

    private static int offerSubtreeScore(List<AccessibilityNode> nodes, List<Integer> indices) {
        StringBuilder text = new StringBuilder();
        for (int index : indices) {
            AccessibilityNode node = nodes.get(index);
            if (node == null || !node.visibleToUser()) continue;
            if (!text.isEmpty()) text.append(' ');
            text.append(node.accessibleText()).append(' ');
            if (node.resourceId() != null) text.append(node.resourceId()).append(' ');
        }
        String value = text.toString().toLowerCase(Locale.ROOT);
        boolean money = MONEY.matcher(value).find();
        if (!money) return -1;
        boolean route = DISTANCE.matcher(value).find() || DURATION.matcher(value).find();
        boolean category = CATEGORY.matcher(value).find() || CATEGORY_LABEL.matcher(value).find();
        boolean rating = RATING.matcher(value).find() || containsAny(value, "nota", "rating", "passageiro", "★");
        if (!route && !category && !rating) return -1;
        return 4 + (route ? 3 : 0) + (category ? 2 : 0) + (rating ? 1 : 0);
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
            String id = node == null || !node.visibleToUser() || node.resourceId() == null
                    ? "" : node.resourceId().toLowerCase(Locale.ROOT);
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
                int parentKind = resourceTravelContext(!parentNode.visibleToUser() || parentNode.resourceId() == null ? ""
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
            int kind = resourceTravelContext(!nearby.visibleToUser() || nearby.resourceId() == null ? ""
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

    private static void addTrace(List<String> trace, String value) {
        if (trace != null) trace.add(value);
    }

    private static String safeTraceValue(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String travelContextName(int kind) {
        return switch (kind) {
            case 1 -> "pickup";
            case 2 -> "trip";
            default -> "unassigned";
        };
    }

    private static void addIf(Set<String> found, String name, Object value) { if (value != null) found.add(name); }

    private static OfferParseResult rejected(String reason) {
        return rejected(reason, List.of());
    }

    private static OfferParseResult rejected(String reason, List<String> trace) {
        addTrace(trace, "parser_result=rejected; reason=" + reason);
        Set<String> unavailable = new LinkedHashSet<>(List.of("offered_amount", "category", "passenger_rating",
                "pickup_distance", "pickup_time", "trip_distance", "trip_time"));
        return new OfferParseResult(null, reason, Set.of(), unavailable, trace);
    }
}
