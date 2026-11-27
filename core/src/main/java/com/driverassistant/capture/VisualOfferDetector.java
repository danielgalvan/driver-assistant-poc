package com.driverassistant.capture;

import com.driverassistant.parser.AccessibilityNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Groups OCR lines by relative geometry and exposes only regions with multiple offer signals. */
public final class VisualOfferDetector {
    private static final int MAX_LINES = 500;
    private static final Pattern MONEY = Pattern.compile("(?i)(?:R\\$|BRL)\\s*[0-9][0-9.,]*|[0-9][0-9.,]*\\s*(?:R\\$|BRL)");
    private static final Pattern DISTANCE = Pattern.compile("(?i)[0-9][0-9.,]*\\s*(?:km|quil[oô]metros?|m|metros?)\\b");
    private static final Pattern DURATION = Pattern.compile("(?i)[0-9][0-9.,]*\\s*(?:h(?:oras?)?|min(?:utos?)?)\\b");
    private static final Pattern CATEGORY = Pattern.compile("(?i)\\b(?:Uber\\s*X|Uber\\s*(?:Black|Comfort|Flash|Moto|Taxi)|Comfort|Black|Priority|Flash|Moto|Taxi)\\b");

    public Detection detect(List<VisualTextLine> source, int imageWidth, int imageHeight) {
        if (source == null || source.isEmpty()) return Detection.rejected("ocr_empty");
        List<VisualTextLine> lines = new ArrayList<>();
        for (VisualTextLine line : source) {
            if (line != null && line.text() != null && !line.text().isBlank()
                    && line.right() > line.left() && line.bottom() > line.top()) {
                lines.add(line);
                if (lines.size() >= MAX_LINES) break;
            }
        }
        if (lines.isEmpty()) return Detection.rejected("ocr_no_valid_lines");
        lines.sort(Comparator.comparingInt(VisualTextLine::centerY).thenComparingInt(VisualTextLine::left));
        int medianHeight = medianHeight(lines);
        int verticalGap = Math.max(medianHeight * 5, Math.max(24, imageHeight / 45));
        int horizontalGap = Math.max(medianHeight * 10, Math.max(80, imageWidth * 2 / 5));
        DisjointSet groups = new DisjointSet(lines.size());
        for (int left = 0; left < lines.size(); left++) {
            VisualTextLine a = lines.get(left);
            for (int right = left + 1; right < lines.size(); right++) {
                VisualTextLine b = lines.get(right);
                if (verticalDistance(a, b) > verticalGap) continue;
                if (horizontalDistance(a, b) <= horizontalGap) groups.union(left, right);
            }
        }

        List<Region> regions = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            int root = groups.find(index);
            if (!seen.add(root)) continue;
            List<VisualTextLine> regionLines = new ArrayList<>();
            for (int other = 0; other < lines.size(); other++) {
                if (groups.find(other) == root) regionLines.add(lines.get(other));
            }
            regionLines.sort(Comparator.comparingInt(VisualTextLine::centerY).thenComparingInt(VisualTextLine::left));
            Evidence evidence = evidence(regionLines);
            Region region = new Region(regionLines, evidence, bounds(regionLines), evidence.offerLike());
            regions.add(region);
        }
        regions.sort(Comparator.comparingInt((Region region) -> region.evidence().score()).reversed());
        for (Region region : regions) if (region.offerLike()) return new Detection(region, regions, null);
        String reason = regions.stream().anyMatch(region -> region.evidence().moneyCandidates() > 0)
                ? "ocr_money_without_offer_context" : "ocr_no_offer_evidence";
        return new Detection(null, regions, reason);
    }

    private static Evidence evidence(List<VisualTextLine> lines) {
        int money = 0, actions = 0, labels = 0, categories = 0, ratings = 0, distances = 0, durations = 0;
        for (VisualTextLine line : lines) {
            String text = line.text().toLowerCase(Locale.ROOT).trim();
            if (text.matches("(?i)^\\+\\s*R\\$.*")) continue;
            if (MONEY.matcher(text).find()) money++;
            if (text.matches("(?i)^(?:aceitar|selecionar)(?:\\s+(?:corrida|oferta|viagem))?[.!]?$")) actions++;
            if (containsAny(text, "oferta uber", "oferta da viagem", "valor da corrida", "valor da viagem", "nova corrida", "nova oferta")) labels++;
            if (CATEGORY.matcher(text).find()) categories++;
            if (containsAny(text, "nota", "avaliação", "avaliacao", "rating", "★", "⭐")) ratings++;
            distances += count(DISTANCE, text);
            durations += count(DURATION, text);
        }
        boolean explicitFare = money > 0 && (actions > 0 || labels > 0);
        boolean structuredFare = money > 0 && categories > 0 && ratings > 0 && distances >= 2 && durations >= 2;
        int score = money * 2 + actions * 4 + labels * 4 + categories * 2 + ratings + distances + durations;
        return new Evidence(money, actions, labels, categories, ratings, distances, durations,
                explicitFare || structuredFare, score);
    }

    private static int count(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private static int verticalDistance(VisualTextLine a, VisualTextLine b) {
        if (a.bottom() < b.top()) return b.top() - a.bottom();
        if (b.bottom() < a.top()) return a.top() - b.bottom();
        return 0;
    }

    private static int horizontalDistance(VisualTextLine a, VisualTextLine b) {
        if (a.right() < b.left()) return b.left() - a.right();
        if (b.right() < a.left()) return a.left() - b.right();
        return 0;
    }

    private static int medianHeight(List<VisualTextLine> lines) {
        int[] heights = lines.stream().mapToInt(VisualTextLine::height).sorted().toArray();
        return Math.max(1, heights[heights.length / 2]);
    }

    private static Bounds bounds(List<VisualTextLine> lines) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = 0, bottom = 0;
        for (VisualTextLine line : lines) {
            left = Math.min(left, line.left()); top = Math.min(top, line.top());
            right = Math.max(right, line.right()); bottom = Math.max(bottom, line.bottom());
        }
        return new Bounds(left, top, right, bottom);
    }

    public record Evidence(int moneyCandidates, int acceptanceActions, int labeledOffers,
                           int categories, int ratingCues, int distances, int durations,
                           boolean offerLike, int score) { }
    public record Bounds(int left, int top, int right, int bottom) { }
    public record Region(List<VisualTextLine> lines, Evidence evidence, Bounds bounds, boolean offerLike) {
        public Region { lines = List.copyOf(lines); }
        public List<AccessibilityNode> toParserNodes() {
            List<AccessibilityNode> nodes = new ArrayList<>(lines.size());
            int order = 0;
            for (VisualTextLine line : lines) nodes.add(AccessibilityNode.textOnly(line.text(), order++));
            return List.copyOf(nodes);
        }
    }
    public record Detection(Region candidate, List<Region> regions, String rejectionReason) {
        public Detection { regions = List.copyOf(regions); }
        static Detection rejected(String reason) { return new Detection(null, List.of(), reason); }
        public boolean hasCandidate() { return candidate != null; }
    }

    private static final class DisjointSet {
        private final int[] parent;
        DisjointSet(int size) { parent = new int[size]; for (int i = 0; i < size; i++) parent[i] = i; }
        int find(int value) { if (parent[value] != value) parent[value] = find(parent[value]); return parent[value]; }
        void union(int left, int right) { int a = find(left), b = find(right); if (a != b) parent[b] = a; }
    }
}
