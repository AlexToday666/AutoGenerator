import java.io.*;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

public class AutoTester {
    static final int GRID_SIZE = 13;
    static final int[] DX = {-1, 1, 0, 0};
    static final int[] DY = {0, 0, -1, 1};
    
    static char[][] grid;
    static int variant;
    static int gollumX, gollumY;
    static int mountDoomX, mountDoomY;
    static int mithrilX = -1, mithrilY = -1;
    static int frodoX = 0, frodoY = 0;
    static boolean ringOn = false;
    static boolean hasMithril = false;
    static boolean foundGollum = false;
    static Random rand = new Random();
    static Difficulty difficulty = Difficulty.EASY;
        static Difficulty activeDifficulty = Difficulty.EASY;
    static boolean randomDifficulty = false;
    static int safePlacementDistance = 3;
    static boolean dumpMap = false;
    static boolean verboseOutput = true;
    static void resetSimulationState() {
        frodoX = 0;
        frodoY = 0;
        ringOn = false;
        hasMithril = false;
        foundGollum = false;
    }
    
    static class ReportConfig {
        boolean enabled;
        int mapsPerVariant = 1000;
        Long randomSeed;
        List<String> command = new ArrayList<>(defaultCommand());
        List<Integer> variants = Arrays.asList(1, 2);
    }
    
    static class TestResult {
        final boolean success;
        final int actualMoves;
        final int reportedMoves;
        final String failureReason;
        
        private TestResult(boolean success, int actualMoves, int reportedMoves, String failureReason) {
            this.success = success;
            this.actualMoves = actualMoves;
            this.reportedMoves = reportedMoves;
            this.failureReason = failureReason;
        }
        
        static TestResult success(int actualMoves, int reportedMoves) {
            return new TestResult(true, actualMoves, reportedMoves, null);
        }
        
        static TestResult failure(String reason, int actualMoves, int reportedMoves) {
            return new TestResult(false, actualMoves, reportedMoves, reason);
        }
    }
    
    static class ExecutionRecord {
        final int variant;
        final boolean success;
        final double executionTimeMs;
        final int actualMoves;
        final int reportedMoves;
        final String failureReason;
        
        ExecutionRecord(int variant, boolean success, double executionTimeMs,
                        int actualMoves, int reportedMoves, String failureReason) {
            this.variant = variant;
            this.success = success;
            this.executionTimeMs = executionTimeMs;
            this.actualMoves = actualMoves;
            this.reportedMoves = reportedMoves;
            this.failureReason = failureReason;
        }
    }
    
    static class RunMetrics {
        final List<Double> executionTimesMs = new ArrayList<>();
        final List<Integer> actualMoves = new ArrayList<>();
        final List<Integer> reportedMoves = new ArrayList<>();
        final List<String> failureReasons = new ArrayList<>();
        int wins = 0;
        int losses = 0;
        
        void record(ExecutionRecord record) {
            executionTimesMs.add(record.executionTimeMs);
            actualMoves.add(record.actualMoves);
            if (record.reportedMoves >= 0) {
                reportedMoves.add(record.reportedMoves);
            }
            if (record.success) {
                wins++;
            } else {
                losses++;
                if (record.failureReason != null) {
                    failureReasons.add(record.failureReason);
                }
            }
        }
        
        int sampleSize() {
            return wins + losses;
        }
        
        int totalWins() {
            return wins;
        }
        
        int totalLosses() {
            return losses;
        }
        
        double winPercentage() {
            return sampleSize() == 0 ? Double.NaN : (totalWins() * 100.0) / sampleSize();
        }
        
        double lossPercentage() {
            return sampleSize() == 0 ? Double.NaN : (totalLosses() * 100.0) / sampleSize();
        }
    }
    
    static class StatsSummary {
        double mean = Double.NaN;
        double median = Double.NaN;
        double mode = Double.NaN;
        double stdDev = Double.NaN;
        
        StatsSummary() {
        }
        
        StatsSummary(double mean, double median, double mode, double stdDev) {
            this.mean = mean;
            this.median = median;
            this.mode = mode;
            this.stdDev = stdDev;
        }
        
        double getMean() {
            return mean;
        }
        
        double getMedian() {
            return median;
        }
        
        double getMode() {
            return mode;
        }
        
        double getStdDev() {
            return stdDev;
        }
    }
    
    static void log(String message) {
        if (verboseOutput) {
            System.out.println(message);
        }
    }
    
