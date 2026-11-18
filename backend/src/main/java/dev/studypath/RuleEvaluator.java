package dev.studypath;

import static dev.studypath.StudyData.*;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
class RuleEvaluator {
    Check evaluate(Program program, Rule rule, Profile profile) {
        var evidence = rule.evidence(program);
        return switch (rule.type()) {
            case NON_COMPUTING -> majorCheck(profile, evidence);
            case COMPUTING -> computingCheck(profile, evidence);
            case RELATED_COMPUTING -> profile.major() == Major.COMPUTING
                    ? new Check(Status.MATCH, "计算机相关本科属于官网列出的相关专业方向之一。", evidence)
                    : new Check(Status.UNKNOWN, "按官网核对具体专业或等效职业经验。", evidence);
            case MATH -> preparationCheck(profile.math(), evidence);
            case PROGRAMMING -> preparationCheck(profile.programming(), evidence);
            case IELTS, IELTS_SPLIT -> ieltsCheck(rule, profile.ieltsOverall(), profile.ieltsBand(), evidence);
            case NUS_IELTS -> profile.instruction() == Instruction.OTHER ? ieltsCheck(rule, profile.ieltsOverall(), profile.ieltsBand(), evidence)
                    : new Check(Status.UNKNOWN, "先核实学校对你的授课语言及语言考试材料的要求。", evidence);
            case REVIEW -> new Check(Status.UNKNOWN, "请用实际材料对照官网核实这项要求。", evidence);
        };
    }

    private Check computingCheck(Profile profile, Evidence evidence) {
        return switch (profile.major()) {
            case COMPUTING -> new Check(Status.UNKNOWN, "你选择了计算机相关类别，请核实具体学位是否符合官网的计算机科学要求。", evidence);
            case UNKNOWN -> new Check(Status.UNKNOWN, "请补充本科专业类别。", evidence);
            case QUANTITATIVE, OTHER -> new Check(Status.GAP, "你填写的是非计算机背景，与该项目公布的常规计算机科学背景有差距。", evidence);
        };
    }

    private Check majorCheck(Profile profile, Evidence evidence) {
        return switch (profile.major()) {
            case COMPUTING -> new Check(Status.GAP, "你选择的是计算机相关本科背景，与该项专业范围不符。", evidence);
            case UNKNOWN -> new Check(Status.UNKNOWN, "请补充本科专业类别。", evidence);
            case QUANTITATIVE, OTHER -> new Check(Status.MATCH, "按你选择的类别，符合这项非计算机专业范围。", evidence);
        };
    }

    private Check preparationCheck(Boolean present, Evidence evidence) {
        if (present == null) return new Check(Status.UNKNOWN, "请补充这项学习或实践证据。", evidence);
        if (!present) return new Check(Status.GAP, "你标记为暂无这项证据，建议先核对可提交的材料。", evidence);
        return new Check(Status.UNKNOWN, "你已标记有相关证据，具体内容和水平仍需核实。", evidence);
    }

    Check ieltsCheck(Rule rule, BigDecimal overall, BigDecimal band, Evidence evidence) {
        if (below(overall, rule.overall()) || below(band, rule.lowerBand() == null ? rule.band() : rule.lowerBand())) {
            return new Check(Status.GAP, "已填写的总分或最低单项低于这条雅思分数线。", evidence);
        }
        if (rule.lowerBand() != null && below(band, rule.band())) {
            return new Check(Status.UNKNOWN, "本项目各科分数线不同。请按官网逐项对照听、说、读、写。", evidence);
        }
        if (overall == null || (rule.band() != null && band == null)) {
            return new Check(Status.UNKNOWN, "请补充雅思学术类成绩，或查看官网接受的其他证明。", evidence);
        }
        return new Check(Status.MATCH, "你填写的分数达到该项雅思分数线。成绩有效性请按官网核实。", evidence);
    }

    private boolean below(BigDecimal actual, BigDecimal minimum) {
        return actual != null && minimum != null && actual.compareTo(minimum) < 0;
    }
}
