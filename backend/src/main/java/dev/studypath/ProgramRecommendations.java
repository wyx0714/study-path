package dev.studypath;

import static dev.studypath.StudyData.*;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class ProgramRecommendations {
    private static final BigDecimal HALF_POINT = BigDecimal.valueOf(0.5);

    Recommendation classify(Program program, Profile profile, List<Check> checks, GradeCheck academics) {
        boolean confirmed = profile.confirmedProgramSnapshots().contains(program.confirmationKey());
        Rule language = program.rules().stream().filter(r -> r.type() == RuleType.IELTS || r.type() == RuleType.IELTS_SPLIT || r.type() == RuleType.NUS_IELTS)
                .findFirst().orElse(null);
        Check languageCheck = language == null ? null : checks.stream()
                .filter(check -> check.evidence().id().equals(language.evidence(program).id())).findFirst().orElse(null);

        var gap = checks.stream().filter(check -> check != languageCheck && check.status() == Status.GAP).findFirst();
        if (gap.isPresent()) {
            return result(Tier.GAP, "「" + gap.get().evidence().title() + "」目前存在差距，建议先核对专业范围或补足所需材料。", confirmed);
        }
        if (languageCheck == null || languageCheck.status() == Status.UNKNOWN || profile.major() == Major.UNKNOWN) {
            return result(Tier.VERIFY, "补充本科背景或适用的语言成绩后可重新分层。", confirmed);
        }

        BigDecimal overallMargin = margin(profile.ieltsOverall(), language.overall());
        BigDecimal bandMargin = margin(profile.ieltsBand(), languageCheck.status() == Status.GAP && language.lowerBand() != null
                ? language.lowerBand() : language.band());
        if (overallMargin == null || bandMargin == null) {
            return result(Tier.VERIFY, "缺少总分或单项成绩，补齐后可以重新分层。", confirmed);
        }
        BigDecimal minimumMargin = overallMargin.min(bandMargin);
        if (academics.state() == GradeState.GAP) {
            if (languageCheck.status() == Status.MATCH && (academics.rule().preferred() || smallGradeGap(profile, academics.rule()))) {
                return result(Tier.REACH, "雅思已达到要求，当前学历成绩低于官网分数线或参考水平。", confirmed);
            }
            return result(Tier.GAP, "当前学历成绩低于官网分数线或参考水平，请先核对相应口径和可选路径。", confirmed);
        }
        if (languageCheck.status() == Status.GAP) {
            BigDecimal shortfall = minimumMargin.negate();
            if (shortfall.compareTo(HALF_POINT) <= 0) {
                return result(Tier.REACH, "至少一项雅思成绩还差 " + points(shortfall)
                        + " 分。补齐并核实学术条件后再考虑。", confirmed);
            }
            return result(Tier.GAP, "雅思总分或最低单项与公布的分数线相差 " + points(shortfall)
                    + " 分，可先准备语言成绩或核实其他语言路径。", confirmed);
        }

        boolean outstanding = checks.stream().anyMatch(check -> check != languageCheck && check.status() == Status.UNKNOWN);
        boolean academicReady = academics.state() == GradeState.MATCH || confirmed;
        boolean referenceOnly = academics.rule() != null && academics.rule().preferred();
        if (minimumMargin.compareTo(HALF_POINT) >= 0 && academicReady && (!outstanding || confirmed) && !referenceOnly) {
            return result(Tier.SAFETY_CANDIDATE, "适用的雅思分数线至少留有 " + points(minimumMargin)
                    + " 分余量，其余已收录条件已核对。", confirmed);
        }
        String reason = (outstanding || !academicReady) && !confirmed ? "雅思分数线已达到，下一步对照该校的学历成绩和材料。"
                : "已达到所核对的分数线，可以优先考虑并继续准备申请材料。";
        return result(Tier.FIT, reason, confirmed);
    }

    private boolean smallGradeGap(Profile profile, GradeRule rule) {
        BigDecimal tolerance = switch (rule.scale()) {
            case GPA4, LAST60_GPA4 -> new BigDecimal("0.25");
            case CN_PERCENT, MELBOURNE_WAM -> BigDecimal.valueOf(5);
            case UK_HONOURS -> BigDecimal.ZERO;
        };
        return rule.minimum().subtract(profile.grade()).compareTo(tolerance) <= 0;
    }

    private BigDecimal margin(BigDecimal actual, BigDecimal required) {
        if (required == null) return BigDecimal.valueOf(9);
        return actual == null ? null : actual.subtract(required);
    }

    private Recommendation result(Tier tier, String reason, boolean confirmed) {
        return new Recommendation(tier, reason, confirmed);
    }

    private String points(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