    static void logInline(String message) {
        if (verboseOutput) {
            System.out.print(message);
        }
    }
    
    static void logFormatted(String format, Object... args) {
        if (verboseOutput) {
            System.out.printf(format, args);
        }
    }
    
    enum Difficulty {
        EASY("Easy", 1, 2, 3),
        NORMAL("Normal", 2, 3, 2),
        HARD("Hard", 1, 1, 0);
        
        private final String displayName;
        private final int minVision;
        private final int maxVision;
        private final int safeDistance;
        
        Difficulty(String displayName, int minVision, int maxVision, int safeDistance) {
            this.displayName = displayName;
            this.minVision = minVision;
            this.maxVision = maxVision;
            this.safeDistance = safeDistance;
        }
        
        int randomVision(Random random) {
            if (minVision == maxVision) {
                return minVision;
            }
            return random.nextInt(maxVision - minVision + 1) + minVision;
        }
        
        int safeDistance() {
            return safeDistance;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
        
        static Difficulty fromString(String value) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            switch (normalized) {
                case "EASY":
                case "LIGHT":
                    return EASY;
                case "NORMAL":
                case "MEDIUM":
                    return NORMAL;
                case "HARD":
                case "ADVANCED":
                case "EXTREME":
                    return HARD;
                default:
                    throw new IllegalArgumentException("Unknown difficulty: " + value);
            }
        }
        
        static Difficulty randomDifficulty(Random random) {
            Difficulty[] values = values();
            return values[random.nextInt(values.length)];
        }
    }
    
    static class Enemy {
        int x, y;
        char type;
        Enemy(int x, int y, char type) {
            this.x = x;
            this.y = y;
            this.type = type;
        }
    }
    
    static List<Enemy> enemies = new ArrayList<>();
    
    public static void main(String[] args) throws Exception {
        ReportConfig reportConfig = parseReportConfig(args);
        if (reportConfig.enabled) {
            verboseOutput = false;
            runReportMode(reportConfig);
            return;
        }
        
        runStandardMode(args, reportConfig.command);
    }
    
    static void runStandardMode(String[] args, List<String> commandOverride) throws Exception {
        int testCount = 1;
        boolean countSet = false;
        boolean difficultySet = false;
        List<String> effectiveCommand = (commandOverride == null || commandOverride.isEmpty())
                ? new ArrayList<>(defaultCommand())
                : new ArrayList<>(commandOverride);
        
        for (int i = 0; args != null && i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--command=")) {
                continue;
            }
            if (arg.equalsIgnoreCase("--command")) {
                if (i + 1 < args.length) {
                    i++;
                }
                continue;
            }
            if (isMapDumpKeyword(arg)) {
                dumpMap = true;
                continue;
            }
            
            Integer maybeCount = tryParseInt(arg);
            if (!countSet && maybeCount != null) {
                testCount = Math.max(1, maybeCount);
                countSet = true;
                continue;
            }
            
            if (!difficultySet) {
                if (isRandomKeyword(arg)) {
                    randomDifficulty = true;
                    difficultySet = true;
                    continue;
                }
                try {
                    difficulty = Difficulty.fromString(arg);
                    difficultySet = true;
                    continue;
                } catch (IllegalArgumentException ignored) {
                }
            }
            
            log("Ignoring argument '" + arg + "'");
        }
        
        if (!difficultySet && !randomDifficulty) {
            difficulty = Difficulty.EASY;
        }
        
        int passed = 0;
        
        for (int t = 1; t <= testCount; t++) {
            activeDifficulty = randomDifficulty ? Difficulty.randomDifficulty(rand) : difficulty;
            log("\n╔═══════════════════════════════════╗");
            log("║       TEST " + t + " / " + testCount + "              ║");
            log("╚═══════════════════════════════════╝");
            
            generateTest();
            TestResult result = runTest(new ArrayList<>(effectiveCommand));
            
            if (result.success) {
                log("✓ PASSED");
                passed++;
            } else {
                log("✗ FAILED");
            }
        }
        
