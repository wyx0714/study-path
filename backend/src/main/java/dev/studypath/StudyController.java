package dev.studypath;

import static dev.studypath.StudyData.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class StudyController {
    private final StudyStore store;
    private final RuleEvaluator evaluator;
    private final SourceAssistant assistant;
    private final ProgramRecommendations recommendations;
    private final ProgramGuides guides;

    StudyController(StudyStore store, RuleEvaluator evaluator, SourceAssistant assistant, ProgramRecommendations recommendations,
                    ProgramGuides guides) {
        this.store = store;
        this.evaluator = evaluator;
        this.assistant = assistant;
        this.recommendations = recommendations;
        this.guides = guides;
    }

    @GetMapping("/catalog")
    List<Program> catalog() {
        return store.catalog();
    }

    @PostMapping("/assessments")
    Assessment assess(@RequestBody @Valid Profile profile) {
        if (profile.grade() != null && (profile.gradeScale() == null || profile.grade().compareTo(ProgramGuides.maximum(profile.gradeScale())) > 0
                || (profile.gradeScale() == GradeScale.UK_HONOURS && profile.grade().stripTrailingZeros().scale() > 0))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请按选定的成绩口径填写 GPA 或学位等级。");
        }
        if (profile.ieltsBand() != null && profile.ieltsOverall() != null
                && profile.ieltsBand().compareTo(profile.ieltsOverall()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最低单项不能高于总分。");
        }
        List<Match> results = store.catalog().stream()
                .filter(p -> p.year() == profile.year() && profile.countries().contains(p.country()))
                .filter(p -> profile.track() == Track.ALL || p.track() == profile.track())
                .map(p -> match(p, profile))
                .toList();
        var assessment = new Assessment(UUID.randomUUID().toString(), System.currentTimeMillis(), profile, results);
        if (!results.isEmpty()) store.save(assessment);
        return assessment;
    }

    private Match match(Program program, Profile profile) {
        var checks = program.rules().stream().map(rule -> evaluator.evaluate(program, rule, profile)).toList();
        var guide = guides.forProgram(program);
        var academics = guides.check(program, profile, guide);
        return new Match(program, checks, recommendations.classify(program, profile, checks, academics), academics, guide);
    }

    @GetMapping("/assessments")
    List<History> history() {
        return store.history();
    }

    @GetMapping("/assessments/{id}")
    Assessment read(@PathVariable UUID id) {
        return store.find(id.toString())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到该次对照。"));
    }

    @GetMapping("/model")
    ModelStatus model() {
        return assistant.status();
    }

    @PostMapping("/assessments/{id}/plan")
    PlanAdvice plan(@PathVariable UUID id) {
        return assistant.plan(read(id));
    }

    @PostMapping("/assessments/{id}/programs/{programId}/ask")
    Answer ask(@PathVariable UUID id, @PathVariable String programId, @RequestBody @Valid Question question) {
        Assessment assessment = read(id);
        Program program = assessment.results().stream().map(Match::program).filter(p -> p.id().equals(programId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该项目不在这次对照里。"));
        return assistant.ask(program, assessment.profile(), new AdmissionQuestion(question.question().strip()), store.catalog());
    }
}
