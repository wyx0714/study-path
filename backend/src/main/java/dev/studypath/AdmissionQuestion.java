package dev.studypath;

import static dev.studypath.StudyData.*;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

record AdmissionQuestion(String text) {
    private static final Pattern OUTSIDE = Pattern.compile(
            "签证|学费|奖学金|生活费|就业率|录取(?:概率|率)|截止(?:日期|时间)?|住宿|排名|托福.*(?:多少|几分|分数线)|TOEFL.*(?:多少|几分|分数线)|PTE|多邻国|Duolingo",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INTAKE = Pattern.compile("(20[0-9]{2})(?:[/–-][0-9]{2})?\\s*年?\\s*(?:入学|秋季|春季|批次|intake)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERALL = scorePattern("雅思(?:学术类)?(?:\\s*总分)?|IELTS(?:\\s*overall)?|总分|overall");
    private static final Pattern BAND = scorePattern("最低单项(?:分数)?|单项最低(?:分数)?|最低小分|最低分|单项(?:分数)?|小分|min(?:imum)?\\s*band");
    private static final Pattern ELIGIBILITY = Pattern.compile("够|达标|达线|满足|过线|符合|可否|能否|能过|能申请");

    private static Pattern scorePattern(String labels) {
        return Pattern.compile("(?:" + labels + ")\\s*(?:为|是|[:：=])?\\s*([0-9]{1,2}(?:\\.[0-9]+)?)(?![0-9.])",
                Pattern.CASE_INSENSITIVE);
    }

    boolean outsideCoverage() {
        return OUTSIDE.matcher(text).find();
    }

    boolean wrongIntake(int year) {
        var matches = INTAKE.matcher(text);
        while (matches.find()) {
            if (Integer.parseInt(matches.group(1)) != year) return true;
        }
        return false;
    }

    boolean namesAnotherProgram(Program selected, List<Program> catalog) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (catalog.stream().anyMatch(p -> !p.id().equals(selected.id()) && normalized.contains(p.id()))) return true;
        if (catalog.stream().anyMatch(p -> !p.school().equals(selected.school()) && text.contains(p.school()))) return true;
        String explicitTitle = catalog.stream().map(p -> p.name().toLowerCase(Locale.ROOT))
                .filter(normalized::contains).max(Comparator.comparingInt(String::length)).orElse(null);
        return explicitTitle != null && !explicitTitle.equals(selected.name().toLowerCase(Locale.ROOT));
    }

    boolean covers(Rule rule) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return switch (rule.id()) {
            case "ielts" -> has(normalized, "雅思", "ielts", "英语", "语言成绩", "语言要求", "小分", "单项");
            case "major", "degree" -> has(normalized, "本科", "专业", "背景", "学历", "学位", "成绩单", "转码", "转专业", "工作经验", "行业经验", "degree");
            case "math" -> has(normalized, "数学", "统计", "概率", "线性代数", "mathematics", "statistics");
            case "programming" -> has(normalized, "编程", "python", "matlab", "代码", "programming");
            case "gre" -> has(normalized, "gre", "gmat", "gate");
            case "materials" -> has(normalized, "材料", "成绩单", "文书", "简历", "译本", "个人陈述", "documents");
            default -> false;
        };
    }

    Scores scores(Profile profile) {
        BigDecimal overall = readScore(OVERALL);
        BigDecimal band = readScore(BAND);
        if (overall != null || band != null) return new Scores(overall, band, "按你在问题中提供的雅思学术类成绩");
        if (ELIGIBILITY.matcher(text).find()) {
            return new Scores(profile.ieltsOverall(), profile.ieltsBand(), "按本次申请对照中保存的雅思学术类成绩");
        }
        return null;
    }

    private BigDecimal readScore(Pattern pattern) {
        var match = pattern.matcher(text);
        return match.find() ? new BigDecimal(match.group(1)) : null;
    }

    private boolean has(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    record Scores(BigDecimal overall, BigDecimal band, String origin) {
        boolean valid() {
            return validScore(overall) && validScore(band)
                    && (overall == null || band == null || band.compareTo(overall) <= 0);
        }

        private boolean validScore(BigDecimal score) {
            return score == null || (score.signum() >= 0 && score.compareTo(BigDecimal.valueOf(9)) <= 0
                    && score.remainder(BigDecimal.valueOf(0.5)).signum() == 0);
        }
    }
}
