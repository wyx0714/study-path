package dev.studypath;

import static dev.studypath.StudyData.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
class ProgramGuides {
    private final Map<String, Guide> guides;

    ProgramGuides(ObjectMapper json) throws IOException {
        try (var file = new ClassPathResource("guides.json").getInputStream()) {
            guides = json.readValue(file, new TypeReference<>() {});
        }
    }

    Guide forProgram(Program program) {
        return guides.getOrDefault(program.id(), new Guide("按目标批次查看官网", program.url(),
                "以官网该批次及国际生费率为准", program.url(), List.of("成绩单", "学位证明", "语言成绩（如适用）"),
                program.url(), null));
    }

    GradeCheck check(Program program, Profile profile, Guide guide) {
        GradeRule rule = guide.grade();
        if (rule == null) {
            String requirement = program.rules().stream().filter(r -> r.id().equals("major") || r.id().equals("degree"))
                    .map(Rule::summary).findFirst().orElse("学校按学位和成绩单审核。");
            return new GradeCheck(GradeState.DEGREE_REVIEW, "按学位和背景审核", requirement, null);
        }
        if (profile.grade() == null) return new GradeCheck(GradeState.NO_SCORE, "等你填成绩", rule.label(), rule);
        if (profile.gradeScale() != rule.scale()) {
            return new GradeCheck(GradeState.SCALE_DIFFERS, "成绩口径不同", rule.label() + "。请使用对应口径或学校官方等效表。", rule);
        }
        boolean met = profile.grade().compareTo(rule.minimum()) >= 0;
        String label = met ? (rule.preferred() ? "达到参考水平" : "达到分数线")
                : (rule.preferred() ? "低于参考水平" : "低于分数线");
        return new GradeCheck(met ? GradeState.MATCH : GradeState.GAP, label, rule.label(), rule);
    }

    static BigDecimal maximum(GradeScale scale) {
        return switch (scale) {
            case GPA4, LAST60_GPA4 -> BigDecimal.valueOf(4);
            case UK_HONOURS -> BigDecimal.valueOf(3);
            case CN_PERCENT, MELBOURNE_WAM -> BigDecimal.valueOf(100);
        };
    }
}
