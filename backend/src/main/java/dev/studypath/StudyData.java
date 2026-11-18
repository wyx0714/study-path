package dev.studypath;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

final class StudyData {
    private StudyData() {}

    enum Country { UK, SG, AU, US }
    enum Track { COMPUTING, AI_DATA, ALL }
    enum Major { COMPUTING, QUANTITATIVE, OTHER, UNKNOWN }
    enum Instruction { ENGLISH, OTHER, UNKNOWN }
    enum RuleType { NON_COMPUTING, COMPUTING, RELATED_COMPUTING, MATH, PROGRAMMING, IELTS, IELTS_SPLIT, NUS_IELTS, REVIEW }
    enum Status { MATCH, GAP, UNKNOWN }
    enum Tier { SAFETY_CANDIDATE, FIT, REACH, VERIFY, GAP }
    enum GradeScale { CN_PERCENT, GPA4, LAST60_GPA4, UK_HONOURS, MELBOURNE_WAM }
    enum GradeState { MATCH, GAP, NO_SCORE, SCALE_DIFFERS, DEGREE_REVIEW }

    record Program(String id, Country country, Track track, int year, String intake, String snapshot,
                   String school, String name, String kind, String url, List<Rule> rules) {
        String confirmationKey() { return id + "@" + year + "/" + snapshot; }
    }

    record Rule(String id, RuleType type, String title, String summary, String section, String url,
                BigDecimal overall, BigDecimal band, BigDecimal lowerBand) {
        Evidence evidence(Program program) {
            return new Evidence(program.id() + ":" + id, title, summary, section,
                    url == null ? program.url() : url, program.snapshot());
        }
    }

    record Evidence(String id, String title, String summary, String section, String url, String snapshot) {}

    record Profile(@NotNull @Size(min = 1, max = 4) Set<@NotNull Country> countries,
                   @NotNull Track track, @Min(2020) @Max(2100) int year,
                   @NotNull Major major, @NotNull Instruction instruction,
                   Boolean math, Boolean programming,
                   @DecimalMin("0") @DecimalMax("9") BigDecimal ieltsOverall,
                   @DecimalMin("0") @DecimalMax("9") BigDecimal ieltsBand,
                   @Size(max = 40) Set<String> confirmedProgramSnapshots,
                   GradeScale gradeScale, @DecimalMin("0") @DecimalMax("100") BigDecimal grade) {
        Profile {
            confirmedProgramSnapshots = confirmedProgramSnapshots == null ? Set.of() : confirmedProgramSnapshots;
        }
    }

    record Check(Status status, String note, Evidence evidence) {}
    record GradeRule(GradeScale scale, BigDecimal minimum, String label, String url, boolean preferred) {}
    record GradeCheck(GradeState state, String label, String note, GradeRule rule) {}
    record Guide(String window, String datesUrl, String tuition, String tuitionUrl, List<String> materials,
                 String applyUrl, GradeRule grade) {}
    record Recommendation(Tier tier, String reason, boolean userConfirmed) {}
    record Match(Program program, List<Check> checks, Recommendation recommendation, GradeCheck academics, Guide guide) {}
    record Assessment(String id, long createdAt, Profile profile, List<Match> results) {}
    record History(String id, long createdAt, int year, Track track, Set<Country> countries, int count) {}
    record Question(@NotBlank @Size(max = 300) String question) {}
    record Answer(String mode, String conclusion, String explanation, List<Evidence> sources, List<String> toVerify) {}
    record ModelStatus(boolean configured, String provider, String framework) {}
    record PlanAdvice(boolean generated, String provider, String advice) {}
}