        log("\n═══════════════════════════════════");
        log("Results: " + passed + " / " + testCount + " passed");
        log("═══════════════════════════════════\n");
    }
    
    static ReportConfig parseReportConfig(String[] args) {
        ReportConfig config = new ReportConfig();
        if (args == null) {
            return config;
        }
        for (String arg : args) {
            if (isReportKeyword(arg)) {
                config.enabled = true;
                break;
            }
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--command=")) {
                List<String> parsed = parseCommandLine(arg.substring("--command=".length()));
                if (!parsed.isEmpty()) {
                    config.command = parsed;
                }
                continue;
            }
            if (arg.equalsIgnoreCase("--command") && i + 1 < args.length) {
                List<String> parsed = parseCommandLine(args[++i]);
                if (!parsed.isEmpty()) {
                    config.command = parsed;
                }
            }
        }
        if (!config.enabled) {
            return config;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (isReportKeyword(arg)) {
                continue;
            }
            if (arg.startsWith("--maps=")) {
                Integer value = tryParseInt(arg.substring("--maps=".length()));
                if (value != null && value > 0) {
                    config.mapsPerVariant = value;
                }
                continue;
            }
            if (arg.equalsIgnoreCase("--maps") && i + 1 < args.length) {
                Integer value = tryParseInt(args[++i]);
                if (value != null && value > 0) {
                    config.mapsPerVariant = value;
                }
                continue;
            }
            if (arg.startsWith("--seed=")) {
                try {
                    config.randomSeed = Long.parseLong(arg.substring("--seed=".length()));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            if (arg.equalsIgnoreCase("--seed") && i + 1 < args.length) {
                try {
                    config.randomSeed = Long.parseLong(args[++i]);
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            if (arg.startsWith("--variants=")) {
                applyVariants(config, arg.substring("--variants=".length()));
                continue;
            }
            if (arg.equalsIgnoreCase("--variants") && i + 1 < args.length) {
                applyVariants(config, args[++i]);
                continue;
            }
            Integer numeric = tryParseInt(arg);
            if (numeric != null && numeric > 0) {
                config.mapsPerVariant = numeric;
            }
        }
        if (config.variants == null || config.variants.isEmpty()) {
            config.variants = Arrays.asList(1, 2);
        }
        if (config.command == null || config.command.isEmpty()) {
            config.command = new ArrayList<>(defaultCommand());
        }
        return config;
    }
    
    static void applyVariants(ReportConfig config, String value) {
        if (value == null) {
            return;
        }
        Set<Integer> variants = new TreeSet<>();
        for (String token : value.split(",")) {
            Integer parsed = tryParseInt(token.trim());
            if (parsed != null && parsed > 0) {
                variants.add(parsed);
            }
        }
        if (!variants.isEmpty()) {
            config.variants = new ArrayList<>(variants);
        }
    }
    
    static List<String> parseCommandLine(String value) {
        if (value == null) {
            return Collections.emptyList();
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        boolean escaping = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuotes = true;
                    quoteChar = c;
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }
        if (escaping) {
            current.append('\\');
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
    
    static List<String> defaultCommand() {
        return Arrays.asList("java", "Main");
    }
    
    static void runReportMode(ReportConfig config) throws Exception {
        if (config.randomSeed != null) {
            rand.setSeed(config.randomSeed);
        } else {
            rand.setSeed(System.currentTimeMillis());
        }
        Map<Integer, RunMetrics> metricsByVariant = new LinkedHashMap<>();
        long totalRuns = (long) config.mapsPerVariant * config.variants.size();
        System.out.println("=== AutoTester Statistical Report ===");
        System.out.println("Maps per variant: " + config.mapsPerVariant);
        System.out.println("Variants analysed: " + config.variants);
        System.out.println("Command under test: " + commandToDisplay(config.command));
        if (config.randomSeed != null) {
            System.out.println("Random seed: " + config.randomSeed);
        }
        System.out.println("Total solver runs: " + totalRuns);
        System.out.println();
        for (int variantValue : config.variants) {
            RunMetrics metrics = metricsByVariant.computeIfAbsent(variantValue, v -> new RunMetrics());
            for (int mapIndex = 0; mapIndex < config.mapsPerVariant; mapIndex++) {
                generateTestForVariant(variantValue);
                ExecutionRecord record = executeCommand(config.command);
                metrics.record(record);
                int progressStep = Math.max(1, config.mapsPerVariant / 10);
                if ((mapIndex + 1) % progressStep == 0) {
                    System.out.println("Variant " + variantValue + ": " + (mapIndex + 1) + " / " + config.mapsPerVariant + " maps processed");
                }
            }
        }
        printReportSummary(config, metricsByVariant);
    }
    
    static void generateTestForVariant(int desiredVariant) {
        Difficulty previousDifficulty = difficulty;
        Difficulty previousActive = activeDifficulty;
        boolean previousRandom = randomDifficulty;
        difficulty = Difficulty.EASY;
        activeDifficulty = Difficulty.EASY;
        randomDifficulty = false;
        int attempts = 0;
        do {
            generateTest();
            attempts++;
        } while (variant != desiredVariant && attempts < 10000);
        if (variant != desiredVariant) {
            throw new IllegalStateException("Unable to generate map for variant " + desiredVariant + " after " + attempts + " attempts");
        }
        difficulty = previousDifficulty;
        activeDifficulty = previousActive;
        randomDifficulty = previousRandom;
    }
    
    static ExecutionRecord executeCommand(List<String> command) throws Exception {
        long start = System.nanoTime();
        TestResult result = runTest(new ArrayList<>(command));
        long end = System.nanoTime();
        double elapsedMs = (end - start) / 1_000_000.0;
        return new ExecutionRecord(variant, result.success, elapsedMs,
                result.actualMoves, result.reportedMoves, result.failureReason);
    }
    
    static void printReportSummary(ReportConfig config, Map<Integer, RunMetrics> metricsMap) {
        System.out.println();
        System.out.println("=== Statistical Summary ===");
        for (int variantValue : config.variants) {
            System.out.println();
            System.out.println("Variant " + variantValue + " (" + config.mapsPerVariant + " maps)");
            RunMetrics metrics = metricsMap.getOrDefault(variantValue, new RunMetrics());
            printMetricsForVariant(metrics);
        }
    }
    
    static void printMetricsForVariant(RunMetrics metrics) {
        if (metrics.sampleSize() == 0) {
            System.out.println("No runs recorded.");
            return;
        }
        String header = padRight("Metric", 28) + " | Value";
        System.out.println(header);
        System.out.println(repeat("-", header.length()));
        System.out.println(formatMetricRow("Runs", Integer.toString(metrics.sampleSize())));
        System.out.println(formatMetricRow("Wins", metrics.totalWins() + " (" + formatPercentage(metrics.winPercentage()) + ")"));
        System.out.println(formatMetricRow("Losses", metrics.totalLosses() + " (" + formatPercentage(metrics.lossPercentage()) + ")"));
        StatsSummary execution = summarizeDoubles(metrics.executionTimesMs);
        printStatsBlock("Execution", execution, "ms");
        StatsSummary actualMoves = summarizeIntegers(metrics.actualMoves);
        printStatsBlock("Actual moves", actualMoves, null);
        if (!metrics.reportedMoves.isEmpty()) {
            StatsSummary reportedMoves = summarizeIntegers(metrics.reportedMoves);
            printStatsBlock("Reported moves", reportedMoves, null);
        }
        String failureSummary = topFailureSummary(metrics);
        if (failureSummary != null) {
            System.out.println(formatMetricRow("Top failures", failureSummary));
        }
    }
    
    static void printStatsBlock(String baseLabel, StatsSummary summary, String unit) {
        String suffix = (unit == null) ? "" : " " + unit;
        System.out.println(formatMetricRow(baseLabel + " mean", appendUnit(formatNumber(summary.getMean()), suffix)));
        System.out.println(formatMetricRow(baseLabel + " median", appendUnit(formatNumber(summary.getMedian()), suffix)));
        System.out.println(formatMetricRow(baseLabel + " mode", appendUnit(formatNumber(summary.getMode()), suffix)));
        System.out.println(formatMetricRow(baseLabel + " std dev", appendUnit(formatNumber(summary.getStdDev()), suffix)));
    }
    
    static String appendUnit(String value, String unitSuffix) {
        if (unitSuffix == null || unitSuffix.isEmpty() || "-".equals(value)) {
            return value;
        }
        return value + unitSuffix;
    }
    
    static String formatMetricRow(String label, String value) {
        return padRight(label, 28) + " | " + value;
    }
    
    static String commandToDisplay(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "(empty)";
        }
        return command.stream()
                .map(token -> token.contains(" ") ? "\"" + token.replace("\"", "\\\"") + "\"" : token)
                .collect(Collectors.joining(" "));
    }
    
    static String topFailureSummary(RunMetrics metrics) {
        if (metrics == null || metrics.failureReasons.isEmpty()) {
            return null;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String reason : metrics.failureReasons) {
            counts.merge(reason, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> {
                    int cmp = Integer.compare(b.getValue(), a.getValue());
                    if (cmp != 0) {
                        return cmp;
                    }
                    return a.getKey().compareTo(b.getKey());
                })
                .limit(2)
                .map(entry -> entry.getValue() + "× " + entry.getKey())
                .collect(Collectors.joining("; "));
    }
    
    static StatsSummary summarizeDoubles(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return new StatsSummary();
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        int size = sorted.size();
        double median;
        if (size % 2 == 0) {
            median = (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        } else {
            median = sorted.get(size / 2);
        }
        double mode = computeModeForDoubles(sorted);
        double variance = 0.0;
        for (double value : sorted) {
            double diff = value - mean;
            variance += diff * diff;
        }
        variance /= size;
        double stdDev = Math.sqrt(variance);
        return new StatsSummary(mean, median, mode, stdDev);
    }
    
    static StatsSummary summarizeIntegers(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return new StatsSummary();
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double mean = sorted.stream().mapToInt(Integer::intValue).average().orElse(Double.NaN);
        int size = sorted.size();
        double median;
        if (size % 2 == 0) {
            median = (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        } else {
            median = sorted.get(size / 2);
        }
        double mode = computeModeForIntegers(sorted);
        double variance = 0.0;
        for (int value : sorted) {
            double diff = value - mean;
            variance += diff * diff;
        }
        variance /= size;
        double stdDev = Math.sqrt(variance);
        return new StatsSummary(mean, median, mode, stdDev);
    }
    
    static double computeModeForDoubles(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        Map<Double, Integer> counts = new HashMap<>();
        double mode = Double.NaN;
        int maxCount = -1;
        for (double value : values) {
            double key = Math.round(value * 10.0) / 10.0;
            int count = counts.merge(key, 1, Integer::sum);
            if (count > maxCount || (count == maxCount && (Double.isNaN(mode) || key < mode))) {
                maxCount = count;
                mode = key;
            }
        }
        return mode;
    }
    
    static double computeModeForIntegers(List<Integer> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        Map<Integer, Integer> counts = new HashMap<>();
        int mode = 0;
        int maxCount = -1;
        for (int value : values) {
            int count = counts.merge(value, 1, Integer::sum);
            if (count > maxCount || (count == maxCount && value < mode)) {
                maxCount = count;
                mode = value;
            }
        }
        return mode;
    }
    
    static String padRight(String value, int width) {
        if (value == null) {
            value = "";
        }
        if (value.length() >= width) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
    
    static String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
    
    static String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
    
    static String formatPercentage(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f%%", value);
    }
    
    static void generateTest() {
        variant = activeDifficulty.randomVision(rand);
        safePlacementDistance = activeDifficulty.safeDistance();
        grid = new char[GRID_SIZE][GRID_SIZE];
        enemies.clear();
        frodoX = 0;
        frodoY = 0;
        ringOn = false;
        hasMithril = false;
        foundGollum = false;
        mithrilX = -1;
        mithrilY = -1;
        
        for (int i = 0; i < GRID_SIZE; i++) {
            Arrays.fill(grid[i], '.');
        }
        
        grid[0][0] = 'S';
        
        switch (activeDifficulty) {
            case EASY:
                setupEasyScenario();
                break;
            case NORMAL:
                setupNormalScenario();
                break;
            case HARD:
                setupHardScenario();
                break;
        }
        
        log("Difficulty: " + activeDifficulty);
        log("Variant: " + variant);
        log("Gollum: (" + gollumX + ", " + gollumY + ")");
        log("Mount Doom: (" + mountDoomX + ", " + mountDoomY + ")");
        if (mithrilX >= 0) {
            log("Mithril: (" + mithrilX + ", " + mithrilY + ")");
        }
        log("Enemies: " + enemies.size());
        if (dumpMap) {
            printGrid();
        }
    }
    
    static TestResult runTest(List<String> command) throws Exception {
        resetSimulationState();
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File("."));
        Process process = pb.start();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
            
            writer.write(variant + "\n");
            writer.write(gollumX + " " + gollumY + "\n");
            writer.flush();
            
            List<String> surroundings = getSurroundings(0, 0);
            writer.write(surroundings.size() + "\n");
            for (String s : surroundings) {
                writer.write(s + "\n");
            }
            writer.flush();
            
            int actualMoves = 0;
            
            while (true) {
                String commandLine = reader.readLine();
                if (commandLine == null) {
                    String reason = "ERROR: Unexpected end of stream";
                    log(reason);
                    return TestResult.failure(reason, actualMoves, -1);
                }
                
                commandLine = commandLine.trim();
                if (commandLine.isEmpty()) {
                    continue;
                }
                
                String[] parts = commandLine.split("\\s+");
                String op = parts[0];
                
                switch (op) {
                    case "m": {
                        if (parts.length < 3) {
                            String reason = "ERROR: Malformed move command: '" + commandLine + "'";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, -1);
                        }
                        int x;
                        int y;
                        try {
                            x = Integer.parseInt(parts[1]);
                            y = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException e) {
                            String reason = "ERROR: Invalid move coordinates: '" + commandLine + "'";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, -1);
                        }
                        
                        if (!isValidMove(x, y)) {
                            String reason = "ERROR: Invalid move (" + frodoX + "," + frodoY + ") -> (" + x + "," + y + ")";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, -1);
                        }
                        
                        frodoX = x;
                        frodoY = y;
                        actualMoves++;
                        
                        if (isDead()) {
                            String reason = "ERROR: Frodo died at (" + frodoX + "," + frodoY + ")";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, -1);
                        }
                        
                        if (grid[frodoX][frodoY] == 'C') {
                            hasMithril = true;
                        }
                        
                        surroundings = getSurroundings(frodoX, frodoY);
                        writer.write(surroundings.size() + "\n");
                        for (String s : surroundings) {
                            writer.write(s + "\n");
                        }
                        
                        if (frodoX == gollumX && frodoY == gollumY && !foundGollum) {
                            foundGollum = true;
                            writer.write("My precious! Mount Doom is " + mountDoomX + " " + mountDoomY + "\n");
                        }
                        
                        writer.flush();
                        break;
                    }
                    case "r": {
                        if (ringOn) {
                            String reason = "ERROR: Ring already on";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, -1);
                        }
                        ringOn = true;
                        
                        surroundings = getSurroundings(frodoX, frodoY);
                        writer.write(surroundings.size() + "\n");
                        for (String s : surroundings) {
                            writer.write(s + "\n");
                        }
                        writer.flush();
                        break;
                    }
                    case "rr": {
                        if (!ringOn) {
                            String reason = "ERROR: Ring already off";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, -1);
                        }
                        ringOn = false;
                        
                        surroundings = getSurroundings(frodoX, frodoY);
                        writer.write(surroundings.size() + "\n");
                        for (String s : surroundings) {
                            writer.write(s + "\n");
                        }
                        writer.flush();
                        break;
                    }
                    case "e": {
                        if (parts.length < 2) {
                            String reason = "ERROR: Malformed end command: '" + commandLine + "'";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, -1);
                        }
                        int reported;
                        try {
                            reported = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException e) {
                            String reason = "ERROR: Invalid end command payload: '" + commandLine + "'";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, -1);
                        }
                        
                        if (reported == -1) {
                            log("Solution reported: NO PATH");
                            return TestResult.success(actualMoves, reported);
                        }
                        
                        if (frodoX != mountDoomX || frodoY != mountDoomY) {
                            String reason = "ERROR: Not at Mount Doom! At (" + frodoX + "," + frodoY + ")";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, reported);
                        }
                        
                        if (!foundGollum) {
                            String reason = "ERROR: Didn't find Gollum";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, reported);
                        }
                        
                        log("Moves: " + actualMoves + " (reported: " + reported + ")");
                        
                        if (actualMoves == reported) {
                            return TestResult.success(actualMoves, reported);
                        } else {
                            String reason = "ERROR: Wrong move count";
                            log(reason);
                            return TestResult.failure(reason, actualMoves, reported);
                        }
                    }
                    default: {
                        String reason = "ERROR: Unknown command '" + op + "'";
                        log(reason);
                        return TestResult.failure(reason, actualMoves, -1);
                    }
                }
            }
        } finally {
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    static void placeEnemy(char type) {
        placeEnemy(type, null);
    }
    
    static void placeEnemy(char type, BiPredicate<Integer, Integer> predicate) {
        int[] pos = predicate == null ? findEmptyCell() : findCell(predicate);
        enemies.add(new Enemy(pos[0], pos[1], type));
        grid[pos[0]][pos[1]] = type;
    }
    
    static void setupEasyScenario() {
        int orcCount = rand.nextInt(2) + 1;
        for (int i = 0; i < orcCount; i++) {
            placeEnemy('O');
        }
        
        placeEnemy('U');
        
        if (rand.nextBoolean()) {
            placeEnemy('N');
        }
        
        placeEnemy('W');
        
        if (rand.nextBoolean()) {
            int[] pos = findSafeCell();
            placeMithril(pos);
        }
        
        int[] gPos = findSafeCell();
        placeGollum(gPos);
        
        int[] dPos = findSafeCell();
        placeMountDoom(dPos);
    }
    
    static void setupNormalScenario() {
        int orcCount = 3 + rand.nextInt(2);
        for (int i = 0; i < orcCount; i++) {
            placeEnemy('O');
        }
        
        placeEnemy('U');
        placeEnemy('U', (x, y) -> distanceFromStart(x, y) <= 6);
        
        placeEnemy('N');
        
        placeEnemy('W');
        placeEnemy('W', (x, y) -> hasEnemyWithin(x, y, 2));
        
        int[] mithrilPos = findCell((x, y) -> isSafeForPlacement(x, y) && distanceFromStart(x, y) >= 6);
        placeMithril(mithrilPos);
        
        int[] gPos = findCell((x, y) -> isSafeForPlacement(x, y) && distanceFromStart(x, y) >= 4);
        placeGollum(gPos);
        
        int[] dPos = findCell((x, y) -> isSafeForPlacement(x, y) && distanceFromStart(x, y) >= 7);
        placeMountDoom(dPos);
    }
    
    static void setupHardScenario() {
        int orcCount = 4 + rand.nextInt(3);
        for (int i = 0; i < orcCount; i++) {
            placeEnemy('O', (x, y) -> distanceFromStart(x, y) <= 6);
        }
        
        placeEnemy('U', (x, y) -> distanceFromStart(x, y) <= 5);
        placeEnemy('U', (x, y) -> hasEnemyWithin(x, y, 1));
        
        placeEnemy('N');
        placeEnemy('N', (x, y) -> distanceFromStart(x, y) <= 6);
        
        placeEnemy('W');
        placeEnemy('W', (x, y) -> hasEnemyWithin(x, y, 2));
        
        int[] mithrilPos = findCell((x, y) -> grid[x][y] == '.' && hasEnemyWithin(x, y, 1));
        placeMithril(mithrilPos);
        
        int[] gPos = findCell((x, y) -> grid[x][y] == '.' && hasEnemyWithin(x, y, 1));
        placeGollum(gPos);
        
        int[] dPos = findCell((x, y) -> grid[x][y] == '.' && hasEnemyWithin(x, y, 2) && distanceFromStart(x, y) >= 6);
        placeMountDoom(dPos);
    }
    
    static void placeMithril(int[] pos) {
        mithrilX = pos[0];
        mithrilY = pos[1];
        grid[mithrilX][mithrilY] = 'C';
    }
    
    static void placeGollum(int[] pos) {
        gollumX = pos[0];
        gollumY = pos[1];
        grid[gollumX][gollumY] = 'G';
    }
    
    static void placeMountDoom(int[] pos) {
        mountDoomX = pos[0];
        mountDoomY = pos[1];
        grid[mountDoomX][mountDoomY] = 'M';
    }
    
    static int[] findEmptyCell() {
        while (true) {
            int x = rand.nextInt(GRID_SIZE);
            int y = rand.nextInt(GRID_SIZE);
            if (grid[x][y] == '.') {
                return new int[]{x, y};
            }
        }
    }
    
    static int[] findSafeCell() {
        return findSafeCell(safePlacementDistance);
    }
    
    static int[] findSafeCell(int minDistance) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            int x = rand.nextInt(GRID_SIZE);
            int y = rand.nextInt(GRID_SIZE);
            if (isSafeForPlacement(x, y, minDistance)) {
                return new int[]{x, y};
            }
        }
        return findEmptyCell();
    }
    
    static boolean isSafeForPlacement(int x, int y) {
        return isSafeForPlacement(x, y, safePlacementDistance);
    }
    
    static boolean isSafeForPlacement(int x, int y, int minDistance) {
        if (grid[x][y] != '.') return false;
        
        for (Enemy e : enemies) {
            int dist = Math.abs(x - e.x) + Math.abs(y - e.y);
            if (dist <= minDistance) return false;
        }
        
        return true;
    }
    
    static boolean isValidMove(int x, int y) {
        if (x < 0 || x >= GRID_SIZE || y < 0 || y >= GRID_SIZE) return false;
        int dx = Math.abs(x - frodoX);
        int dy = Math.abs(y - frodoY);
        return (dx == 1 && dy == 0) || (dx == 0 && dy == 1);
    }
    
    static boolean isDead() {
        for (Enemy e : enemies) {
            if (isInLethalZone(frodoX, frodoY, e)) {
                return true;
            }
        }
        return false;
    }
    
    static boolean isInLethalZone(int x, int y, Enemy e) {
        if (x == e.x && y == e.y) return true;
        
        int dist = Math.abs(x - e.x) + Math.abs(y - e.y);
        int chebDist = Math.max(Math.abs(x - e.x), Math.abs(y - e.y));
        
        switch (e.type) {
            case 'O':
                if (ringOn || hasMithril) return false;
                return dist <= 1;
            case 'U':
                if (ringOn || hasMithril) return dist <= 1;
                return dist <= 2;
            case 'N':
                if (hasMithril) return chebDist <= 1;
                if (ringOn) return chebDist <= 2;
                return chebDist <= 1;
            case 'W':
                return chebDist <= 2;
        }
        return false;
    }
    
    static List<String> getSurroundings(int x, int y) {
        List<String> visible = new ArrayList<>();
        int radius = variant;
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx == 0 && dy == 0) continue;
                
                int nx = x + dx;
                int ny = y + dy;
                
                if (nx < 0 || nx >= GRID_SIZE || ny < 0 || ny >= GRID_SIZE) continue;
                
                char cell = grid[nx][ny];
                
                if (cell == 'G' && !foundGollum) {
                    visible.add(nx + " " + ny + "G");
                } else if (cell == 'M' && foundGollum) {
                    visible.add(nx + " " + ny + "M");
                } else if (cell == 'C' && nx == mithrilX && ny == mithrilY && !hasMithril) {
                    visible.add(nx + "" + ny + "C");
                } else {
                    for (Enemy e : enemies) {
                        if (e.x == nx && e.y == ny) {
                            visible.add(nx + " " + ny + e.type);
                            break;
                        } else if (isInPerceptionZone(nx, ny, e)) {
                            visible.add(nx + " " + ny + "P");
                            break;
                        }
                    }
                }
            }
        }
        
        return visible;
    }
    
    static boolean isInPerceptionZone(int x, int y, Enemy e) {
        if (x == e.x && y == e.y) return false;
        
        int dist = Math.abs(x - e.x) + Math.abs(y - e.y);
        int chebDist = Math.max(Math.abs(x - e.x), Math.abs(y - e.y));
        
        switch (e.type) {
            case 'O':
                if (ringOn || hasMithril) return false;
                return dist == 1;
            case 'U':
                if (ringOn || hasMithril) {
                    if (dist == 1) return false;
                    return dist == 2;
                }
                return dist <= 2 && dist > 0;
            case 'N':
                if (hasMithril) return chebDist == 1;
                if (ringOn) return chebDist <= 2 && chebDist > 0;
                return chebDist == 1;
            case 'W':
                return chebDist <= 2 && chebDist > 0;
        }
        return false;
    }
    
    static int[] findCell(BiPredicate<Integer, Integer> predicate) {
        for (int attempt = 0; attempt < 2000; attempt++) {
            int x = rand.nextInt(GRID_SIZE);
            int y = rand.nextInt(GRID_SIZE);
            if (grid[x][y] != '.') continue;
            if (predicate.test(x, y)) {
                return new int[]{x, y};
            }
        }
        return findEmptyCell();
    }
    
    static boolean hasEnemyWithin(int x, int y, int radius) {
        for (Enemy enemy : enemies) {
            if (Math.abs(x - enemy.x) + Math.abs(y - enemy.y) <= radius) {
                return true;
            }
        }
        return false;
    }
    
    static int distanceFromStart(int x, int y) {
        return Math.abs(x) + Math.abs(y);
    }
    
    static Integer tryParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    static boolean isRandomKeyword(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("random") || normalized.equals("mixed");
    }
    
    static boolean isMapDumpKeyword(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("map") || normalized.equals("dump") || normalized.equals("--map") || normalized.equals("--dump");
    }
    
    static boolean isReportKeyword(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("report") || normalized.equals("--report");
    }
    
    static void printGrid() {
        if (!verboseOutput) {
            return;
        }
        System.out.println("Grid:");
        System.out.print("    ");
        for (int col = 0; col < GRID_SIZE; col++) {
            System.out.print(col < 10 ? (col + "  ") : (col + " "));
        }
        System.out.println();
        for (int row = 0; row < GRID_SIZE; row++) {
            System.out.printf("%2d ", row);
            for (int col = 0; col < GRID_SIZE; col++) {
                System.out.print(" " + grid[row][col] + " ");
            }
            System.out.println();
        }
    }
}
